-- 사용자 입력으로만 실행하는 연습 큐입니다. 정식 제출/해결/문제 통계와 연결하지 않습니다.
CREATE TABLE user_test_runs (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    problem_id BIGINT NOT NULL,
    language VARCHAR(30) NOT NULL,
    source_code LONGTEXT NOT NULL,
    execution_mode VARCHAR(16) NOT NULL,
    function_signature JSON NULL,
    time_limit_ms INT NOT NULL,
    memory_limit_mb INT NOT NULL,
    input_cases JSON NOT NULL,
    status ENUM('QUEUED','RUNNING','COMPLETED','SYSTEM_ERROR') NOT NULL DEFAULT 'QUEUED',
    result_json JSON NULL,
    judge_token VARCHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at DATETIME(6) NULL,
    CONSTRAINT fk_test_run_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_test_run_problem FOREIGN KEY(problem_id) REFERENCES problems(id) ON DELETE CASCADE,
    CONSTRAINT ck_test_source_bytes CHECK(OCTET_LENGTH(source_code) BETWEEN 1 AND 65536),
    CONSTRAINT ck_test_cases CHECK(JSON_TYPE(input_cases)='ARRAY' AND JSON_LENGTH(input_cases) BETWEEN 1 AND 3 AND OCTET_LENGTH(input_cases)<=524288),
    CONSTRAINT ck_test_limits CHECK(time_limit_ms BETWEEN 100 AND 10000 AND memory_limit_mb BETWEEN 64 AND 512),
    CONSTRAINT ck_test_result_size CHECK(result_json IS NULL OR OCTET_LENGTH(result_json)<=2097152),
    INDEX idx_test_run_queue(status,id),
    INDEX idx_test_run_user(user_id,created_at)
);
-- 접수 전체 상한도 여러 웹 프로세스에서 한 트랜잭션으로 검사합니다.
CREATE TABLE user_test_run_queue_lock (id TINYINT NOT NULL PRIMARY KEY);
INSERT INTO user_test_run_queue_lock(id) VALUES(1);
