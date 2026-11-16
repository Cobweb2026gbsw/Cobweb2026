package com.drewdrew0414.global.openai;

/** 비밀 설정·입력·외부 응답·원인 예외를 보관하지 않는 고정 오류입니다. */
public final class OpenAiUnavailableException extends IllegalStateException {
    public enum Reason { DISABLED, INVALID_CONFIGURATION, NOT_IMPLEMENTED }
    private final Reason reason;

    OpenAiUnavailableException(Reason reason) {
        super("OpenAI 연결은 준비 단계이며 사용할 수 없습니다.");
        this.reason = reason;
    }

    public Reason getReason() { return reason; }
}
