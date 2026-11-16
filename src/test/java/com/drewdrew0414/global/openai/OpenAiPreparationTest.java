package com.drewdrew0414.global.openai;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/** 실제 키·HTTP 클라이언트·OpenAI SDK를 사용하지 않는 오프라인 계약 검사입니다. */
class OpenAiPreparationTest {
    private static final String FAKE_KEY = "test-only-not-a-real-openai-key-20261028";
    private static final String PRIVATE_INPUT = "private-answer-and-hidden-test-must-never-be-logged";

    @Test void defaultsAreDisabledWithoutRequiringAnySecret() {
        var properties = new OpenAiProperties();
        assertFalse(properties.isEnabled());
        assertEquals("", properties.getApiKey());
        assertEquals("", properties.getModel());
        assertTrue(properties.getAllowedModels().isEmpty());
        assertFalse(properties.hasValidFutureConfiguration());
        assertEquals(8000, properties.getMaxInputCharacters());
        assertEquals(512, properties.getMaxOutputTokens());
        assertEquals(5000, properties.getTimeoutMs());
        assertUnavailable(properties, OpenAiUnavailableException.Reason.DISABLED);
    }

    @Test void enablingFlagWithoutSecretAndExplicitModelAllowlistStillFailsClosed() {
        var properties = new OpenAiProperties();
        properties.setEnabled(true);
        assertUnavailable(properties, OpenAiUnavailableException.Reason.INVALID_CONFIGURATION);
        properties.setApiKey(FAKE_KEY);
        properties.setModel("test-approved-model");
        assertUnavailable(properties, OpenAiUnavailableException.Reason.INVALID_CONFIGURATION);
        properties.setAllowedModels(List.of("another-test-model"));
        assertUnavailable(properties, OpenAiUnavailableException.Reason.INVALID_CONFIGURATION);
    }

    @Test void fullyConfiguredEnabledFlagNeverCreatesAnImplementationOrMakesAnyRequest() {
        var properties = configured();
        assertTrue(properties.hasValidFutureConfiguration());
        for (int i = 0; i < 100; i++) {
            assertUnavailable(properties, OpenAiUnavailableException.Reason.NOT_IMPLEMENTED);
        }
        assertThrows(OpenAiUnavailableException.class,
                () -> new PreparedOpenAiGateway(properties).generateForApprovedServerTask(null));
        assertThrows(OpenAiUnavailableException.class,
                () -> new PreparedOpenAiGateway(properties).generateForApprovedServerTask("x".repeat(20000)));
    }

    @Test void invalidSecretIsNotThrownAsABindingValidationErrorAndCannotBeUsed() {
        for (String value : Arrays.asList(null, "", "tiny", FAKE_KEY + "\n", FAKE_KEY + " ", "x".repeat(4097))) {
            var properties = configured();
            assertDoesNotThrow(() -> properties.setApiKey(value));
            assertFalse(properties.hasValidFutureConfiguration());
            assertUnavailable(properties, OpenAiUnavailableException.Reason.INVALID_CONFIGURATION);
            assertFalse(properties.toString().contains(FAKE_KEY));
        }
    }

    @Test void invalidModelAndBudgetSettingsCannotBypassFutureBounds() {
        var properties = configured();
        for (String model : List.of("", "https://untrusted.invalid", "../model", "model?secret=value")) {
            properties.setModel(model);
            assertUnavailable(properties, OpenAiUnavailableException.Reason.INVALID_CONFIGURATION);
        }
        properties = configured(); properties.setAllowedModels(Arrays.asList("test-approved-model", null));
        assertUnavailable(properties, OpenAiUnavailableException.Reason.INVALID_CONFIGURATION);
        properties = configured(); properties.setAllowedModels(List.of("a", "b", "c", "d", "e", "f", "g", "h", "i"));
        assertUnavailable(properties, OpenAiUnavailableException.Reason.INVALID_CONFIGURATION);
        for (int invalid : new int[]{0, 16001}) {
            properties = configured(); properties.setMaxInputCharacters(invalid);
            assertUnavailable(properties, OpenAiUnavailableException.Reason.INVALID_CONFIGURATION);
        }
        for (int invalid : new int[]{0, 2049}) {
            properties = configured(); properties.setMaxOutputTokens(invalid);
            assertUnavailable(properties, OpenAiUnavailableException.Reason.INVALID_CONFIGURATION);
        }
        for (int invalid : new int[]{999, 10001}) {
            properties = configured(); properties.setTimeoutMs(invalid);
            assertUnavailable(properties, OpenAiUnavailableException.Reason.INVALID_CONFIGURATION);
        }
    }

    @Test void keyIsAbsentFromJsonStringificationAndEveryFailureDiagnostic() throws Exception {
        var properties = configured();
        assertFalse(new ObjectMapper().writeValueAsString(properties).contains(FAKE_KEY));
        assertFalse(properties.toString().contains(FAKE_KEY));
        // 모델 칸에 비밀을 잘못 붙여 넣어도 JSON/toString에는 원문을 출력하지 않습니다.
        properties.setModel(FAKE_KEY);
        properties.setAllowedModels(List.of(FAKE_KEY));
        assertFalse(properties.toString().contains(FAKE_KEY));
        assertFalse(new ObjectMapper().writeValueAsString(properties).contains(FAKE_KEY));
        assertUnavailable(properties, OpenAiUnavailableException.Reason.NOT_IMPLEMENTED);
    }

    @Test void realSpringBindingLoadsOptionalServerSettingsButRegistersOnlyTheFailingStub() {
        var runner = new ApplicationContextRunner().withUserConfiguration(OpenAiPreparationConfiguration.class);
        runner.run(context -> {
            assertThat(context).hasSingleBean(OpenAiProperties.class).hasSingleBean(OpenAiGateway.class);
            assertUnavailable(context.getBean(OpenAiProperties.class), OpenAiUnavailableException.Reason.DISABLED);
        });
        runner.withPropertyValues("openai.enabled=true", "openai.api-key=" + FAKE_KEY,
                "openai.model=test-approved-model", "openai.allowed-models=test-approved-model,other-model")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(OpenAiGateway.class)).isInstanceOf(PreparedOpenAiGateway.class);
                    assertUnavailable(context.getBean(OpenAiProperties.class), OpenAiUnavailableException.Reason.NOT_IMPLEMENTED);
                });
        runner.withPropertyValues("openai.enabled=true", "openai.api-key=" + FAKE_KEY + " ")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertUnavailable(context.getBean(OpenAiProperties.class), OpenAiUnavailableException.Reason.INVALID_CONFIGURATION);
                });
    }

    private static OpenAiProperties configured() {
        var properties = new OpenAiProperties();
        properties.setEnabled(true);
        properties.setApiKey(FAKE_KEY);
        properties.setModel("test-approved-model");
        properties.setAllowedModels(List.of("test-approved-model"));
        return properties;
    }

    private static void assertUnavailable(OpenAiProperties properties, OpenAiUnavailableException.Reason reason) {
        var gateway = new PreparedOpenAiGateway(properties);
        var error = assertThrows(OpenAiUnavailableException.class,
                () -> gateway.generateForApprovedServerTask(PRIVATE_INPUT));
        assertEquals(reason, error.getReason());
        assertNull(error.getCause());
        for (String diagnostic : List.of(error.toString(), error.getMessage(), gateway.toString(), properties.toString())) {
            assertFalse(diagnostic.contains(FAKE_KEY));
            assertFalse(diagnostic.contains(PRIVATE_INPUT));
        }
    }
}
