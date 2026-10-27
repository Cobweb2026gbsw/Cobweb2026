-- 오류 출력 원문 대신 허용된 오류 유형·고정 설명·제출 파일 위치만 저장합니다.
ALTER TABLE submissions ADD COLUMN diagnostic_json JSON NULL;
ALTER TABLE submissions ADD CONSTRAINT chk_submission_diagnostic_size
    CHECK (diagnostic_json IS NULL OR OCTET_LENGTH(CAST(diagnostic_json AS CHAR CHARACTER SET utf8mb4)) <= 8192);
