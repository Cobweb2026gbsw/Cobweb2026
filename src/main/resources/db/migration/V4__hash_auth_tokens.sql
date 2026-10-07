-- 서버가 발급한 고엔트로피 토큰의 기존 원문을 SHA-256 지문으로 변환합니다.
-- 유효 기간과 사용자 연결을 유지하므로 배포 전 로그인/재설정 토큰도 계속 검증할 수 있습니다.
-- 이 마이그레이션은 한 번만 적용되며 원래 토큰은 DB에서 복구할 수 없습니다.
UPDATE refresh_token SET token = LOWER(SHA2(token, 256));
ALTER TABLE refresh_token MODIFY token CHAR(64) NOT NULL;

UPDATE password_reset SET reset_token = LOWER(SHA2(reset_token, 256));
ALTER TABLE password_reset MODIFY reset_token CHAR(64) NOT NULL;
