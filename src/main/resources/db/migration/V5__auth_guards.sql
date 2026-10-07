-- Access JWT를 비밀번호 변경·로그아웃 즉시 무효화하는 사용자별 버전입니다.
ALTER TABLE users ADD COLUMN access_token_version BIGINT NOT NULL DEFAULT 0;

-- 여러 앱 인스턴스에서 공유하는 인증 시도 제한 상태입니다. 키는 애플리케이션에서 SHA-256으로 변환합니다.
CREATE TABLE auth_rate_limits (
    limit_key CHAR(64) PRIMARY KEY,
    window_started_at DATETIME(6) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    INDEX idx_auth_rate_limits_started_at (window_started_at)
);

-- 인증 코드 발급·검증·소비를 email+purpose 단위로 직렬화하는 잠금 행입니다.
CREATE TABLE email_verification_guards (
    guard_key CHAR(64) PRIMARY KEY,
    touched_at DATETIME(6) NOT NULL,
    INDEX idx_email_verification_guards_touched_at (touched_at)
);
