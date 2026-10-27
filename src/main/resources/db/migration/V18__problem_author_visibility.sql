-- 기존 제작자에게 공개 동의가 있었다고 추정하지 않습니다. 문제의 공개 범위와도 별개입니다.
-- 원본 author_id FK는 변경하지 않으며 공개 응답에는 표시 이름만 선택적으로 제공합니다.
ALTER TABLE problems
    ADD COLUMN author_public BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT chk_problems_author_public CHECK (author_public IN (0,1));
