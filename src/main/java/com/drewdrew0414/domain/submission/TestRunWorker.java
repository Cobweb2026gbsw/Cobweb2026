package com.drewdrew0414.domain.submission;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.Map;

/** 정식 워커와 같은 격리를 사용하며 비공개 샘플은 출력 수집 없는 정식 판정 경로로 실행합니다. */
@Component @RequiredArgsConstructor
@ConditionalOnProperty(name="judge.enabled",havingValue="true")
public class TestRunWorker {
    private static final Logger log=LoggerFactory.getLogger(TestRunWorker.class);
    private final TestRunService tests;
    private final DockerJudge runner;
    @Scheduled(fixedDelay=1000) public void tick() {
        if(Thread.currentThread().isInterrupted())return;
        Map<String,Object> run;
        try {run=tests.claim();}catch(Exception error){log.warn("Test run claim failed");return;}
        if(run==null)return;
        var results=new ArrayList<TestRunService.Result>();boolean failed=false;
        try {
            if(!tests.current(run))return;
            var language=Language.parse((String)run.get("language"));
            String image=runner.imageIdentity();
            if(image==null)throw new IllegalStateException("Judge image unavailable");
            if(!tests.current(run)||Thread.currentThread().isInterrupted())return;
            try(var prepared=runner.preparePinned(language,(String)run.get("source_code"),(String)run.get("execution_mode"),image,(String)run.get("function_signature"))) {
                if(!tests.current(run)||Thread.currentThread().isInterrupted())return;
                boolean privateSample=TestRunService.isPrivateSample(run);
                for(var input:privateSample?tests.privateCases(run):tests.cases(run)) {
                    if(!tests.current(run)||Thread.currentThread().isInterrupted())return;
                    SubmissionService.JudgeResult judged;
                    if(privateSample) {
                        // 실제 출력 캡처/64KiB 표시 상한을 적용하지 않습니다. 정식 채점과 같은
                        // 대용량 반환 비교를 하고 비공개 원문·안전 진단조차 결과에 넣지 않습니다.
                        judged=prepared.run(input.input(),input.expected(),((Number)run.get("time_limit_ms")).intValue(),((Number)run.get("memory_limit_mb")).intValue(),null);
                        results.add(new TestRunService.Result(null,null,null,judged.status(),judged.elapsedMs(),null));
                    } else {
                        var output=prepared.runForTesting(input.input(),input.expected(),((Number)run.get("time_limit_ms")).intValue(),((Number)run.get("memory_limit_mb")).intValue());
                        judged=output.result();
                        results.add(new TestRunService.Result(input.input(),input.expected(),"SYSTEM_ERROR".equals(judged.status())?null:output.actual(),judged.status(),judged.elapsedMs(),
                            "SYSTEM_ERROR".equals(judged.status())||judged.diagnostic()==null?null:judged.diagnostic().publicValue()));
                    }
                    if("SYSTEM_ERROR".equals(judged.status())){failed=true;break;}
                }
            }
        }catch(Exception error){failed=true;log.warn("Test run failed: id={}",run.get("id"));}
        try {if(!Thread.currentThread().isInterrupted()&&tests.current(run))tests.finish(run,results,failed);}catch(Exception error){log.warn("Test run completion failed: id={}",run.get("id"));}
    }
}
