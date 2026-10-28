package com.drewdrew0414;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 전용 test DB에서 기존 이름을 변경하지 않고 공개 프로필의 query 별칭과 SPA 경로를 검사합니다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ProfileQueryIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private HttpResponse<String> get(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(5)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"..", "a/b", "기존사용자"})
    void queryAliasPreservesExistingDatabaseNamesAndOnlyReturnsPublicFields(String username) throws Exception {
        // 재실행 시 같은 이름이 있으면 그 사용자 행을 변경하지 않습니다. 테스트 콘텐츠도 삭제하지 않습니다.
        db.update("INSERT INTO users(username,password,role) VALUES(?,'profile-query-test-only','USER') ON CONFLICT DO NOTHING", username);
        String query = "?username=" + URLEncoder.encode(username, StandardCharsets.UTF_8);

        var response = get("/api/profiles" + query);
        assertEquals(200, response.statusCode(), username);
        var envelope = json.readTree(response.body());
        assertTrue(envelope.get("success").asBoolean());
        var profile = envelope.get("data");
        assertEquals(username, profile.get("username").asText());
        assertTrue(profile.get("solvedProblems").isArray());
        for (String privateField : new String[]{"id", "email", "password", "role", "provider_id", "accessToken"})
            assertFalse(profile.has(privateField), privateField);
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM users WHERE username=?", Integer.class, username));

        var page = get("/users" + query);
        assertEquals(200, page.statusCode(), "익명 SPA 접근: " + username);
        assertTrue(page.body().contains("id=\"root\""));
        assertEquals(get("/ui/index.html").body(), page.body());
    }

    @Test
    void legacySingleSegmentEndpointAndPageRemainCompatible() throws Exception {
        String username = "p" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        db.update("INSERT INTO users(username,password,role) VALUES(?,'profile-query-test-only','USER')", username);
        var legacy = get("/api/profiles/" + username);
        var alias = get("/api/profiles?username=" + username);
        assertEquals(200, legacy.statusCode());
        assertEquals(200, alias.statusCode());
        assertEquals(json.readTree(legacy.body()).get("data"), json.readTree(alias.body()).get("data"));
        assertEquals(200, get("/users/" + username).statusCode());
    }
}
