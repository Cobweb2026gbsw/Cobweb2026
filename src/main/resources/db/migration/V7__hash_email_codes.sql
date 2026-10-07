-- 기존 5분짜리 인증 코드는 새 서버 전용 키로 SQL에서 안전하게 변환할 수 없습니다.
-- 모든 미완료 인증을 재요청하게 하고 원문 열을 지문 열로 교체합니다.
DELETE FROM email_verifications;
ALTER TABLE email_verifications CHANGE COLUMN code code_hash CHAR(64) NOT NULL;
