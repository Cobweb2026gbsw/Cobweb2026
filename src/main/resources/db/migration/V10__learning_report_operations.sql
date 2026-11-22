-- 웹 인스턴스가 바뀌어도 신규 생성 중지 상태와 이미 접수한 시도별 결과를 보존합니다.
CREATE TABLE learning_report_controls (
    singleton BOOLEAN PRIMARY KEY CHECK (singleton),
    paused BOOLEAN NOT NULL DEFAULT FALSE,
    changed_at TIMESTAMPTZ,
    changed_by BIGINT REFERENCES users(id) ON DELETE SET NULL
);
INSERT INTO learning_report_controls(singleton) VALUES (TRUE);

CREATE TABLE learning_report_attempts (
    claim_token UUID PRIMARY KEY,
    usage_date DATE NOT NULL REFERENCES learning_report_daily_budget(usage_date) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    outcome VARCHAR(10) NOT NULL CHECK (outcome IN ('RUNNING', 'SUCCEEDED', 'FAILED'))
);
CREATE INDEX idx_learning_report_attempts_day ON learning_report_attempts(usage_date);
