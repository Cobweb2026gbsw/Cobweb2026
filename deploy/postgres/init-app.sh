#!/usr/bin/env bash
# 공식 PostgreSQL 이미지가 빈 전용 볼륨을 초기화할 때만 실행합니다.
# 비밀번호는 argv/로그에 넣지 않고 psql 환경 변수 가져오기로 인용합니다.
set -euo pipefail
: "${COBWEB_APP_PASSWORD:?COBWEB_APP_PASSWORD required}"
psql -X --no-password --set=ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'PSQL'
\getenv app_password COBWEB_APP_PASSWORD
CREATE ROLE cobweb LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD :'app_password';
GRANT CONNECT ON DATABASE :"DBNAME" TO cobweb;
GRANT USAGE, CREATE ON SCHEMA public TO cobweb;
-- 로컬 단발 관리자 마이그레이션 뒤 웹·워커는 데이터 권한만으로 기존 테이블을 사용합니다.
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO cobweb;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO cobweb;
PSQL
