-- 기존 시험과 구버전 클라이언트에서 만든 시험은 편집기의 자동완성 설정을 그대로 허용합니다.
ALTER TABLE group_contests ADD COLUMN autocomplete_allowed boolean NOT NULL DEFAULT true;
