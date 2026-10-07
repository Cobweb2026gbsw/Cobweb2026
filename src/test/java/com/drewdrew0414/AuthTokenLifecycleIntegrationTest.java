package com.drewdrew0414;

import com.drewdrew0414.domain.user.entity.VerificationPurpose;
import com.drewdrew0414.domain.user.exception.InvalidResetTokenException;
import com.drewdrew0414.domain.user.exception.VerificationCodeMismatchException;
import com.drewdrew0414.domain.user.service.EmailVerificationService;
import com.drewdrew0414.domain.user.service.PasswordResetService;
import com.drewdrew0414.global.security.EmailCodeHash;
import com.drewdrew0414.global.security.TokenHash;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 인증 토큰의 계정 단위 폐기와 같은 초에 발급된 코드의 순서를 실제 MySQL에서 확인합니다. */
@SpringBootTest
@ActiveProfiles("test")
class AuthTokenLifecycleIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired PasswordResetService passwordReset;
    @Autowired EmailVerificationService emailVerification;
    @Autowired PasswordEncoder encoder;
    @Autowired EmailCodeHash emailCodeHash;

    @Test
    void passwordChangeRevokesOtherOutstandingResetTokens() {
        String username = "r" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        db.update("INSERT INTO users(username,password) VALUES(?,?)", username, encoder.encode("old-password"));
        long userId = db.queryForObject("SELECT id FROM users WHERE username=?", Long.class, username);
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        LocalDateTime expiry = LocalDateTime.now().plusMinutes(30);
        db.update("INSERT INTO password_reset(user_id,reset_token,expires_at) VALUES(?,?,?)",
                userId, TokenHash.sha256(first), expiry);
        db.update("INSERT INTO password_reset(user_id,reset_token,expires_at) VALUES(?,?,?)",
                userId, TokenHash.sha256(second), expiry);

        passwordReset.confirmReset(first, "new-password");

        assertEquals(2, db.queryForObject(
                "SELECT COUNT(*) FROM password_reset WHERE user_id=? AND is_used=TRUE", Integer.class, userId));
        assertThrows(InvalidResetTokenException.class,
                () -> passwordReset.confirmReset(second, "replayed-password"));
        assertTrue(encoder.matches("new-password",
                db.queryForObject("SELECT password FROM users WHERE id=?", String.class, userId)));
    }

    @Test
    void latestVerificationCodeWinsWhenCreatedAtIsTheSameSecond() {
        String email = "same-second-" + UUID.randomUUID() + "@example.invalid";
        String first = "12345678";
        String second = "87654321";
        LocalDateTime createdAt = LocalDateTime.now().withNano(0);
        LocalDateTime expiry = createdAt.plusMinutes(5);
        db.update("INSERT INTO email_verifications(email,code_hash,purpose,expires_at,created_at) " +
                        "VALUES(?,?,'JOIN',?,?)",
                email, emailCodeHash.hash(email, VerificationPurpose.JOIN, first), expiry, createdAt);
        db.update("INSERT INTO email_verifications(email,code_hash,purpose,expires_at,created_at) " +
                        "VALUES(?,?,'JOIN',?,?)",
                email, emailCodeHash.hash(email, VerificationPurpose.JOIN, second), expiry, createdAt);

        assertThrows(VerificationCodeMismatchException.class,
                () -> emailVerification.verifyCode(email, first, VerificationPurpose.JOIN));
        emailVerification.verifyCode(email, second, VerificationPurpose.JOIN);

        assertEquals(1, db.queryForObject(
                "SELECT is_verified FROM email_verifications WHERE email=? ORDER BY id DESC LIMIT 1",
                Integer.class, email));
        assertEquals(0, db.queryForObject(
                "SELECT is_verified FROM email_verifications WHERE email=? ORDER BY id ASC LIMIT 1",
                Integer.class, email));
    }
}
