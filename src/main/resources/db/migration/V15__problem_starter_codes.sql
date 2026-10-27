-- 시작 코드는 풀이자에게 공개되는 메타데이터입니다. 비공개 참고 정답에서 복사하지 않습니다.
-- 기존 문제·정답·테스트·언어 목록에는 아무 값도 추가하거나 덮어쓰지 않습니다.
CREATE TABLE problem_starter_codes (
    problem_id BIGINT NOT NULL,
    language VARCHAR(32) NOT NULL,
    source_code MEDIUMTEXT NOT NULL,
    PRIMARY KEY (problem_id, language),
    CONSTRAINT fk_problem_starter_codes_problem FOREIGN KEY (problem_id) REFERENCES problems(id) ON DELETE CASCADE,
    CONSTRAINT chk_problem_starter_codes_bytes CHECK (OCTET_LENGTH(source_code) BETWEEN 1 AND 65536)
);
