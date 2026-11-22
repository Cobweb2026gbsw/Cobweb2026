package com.drewdrew0414.domain.submission;

import lombok.RequiredArgsConstructor;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.regex.Pattern;

@Component @RequiredArgsConstructor @ConditionalOnProperty(name="judge.enabled",havingValue="true")
public class AdminJudgeWorker {
    private final AdminJudgeQueue queue;
    private final DockerJudge runner;
    private final ObjectMapper json;

    record CaseResult(String kind,int order,Long caseId,String status,int timeMs,String expected,String actual) {}
    @Scheduled(fixedDelay=1000) public void tick() {
        if(Thread.currentThread().isInterrupted()) return;
        var job=queue.claim();
        if(job==null) return;
        String image=null;
        var results=new ArrayList<CaseResult>();
        JudgeDiagnostic diagnostic=null;
        try {
            if(((Number)job.get("attempts")).intValue()>=3) throw new IllegalStateException("Worker lease expired");
            boolean reference="REFERENCE".equals(job.get("kind"));
            var language=Language.parse((String)job.get("language"));
            var config=json.readValue((String)job.get("config"),new tools.jackson.core.type.TypeReference<Map<String,Object>>(){});
            String mode=(String)config.get("execution_mode"),signature=(String)config.get("function_signature");
            image=runner.imageIdentity();
            if(image==null) throw new IllegalStateException("Judge image unavailable");
            try(var prepared=runner.preparePinned(language,(String)job.get("code"),mode,image,signature)) {
                if(prepared.failure()!=null) {
                    results.add(new CaseResult("COMPILE",0,null,prepared.failure(),0,null,null));
                    diagnostic=prepared.publicDiagnostic();
                } else {
                    int time=language.timeLimitMs(config),memory=((Number)config.get("memory_limit_mb")).intValue();
                    for(var input:queue.inputs(job.get("batch_id"))) {
                        if(Thread.currentThread().isInterrupted() || !queue.renew(job)) return;
                        String kind=(String)input.get("kind"),raw=(String)input.get("input_data"),expected=(String)input.get("expected_output");
                        int order=((Number)input.get("display_order")).intValue();
                        Long caseId=(Long)input.get("case_id");
                        boolean legacy="EXAMPLE".equals(kind) && "FUNCTION".equals(mode) && signature==null;
                        if(legacy) {
                            raw=legacyInput(raw);
                            expected=withoutFence(expected).strip().toLowerCase(Locale.ROOT);
                        }
                        SubmissionService.JudgeResult judged;
                        String actual=null;
                        if(reference) {
                            var output=prepared.runForReview(raw,expected,time,memory,caseId);
                            judged=output.result();actual=output.actual();
                        } else judged=prepared.run(raw,expected,time,memory,caseId);
                        if(diagnostic==null) diagnostic=judged.diagnostic();
                        results.add(new CaseResult(kind,order,caseId,judged.status(),judged.elapsedMs(),
                            reference?DockerJudge.Prepared.reviewExcerpt(expected):null,actual));
                    }
                }
            }
        } catch(Exception error) {
            LoggerFactory.getLogger(getClass()).warn("Admin judge job failed: job={}",job.get("id"));
            results.add(new CaseResult("WORKER",0,null,"SYSTEM_ERROR",0,null,null));
        }
        if(!Thread.currentThread().isInterrupted()) queue.finish(job,results,image,diagnostic);
    }
    // 이전 체인 레이저의 설명용 Python 표기는 허용된 세 가지 대입 형식만 데이터로 해석합니다.
    private static final Pattern CELL=Pattern.compile("matrix\\[(\\d{1,2})\\]\\[(\\d{1,2})\\] = ([01])");
    private static final Pattern TARGET=Pattern.compile("target = \\((\\d{1,2}), (\\d{1,2})\\)");
    static String withoutFence(String text) {
        String normalized=text.strip().replace("\r\n","\n");
        return normalized.startsWith("```python\n") && normalized.endsWith("\n```")
            ?normalized.substring(10,normalized.length()-4):text;
    }
    private String legacyInput(String input) {
        if(input.stripLeading().startsWith("{")) return input;
        var lines=withoutFence(input).lines().map(String::strip).filter(s->!s.isEmpty()).toList();
        if(lines.size()<2||!"matrix = [[0] * 15 for _ in range(15)]".equals(lines.getFirst())) throw new IllegalArgumentException("Invalid chain example");
        int[][] matrix=new int[15][15];
        for(String line:lines.subList(1,lines.size()-1)) {
            var cell=CELL.matcher(line);
            if(!cell.matches()) throw new IllegalArgumentException("Invalid cell");
            matrix[Integer.parseInt(cell.group(1))][Integer.parseInt(cell.group(2))]=Integer.parseInt(cell.group(3));
        }
        var target=TARGET.matcher(lines.getLast());
        if(!target.matches()) throw new IllegalArgumentException("Invalid target");
        return json.writeValueAsString(Map.of("matrix",matrix,"target",List.of(Integer.parseInt(target.group(1)),Integer.parseInt(target.group(2)))));
    }
}
