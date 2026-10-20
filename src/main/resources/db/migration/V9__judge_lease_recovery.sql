-- claimed_at은 최초 할당 시각으로 유지하고, 살아 있는 워커의 lease는 별도로 갱신합니다.
ALTER TABLE submissions
    ADD COLUMN judge_heartbeat_at DATETIME(6) NULL,
    ADD COLUMN judge_attempts INT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_submissions_judge_attempts CHECK (judge_attempts >= 0);

-- 배포 시 이미 처리 중인 제출의 첫 실행을 보존합니다.
UPDATE submissions
SET judge_heartbeat_at=COALESCE(claimed_at,CURRENT_TIMESTAMP(6)),
    judge_attempts=1
WHERE status='RUNNING';

CREATE INDEX idx_submission_lease ON submissions(status,judge_heartbeat_at,id);
