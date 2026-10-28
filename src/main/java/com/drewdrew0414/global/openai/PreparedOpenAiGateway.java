package com.drewdrew0414.global.openai;

/** HTTP 클라이언트·SDK·스케줄러·재시도가 없는 닫힌 스텁입니다. 호출/과금은 0입니다. */
public final class PreparedOpenAiGateway implements OpenAiGateway {
    private final OpenAiProperties properties;

    public PreparedOpenAiGateway(OpenAiProperties properties) { this.properties = properties; }

    @Override public String generateForApprovedServerTask(String input) {
        // input을 로깅·저장·전송하거나 예외에 포함하지 않습니다.
        var reason = !properties.isEnabled() ? OpenAiUnavailableException.Reason.DISABLED
                : !properties.hasValidFutureConfiguration() ? OpenAiUnavailableException.Reason.INVALID_CONFIGURATION
                : OpenAiUnavailableException.Reason.NOT_IMPLEMENTED;
        throw new OpenAiUnavailableException(reason);
    }

    @Override public String toString() { return "PreparedOpenAiGateway[unavailable]"; }
}
