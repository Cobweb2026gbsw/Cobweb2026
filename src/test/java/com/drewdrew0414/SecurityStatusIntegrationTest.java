package com.drewdrew0414;

import com.drewdrew0414.global.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 인증 누락(401)과 인증된 사용자의 권한 부족(403)을 HTTP 경계에서 구분합니다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SecurityStatusIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired JwtTokenProvider jwt;
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;

    @Test void missingAuthenticationIsUnauthorized() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/me")).GET().build();
        assertEquals(401, HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode());

        var logout = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/logout"))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        assertEquals(401, HttpClient.newHttpClient().send(logout, HttpResponse.BodyHandlers.discarding()).statusCode());
    }

    @Test void authenticatedNonOperatorIsForbidden() throws Exception {
        String name = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        db.update("INSERT INTO users(username,password,role) VALUES(?,'unused','USER')", name);
        long id = db.queryForObject("SELECT id FROM users WHERE username=?", Long.class, name);
        String access = jwt.generateAccessToken(id, name, "USER", 0);
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/problems/1000/review"))
                .header("Authorization", "Bearer " + access)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"status\":\"PUBLISHED\"}"))
                .build();
        assertEquals(403, HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
    }
}
