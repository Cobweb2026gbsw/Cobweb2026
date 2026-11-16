CREATE TABLE learning_reports (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    report_number INTEGER NOT NULL CHECK (report_number > 0),
    status VARCHAR(10) NOT NULL CHECK (status IN ('RUNNING', 'RETRY', 'READY')),
    report_json JSONB NOT NULL CHECK (jsonb_typeof(report_json) = 'object'),
    facts_json JSONB CHECK (facts_json IS NULL OR jsonb_typeof(facts_json) = 'object'),
    carry_forward_summary TEXT CHECK (char_length(carry_forward_summary) BETWEEN 1 AND 700),
    claim_token UUID,
    claim_until TIMESTAMPTZ,
    retry_after TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (user_id, report_number),
    CONSTRAINT chk_learning_report_status CHECK (
        (status = 'RUNNING' AND claim_token IS NOT NULL AND claim_until IS NOT NULL AND retry_after IS NULL
            AND facts_json IS NOT NULL AND carry_forward_summary IS NULL AND completed_at IS NULL)
        OR (status = 'RETRY' AND claim_token IS NULL AND claim_until IS NULL AND retry_after IS NOT NULL
            AND facts_json IS NOT NULL AND carry_forward_summary IS NULL AND completed_at IS NULL)
        OR (status = 'READY' AND claim_token IS NULL AND claim_until IS NULL AND retry_after IS NULL
            AND facts_json IS NULL AND carry_forward_summary IS NOT NULL AND completed_at IS NOT NULL)
    )
);
CREATE UNIQUE INDEX uk_learning_report_open_per_user ON learning_reports (user_id) WHERE status IN ('RUNNING', 'RETRY');

CREATE TABLE learning_report_problems (
    user_id BIGINT NOT NULL,
    report_number INTEGER NOT NULL,
    -- 문제를 숨기거나 정리한 뒤에도 이미 배정된 회차는 그대로 유지합니다.
    problem_id BIGINT NOT NULL,
    position SMALLINT NOT NULL CHECK (position BETWEEN 1 AND 5),
    PRIMARY KEY (user_id, problem_id),
    UNIQUE (user_id, report_number, position),
    FOREIGN KEY (user_id, report_number) REFERENCES learning_reports(user_id, report_number) ON DELETE CASCADE
);

CREATE TABLE learning_report_daily_budget (
    usage_date DATE PRIMARY KEY,
    attempts INTEGER NOT NULL CHECK (attempts >= 0)
);

CREATE TABLE learning_report_user_daily_budget (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    usage_date DATE NOT NULL REFERENCES learning_report_daily_budget(usage_date) ON DELETE CASCADE,
    attempts INTEGER NOT NULL CHECK (attempts > 0),
    PRIMARY KEY (user_id, usage_date)
);

CREATE INDEX idx_user_solved_problems_activity ON user_solved_problems (user_id, solved_at, problem_id);
