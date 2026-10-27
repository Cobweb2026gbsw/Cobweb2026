-- 제출 원문은 기존 source_code에 계속 보존합니다. 해시는 조회 후보를 줄일 뿐 원문 비교를 대신하지 않습니다.
-- 과거 판정은 당시 이미지/테스트 규격을 증명할 수 없으므로 context를 소급 생성하지 않습니다.
ALTER TABLE submissions
    ADD COLUMN source_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN judge_context_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN reused_from_submission_id BIGINT NULL,
    -- 기존 위반 행이 있으면 중단합니다. 자동 삭제/수정/잘라내기는 하지 않습니다.
    ADD CONSTRAINT chk_submissions_source_bytes CHECK (OCTET_LENGTH(source_code) BETWEEN 1 AND 65536),
    ADD INDEX idx_submissions_judgement_reuse
        (user_id,problem_id,language,contest_id,source_sha256,judge_context_sha256,id),
    ADD CONSTRAINT fk_submissions_reused_from FOREIGN KEY (reused_from_submission_id)
        REFERENCES submissions(id) ON DELETE SET NULL;
