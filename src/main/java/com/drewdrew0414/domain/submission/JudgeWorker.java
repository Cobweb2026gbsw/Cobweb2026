package com.drewdrew0414.domain.submission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import java.util.ArrayList;

/** 한 번에 제출 하나를 처리합니다. 여러 워커는 DB의 SKIP LOCKED로 서로 다른 제출을 가져갑니다. */
@Component
@ConditionalOnProperty(name="judge.enabled",havingValue="true")
public class JudgeWorker {
    private static final Logger log=LoggerFactory.getLogger(JudgeWorker.class);
    private final SubmissionService submissions;
    private final DockerJudge runner;
    private final long heartbeatIntervalMs;

    @Autowired public JudgeWorker(SubmissionService submissions,DockerJudge runner) {
        this(submissions,runner,30000);
    }
    JudgeWorker(SubmissionService submissions,DockerJudge runner,long heartbeatIntervalMs) {
        this.submissions=submissions;
        this.runner=runner;
        this.heartbeatIntervalMs=heartbeatIntervalMs;
    }

    @Scheduled(fixedDelay=1000)
    public void tick() {
        final java.util.Map<String,Object> s;
        try { s=submissions.claim(); }
        catch(Exception ignored) { log.warn("Judge claim failed"); return; }
        if(s==null) return;
        String stage="lease";
        try(var lease=new JudgeLease(submissions,s,heartbeatIntervalMs)) {
            if(!lease.renew("claimed")) return;
            var results=new ArrayList<SubmissionService.JudgeResult>();
            try {
                stage="problem";
                var p=submissions.problem(s.get("problem_id"));
                stage="prepare";
                if(!lease.renew(stage)) return;
                try(var prepared=runner.prepare(Language.parse((String)s.get("language")),(String)s.get("source_code"),
                        (String)p.get("execution_mode"))) {
                    if(!lease.renew("prepared")) return;
                    if(prepared.failure()!=null) {
                        // 컴파일 실패는 특정 테스트 입력과 관계없으므로 제출 단계 결과 한 건으로 기록합니다.
                        results.add(new SubmissionService.JudgeResult(null,prepared.failure(),0));
                    } else {
                        stage="cases";
                        for(var t:submissions.cases(s.get("problem_id"))) {
                            stage="case";
                            if(!lease.renew(stage)) return;
                            results.add(prepared.run((String)t.get("input_data"),(String)t.get("expected_output"),
                                ((Number)p.get("time_limit_ms")).intValue(),((Number)p.get("memory_limit_mb")).intValue(),
                                ((Number)t.get("id")).longValue()));
                            if(!lease.current()) return;
                        }
                    }
                }
            } catch(Exception ignored) {
                log.warn("Judge stage failed: submissionId={} stage={}",s.get("id"),stage);
                results.add(new SubmissionService.JudgeResult(null,"SYSTEM_ERROR",0));
            }
            if(!lease.renew("finish")) return;
            try { submissions.finish(s,results); }
            catch(Exception ignored) { log.warn("Judge finish failed: submissionId={}",s.get("id")); }
        }
        catch(Exception ignored) { log.warn("Judge lease failed: submissionId={} stage={}",s.get("id"),stage); }
    }
}
