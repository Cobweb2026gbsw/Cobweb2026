-- NULL은 기존 함수 ABI를 유지합니다. 공개 계약만 저장하고 참고 정답과 테스트는 분리합니다.
ALTER TABLE problems ADD COLUMN function_signature JSON NULL;

-- 가져온 문제의 ID를 기록하여 재시작 때 사용자 편집을 덮어쓰지 않습니다.
CREATE TABLE curriculum_problem_entries (
    pack_key VARCHAR(80) NOT NULL,
    ordinal INT NOT NULL,
    problem_id BIGINT NOT NULL,
    catalog_sha256 CHAR(64) NOT NULL,
    imported_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (pack_key,ordinal),
    UNIQUE KEY uk_curriculum_problem (problem_id),
    CONSTRAINT fk_curriculum_problem FOREIGN KEY (problem_id) REFERENCES problems(id),
    CONSTRAINT chk_curriculum_ordinal CHECK (ordinal >= 0)
);
