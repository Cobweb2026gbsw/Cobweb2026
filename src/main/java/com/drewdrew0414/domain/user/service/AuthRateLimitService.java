package com.drewdrew0414.domain.user.service;

import com.drewdrew0414.domain.user.exception.TooManyAuthAttemptsException;
import com.drewdrew0414.global.security.TokenHash;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;

/** DB 행 잠금으로 모든 앱 인스턴스에 동일한 인증 시도 제한을 적용합니다. */
@Service
@RequiredArgsConstructor
public class AuthRateLimitService {
    private final JdbcTemplate db;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void hit(String scope, String identity, int maximum, Duration window) {
        // 원문 이메일·아이디·IP를 제한 테이블에 남기지 않습니다.
        String key = TokenHash.sha256(scope + "\0" + identity.toLowerCase(Locale.ROOT));
        LocalDateTime now = LocalDateTime.now();
        db.update("INSERT IGNORE INTO auth_rate_limits(limit_key,window_started_at,attempts) VALUES(?,?,0)", key, now);
        var state = db.queryForMap(
                "SELECT window_started_at,attempts FROM auth_rate_limits WHERE limit_key=? FOR UPDATE", key);
        Object storedStart = state.get("window_started_at");
        LocalDateTime start = storedStart instanceof LocalDateTime value ? value
                : ((java.sql.Timestamp) storedStart).toLocalDateTime();
        int attempts = ((Number) state.get("attempts")).intValue();
        if (!start.isAfter(now.minus(window))) {
            db.update("UPDATE auth_rate_limits SET window_started_at=?,attempts=1 WHERE limit_key=?", now, key);
            return;
        }
        if (attempts >= maximum) throw new TooManyAuthAttemptsException();
        db.update("UPDATE auth_rate_limits SET attempts=attempts+1 WHERE limit_key=?", key);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockVerification(String email, String purpose) {
        String key = TokenHash.sha256("verification-lock\0" + purpose + "\0" + email.toLowerCase(Locale.ROOT));
        // 잠금은 호출자의 트랜잭션이 끝날 때까지 유지됩니다. 발급·검증·소비가 같은 순서로 대기합니다.
        db.update("INSERT INTO email_verification_guards(guard_key,touched_at) VALUES(?,?) " +
                "ON DUPLICATE KEY UPDATE touched_at=VALUES(touched_at)", key, LocalDateTime.now());
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
    public void removeOldWindows() {
        db.update("DELETE FROM auth_rate_limits WHERE window_started_at < ?",
                LocalDateTime.now().minusDays(2));
        db.update("DELETE FROM email_verification_guards WHERE touched_at < ?",
                LocalDateTime.now().minusDays(2));
    }
}
