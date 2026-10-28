package com.drewdrew0414.global.openai;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonValue;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 서버 전용 연결 준비 값입니다. 설정이 모두 유효해도 현재 구현은 외부 호출을 하지 않습니다.
 * 비밀값에는 바인딩 오류를 발생시키는 검증 애너테이션을 사용하지 않습니다.
 * 잘못된 값은 고정된 오류로 닫히며 값 자체를 예외나 로그에 넣지 않습니다.
 */
@ConfigurationProperties(prefix = "openai")
public final class OpenAiProperties {
    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private boolean enabled;
    @JsonIgnore private String apiKey = "";
    private String model = "";
    private List<String> allowedModels = List.of();
    private int maxInputCharacters = 8000;
    private int maxOutputTokens = 512;
    private int timeoutMs = 5000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    @JsonIgnore public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey == null ? "" : apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model == null ? "" : model; }
    public List<String> getAllowedModels() { return Collections.unmodifiableList(new ArrayList<>(allowedModels)); }
    public void setAllowedModels(List<String> allowedModels) {
        this.allowedModels = allowedModels == null ? List.of() : new ArrayList<>(allowedModels);
    }
    public int getMaxInputCharacters() { return maxInputCharacters; }
    public void setMaxInputCharacters(int maxInputCharacters) { this.maxInputCharacters = maxInputCharacters; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }

    /** 향후 구현의 전제 조건일 뿐, 호출 허가나 사용 가능 상태를 뜻하지 않습니다. */
    @JsonIgnore
    public boolean hasValidFutureConfiguration() {
        return validSecret() && validModel(model) && !allowedModels.isEmpty() && allowedModels.size() <= 8
                && allowedModels.stream().allMatch(OpenAiProperties::validModel)
                && allowedModels.contains(model)
                && maxInputCharacters >= 1 && maxInputCharacters <= 16000
                && maxOutputTokens >= 1 && maxOutputTokens <= 2048
                && timeoutMs >= 1000 && timeoutMs <= 10000;
    }

    private boolean validSecret() {
        // 특정 API 키 접두사에 의존하지 않으며 제어 문자·공백·과도한 길이는 허용하지 않습니다.
        return apiKey.length() >= 20 && apiKey.length() <= 4096
                && apiKey.chars().allMatch(character -> character >= 33 && character <= 126);
    }

    private static boolean validModel(String candidate) {
        return candidate != null && MODEL_ID.matcher(candidate).matches();
    }

    @JsonValue
    @Override public String toString() {
        // 모델/allowlist에도 잘못 복사한 비밀이 있을 수 있으므로 원문 설정은 전혀 출력하지 않습니다.
        return "OpenAiProperties[enabled=" + enabled + ", configuration=REDACTED]";
    }
}
