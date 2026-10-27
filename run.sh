#!/usr/bin/env bash
# 로컬 개발 전용: DB → 채점 이미지/JAR 빌드 → 단발 마이그레이션 → 웹 → 별도 채점 워커.
# 정상 Ctrl+C는 이번 실행을 종료하고, 실패는 실행 전 DB on/off 상태로 복구합니다.
# DB 내용·마이그레이션·설정·인증서·로그는 복구/삭제하지 않고 보존합니다.
# 운영 배포는 compose.prod.yaml/compose.worker.yaml의 호스트 분리를 사용하세요.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
cd "$ROOT_DIR"

usage() {
  printf '%s\n' \
    '사용법: ./run.sh [--rebuild-judge]  (종료: Ctrl+C)' \
    '' \
    '필수: Java JDK 21(keytool 포함), Node.js 20 이상/npm, 실행 중인 Docker Desktop/Engine, Docker Compose, curl, openssl' \
    '웹 바인딩: HTTP 0.0.0.0:8080 + HTTPS 0.0.0.0:8443 / 전용 MySQL: 127.0.0.1:3307' \
    '접속: http://localhost:8080 또는 https://localhost:8443 (8080으로 https 접속 금지)' \
    '옵션: COBWEB_PORT=18080 COBWEB_HTTPS_PORT=18443 ./run.sh, COBWEB_RUN_PROJECT=cobweb-local' \
    '실행 설정·로그: .local/run/<Compose 이름>/' \
    '처음에는 컴파일러 이미지 다운로드와 Flyway V1 이후 마이그레이션으로 시간이 걸립니다.' \
    '기존 .env는 덮어쓰지 않습니다. 메일/OAuth는 .env에 실제 제공자 설정이 있을 때만 사용할 수 있습니다.' \
    '로컬 자체서명 인증서는 localhost/127.0.0.1 전용이며 브라우저의 신뢰 경고가 날 수 있습니다.' \
    '시스템 CA 신뢰나 브라우저 인증서 검증을 자동 변경하지 않습니다. 외부 접속은 유효한 도메인/인증서의 운영 프록시를 사용하세요.' \
    'DB 데이터와 실행용 config.env는 종료 후에도 보존합니다. config.env를 삭제하면 DB 비밀번호를 잃습니다.' \
    '기동 실패/준비 전 취소: 이번 프로세스를 종료하고 DB의 시작 전 실행 상태를 복구합니다.' \
    '신규 DB 컨테이너/네트워크만 제거하며 데이터 볼륨·DB 내용·마이그레이션·설정·인증서·로그는 보존합니다.' \
    '정상 기동 뒤 Ctrl+C: 웹·워커·전용 DB를 모두 중지합니다. DB 내용을 되돌리지 않습니다.' \
    '실행 JAR는 실행별 로그 폴더의 app.jar 사본이므로 이후 재빌드해도 실행본을 덮지 않습니다.' \
    '로컬 마이그레이션만 단발 JVM의 Flyway 전용 root 연결로 실행하고 웹·워커는 제한 계정을 유지합니다.' \
    '현재 소스로 JAR를 빌드하며, 채점 이미지가 없거나 이전 언어 세트이면 자동 갱신합니다(테스트는 별도).' \
    'judge/의 코드·도구 체인 변경 후에는 ./run.sh --rebuild-judge로 이미지를 다시 빌드하세요.'
}

if [[ "${1:-}" == '--help' || "${1:-}" == '-h' ]]; then usage; exit 0; fi
REBUILD_JUDGE=false
if [[ $# == 1 && "$1" == '--rebuild-judge' ]]; then REBUILD_JUDGE=true;
elif (( $# )); then usage >&2; exit 2; fi

fail() { printf '[Cobweb] 오류: %s\n' "$*" >&2; exit 1; }
info() { printf '[Cobweb] %s\n' "$*"; }
# 이 입력들은 고정한 로컬 연결보다 높은 우선순위로 JVM/Spring 설정을
# 주입할 수 있습니다. 값(비밀값 포함)은 출력하지 않고 실행 전에 거절합니다.
for external_option in SPRING_APPLICATION_JSON JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS \
    SPRING_CONFIG_LOCATION SPRING_CONFIG_ADDITIONAL_LOCATION SPRING_CONFIG_NAME; do
  [[ -z "${!external_option:-}" ]] || fail "$external_option 환경변수를 해제한 뒤 실행하세요. 로컬 실행의 외부 JVM/Spring 옵션 주입은 허용하지 않습니다."
done
for external_option in "${!SPRING_FLYWAY_@}"; do
  case "$external_option" in
    SPRING_FLYWAY_URL|SPRING_FLYWAY_USER|SPRING_FLYWAY_PASSWORD|'') ;; # 아래에서 전용 연결로 덮어씁니다.
    *) [[ -z "${!external_option:-}" ]] || fail "$external_option 환경변수를 해제한 뒤 실행하세요. 외부 Flyway 실행 옵션은 허용하지 않습니다." ;;
  esac
done
for tool in docker curl openssl ps awk node npm; do command -v "$tool" >/dev/null || fail "$tool 명령이 필요합니다."; done
node -e 'if(Number(process.versions.node.split(".")[0])<20)process.exit(1)' || fail 'React UI 빌드에 Node.js 20 이상이 필요합니다.'
docker compose version >/dev/null 2>&1 || fail 'Docker Compose 플러그인이 필요합니다.'
docker info >/dev/null 2>&1 || fail 'Docker가 실행되지 않았거나 접근할 수 없습니다. Docker를 켜고 다시 실행하세요.'

PORT="${COBWEB_PORT:-8080}"
HTTPS_PORT="${COBWEB_HTTPS_PORT:-8443}"
PROJECT="${COBWEB_RUN_PROJECT:-cobweb-local}"
[[ "$PORT" =~ ^[1-9][0-9]{0,4}$ ]] && (( PORT <= 65535 )) || fail 'COBWEB_PORT는 1~65535여야 합니다.'
[[ "$HTTPS_PORT" =~ ^[1-9][0-9]{0,4}$ ]] && (( HTTPS_PORT <= 65535 )) || fail 'COBWEB_HTTPS_PORT는 1~65535여야 합니다.'
[[ "$PORT" != "$HTTPS_PORT" ]] || fail 'HTTP와 HTTPS 포트는 달라야 합니다.'
[[ "$PROJECT" =~ ^[a-z0-9][a-z0-9-]{0,39}$ ]] || fail 'Compose 이름은 영문 소문자·숫자·하이픈 1~40자로 지정하세요.'
if command -v lsof >/dev/null && lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  fail "웹 포트 $PORT 사용 중입니다. COBWEB_PORT로 다른 포트를 지정하세요. 기존 서버는 종료하지 않습니다."
fi
if command -v lsof >/dev/null && lsof -nP -iTCP:"$HTTPS_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  fail "HTTPS 포트 $HTTPS_PORT 사용 중입니다. COBWEB_HTTPS_PORT로 다른 포트를 지정하세요. 기존 서버는 종료하지 않습니다."
fi

# macOS의 기본 java가 다른 버전이면 설치된 JDK 21을 찾아 사용합니다.
if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then
  detected_jdk="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
  if [[ -n "$detected_jdk" ]]; then export JAVA_HOME="$detected_jdk"; fi
fi
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAVA_VERSION="$("$JAVA_BIN" -XshowSettings:properties -version 2>&1 | awk '/java.specification.version =/ {print $3}' || true)"
[[ "$JAVA_VERSION" == 21 ]] || fail 'JDK 21을 설치하고 JAVA_HOME을 설정하세요.'
JAVAC_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
command -v "$JAVAC_BIN" >/dev/null || fail 'JRE가 아닌 JDK 21이 필요합니다(javac 없음).'
KEYTOOL_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
command -v "$KEYTOOL_BIN" >/dev/null || fail '로컬 HTTPS 인증서를 준비할 keytool(JDK 21)이 필요합니다.'

umask 077
STATE_DIR="$ROOT_DIR/.local/run/$PROJECT"
mkdir -p "$STATE_DIR"
LOCK_DIR="$STATE_DIR/lock"
CONFIG_FILE="$STATE_DIR/config.env"
LOCK_OWNED=false
DB_OWNED=false
DB_INITIAL_ID=''
DB_INITIAL_STATUS='absent'
NETWORK_INITIAL_ID=''
STARTUP_COMPLETE=false
STOP_REQUESTED=false
BUILD_LOCK_OWNED=false
BUILD_LOCK_DIR="$ROOT_DIR/.local/run/build.lock"
STAGE='configuration'
LOG_DIR=''
JUDGE_WORKSPACE=''
TASK_PID=''
TASK_CHILD_PIDS=''
WEB_PID=''
WORKER_PID=''
# PID만 기억하면 긴 빌드 도중 재사용된 번호를 다른 프로세스로 오인할 수 있습니다.
# Bash 3.2에서도 동작하도록 pid|시작 시각의 줄 목록으로 소유한 실행을 기록합니다.
PROCESS_IDENTITIES=''

compose() { docker compose --ansi never --progress plain --project-directory "$ROOT_DIR" --env-file "$CONFIG_FILE" -p "$PROJECT" -f "$ROOT_DIR/compose.dev.yaml" "$@"; }

# 조회 실패를 '리소스 없음'으로 오인하지 않도록 Docker 오류는 그대로 반환합니다.
current_db_id() {
  local id
  id="$(compose ps -a -q mysql)" || return 1
  [[ -z "$id" || "$id" =~ ^[a-f0-9]{12,64}$ ]] || return 1
  printf '%s' "$id"
}
require_db_owner() {
  local owner
  owner="$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}|{{index .Config.Labels "com.docker.compose.service"}}|{{index .Config.Labels "com.docker.compose.project.working_dir"}}' "$1")" || return 1
  [[ "$owner" == "$PROJECT|mysql|$ROOT_DIR" ]]
}
current_network_id() {
  local id
  id="$(docker network ls --filter "name=^${PROJECT}_default$" --format '{{.ID}}')" || return 1
  [[ -z "$id" || "$id" =~ ^[a-f0-9]{12,64}$ ]] || return 1
  printf '%s' "$id"
}
write_state() {
  [[ -n "$LOG_DIR" ]] || return 0
  printf 'stage=%s\nstartup_complete=%s\nstop_requested=%s\ninitial_db_id=%s\ninitial_db_status=%s\ninitial_network_id=%s\nweb_pid=%s\nworker_pid=%s\n' \
    "$STAGE" "$STARTUP_COMPLETE" "$STOP_REQUESTED" "$DB_INITIAL_ID" "$DB_INITIAL_STATUS" "$NETWORK_INITIAL_ID" "$WEB_PID" "$WORKER_PID" > "$LOG_DIR/state.txt"
}

# 각 작업을 독립 프로세스 그룹으로 실행합니다(Bash 3.2 호환).
# Gradle처럼 새 그룹으로 분리되는 자식은 종료 전에 PPID 트리로 별도 추적합니다.
# monitor는 background 작업을 만들 때만 켭니다. 계속 켜 두면 macOS TTY의
# foreground 대기 명령과 터미널 PGID 전환이 경합할 수 있습니다.
group_alive() { [[ -n "$1" ]] && kill -0 -- "-$1" 2>/dev/null; }
process_start() { LC_ALL=C ps -p "$1" -o lstart= 2>/dev/null | awk '{$1=$1; if (NF) print}'; }
remember_process() {
  local pid="$1" start known saved
  [[ -n "$pid" ]] || return 0
  if ! start="$(process_start "$pid")" || [[ -z "$start" ]]; then
    # 이미 종료한 짧은 작업은 기록할 필요가 없지만, 살아 있는데 조회할 수
    # 없는 프로세스는 소유했다고 추정하지 않습니다.
    ! kill -0 "$pid" 2>/dev/null
    return
  fi
  while IFS='|' read -r known saved; do
    [[ "$known" != "$pid" ]] || { [[ "$saved" == "$start" ]]; return; }
  done <<< "$PROCESS_IDENTITIES"
  PROCESS_IDENTITIES="${PROCESS_IDENTITIES}${pid}|${start}"$'\n'
}
# 0=기록한 실행이 생존, 1=종료, 2=PID 재사용/조회 실패로 소유권 불명.
process_state() {
  local pid="$1" start known saved=''
  [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null || return 1
  while IFS='|' read -r known start; do
    if [[ "$known" == "$pid" ]]; then saved="$start"; break; fi
  done <<< "$PROCESS_IDENTITIES"
  [[ -n "$saved" ]] || return 2
  start="$(process_start "$pid")" || { kill -0 "$pid" 2>/dev/null && return 2; return 1; }
  [[ -n "$start" ]] || { kill -0 "$pid" 2>/dev/null && return 2; return 1; }
  [[ "$start" == "$saved" ]] || return 2
}
owned_group() {
  local root="$1" pid saved pgid state uncertain=false
  [[ -n "$root" ]] && group_alive "$root" || return 1
  # leader가 먼저 종료해도 시작 정보가 일치하는 자식이 같은 PGID에 남아
  # 있을 때만 그룹 신호를 허용합니다. 번호만 같은 새 그룹은 건드리지 않습니다.
  while IFS='|' read -r pid saved; do
    [[ -n "$pid" ]] || continue
    if process_state "$pid"; then
      if pgid="$(ps -p "$pid" -o pgid= 2>/dev/null | awk '{$1=$1; print}')"; then
        if [[ "$pgid" == "$root" ]] && process_state "$pid"; then return 0; fi
      else uncertain=true; fi
    else state=$?; [[ "$state" != 2 ]] || uncertain=true; fi
  done <<< "$PROCESS_IDENTITIES"
  [[ "$uncertain" != true ]] || return 2
  return 1
}
owned_alive() { process_state "$1" || owned_group "$1"; }
signal_process() {
  local state
  if process_state "$2"; then kill "-$1" "$2" 2>/dev/null || { ! kill -0 "$2" 2>/dev/null; };
  else state=$?; [[ "$state" == 1 ]]; fi
}
signal_group() {
  local state
  if owned_group "$2"; then kill "-$1" -- "-$2" 2>/dev/null || { ! group_alive "$2"; };
  else state=$?; [[ "$state" != 2 ]] || return 1; signal_process "$1" "$2"; fi
}
descendants() {
  local captured state
  [[ -n "$1" ]] || return 0
  if process_state "$1"; then :; else state=$?; [[ "$state" == 1 ]]; return; fi
  captured="$(ps -axo pid=,ppid= | awk -v root="$1" '
    { parent[$1]=$2 }
    END { for (pid in parent) { cursor=pid;
      for (depth=0; depth<128; depth++) {
        if (parent[cursor]==root) { print pid; break }
        if (!(cursor in parent) || parent[cursor]==cursor) break;
        cursor=parent[cursor];
      }
    } }')" || return 1
  # 조회 중 leader가 종료/재사용되었으면 그 번호의 새 자손을 기록하지 않습니다.
  if process_state "$1"; then printf '%s\n' "$captured";
  else state=$?; [[ "$state" == 1 ]]; fi
}
stop_judge_containers() {
  local ids id sources source matched
  [[ -n "$JUDGE_WORKSPACE" ]] || return 0
  ids="$(docker ps -q --filter name=cobweb-)" || return 1
  for id in $ids; do
    sources="$(docker inspect --format '{{range .Mounts}}{{if eq .Type "bind"}}{{println .Source}}{{end}}{{end}}' "$id" 2>/dev/null)" || continue
    matched=false
    while IFS= read -r source; do
      case "$source" in "$JUDGE_WORKSPACE/"*|"/host_mnt$JUDGE_WORKSPACE/"*) matched=true ;; esac
    done <<< "$sources"
    # 다른 프로젝트/워커의 컨테이너는 이름만 같아도 건드리지 않습니다.
    # 이 실행의 소스 디렉터리를 마운트한 채점 샌드박스만 멈춥니다.
    if [[ "$matched" == true ]]; then docker stop --timeout 2 "$id" || return 1; fi
  done
  return 0
}

# 정리 중 Docker API가 멈춰도 무한 대기하지 않습니다. 자식 이벤트 루프와 독립된 제한입니다.
bounded_cleanup() {
  local pid attempt status child children captured state
  set -m
  (trap - EXIT INT TERM HUP; "$@") </dev/null & pid=$!
  set +m
  remember_process "$pid" || return 1
  for ((attempt=0;attempt<225;attempt++)); do
    if process_state "$pid"; then
      captured="$(descendants "$pid")" || return 1
      for child in $captured; do remember_process "$child" || return 1; done
    else
      state=$?; [[ "$state" == 1 ]] || return 1
      if wait "$pid"; then status=0; else status=$?; fi
      signal_group KILL "$pid" || return 1
      return "$status"
    fi
    sleep 0.2
  done
  children="$(descendants "$pid")" || return 1
  for child in $children; do remember_process "$child" || return 1; done
  for child in $children; do signal_process TERM "$child" || return 1; done
  signal_group TERM "$pid" || return 1
  sleep 0.3
  for child in $children; do signal_process KILL "$child" || return 1; done
  signal_group KILL "$pid" || return 1
  wait "$pid" 2>/dev/null || true
  printf '[Cobweb] 정리 명령 시간 초과. Docker 상태를 확인하세요.\n' >&2
  return 1
}
restore_runtime() {
  local id state network owner count
  id="$(current_db_id)" || return 1
  if [[ -n "$DB_INITIAL_ID" && "$id" != "$DB_INITIAL_ID" ]]; then
    printf '[Cobweb] DB 컨테이너 ID가 바뀌어 자동 복구를 중단합니다: 기존 %s / 현재 %s\n' "$DB_INITIAL_ID" "${id:-없음}" >&2
    return 1
  fi
  if [[ -n "$id" ]]; then
    require_db_owner "$id" || return 1
    state="$(docker inspect --format '{{.State.Status}}' "$id")" || return 1
    if [[ "$DB_INITIAL_STATUS" == running ]]; then
      [[ "$state" == running ]] || docker start "$id" || return 1
    else
      docker stop --timeout 15 "$id" || return 1
      # -v/volume prune/down --volumes는 사용하지 않습니다. DB 내용과 비밀번호는 남깁니다.
      if [[ -z "$DB_INITIAL_ID" ]]; then docker rm "$id" || return 1; fi
    fi
  fi
  if [[ -z "$NETWORK_INITIAL_ID" ]]; then
    network="$(current_network_id)" || return 1
    if [[ -n "$network" ]]; then
      owner="$(docker network inspect --format '{{index .Labels "com.docker.compose.project"}}' "$network")" || return 1
      count="$(docker network inspect --format '{{len .Containers}}' "$network")" || return 1
      [[ "$owner" == "$PROJECT" && "$count" == 0 ]] || return 1
      docker network rm "$network" || return 1
    fi
  fi
}
stop_owned_db() {
  local id
  id="$(current_db_id)" || return 1
  [[ -n "$id" ]] || return 0
  require_db_owner "$id" || return 1
  [[ -z "$DB_INITIAL_ID" || "$id" == "$DB_INITIAL_ID" ]] || return 1
  docker stop --timeout 15 "$id"
}
cleanup() {
  local status=$? pid attempt remaining captured extra_pids='' cleanup_failed=false rollback=true
  # job control을 사용하는 실제 TTY에서는 Ctrl+C가 foreground sleep에만
  # 전달되어 INT trap 없이 set -e가 130으로 끝날 수도 있습니다.
  # 이 경우도 정상 준비 뒤 사용자가 요청한 종료로 분류합니다.
  case "$status" in 130|143) STOP_REQUESTED=true ;; esac
  trap ':' INT TERM HUP
  set +e
  set +m
  if [[ "$LOCK_OWNED" == true ]]; then
    if [[ "$STARTUP_COMPLETE" == true && "$STOP_REQUESTED" == true ]]; then rollback=false; fi
    write_state || cleanup_failed=true
    info '종료 중: 실행 작업·채점 워커·웹 서버를 정리합니다.'
    # 부모 종료로 자식의 PPID가 바뀌기 전에 이 실행의 자손만 수집합니다.
    extra_pids="$TASK_CHILD_PIDS"
    for pid in "$TASK_PID" "$WORKER_PID" "$WEB_PID"; do
      if captured="$(descendants "$pid")"; then
        for child in $captured; do
          if remember_process "$child"; then extra_pids="$extra_pids $child"; else cleanup_failed=true; fi
        done
      else cleanup_failed=true; fi
    done
    for pid in $extra_pids; do signal_process TERM "$pid" || cleanup_failed=true; signal_process CONT "$pid" || cleanup_failed=true; done
    for pid in "$TASK_PID" "$WORKER_PID" "$WEB_PID"; do signal_group TERM "$pid" || cleanup_failed=true; signal_group CONT "$pid" || cleanup_failed=true; done
    for ((attempt=0;attempt<20;attempt++)); do
      remaining=false
      for pid in "$TASK_PID" "$WORKER_PID" "$WEB_PID"; do if owned_alive "$pid"; then remaining=true; fi; done
      for pid in $extra_pids; do if process_state "$pid"; then remaining=true; fi; done
      [[ "$remaining" == true ]] || break
      sleep 1
    done
    for pid in $extra_pids; do signal_process KILL "$pid" || cleanup_failed=true; done
    for pid in "$TASK_PID" "$WORKER_PID" "$WEB_PID"; do
      signal_group KILL "$pid" || cleanup_failed=true
      # 시작 정보가 바뀐 PID를 wait하면 관계없는 실행 때문에 정리가 멈출 수 있습니다.
      if [[ -n "$pid" ]] && ! kill -0 "$pid" 2>/dev/null; then wait "$pid" 2>/dev/null; fi
    done
    if [[ "$DB_OWNED" == true ]]; then
      if ! bounded_cleanup stop_judge_containers; then
        printf '[Cobweb] 일부 채점 컨테이너를 확인/중지하지 못했습니다. Docker 상태를 확인하세요.\n' >&2
        cleanup_failed=true
      fi
      if [[ "$rollback" == true ]]; then
        info "실패 복구: DB를 시작 전 실행 상태($DB_INITIAL_STATUS)로 되돌립니다. DB 내용은 유지합니다."
        if ! bounded_cleanup restore_runtime; then cleanup_failed=true; fi
      elif ! bounded_cleanup stop_owned_db; then
        cleanup_failed=true
      fi
    fi
    if [[ "$BUILD_LOCK_OWNED" == true ]]; then rmdir "$BUILD_LOCK_DIR" || cleanup_failed=true; fi
    # 정리가 불완전하면 잠금을 남겨 다른 실행이 남은 프로세스와 충돌하지 않게 합니다.
    if [[ "$cleanup_failed" == false ]]; then rmdir "$LOCK_DIR" || cleanup_failed=true; fi
    if [[ "$cleanup_failed" == true ]]; then
      [[ "$status" != 0 ]] || status=1
      info "실행 상태 복구가 완료되지 않았습니다. Docker/프로세스를 확인하세요. 잠금: $LOCK_DIR / 로그: $LOG_DIR"
    elif [[ "$rollback" == true ]]; then info "실행 전 상태 복구 완료. DB 내용·설정·인증서·로그는 보존했습니다. 마지막 단계: $STAGE";
    else info '종료했습니다. DB 데이터·설정·인증서·로그는 보존했습니다.'; fi
  fi
  trap - EXIT
  exit "$status"
}
trap cleanup EXIT
trap 'STOP_REQUESTED=true; exit 130' INT
trap 'STOP_REQUESTED=true; exit 143' TERM HUP
mkdir "$LOCK_DIR" 2>/dev/null || fail "이미 실행 중이거나 이전 강제 종료의 잠금이 남았습니다: $LOCK_DIR (실행이 없는지 확인 후 빈 lock 디렉터리만 rmdir로 정리하세요)."
LOCK_OWNED=true
LOG_DIR="$STATE_DIR/logs/$(date +%Y%m%d-%H%M%S)-$$"
JUDGE_WORKSPACE="$LOG_DIR/judge"
mkdir -p "$LOG_DIR" "$JUDGE_WORKSPACE"

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
DEV_DB_PASSWORD=''; DEV_ROOT_PASSWORD=''; JWT_SECRET=''; COBWEB_HTTPS_KEYSTORE_PASSWORD=''
while IFS='=' read -r key value || [[ -n "$key" ]]; do
  case "$key" in
    DEV_DB_PASSWORD) DEV_DB_PASSWORD="$value" ;;
    DEV_ROOT_PASSWORD) DEV_ROOT_PASSWORD="$value" ;;
    JWT_SECRET) JWT_SECRET="$value" ;;
    COBWEB_HTTPS_KEYSTORE_PASSWORD) COBWEB_HTTPS_KEYSTORE_PASSWORD="$value" ;;
  esac
done < "$CONFIG_FILE"
[[ -n "$DEV_DB_PASSWORD" && -n "$DEV_ROOT_PASSWORD" && ${#JWT_SECRET} -ge 32 ]] || fail "config.env의 DB 비밀번호/JWT 설정을 확인하세요: $CONFIG_FILE"
export DEV_DB_PASSWORD DEV_ROOT_PASSWORD JWT_SECRET
compose config --quiet
DB_INITIAL_ID="$(current_db_id)" || fail '초기 DB 컨테이너 상태 조회에 실패했습니다. DB는 변경하지 않았습니다.'
if [[ -n "$DB_INITIAL_ID" ]]; then
  require_db_owner "$DB_INITIAL_ID" || fail '같은 Compose 이름이 다른 경로에서 사용 중입니다. COBWEB_RUN_PROJECT를 다르게 지정하세요.'
  DB_INITIAL_STATUS="$(docker inspect --format '{{.State.Status}}' "$DB_INITIAL_ID")" || fail '초기 DB 상태 조회 실패'
  case "$DB_INITIAL_STATUS" in running|exited|created) ;; *) fail "DB 상태 $DB_INITIAL_STATUS 은 자동 복구 대상이 아닙니다. 먼저 Docker에서 확인하세요." ;; esac
fi
NETWORK_INITIAL_ID="$(current_network_id)" || fail '초기 Docker 네트워크 상태 조회 실패'
write_state

run_task() {
  local status child captured attempt remaining
  TASK_CHILD_PIDS=''
  set -m
  "$@" </dev/null & TASK_PID=$!
  set +m
  remember_process "$TASK_PID" || return 1
  # leader가 먼저 죽어 자식의 PPID가 바뀌기 전에 분리된 Gradle/JVM도 기록합니다.
  while kill -0 "$TASK_PID" 2>/dev/null; do
    captured="$(descendants "$TASK_PID")" || return 1
    for child in $captured; do
      remember_process "$child" || return 1
      case " $TASK_CHILD_PIDS " in *" $child "*) ;; *) TASK_CHILD_PIDS="$TASK_CHILD_PIDS $child" ;; esac
    done
    sleep 0.2
  done
  # 실패한 leader의 PGID를 남겨 잔존 자식도 cleanup이 종료할 수 있게 합니다.
  if wait "$TASK_PID"; then
    # single-use Gradle daemon도 응답 직후 짧게 종료 중일 수 있습니다.
    for ((attempt=0;attempt<10;attempt++)); do
      remaining=false
      for child in $TASK_CHILD_PIDS; do if process_state "$child"; then remaining=true; else status=$?; [[ "$status" != 2 ]] || return 1; fi; done
      [[ "$remaining" == true ]] || break
      sleep 0.2
    done
    if [[ "$remaining" == true ]]; then printf '[Cobweb] 작업 종료 후 남은 자식 프로세스를 정리합니다.\n' >&2; return 1; fi
    TASK_PID=''; TASK_CHILD_PIDS=''
  else status=$?; return "$status"; fi
}

# 인증서는 이 프로젝트의 로컬 실행에만 사용합니다. 기존 키가 있으면 비밀번호를
# 새로 생성해 덮어쓰지 않으며, 시스템 CA/브라우저 신뢰 저장소는 절대 변경하지 않습니다.
TLS_DIR="$STATE_DIR/tls"
STAGE='tls'
TLS_KEYSTORE="$TLS_DIR/localhost.p12"
TLS_CERTIFICATE="$TLS_DIR/localhost.crt"
mkdir -p "$TLS_DIR"
if [[ -z "$COBWEB_HTTPS_KEYSTORE_PASSWORD" ]]; then
  [[ ! -e "$TLS_KEYSTORE" ]] || fail 'HTTPS 키 저장소 비밀번호가 없습니다. config.env 백업을 복원하세요. 기존 인증서는 덮어쓰지 않았습니다.'
  COBWEB_HTTPS_KEYSTORE_PASSWORD="$(openssl rand -hex 24)"
  [[ ${#COBWEB_HTTPS_KEYSTORE_PASSWORD} -ge 16 ]] || fail 'HTTPS 키 저장소 비밀번호 생성 실패'
  printf '\nCOBWEB_HTTPS_KEYSTORE_PASSWORD=%s\n' "$COBWEB_HTTPS_KEYSTORE_PASSWORD" >> "$CONFIG_FILE"
fi
[[ ${#COBWEB_HTTPS_KEYSTORE_PASSWORD} -ge 16 ]] || fail 'config.env의 HTTPS 키 저장소 비밀번호를 확인하세요.'
export COBWEB_HTTPS_KEYSTORE_PASSWORD
if [[ ! -f "$TLS_KEYSTORE" ]]; then
  info 'localhost/127.0.0.1용 로컬 자체서명 HTTPS 인증서를 생성합니다.'
  run_task "$KEYTOOL_BIN" -genkeypair -noprompt -alias cobweb-local -keyalg RSA -keysize 3072 \
    -validity 365 -dname 'CN=localhost,OU=Local Development,O=Cobweb' \
    -ext 'SAN=dns:localhost,ip:127.0.0.1' -ext 'EKU=serverAuth' \
    -storetype PKCS12 -keystore "$TLS_KEYSTORE" -storepass:env COBWEB_HTTPS_KEYSTORE_PASSWORD
fi
run_task "$KEYTOOL_BIN" -exportcert -rfc -alias cobweb-local -keystore "$TLS_KEYSTORE" \
  -storepass:env COBWEB_HTTPS_KEYSTORE_PASSWORD -file "$TLS_CERTIFICATE"
chmod 600 "$TLS_KEYSTORE" "$TLS_CERTIFICATE" "$CONFIG_FILE"
openssl x509 -checkend 86400 -noout -in "$TLS_CERTIFICATE" >/dev/null || fail "로컬 인증서가 만료됐거나 곧 만료됩니다. $TLS_DIR 인증서를 백업한 뒤 새로 발급하세요."

info "전용 로컬 DB 시작: Compose $PROJECT, 127.0.0.1:3307 (기존 사용자 DB 미사용)"
# up 도중 Ctrl+C가 와도 생성된 DB를 정리할 수 있도록 호출 전에 표시합니다.
DB_OWNED=true
STAGE='database'
run_task compose up -d --wait --wait-timeout 180 --no-recreate mysql
current_id="$(current_db_id)" || fail '기동 후 DB 컨테이너 확인 실패'
[[ -n "$current_id" ]] && require_db_owner "$current_id" || fail '기동한 DB의 소유권 확인 실패'
[[ -z "$DB_INITIAL_ID" || "$current_id" == "$DB_INITIAL_ID" ]] || fail '기존 DB 컨테이너가 바뀌었습니다. 자동 진행하지 않습니다.'
STAGE='judge-image'
JUDGE_IMAGE="${JUDGE_IMAGE:-cobweb-languages:local}"
JUDGE_LANGUAGE_SET='17-v1'
# 이전 13언어 이미지가 남아 있어도 새 언어 제출을 SYSTEM_ERROR로 처리하지 않도록 갱신합니다.
installed_language_set="$(docker image inspect --format '{{index .Config.Labels "org.cobweb.judge.language-set"}}' "$JUDGE_IMAGE" 2>/dev/null || true)"
if [[ "$REBUILD_JUDGE" == true || "$installed_language_set" != "$JUDGE_LANGUAGE_SET" ]]; then
  info '17개 언어 채점 이미지를 빌드합니다. 첫 실행은 오래 걸릴 수 있습니다.'
  run_task docker build --progress=plain -t "$JUDGE_IMAGE" "$ROOT_DIR/judge"
else
  info "기존 채점 이미지 재사용: $JUDGE_IMAGE (변경 후에는 --rebuild-judge)"
fi
JUDGE_IMAGE_ID="$(docker image inspect --format '{{.Id}}' "$JUDGE_IMAGE")" || fail '채점 이미지 ID를 확인하지 못했습니다.'
[[ "$JUDGE_IMAGE_ID" =~ ^sha256:[a-f0-9]{64}$ ]] || fail '유효한 채점 이미지 ID가 아닙니다.'
info '현재 소스로 웹·워커 공통 JAR를 빌드합니다.'
STAGE='build'
mkdir "$BUILD_LOCK_DIR" 2>/dev/null || fail "다른 빌드가 진행 중이거나 빌드 잠금이 남았습니다: $BUILD_LOCK_DIR"
BUILD_LOCK_OWNED=true
run_task ./gradlew --no-daemon --console=plain bootJar
jars=("$ROOT_DIR"/build/libs/*-SNAPSHOT.jar)
[[ ${#jars[@]} == 1 && -f "${jars[0]}" ]] || fail '실행 가능한 SNAPSHOT JAR가 정확히 하나 필요합니다.'
BUILD_JAR="${jars[0]}"
APP_JAR="$LOG_DIR/app.jar"
source_hash="$(openssl dgst -sha256 "$BUILD_JAR" | awk '{print $NF}')"
cp "$BUILD_JAR" "$APP_JAR"
copied_hash="$(openssl dgst -sha256 "$APP_JAR" | awk '{print $NF}')"
after_hash="$(openssl dgst -sha256 "$BUILD_JAR" | awk '{print $NF}')"
[[ "$source_hash" =~ ^[a-f0-9]{64}$ && "$source_hash" == "$copied_hash" && "$source_hash" == "$after_hash" ]] || fail 'JAR 복사 중 빌드 산출물이 바뀌었습니다. 실행하지 않습니다.'
printf '%s\n' "$copied_hash" > "$LOG_DIR/app.jar.sha256"
rmdir "$BUILD_LOCK_DIR"
BUILD_LOCK_OWNED=false

# local 프로필의 URL까지 환경 속성으로 고정해 .env의 운영 DB 값이 쓰이지 않습니다.
# 메일/OAuth는 .env가 있으면 읽되, 비밀값을 화면이나 프로세스 인자에 출력하지 않습니다.
export SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3307/cobweb2026?serverTimezone=UTC'
export SPRING_DATASOURCE_USERNAME=cobweb SPRING_DATASOURCE_PASSWORD="$DEV_DB_PASSWORD"
export SPRING_FLYWAY_URL="$SPRING_DATASOURCE_URL"
export SPRING_FLYWAY_USER="$SPRING_DATASOURCE_USERNAME" SPRING_FLYWAY_PASSWORD="$SPRING_DATASOURCE_PASSWORD"
# 이후 Compose는 --env-file을 사용하므로 root 비밀번호를 모든 JVM 환경에 상속할 필요가 없습니다.
export -n DEV_ROOT_PASSWORD
# 원본 config.env에는 DB root 비밀값이 있으므로 JVM에 통째로 import하지 않습니다.
# 앱에 필요한 정확한 키만 사본으로 만들고 셸 source/eval은 사용하지 않습니다.
APP_CONFIG_FILE="$LOG_DIR/runtime.properties"
while IFS='=' read -r key value || [[ -n "$key" ]]; do
  case "$key" in
    DEV_DB_PASSWORD|JWT_SECRET|COBWEB_HTTPS_KEYSTORE_PASSWORD|MAIL_USERNAME|MAIL_PASSWORD|GOOGLE_CLIENT_ID|GOOGLE_CLIENT_SECRET|NAVER_CLIENT_ID|NAVER_CLIENT_SECRET|GITHUB_CLIENT_ID|GITHUB_CLIENT_SECRET)
      printf '%s=%s\n' "$key" "$value" ;;
  esac
done < "$CONFIG_FILE" > "$APP_CONFIG_FILE"
chmod 600 "$APP_CONFIG_FILE"
export SPRING_CONFIG_IMPORT="file:$APP_CONFIG_FILE[.properties],optional:file:$ROOT_DIR/.env[.properties]"
export PUBLIC_BASE_URL="https://localhost:$HTTPS_PORT"
# MySQL의 binlog가 켜진 기본 설정에서도 V17 트리거를 만들 수 있도록
# 이 단발 자식의 Flyway 연결에만 기존 로컬 root 자격을 전달합니다.
# 비밀번호는 argv/로그에 넣지 않으며 앱 datasource/웹/워커의 권한은 바꾸지 않습니다.
STAGE='migrate'
info "로컬 DB 마이그레이션: 로그 $LOG_DIR/migrate.log"
SPRING_CONFIG_IMPORT="file:$APP_CONFIG_FILE[.properties]" SPRING_FLYWAY_URL="$SPRING_DATASOURCE_URL" \
  SPRING_FLYWAY_USER=root SPRING_FLYWAY_PASSWORD="$DEV_ROOT_PASSWORD" \
  run_task "$JAVA_BIN" -jar "$APP_JAR" --local.migrate-only=true --spring.profiles.active=local \
  --spring.main.web-application-type=none --spring.flyway.enabled=true --judge.enabled=false --demo.seed=false \
  > "$LOG_DIR/migrate.log" 2>&1
info "웹 시작 (마이그레이션 검증): HTTP http://localhost:$PORT / HTTPS $PUBLIC_BASE_URL"
STAGE='web'
set -m
"$JAVA_BIN" -jar "$APP_JAR" --spring.profiles.active=local --server.address=0.0.0.0 --server.port="$PORT" \
  --local.https.enabled=true --local.https.port="$HTTPS_PORT" --local.https.key-store="$TLS_KEYSTORE" \
  --spring.flyway.enabled=true --judge.enabled=false --demo.seed=true </dev/null > "$LOG_DIR/web.log" 2>&1 &
WEB_PID=$!
set +m
remember_process "$WEB_PID" || fail '웹 프로세스 시작 정보 조회 실패. 임의 프로세스에 신호를 보내지 않습니다.'
ready=false
for ((attempt=0;attempt<120;attempt++)); do
  owned_alive "$WEB_PID" || fail "웹 기동 실패. 로그: $LOG_DIR/web.log"
  if curl --noproxy '*' --silent --fail --connect-timeout 1 --max-time 2 "http://127.0.0.1:$PORT/api/languages" >/dev/null && \
      curl --noproxy '*' --silent --fail --connect-timeout 1 --max-time 2 --cacert "$TLS_CERTIFICATE" "https://127.0.0.1:$HTTPS_PORT/api/languages" >/dev/null; then ready=true; break; fi
  sleep 1
done
[[ "$ready" == true ]] || fail "웹 준비 시간 초과. 로그: $LOG_DIR/web.log"

# 마이그레이션과 웹 준비가 끝난 뒤 같은 JAR의 non-web 워커를 실행합니다.
STAGE='worker'
set -m
"$JAVA_BIN" -jar "$APP_JAR" --spring.profiles.active=local --spring.main.web-application-type=none \
  --spring.flyway.enabled=false --judge.enabled=true --judge.image="$JUDGE_IMAGE_ID" \
  --judge.workspace="$JUDGE_WORKSPACE" --demo.seed=false </dev/null > "$LOG_DIR/worker.log" 2>&1 &
WORKER_PID=$!
set +m
remember_process "$WORKER_PID" || fail '워커 프로세스 시작 정보 조회 실패. 임의 프로세스에 신호를 보내지 않습니다.'
ready=false
for ((attempt=0;attempt<60;attempt++)); do
  owned_alive "$WEB_PID" && owned_alive "$WORKER_PID" || fail "웹/워커 기동 실패. 로그: $LOG_DIR"
  if grep -q 'Started Cobweb2026Application' "$LOG_DIR/worker.log"; then ready=true; break; fi
  sleep 1
done
[[ "$ready" == true ]] || fail "워커 준비 시간 초과. 로그: $LOG_DIR/worker.log"
STAGE='running'
STARTUP_COMPLETE=true
write_state
info "준비 완료: HTTP http://localhost:$PORT / HTTPS $PUBLIC_BASE_URL (문제 1000번 포함)"
info "HTTPS는 자체서명 인증서입니다. 인증서 확인: $TLS_CERTIFICATE (시스템 신뢰는 자동 설치하지 않음)"
info "$PORT HTTP 포트에 HTTPS를 보내면 프로토콜 오류가 납니다. HTTPS는 $HTTPS_PORT 포트로 접속하세요."
info "로그: $LOG_DIR"
info 'Ctrl+C를 누르면 웹·워커·전용 DB가 모두 종료됩니다. 데이터는 삭제하지 않습니다.'
while owned_alive "$WEB_PID" && owned_alive "$WORKER_PID"; do sleep 1; done
fail "웹 또는 워커가 종료되어 나머지도 정리합니다. 로그: $LOG_DIR"
