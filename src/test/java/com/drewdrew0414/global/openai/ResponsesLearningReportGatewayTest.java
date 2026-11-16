package com.drewdrew0414.global.openai;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ResponsesLearningReportGatewayTest {
    private static final String TEST_KEY = "test-only-fake-api-key-for-learn-report";
    private static final String FACTS = "{\"solvedCount\":5,\"firstPassCount\":3}";
    private static final String SUMMARY = "첫 제출에 세 문제를 맞혔어요.";
    private static final String CARRY = "첫 제출 통과 3/5. 다음 회차에 배열 문제를 확인.";
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = mock(HttpClient.class);

    @Test
    void sendsOnlyFixedResponsesRequestWithBoundedStructuredOutput() {
        var properties = configured();
        var gateway = new ResponsesLearningReportGateway(properties, json, client);
        when(client.<byte[]>sendAsync(any(HttpRequest.class), any())).thenAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            assertThat(request.uri()).isEqualTo(URI.create("https://api.openai.com/v1/responses"));
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.headers().firstValue("Authorization")).contains("Bearer " + TEST_KEY);

            var body = json.readTree(readBody(request));
            assertThat(body.path("model").asString()).isEqualTo("gpt-6-luna");
            assertThat(body.path("reasoning").path("effort").asString()).isEqualTo("none");
            assertThat(body.path("service_tier").asString()).isEqualTo("default");
            assertThat(body.path("store").booleanValue()).isFalse();
            assertThat(body.path("max_output_tokens").intValue()).isEqualTo(512);
            assertThat(body.has("tools")).isFalse();
            var format = body.path("text").path("format");
            assertThat(format.path("type").asString()).isEqualTo("json_schema");
            assertThat(format.path("strict").booleanValue()).isTrue();
            assertThat(format.path("schema").path("additionalProperties").booleanValue()).isFalse();
            assertThat(format.path("schema").path("required").toString())
                    .isEqualTo("[\"userSummary\",\"carryForwardSummary\"]");
            var input = json.readTree(body.path("input").asString());
            assertThat(input.size()).isEqualTo(2);
            assertThat(input.path("facts")).isEqualTo(json.readTree(FACTS));
            assertThat(input.path("previousSummary").asString()).isEqualTo("이전 학습 요약");
            assertThat(readBody(request)).doesNotContain(TEST_KEY);
            return CompletableFuture.completedFuture(response(200, completed(outputJson())));
        });

        assertThat(gateway.generate(FACTS, "이전 학습 요약"))
                .isEqualTo(new LearningReportGateway.Summary(SUMMARY, CARRY));
        verify(client).sendAsync(any(HttpRequest.class), any());
    }

    @Test
    void rejectsUnconfiguredAndMalformedFactsBeforeNetwork() {
        var properties = new OpenAiProperties();
        var gateway = new ResponsesLearningReportGateway(properties, json, client);
        assertThat(gateway.isAvailable()).isFalse();
        assertSanitizedFailure(gateway, FACTS);

        properties = configured();
        var enabled = new ResponsesLearningReportGateway(properties, json, client);
        assertThat(enabled.isAvailable()).isTrue();
        for (String invalid : List.of("", "[]", "{} {}", "{\"x\":1,\"x\":2}",
                "{\"x\":\"" + "a".repeat(8100) + "\"}")) {
            assertSanitizedFailure(enabled, invalid);
        }
        verifyNoInteractions(client);
    }

    @Test
    void refusesProviderErrorsIncompleteAndUnexpectedOutputWithoutLeakingPayload() {
        var gateway = new ResponsesLearningReportGateway(configured(), json, client);
        for (HttpResponse<byte[]> bad : List.of(
                response(429, TEST_KEY),
                response(200, "{\"status\":\"incomplete\",\"output\":[]}"),
                response(200, json.writeValueAsString(Map.of("status", "completed", "output",
                        List.of(message(Map.of("type", "refusal", "refusal", TEST_KEY)))))),
                response(200, completed("{\"userSummary\":\"안녕\",\"carryForwardSummary\":\"기록\",\"score\":99}")),
                response(200, completed("{\"userSummary\":\"안녕\",\"carryForwardSummary\":\"기록\",\"userSummary\":\"중복\"}")),
                response(200, completed(outputJson() + " {}")),
                response(200, completed(json.writeValueAsString(Map.of("userSummary", "가".repeat(201),
                        "carryForwardSummary", CARRY)))),
                response(200, "x".repeat(65537)))) {
            when(client.<byte[]>sendAsync(any(HttpRequest.class), any())).thenReturn(CompletableFuture.completedFuture(bad));
            assertSanitizedFailure(gateway, FACTS);
        }
        when(client.<byte[]>sendAsync(any(HttpRequest.class), any()))
                .thenReturn(CompletableFuture.failedFuture(new IOException(TEST_KEY)));
        assertSanitizedFailure(gateway, FACTS);
    }

    @Test
    void cancelsAnExchangeThatNeverCompletesWithinTheDeadline() {
        var properties = configured();
        properties.setTimeoutMs(1000);
        var gateway = new ResponsesLearningReportGateway(properties, json, client);
        var exchange = new CompletableFuture<HttpResponse<byte[]>>();
        when(client.<byte[]>sendAsync(any(HttpRequest.class), any())).thenReturn(exchange);

        assertSanitizedFailure(gateway, FACTS);
        assertThat(exchange.isCancelled()).isTrue();
        verify(client).sendAsync(any(HttpRequest.class), any());
    }

    private static void assertSanitizedFailure(LearningReportGateway gateway, String input) {
        assertThatThrownBy(() -> gateway.generate(input, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("학습 리포트 생성에 실패했습니다.")
                .hasNoCause();
    }

    private static OpenAiProperties configured() {
        var properties = new OpenAiProperties();
        properties.setEnabled(true);
        properties.setApiKey(TEST_KEY);
        properties.setModel("gpt-6-luna");
        properties.setAllowedModels(List.of("gpt-6-luna"));
        return properties;
    }

    private String outputJson() {
        return json.writeValueAsString(Map.of("userSummary", SUMMARY, "carryForwardSummary", CARRY));
    }

    private String completed(String outputText) {
        return json.writeValueAsString(Map.of("status", "completed", "output",
                List.of(message(Map.of("type", "output_text", "text", outputText)))));
    }

    private static Map<String, Object> message(Map<String, String> content) {
        return Map.of("type", "message", "role", "assistant", "status", "completed", "content", List.of(content));
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<byte[]> response(int status, String body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return response;
    }

    private static String readBody(HttpRequest request) {
        var body = new ByteArrayOutputStream();
        var done = new CompletableFuture<String>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer buffer) {
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                body.writeBytes(bytes);
            }
            @Override public void onError(Throwable error) { done.completeExceptionally(error); }
            @Override public void onComplete() { done.complete(body.toString(StandardCharsets.UTF_8)); }
        });
        return done.join();
    }
}
