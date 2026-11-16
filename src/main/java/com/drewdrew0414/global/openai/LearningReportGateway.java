package com.drewdrew0414.global.openai;

/** 학습 리포트 생성 작업에서 서버가 집계한 사실을 전달하는 내부 경계입니다. */
public interface LearningReportGateway {
    boolean isAvailable();

    /**
     * factsJson에는 식별자나 제출 원문 없이 집계한 사실만 들어갑니다.
     * previousSummary는 같은 사용자의 직전 성공 리포트에서 가져옵니다.
     */
    Summary generate(String factsJson, String previousSummary);

    record Summary(String userSummary, String carryForwardSummary) {}
}
