package com.drewdrew0414.global.config;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.Profiles;

/** Neon CLI의 PostgreSQL URI를 서버의 JDBC 및 Flyway 설정으로 변환합니다. */
public final class NeonDataSourceEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    private static final String POOLED_URL = "DATABASE_URL";
    private static final String DIRECT_URL = "DATABASE_URL_UNPOOLED";
    private static final String JDBC_TLS = "?sslmode=verify-full"
        + "&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory&channelBinding=require";

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        configure(environment, Path.of(".env.local"));
    }

    static void configure(ConfigurableEnvironment environment, Path file) {
        if (!environment.acceptsProfiles(Profiles.of("neon"))) return;
        if (environment.acceptsProfiles(Profiles.of("local", "test"))) {
            throw new IllegalStateException("neon 프로필은 local 또는 test 프로필과 함께 활성화할 수 없습니다.");
        }

        boolean migrate = environment.getProperty("spring.flyway.enabled", Boolean.class, false);
        String pooled = environment.getProperty(POOLED_URL);
        String direct = migrate ? environment.getProperty(DIRECT_URL) : null;
        if (pooled == null || (migrate && direct == null)) {
            Properties dotenv = readLocalEnv(file);
            if (pooled == null) pooled = dotenv.getProperty(POOLED_URL);
            if (migrate && direct == null) direct = dotenv.getProperty(DIRECT_URL);
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        var applicationConnection = parse(pooled, POOLED_URL);
        properties.put("spring.datasource.url", applicationConnection.url());
        properties.put("spring.datasource.username", applicationConnection.username());
        properties.put("spring.datasource.password", applicationConnection.password());
        if (migrate) {
            var migrationConnection = parse(direct, DIRECT_URL);
            if (migrationConnection.host().contains("-pooler.")
                || !applicationConnection.host().replaceFirst("-pooler\\.", ".").equals(migrationConnection.host())
                || !applicationConnection.database().equals(migrationConnection.database())) {
                throw invalid(DIRECT_URL);
            }
            properties.put("spring.flyway.url", migrationConnection.url());
            properties.put("spring.flyway.user", migrationConnection.username());
            properties.put("spring.flyway.password", migrationConnection.password());
        }

        environment.getPropertySources().addFirst(new MapPropertySource("neonJdbc", properties));
    }

    private static Properties readLocalEnv(Path file) {
        var dotenv = new Properties();
        if (Files.notExists(file)) return dotenv;
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            dotenv.load(reader);
            return dotenv;
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Neon 설정 파일 .env.local을 읽을 수 없습니다.");
        }
    }

    private static Connection parse(String value, String key) {
        if (value == null || value.isBlank()) throw invalid(key);
        try {
            String uriValue = value.strip();
            if (uriValue.length() > 1 && ((uriValue.startsWith("\"") && uriValue.endsWith("\""))
                || (uriValue.startsWith("'") && uriValue.endsWith("'")))) {
                uriValue = uriValue.substring(1, uriValue.length() - 1);
            }
            URI uri = URI.create(uriValue);
            String userInfo = uri.getRawUserInfo();
            int separator = userInfo == null ? -1 : userInfo.indexOf(':');
            String path = uri.getRawPath();
            if (!("postgresql".equals(uri.getScheme()) || "postgres".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535
                || separator < 1 || separator == userInfo.length() - 1
                || path == null || path.length() < 2 || path.indexOf('/', 1) >= 0) {
                throw invalid(key);
            }
            String username = decode(userInfo.substring(0, separator));
            String password = decode(userInfo.substring(separator + 1));
            if (username.isBlank() || password.isBlank()
                || username.chars().anyMatch(Character::isISOControl)
                || password.chars().anyMatch(Character::isISOControl)) {
                throw invalid(key);
            }
            int port = uri.getPort() == -1 ? 5432 : uri.getPort();
            return new Connection("jdbc:postgresql://" + uri.getHost() + ":" + port + path + JDBC_TLS,
                username, password, uri.getHost(), decode(path.substring(1)));
        } catch (IllegalArgumentException exception) {
            // URI 파싱 예외의 메시지와 원인은 비밀번호를 포함할 수 있습니다.
            throw invalid(key);
        }
    }

    private static String decode(String value) {
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static IllegalStateException invalid(String key) {
        return new IllegalStateException("Neon 접속 설정 " + key + "을 확인하세요.");
    }

    private record Connection(String url, String username, String password, String host, String database) {
        @Override
        public String toString() {
            return "NeonConnection";
        }
    }
}
