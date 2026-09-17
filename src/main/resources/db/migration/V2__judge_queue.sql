-- 번호 발급은 행 잠금으로 직렬화하여 동시 생성 시에도 중복되지 않게 합니다.
CREATE TABLE problem_number_sequence (
    id INT PRIMARY KEY,
    next_number INT NOT NULL
);
INSERT INTO problem_number_sequence
SELECT 1, GREATEST(1000, COALESCE(MAX(problem_number), 999) + 1) FROM problems;

-- 함수 호출 규격은 정답 비교 방식과 별개의 개념입니다.
ALTER TABLE problems ADD COLUMN execution_mode ENUM('STDIN', 'FUNCTION') NOT NULL DEFAULT 'STDIN';
UPDATE problems SET execution_mode = 'FUNCTION' WHERE problem_number = 1000 AND problem_title = '체인 레이저';
ALTER TABLE submissions ADD COLUMN claimed_at DATETIME NULL;
ALTER TABLE submissions ADD COLUMN judge_token VARCHAR(36) NULL;
CREATE INDEX idx_submission_queue ON submissions(status, submitted_at, id);
