package com.drewdrew0414.global.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

class NeonDataSourceEnvironmentPostProcessorTest {
    private static final String POOLED = "postgresql://web%3Auser:pass+word%40@branch-pooler.example.test/db?sslmode=require";
    private static final String DIRECT = "postgresql://migration:migrate-pass@branch.example.test/db";

    @TempDir Path directory;

    @Test
    void readsNeonDotenvAndSeparatesPooledAndDirectJdbcConnections() throws IOException {
        var file = directory.resolve(".env.local");
        Files.writeString(file, "DATABASE_URL=\"" + POOLED + "\"\nDATABASE_URL_UNPOOLED=" + DIRECT + "\n");
        var environment = new MockEnvironment().withProperty("spring.flyway.enabled", "true");
        environment.setActiveProfiles("neon");

        NeonDataSourceEnvironmentPostProcessor.configure(environment, file);

        String jdbc = environment.getRequiredProperty("spring.datasource.url");
        assertTrue(jdbc.startsWith("jdbc:postgresql://branch-pooler.example.test:5432/db?sslmode=verify-full"));
        assertFalse(jdbc.contains("pass"));
        assertEquals("web:user", environment.getProperty("spring.datasource.username"));
        assertEquals("pass+word@", environment.getProperty("spring.datasource.password"));
        assertTrue(environment.getRequiredProperty("spring.flyway.url").startsWith("jdbc:postgresql://branch.example.test:5432/db?"));
        assertEquals("migration", environment.getProperty("spring.flyway.user"));
    }

    @Test
    void ignoresTheNeonFileWithoutTheProfile() throws IOException {
        var file = directory.resolve(".env.local");
        Files.writeString(file, "DATABASE_URL=" + POOLED);
        var environment = new MockEnvironment().withProperty("spring.datasource.url", "jdbc:postgresql://localhost/local");

        NeonDataSourceEnvironmentPostProcessor.configure(environment, file);

        assertEquals("jdbc:postgresql://localhost/local", environment.getProperty("spring.datasource.url"));
        assertNull(environment.getPropertySources().get("neonJdbc"));
    }

    @Test
    void usesProvidedEnvironmentAndRequiresDirectConnectionForFlyway() throws IOException {
        var file = directory.resolve(".env.local");
        Files.writeString(file, "DATABASE_URL=not-a-uri");
        var environment = new MockEnvironment().withProperty("DATABASE_URL", POOLED);
        environment.setActiveProfiles("neon");

        NeonDataSourceEnvironmentPostProcessor.configure(environment, file);
        assertEquals("web:user", environment.getProperty("spring.datasource.username"));
        assertNull(environment.getProperty("spring.flyway.url"));

        environment.setProperty("spring.flyway.enabled", "true");
        for (String wrong : new String[]{"", POOLED, DIRECT.replace("branch.example", "other.example")}) {
            environment.setProperty("DATABASE_URL_UNPOOLED", wrong);
            var error = assertThrows(IllegalStateException.class,
                () -> NeonDataSourceEnvironmentPostProcessor.configure(environment, file));
            assertTrue(error.getMessage().contains("DATABASE_URL_UNPOOLED"));
            assertNull(error.getCause());
        }
    }

    @Test
    void malformedUriNeverAppearsInAnError() {
        var environment = new MockEnvironment().withProperty("DATABASE_URL", "postgresql://user:private-test-marker%@host/db");
        environment.setActiveProfiles("neon");

        var error = assertThrows(IllegalStateException.class,
            () -> NeonDataSourceEnvironmentPostProcessor.configure(environment, directory.resolve(".env.local")));

        assertFalse(error.getMessage().contains("private-test-marker"));
        assertNull(error.getCause());
    }

    @Test
    void springRegistersTheProcessorOnTheNormalStartupPath() {
        var app = new SpringApplication(Fixture.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(Map.of("DATABASE_URL", POOLED, "spring.flyway.enabled", "false", "spring.main.banner-mode", "off"));

        try (var context = app.run("--spring.profiles.active=neon")) {
            assertEquals("web:user", context.getEnvironment().getProperty("spring.datasource.username"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Fixture {}
}
