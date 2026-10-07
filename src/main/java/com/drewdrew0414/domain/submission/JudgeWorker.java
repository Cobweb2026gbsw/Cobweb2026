package com.drewdrew0414.domain.submission;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import java.util.ArrayList;

/** 한 번에 제출 하나를 처리합니다. 여러 워커는 DB의 SKIP LOCKED로 서로 다른 제출을 가져갑니다. */
@Component @RequiredArgsConstructor
@ConditionalOnProperty(name="judge.enabled",havingValue="true")
public class JudgeWorker {
    private final SubmissionService submissions;
    private final DockerJudge runner;
    @Scheduled(fixedDelay=1000)
    public void tick() {
        var s=submissions.claim(); if(s==null) return;
        var results=new ArrayList<SubmissionService.JudgeResult>();
        try {
            var p=submissions.problem(s.get("problem_id"));
            try(var prepared=runner.prepare(Language.parse((String)s.get("language")),(String)s.get("source_code"),
                    (String)p.get("execution_mode"))) {
                if(prepared.failure()!=null) {
                    // 컴파일 실패는 특정 테스트 입력과 관계없으므로 제출 단계 결과 한 건으로 기록합니다.
                    results.add(new SubmissionService.JudgeResult(null,prepared.failure(),0));
                } else for(var t:submissions.cases(s.get("problem_id"))) {
                    results.add(prepared.run((String)t.get("input_data"),(String)t.get("expected_output"),
                        ((Number)p.get("time_limit_ms")).intValue(),((Number)p.get("memory_limit_mb")).intValue(),
                        ((Number)t.get("id")).longValue()));
                }
            }
        } catch(Exception e) {
            results.add(new SubmissionService.JudgeResult(null,"SYSTEM_ERROR",0));
        }
        submissions.finish(s,results);
    }
}
