package com.drewdrew0414;

import com.drewdrew0414.domain.user.entity.VerificationPurpose;
import com.drewdrew0414.global.security.EmailCodeHash;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 이메일 코드 소유 전에는 가입 여부를 숨기고, 확인 뒤에는 기존 중복 응답을 유지합니다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SignupEmailPrivacyIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired EmailCodeHash emailCodeHash;
    @Value("${local.server.port}") int port;

    @Test
    void signupRevealsDuplicateEmailOnlyAfterValidCodeAndConsumesSuccessfulCode() throws Exception {
        String registeredEmail = "registered-" + UUID.randomUUID() + "@example.invalid";
        String newEmail = "new-" + UUID.randomUUID() + "@example.invalid";
        db.update("INSERT INTO users(username,password,email) VALUES(?,'unused',?)", username(), registeredEmail);

        HttpResponse<String> registeredProbe = signup(username(), registeredEmail, "12345678");
        HttpResponse<String> newProbe = signup(username(), newEmail, "12345678");
        assertEquals(400, registeredProbe.statusCode());
        assertEquals(400, newProbe.statusCode());
        assertEquals(newProbe.body(), registeredProbe.body());

        String registeredCode = "87654321";
        saveVerifiedCode(registeredEmail, registeredCode);
        assertEquals(409, signup(username(), registeredEmail, registeredCode).statusCode());
        assertEquals(1, db.queryForObject(
                "SELECT COUNT(*) FROM email_verifications WHERE email=? AND purpose='JOIN'",
                Integer.class, registeredEmail));
        assertEquals(1, db.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email=?", Integer.class, registeredEmail));

        String newCode = "23456789";
        saveVerifiedCode(newEmail, newCode);
        assertEquals(200, signup(username(), newEmail, newCode).statusCode());
        assertEquals(1, db.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email=?", Integer.class, newEmail));
        assertEquals(0, db.queryForObject(
                "SELECT COUNT(*) FROM email_verifications WHERE email=? AND purpose='JOIN'",
                Integer.class, newEmail));
    }

    private void saveVerifiedCode(String email, String code) {
        db.update("INSERT INTO email_verifications(email,code_hash,purpose,is_verified,expires_at) " +
                        "VALUES(?,?,'JOIN',TRUE,?)",
                email, emailCodeHash.hash(email, VerificationPurpose.JOIN, code),
                LocalDateTime.now().plusMinutes(5));
    }

    private HttpResponse<String> signup(String username, String email, String code) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/signup"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"" + username +
                        "\",\"email\":\"" + email +
                        "\",\"verificationCode\":\"" + code +
                        "\",\"password\":\"new-password\"}"))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String username() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
