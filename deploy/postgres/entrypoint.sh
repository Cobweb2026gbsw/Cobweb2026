#!/usr/bin/env bash
set -euo pipefail
# Docker Desktop 공유 파일시스템의 -x 판정과 exec 허용 여부가 다를 수 있습니다.
# 읽기 전용 소스를 컨테이너 내부의 비실행 파일로 복사해 공식 entrypoint가 source하게 합니다.
install -m 0644 /cobweb-init/init-app.sh /docker-entrypoint-initdb.d/10-cobweb-app.sh
exec /usr/local/bin/docker-entrypoint.sh "$@"
