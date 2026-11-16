package com.drewdrew0414;

import com.drewdrew0414.domain.user.entity.VerificationPurpose;
import com.drewdrew0414.domain.user.service.MailService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 공개 재설정 요청의 응답과 발송 제한을 실제 HTTP·PostgreSQL 경로에서 확인합니다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PasswordResetRequestIntegrationTest {
    @Autowired JdbcTemplate db;
    @MockitoBean MailService mailService;
    @Value("${local.server.port}") int port;

    @Test
    void unknownEmailHasSameSuccessResponseAndCannotBypassSendLimit() throws Exception {
        String username = "r" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String known = "known-" + UUID.randomUUID() + "@example.invalid";
        String missing = "missing-" + UUID.randomUUID() + "@example.invalid";
        db.update("INSERT INTO users(username,password,email) VALUES(?,'unused',?)", username, known);

        HttpResponse<String> knownResponse = requestReset(known);
        HttpResponse<String> missingResponse = requestReset(missing);
        assertEquals(200, knownResponse.statusCode());
        assertEquals(200, missingResponse.statusCode());
        assertEquals(knownResponse.body(), missingResponse.body());
        assertEquals(200, requestReset(missing).statusCode());
        // 공용 코드 발송 API로 PASSWORD_RESET을 선택해도 같은 정책과 횟수를 사용합니다.
        assertEquals(200, requestGenericSend(missing).statusCode());
        assertEquals(429, requestReset(missing).statusCode());

        assertEquals(1, db.queryForObject(
                "SELECT COUNT(*) FROM email_verifications WHERE email=? AND purpose='PASSWORD_RESET'",
                Integer.class, known));
        assertEquals(0, db.queryForObject(
                "SELECT COUNT(*) FROM email_verifications WHERE email=? AND purpose='PASSWORD_RESET'",
                Integer.class, missing));
        verify(mailService).sendVerificationCode(eq(known), anyString(), eq(VerificationPurpose.PASSWORD_RESET));
        verify(mailService, never()).sendVerificationCode(eq(missing), anyString(), eq(VerificationPurpose.PASSWORD_RESET));
    }

    private HttpResponse<String> requestReset(String email) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/auth/password-reset/request"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"" + email + "\"}"))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> requestGenericSend(String email) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/email-verifications"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"" + email + "\",\"purpose\":\"PASSWORD_RESET\"}"))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
