```
#!/usr/bin/env bash
# 로컬 개발 전용: DB → 채점 이미지/JAR 빌드 → 웹 → 별도 채점 워커.
# Ctrl+C/TERM/기동 실패 시 이 실행의 프로세스와 DB만 멈추고 데이터는 보존합니다.
# 운영 배포는 compose.prod.yaml/compose.worker.yaml의 호스트 분리를 사용하세요.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
cd "$ROOT_DIR"

usage() {
  printf '%s\n' \
    '사용법: ./run.sh [--rebuild-judge]  (종료: Ctrl+C)' \
    '' \
    '필수: Java JDK 21, 실행 중인 Docker Desktop/Engine, Docker Compose, curl, openssl' \
    '웹 바인딩: 0.0.0.0:8080 (접속: http://localhost:8080 또는 http://서버IP:8080) / 전용 MySQL: 127.0.0.1:3307' \
    '옵션: COBWEB_PORT=18080 ./run.sh (웹 포트), COBWEB_RUN_PROJECT=cobweb-local (격리된 Compose 이름)' \
    '실행 설정·로그: .local/run/<Compose 이름>/' \
    '처음에는 컴파일러 이미지 다운로드와 Flyway V1 이후 마이그레이션으로 시간이 걸립니다.' \
    '기존 .env는 덮어쓰지 않습니다. 메일/OAuth는 .env에 실제 제공자 설정이 있을 때만 사용할 수 있습니다.' \
    'DB 데이터와 실행용 config.env는 종료 후에도 보존합니다. config.env를 삭제하면 DB 비밀번호를 잃습니다.' \
    '현재 소스로 JAR를 빌드하며, 채점 이미지는 없을 때만 빌드합니다(테스트는 별도).' \
    'judge/의 코드·도구 체인 변경 후에는 ./run.sh --rebuild-judge로 이미지를 다시 빌드하세요.'
}

if [[ "${1:-}" == '--help' || "${1:-}" == '-h' ]]; then usage; exit 0; fi
REBUILD_JUDGE=false
if [[ $# == 1 && "$1" == '--rebuild-judge' ]]; then REBUILD_JUDGE=true;
elif (( $# )); then usage >&2; exit 2; fi

fail() { printf '[Cobweb] 오류: %s\n' "$*" >&2; exit 1; }
info() { printf '[Cobweb] %s\n' "$*"; }
for tool in docker curl openssl ps awk; do command -v "$tool" >/dev/null || fail "$tool 명령이 필요합니다."; done
docker compose version >/dev/null 2>&1 || fail 'Docker Compose 플러그인이 필요합니다.'
docker info >/dev/null 2>&1 || fail 'Docker가 실행되지 않았거나 접근할 수 없습니다. Docker를 켜고 다시 실행하세요.'

PORT="${COBWEB_PORT:-8080}"
PROJECT="${COBWEB_RUN_PROJECT:-cobweb-local}"
[[ "$PORT" =~ ^[1-9][0-9]{0,4}$ ]] && (( PORT <= 65535 )) || fail 'COBWEB_PORT는 1~65535여야 합니다.'
[[ "$PROJECT" =~ ^[a-z0-9][a-z0-9-]{0,39}$ ]] || fail 'Compose 이름은 영문 소문자·숫자·하이픈 1~40자로 지정하세요.'
if command -v lsof >/dev/null && lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  fail "웹 포트 $PORT 사용 중입니다. COBWEB_PORT로 다른 포트를 지정하세요. 기존 서버는 종료하지 않습니다."
fi

# macOS의 기본 java가 다른 버전이면 설치된 JDK 21을 찾아 사용합니다.
if [[ -x /usr/libexec/java_home ]]; then
  detected_jdk="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
  if [[ -n "$detected_jdk" ]]; then export JAVA_HOME="$detected_jdk"; fi
fi
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAVA_VERSION="$("$JAVA_BIN" -XshowSettings:properties -version 2>&1 | awk '/java.specification.version =/ {print $3}' || true)"
[[ "$JAVA_VERSION" == 21 ]] || fail 'JDK 21을 설치하고 JAVA_HOME을 설정하세요.'
JAVAC_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
command -v "$JAVAC_BIN" >/dev/null || fail 'JRE가 아닌 JDK 21이 필요합니다(javac 없음).'

umask 077
STATE_DIR="$ROOT_DIR/.local/run/$PROJECT"
mkdir -p "$STATE_DIR"
LOCK_DIR="$STATE_DIR/lock"
CONFIG_FILE="$STATE_DIR/config.env"
LOCK_OWNED=false
DB_OWNED=false
TASK_PID=''
WEB_PID=''
WORKER_PID=''

compose() { docker compose --ansi never --progress plain --project-directory "$ROOT_DIR" --env-file "$CONFIG_FILE" -p "$PROJECT" -f "$ROOT_DIR/compose.dev.yaml" "$@"; }

# 각 작업을 독립 프로세스 그룹으로 실행합니다(Bash 3.2 호환).
# Gradle처럼 새 그룹으로 분리되는 자식은 종료 전에 PPID 트리로 별도 추적합니다.
set -m
group_alive() { [[ -n "$1" ]] && kill -0 -- "-$1" 2>/dev/null; }
owned_alive() { [[ -n "$1" ]] && { kill -0 "$1" 2>/dev/null || group_alive "$1"; }; }
signal_group() {
  if group_alive "$2"; then kill "-$1" -- "-$2" 2>/dev/null || true;
  elif [[ -n "$2" ]]; then kill "-$1" "$2" 2>/dev/null || true; fi
}
descendants() {
  [[ -n "$1" ]] || return 0
  ps -axo pid=,ppid= | awk -v root="$1" '
    { parent[$1]=$2 }
    END { for (pid in parent) { cursor=pid;
      for (depth=0; depth<128; depth++) {
        if (parent[cursor]==root) { print pid; break }
        if (!(cursor in parent) || parent[cursor]==cursor) break;
        cursor=parent[cursor];
      }
    } }'
}
stop_judge_containers() {
  local ids id sources source matched
  ids="$(docker ps -q --filter name=cobweb-)" || return 1
  for id in $ids; do
    sources="$(docker inspect --format '{{range .Mounts}}{{if eq .Type "bind"}}{{println .Source}}{{end}}{{end}}' "$id" 2>/dev/null)" || continue
    matched=false
    while IFS= read -r source; do
      case "$source" in "$STATE_DIR/judge/"*|"/host_mnt$STATE_DIR/judge/"*) matched=true ;; esac
    done <<< "$sources"
    # 다른 프로젝트/워커의 컨테이너는 이름만 같아도 건드리지 않습니다.
    # 이 실행의 소스 디렉터리를 마운트한 채점 샌드박스만 멈춥니다.
    if [[ "$matched" == true ]]; then docker stop --timeout 2 "$id" || return 1; fi
  done
  return 0
}
cleanup() {
  local status=$? pid attempt remaining extra_pids='' cleanup_failed=false
  trap '' INT TERM HUP
  set +e
  set +m
  if [[ "$LOCK_OWNED" == true ]]; then
    info '종료 중: 실행 작업·채점 워커·웹 서버를 정리합니다.'
    # 부모 종료로 자식의 PPID가 바뀌기 전에 이 실행의 자손만 수집합니다.
    for pid in "$TASK_PID" "$WORKER_PID" "$WEB_PID"; do extra_pids="$extra_pids $(descendants "$pid")"; done
    for pid in $extra_pids; do kill -TERM "$pid" 2>/dev/null; kill -CONT "$pid" 2>/dev/null; done
    for pid in "$TASK_PID" "$WORKER_PID" "$WEB_PID"; do signal_group TERM "$pid"; signal_group CONT "$pid"; done
    for ((attempt=0;attempt<20;attempt++)); do
      remaining=false
      for pid in "$TASK_PID" "$WORKER_PID" "$WEB_PID"; do if owned_alive "$pid"; then remaining=true; fi; done
      for pid in $extra_pids; do if kill -0 "$pid" 2>/dev/null; then remaining=true; fi; done
      [[ "$remaining" == true ]] || break
      sleep 1
    done
    for pid in $extra_pids; do kill -KILL "$pid" 2>/dev/null; done
    for pid in "$TASK_PID" "$WORKER_PID" "$WEB_PID"; do
      signal_group KILL "$pid"
      if [[ -n "$pid" ]]; then wait "$pid" 2>/dev/null; fi
    done
    if [[ "$DB_OWNED" == true ]]; then
      if ! stop_judge_containers; then
        printf '[Cobweb] 일부 채점 컨테이너를 확인/중지하지 못했습니다. Docker 상태를 확인하세요.\n' >&2
        cleanup_failed=true
      fi
      if ! compose stop --timeout 15 mysql; then
        printf '[Cobweb] DB 중지 실패: Docker 상태를 확인하세요. Compose 프로젝트: %s\n' "$PROJECT" >&2
        cleanup_failed=true
      fi
    fi
    rmdir "$LOCK_DIR"
    if [[ "$cleanup_failed" == true ]]; then
      [[ "$status" != 0 ]] || status=1
      info '종료 처리 중 오류가 있었습니다. 위 경고를 확인하세요. DB 데이터·설정·로그는 보존했습니다.'
    else info '종료했습니다. DB 데이터·설정·로그는 보존했습니다.'; fi
  fi
  trap - EXIT
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP
mkdir "$LOCK_DIR" 2>/dev/null || fail "이미 실행 중이거나 이전 강제 종료의 잠금이 남았습니다: $LOCK_DIR (실행이 없는지 확인 후 빈 lock 디렉터리만 rmdir로 정리하세요)."
LOCK_OWNED=true

# 설정 파일을 셸 source/eval로 실행하지 않습니다. 첫 실행에만 전용 DB 비밀값을
# 만들며, 이미 있는 볼륨에 새 비밀번호를 덮어씌우지 않습니다.
if [[ ! -e "$CONFIG_FILE" ]]; then
  if docker volume inspect "${PROJECT}_cobweb_mysql" >/dev/null 2>&1; then
    fail "기존 DB 볼륨은 있지만 config.env가 없습니다. $CONFIG_FILE 백업을 복원하세요. DB는 변경하지 않았습니다."
  fi
  config_tmp="$(mktemp "$STATE_DIR/config.XXXXXX")"
  printf '%s\n' \
    "DEV_DB_PASSWORD=$(openssl rand -hex 24)" \
    "DEV_ROOT_PASSWORD=$(openssl rand -hex 24)" \
    "JWT_SECRET=$(openssl rand -hex 32)" \
    'MAIL_USERNAME=local-disabled@example.invalid' 'MAIL_PASSWORD=local-disabled' \
    'GOOGLE_CLIENT_ID=local-disabled' 'GOOGLE_CLIENT_SECRET=local-disabled' \
    'NAVER_CLIENT_ID=local-disabled' 'NAVER_CLIENT_SECRET=local-disabled' \
    'GITHUB_CLIENT_ID=local-disabled' 'GITHUB_CLIENT_SECRET=local-disabled' > "$config_tmp"
  mv "$config_tmp" "$CONFIG_FILE"
fi
chmod 600 "$CONFIG_FILE"
DEV_DB_PASSWORD=''; DEV_ROOT_PASSWORD=''; JWT_SECRET=''
while IFS='=' read -r key value || [[ -n "$key" ]]; do
  case "$key" in
    DEV_DB_PASSWORD) DEV_DB_PASSWORD="$value" ;;
    DEV_ROOT_PASSWORD) DEV_ROOT_PASSWORD="$value" ;;
    JWT_SECRET) JWT_SECRET="$value" ;;
  esac
done < "$CONFIG_FILE"
[[ -n "$DEV_DB_PASSWORD" && -n "$DEV_ROOT_PASSWORD" && ${#JWT_SECRET} -ge 32 ]] || fail "config.env의 DB 비밀번호/JWT 설정을 확인하세요: $CONFIG_FILE"
export DEV_DB_PASSWORD DEV_ROOT_PASSWORD JWT_SECRET
compose config --quiet
[[ -z "$(compose ps --status running -q)" ]] || fail '이 Compose 프로젝트가 이미 실행 중입니다. 기존 실행을 종료한 뒤 다시 실행하세요.'

run_task() {
  local status
  "$@" </dev/null & TASK_PID=$!
  if wait "$TASK_PID"; then TASK_PID=''; else status=$?; TASK_PID=''; return "$status"; fi
}

info "전용 로컬 DB 시작: Compose $PROJECT, 127.0.0.1:3307 (기존 사용자 DB 미사용)"
# up 도중 Ctrl+C가 와도 생성된 DB를 정리할 수 있도록 호출 전에 표시합니다.
DB_OWNED=true
run_task compose up -d --wait --wait-timeout 180 mysql
JUDGE_IMAGE="${JUDGE_IMAGE:-cobweb-languages:local}"
if [[ "$REBUILD_JUDGE" == true ]] || ! docker image inspect "$JUDGE_IMAGE" >/dev/null 2>&1; then
  info '13개 언어 채점 이미지를 빌드합니다. 첫 실행은 오래 걸릴 수 있습니다.'
  run_task docker build --progress=plain -t "$JUDGE_IMAGE" "$ROOT_DIR/judge"
else
  info "기존 채점 이미지 재사용: $JUDGE_IMAGE (변경 후에는 --rebuild-judge)"
fi
info '현재 소스로 웹·워커 공통 JAR를 빌드합니다.'
run_task ./gradlew --no-daemon --console=plain bootJar
jars=("$ROOT_DIR"/build/libs/*-SNAPSHOT.jar)
[[ ${#jars[@]} == 1 && -f "${jars[0]}" ]] || fail '실행 가능한 SNAPSHOT JAR가 정확히 하나 필요합니다.'
APP_JAR="${jars[0]}"
LOG_DIR="$STATE_DIR/logs/$(date +%Y%m%d-%H%M%S)-$$"
mkdir -p "$LOG_DIR" "$STATE_DIR/judge"

# local 프로필의 URL까지 환경 속성으로 고정해 .env의 운영 DB 값이 쓰이지 않습니다.
# 메일/OAuth는 .env가 있으면 읽되, 비밀값을 화면이나 프로세스 인자에 출력하지 않습니다.
export SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3307/cobweb2026?serverTimezone=UTC'
export SPRING_DATASOURCE_USERNAME=cobweb SPRING_DATASOURCE_PASSWORD="$DEV_DB_PASSWORD"
export SPRING_CONFIG_IMPORT="file:$CONFIG_FILE[.properties],optional:file:$ROOT_DIR/.env[.properties]"
export PUBLIC_BASE_URL="http://localhost:$PORT"
info "웹 시작 (Flyway 적용): $PUBLIC_BASE_URL"
"$JAVA_BIN" -jar "$APP_JAR" --spring.profiles.active=local --server.address=0.0.0.0 --server.port="$PORT" \
  --spring.flyway.enabled=true --judge.enabled=false --demo.seed=true </dev/null > "$LOG_DIR/web.log" 2>&1 &
WEB_PID=$!
ready=false
for ((attempt=0;attempt<120;attempt++)); do
  owned_alive "$WEB_PID" || fail "웹 기동 실패. 로그: $LOG_DIR/web.log"
  if curl --noproxy '*' --silent --fail --connect-timeout 1 --max-time 2 "http://127.0.0.1:$PORT/api/languages" >/dev/null; then ready=true; break; fi
  sleep 1
done
[[ "$ready" == true ]] || fail "웹 준비 시간 초과. 로그: $LOG_DIR/web.log"

# 마이그레이션과 웹 준비가 끝난 뒤 같은 JAR의 non-web 워커를 실행합니다.
"$JAVA_BIN" -jar "$APP_JAR" --spring.profiles.active=local --spring.main.web-application-type=none \
  --spring.flyway.enabled=false --judge.enabled=true --judge.image="$JUDGE_IMAGE" \
  --judge.workspace="$STATE_DIR/judge" --demo.seed=false </dev/null > "$LOG_DIR/worker.log" 2>&1 &
WORKER_PID=$!
ready=false
for ((attempt=0;attempt<60;attempt++)); do
  owned_alive "$WEB_PID" && owned_alive "$WORKER_PID" || fail "웹/워커 기동 실패. 로그: $LOG_DIR"
  if grep -q 'Started Cobweb2026Application' "$LOG_DIR/worker.log"; then ready=true; break; fi
  sleep 1
done
[[ "$ready" == true ]] || fail "워커 준비 시간 초과. 로그: $LOG_DIR/worker.log"
info "준비 완료: $PUBLIC_BASE_URL (문제 1000번 포함)"
info "로그: $LOG_DIR"
info 'Ctrl+C를 누르면 웹·워커·전용 DB가 모두 종료됩니다. 데이터는 삭제하지 않습니다.'
while owned_alive "$WEB_PID" && owned_alive "$WORKER_PID"; do sleep 1; done
fail "웹 또는 워커가 종료되어 나머지도 정리합니다. 로그: $LOG_DIR"

```