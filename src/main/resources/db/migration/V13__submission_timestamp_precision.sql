-- 기존 제출·판정 시각은 값을 유지하며 소수부 .000000으로 확장합니다.
-- 새 시험 제출은 UTC_TIMESTAMP(6), 일반 제출은 기존 서버 로컬 시각을 사용합니다.
ALTER TABLE submissions
    MODIFY COLUMN submitted_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    MODIFY COLUMN judged_at DATETIME(6) NULL;
