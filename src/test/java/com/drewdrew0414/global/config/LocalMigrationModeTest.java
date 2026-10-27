package com.drewdrew0414.global.config;

import java.util.ArrayList;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class LocalMigrationModeTest {
    private static final String[] VALID = {
        "--local.migrate-only=true", "--spring.profiles.active=local",
        "--spring.main.web-application-type=none", "--spring.flyway.enabled=true",
        "--judge.enabled=false", "--demo.seed=false", "--curriculum.level0.enabled=false"
    };
    private static final String DATABASE = "jdbc:mysql://127.0.0.1:3307/cobweb2026?serverTimezone=UTC";

    @Test void normalWebAndWorkerArgumentsDoNotActivateOneShotMode() {
        assertFalse(LocalMigrationMode.requested(new String[]{}));
        assertFalse(LocalMigrationMode.requested(new String[]{"--spring.profiles.active=local", "--judge.enabled=true"}));
        assertFalse(LocalMigrationMode.requested(new String[]{"--local.migrate-only-alias=true"}));
    }

    @Test void explicitFlagRequiresValidatedArgumentsEvenWhenFalseOrMissingValue() {
        assertTrue(LocalMigrationMode.requested(VALID));
        assertTrue(LocalMigrationMode.requested(new String[]{"--local.migrate-only=false"}));
        assertTrue(LocalMigrationMode.requested(new String[]{"--local.migrate-only"}));
        assertThrows(IllegalArgumentException.class, () -> LocalMigrationMode.validateArguments(new String[]{"--local.migrate-only=false"}));
        assertThrows(IllegalArgumentException.class, () -> LocalMigrationMode.validateArguments(new String[]{"--local.migrate-only"}));
    }

    @Test void exactLocalNonWebMigrationContractIsAccepted() {
        assertDoesNotThrow(() -> LocalMigrationMode.validateArguments(VALID));
        var reversed = new ArrayList<>(Arrays.asList(VALID));
        java.util.Collections.reverse(reversed);
        assertDoesNotThrow(() -> LocalMigrationMode.validateArguments(reversed.toArray(String[]::new)));
    }

    @Test void eachRequiredArgumentMustBePresentAndCannotConflict() {
        for (int index = 0; index < VALID.length; index++) {
            var missing = new ArrayList<>(Arrays.asList(VALID));
            missing.remove(index);
            assertThrows(IllegalArgumentException.class, () -> LocalMigrationMode.validateArguments(missing.toArray(String[]::new)));
            String[] conflicting = VALID.clone();
            conflicting[index] = VALID[index].substring(0, VALID[index].indexOf('=') + 1) + "invalid";
            assertThrows(IllegalArgumentException.class, () -> LocalMigrationMode.validateArguments(conflicting));
        }
    }

    @Test void duplicateArgumentsExtraProfilesAliasesAndCliPasswordsAreRejectedWithoutEchoingValues() {
        for (String extra : new String[]{VALID[0], "--spring.profiles.include=prod",
                "--spring.main.webApplicationType=servlet", "--spring.flyway.password=test-only-secret"}) {
            var args = new ArrayList<>(Arrays.asList(VALID));
            args.add(extra);
            var error = assertThrows(IllegalArgumentException.class,
                () -> LocalMigrationMode.validateArguments(args.toArray(String[]::new)));
            assertFalse(error.getMessage().contains(extra));
            assertFalse(error.getMessage().contains("test-only-secret"));
        }
    }

    @Test void resolvedLocalEnvironmentWithSeparatedCredentialsIsAccepted() {
        assertDoesNotThrow(() -> LocalMigrationMode.validateEnvironment(environment()));
    }

    @Test void resolvedEnvironmentCannotAddProdOrReplaceLocalProfile() {
        for (String[] profiles : new String[][]{{}, {"prod"}, {"local", "prod"}, {"local", "test"}}) {
            var environment = environment();
            // 빈 activeProfiles는 Spring이 spring.profiles.active 속성에서 다시 채우므로
            // 프로필이 실제로 없는 환경은 그 원본 속성도 비워 재현합니다.
            if (profiles.length == 0) environment.setProperty("spring.profiles.active", "");
            environment.setActiveProfiles(profiles);
            assertThrows(IllegalArgumentException.class, () -> LocalMigrationMode.validateEnvironment(environment));
        }
    }

    @Test void resolvedFlagsCannotEnableWebJudgingDemoOrDisableFlyway() {
        for (String key : new String[]{"local.migrate-only", "spring.main.web-application-type",
                "spring.flyway.enabled", "judge.enabled", "demo.seed", "curriculum.level0.enabled"}) {
            var environment = environment().withProperty(key, "invalid");
            assertThrows(IllegalArgumentException.class, () -> LocalMigrationMode.validateEnvironment(environment));
        }
    }

    @Test void resolvedDatabaseAndUserConnectionsCannotEscapeDedicatedLocalDatabase() {
        for (String key : new String[]{"spring.datasource.url", "spring.flyway.url",
                "spring.datasource.username", "spring.flyway.user"}) {
            var environment = environment().withProperty(key, "invalid-sensitive-value");
            var error = assertThrows(IllegalArgumentException.class, () -> LocalMigrationMode.validateEnvironment(environment));
            assertFalse(error.getMessage().contains("invalid-sensitive-value"));
        }
    }

    private static MockEnvironment environment() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        for (String arg : VALID) {
            int separator = arg.indexOf('=');
            environment.setProperty(arg.substring(2, separator), arg.substring(separator + 1));
        }
        return environment.withProperty("spring.datasource.url", DATABASE)
            .withProperty("spring.flyway.url", DATABASE).withProperty("spring.datasource.username", "cobweb")
            .withProperty("spring.flyway.user", "root");
    }
}
