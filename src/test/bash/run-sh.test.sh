#!/usr/bin/env bash
# 셸 런처 회귀: 격리된 임시 프로젝트 + 가짜 도구만 사용합니다.
# 실제 Docker/Gradle/JDK 서버/컴파일/네트워크 호출은 없습니다.
set -euo pipefail
ulimit -v 262144 2>/dev/null || true
TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_DIR="$(cd "$TEST_DIR/../../.." && pwd -P)"
SUITE_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/cobweb-run-test.XXXXXX")"
SUITE_ROOT="$(cd "$SUITE_ROOT" && pwd -P)"
PROJECT=cobweb-regression
CASE_DIR=''; LAUNCHER=''; SENTINEL=''; PASSED=0

finish() {
  local status=$?
  trap - EXIT INT TERM
  if [[ -n "$LAUNCHER" ]]; then kill -TERM "$LAUNCHER" 2>/dev/null || true; wait "$LAUNCHER" 2>/dev/null || true; fi
  if [[ -n "$SENTINEL" ]]; then kill -TERM "$SENTINEL" 2>/dev/null || true; wait "$SENTINEL" 2>/dev/null || true; fi
  if [[ "$status" == 0 ]]; then
    case "${SUITE_ROOT##*/}" in cobweb-run-test.*) [[ "$SUITE_ROOT" != / && -d "$SUITE_ROOT" ]] && /bin/rm -rf "$SUITE_ROOT" ;; esac
  else printf '격리 fixture 보존: %s\n' "$SUITE_ROOT" >&2; fi
  exit "$status"
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
fail() { printf 'FAIL: %s\n' "$*" >&2; [[ -z "$CASE_DIR" || ! -f "$CASE_DIR/output" ]] || tail -60 "$CASE_DIR/output" >&2; exit 1; }
assert_file() { [[ -f "$1" ]] || fail "파일 없음: $1"; }
assert_no_file() { [[ ! -e "$1" ]] || fail "불필요한 파일/잠금 잔존: $1"; }
assert_equal() { [[ "$1" == "$2" ]] || fail "예상=$2, 실제=$1 ($3)"; }
assert_event() { grep -F -- "$1" "$CASE_DIR/state/events" >/dev/null || fail "호출 누락: $1"; }
assert_no_event() { ! grep -F -- "$1" "$CASE_DIR/state/events" >/dev/null || fail "금지 호출: $1"; }
pass() { PASSED=$((PASSED+1)); printf 'ok %s - %s\n' "$PASSED" "$*"; }

fixture() {
  local name="$1" initial="$2" existing_network="${3:-false}" tool
  CASE_DIR="$SUITE_ROOT/$name"
  mkdir -p "$CASE_DIR/bin" "$CASE_DIR/jdk/bin" "$CASE_DIR/state" "$CASE_DIR/.local/run/$PROJECT" "$CASE_DIR/judge"
  cp "$REPO_DIR/run.sh" "$CASE_DIR/run.sh"
  cp "$REPO_DIR/compose.dev.yaml" "$CASE_DIR/compose.dev.yaml"
  cp "$TEST_DIR/fixtures/run-tool.sh" "$CASE_DIR/gradlew"
  for tool in docker curl openssl ps node npm lsof sleep gradle; do cp "$TEST_DIR/fixtures/run-tool.sh" "$CASE_DIR/bin/$tool"; done
  for tool in java javac keytool; do cp "$TEST_DIR/fixtures/run-tool.sh" "$CASE_DIR/jdk/bin/$tool"; done
  chmod +x "$CASE_DIR/run.sh" "$CASE_DIR/gradlew" "$CASE_DIR/bin/"* "$CASE_DIR/jdk/bin/"*
  : > "$CASE_DIR/state/events"
  if [[ "$initial" != absent ]]; then
    printf 'aaaaaaaaaaaa\n' > "$CASE_DIR/state/db.id"
    printf '%s\n' "$initial" > "$CASE_DIR/state/db.status"
    : > "$CASE_DIR/state/volume.exists"
  fi
  [[ "$existing_network" != true ]] || : > "$CASE_DIR/state/network.exists"
  printf '%s\n' 'DEV_DB_PASSWORD=fake-db-password' 'DEV_ROOT_PASSWORD=fake-root-password' \
    'JWT_SECRET=0000000000000000000000000000000000000000000000000000000000000000' \
    'MAIL_USERNAME=fixture-mail@example.invalid' 'NOT_AN_APP_KEY=must-not-be-imported' > "$CASE_DIR/.local/run/$PROJECT/config.env"
}

start() {
  local stage="$1"; shift
  # 백그라운드 런처가 SIGINT를 무시한 채 시작하지 않도록 생성 시에만 job control을 켭니다.
  set -m
  MOCK_STATE="$CASE_DIR/state" MOCK_FAIL_STAGE="$stage" MOCK_CHILD_PID="$SENTINEL" JAVA_HOME="$CASE_DIR/jdk" \
    PATH="$CASE_DIR/bin:$PATH" COBWEB_RUN_PROJECT="$PROJECT" COBWEB_PORT=18080 COBWEB_HTTPS_PORT=18443 \
    bash "$CASE_DIR/run.sh" "$@" > "$CASE_DIR/output" 2>&1 & LAUNCHER=$!
  set +m
}
wait_for() {
  local file="$1" pattern="${2:-}" attempt
  for ((attempt=0;attempt<350;attempt++)); do
    if [[ -f "$file" ]] && { [[ -z "$pattern" ]] || grep -F -- "$pattern" "$file" >/dev/null; }; then return 0; fi
    if ! kill -0 "$LAUNCHER" 2>/dev/null; then fail "기다리던 상태 전에 런처 종료: $file $pattern"; fi
    /bin/sleep 0.02
  done
  fail "가짜 런처 시간 초과: $file $pattern"
}
await_exit() {
  local expected="$1" actual=0 attempt
  for ((attempt=0;attempt<350;attempt++)); do
    kill -0 "$LAUNCHER" 2>/dev/null || break
    /bin/sleep 0.02
  done
  if kill -0 "$LAUNCHER" 2>/dev/null; then kill -TERM "$LAUNCHER" 2>/dev/null || true; fail '런처가 제한 시간 안에 끝나지 않았습니다.'; fi
  wait "$LAUNCHER" || actual=$?
  LAUNCHER=''
  if [[ "$expected" == failure ]]; then [[ "$actual" != 0 ]] || fail '실패 시나리오가 성공했습니다.';
  else assert_equal "$actual" "$expected" '종료 코드'; fi
  if [[ "${2:-}" == keep-lock ]]; then [[ -d "$CASE_DIR/.local/run/$PROJECT/lock" ]] || fail '불완전 복구에서 잠금이 제거됐습니다.';
  else assert_no_file "$CASE_DIR/.local/run/$PROJECT/lock"; fi
  if [[ "${2:-}" == keep-build-lock ]]; then [[ -d "$CASE_DIR/.local/run/build.lock" ]] || fail '다른 실행의 빌드 잠금이 제거됐습니다.';
  else assert_no_file "$CASE_DIR/.local/run/build.lock"; fi
}
assert_preserved_data() {
  assert_file "$CASE_DIR/.local/run/$PROJECT/config.env"
  assert_no_event '<volume> <rm>'
  assert_no_event '<down>'
  assert_no_event '<--volumes>'
  assert_no_event '<rm> <-v>'
  assert_no_event '<rm> <-f> <-v>'
}
assert_no_processes() {
  local kind pid
  for kind in web worker migrate gradle; do
    if [[ -f "$CASE_DIR/state/$kind.pid" ]]; then pid="$(< "$CASE_DIR/state/$kind.pid")"; ! kill -0 "$pid" 2>/dev/null || fail "가짜 $kind 프로세스가 남았습니다: $pid"; fi
  done
}

printf 'TAP version 13\n'
for stage in config tls certificate db_before db judge gradle migrate web worker readiness; do
  fixture "failure-$stage" absent
  if [[ "$stage" == judge ]]; then start "$stage" --rebuild-judge; else start "$stage"; fi
  await_exit failure
  assert_no_file "$CASE_DIR/state/db.id"
  assert_no_file "$CASE_DIR/state/network.exists"
  assert_preserved_data
  if [[ "$stage" == db || "$stage" == judge || "$stage" == gradle || "$stage" == migrate || "$stage" == web || "$stage" == worker || "$stage" == readiness ]]; then assert_file "$CASE_DIR/state/volume.exists"; fi
  assert_no_processes
  pass "단계 실패 $stage: 초기 DB 없음 복원, 데이터·설정 보존, 잠금 해제"
done

for initial in running exited created; do
  fixture "existing-$initial" "$initial" true
  start gradle
  await_exit failure
  assert_equal "$(< "$CASE_DIR/state/db.id")" aaaaaaaaaaaa '기존 컨테이너 ID 유지'
  if [[ "$initial" == running ]]; then assert_equal "$(< "$CASE_DIR/state/db.status")" running '실행 중이던 DB 유지';
  else assert_equal "$(< "$CASE_DIR/state/db.status")" exited '중지되어 있던 DB 복원'; fi
  assert_file "$CASE_DIR/state/network.exists"
  assert_file "$CASE_DIR/state/volume.exists"
  assert_event '<--no-recreate>'
  assert_no_event '<rm> <aaaaaaaaaaaa>'
  assert_preserved_data
  assert_no_processes
  pass "기존 DB $initial: 기동 실패에 실행 상태/컨테이너/기존 network 보존"
done

for initial in running exited; do
  fixture "migration-failure-$initial" "$initial" true
  start migrate
  await_exit failure
  assert_equal "$(< "$CASE_DIR/state/db.id")" aaaaaaaaaaaa '마이그레이션 실패 뒤 기존 DB ID 유지'
  assert_equal "$(< "$CASE_DIR/state/db.status")" "$initial" '마이그레이션 실패 뒤 기존 DB 실행 상태 복구'
  assert_file "$CASE_DIR/state/migrate.credentials-checked"
  assert_no_file "$CASE_DIR/state/web.pid"
  assert_no_file "$CASE_DIR/state/worker.pid"
  assert_preserved_data
  assert_no_processes
  state_files=("$CASE_DIR/.local/run/$PROJECT/logs/"*/state.txt)
  assert_file "${state_files[0]}"
  grep -Fx 'stage=migrate' "${state_files[0]}" >/dev/null || fail '마이그레이션 실패 단계 기록 누락'
  pass "마이그레이션 실패: 기존 DB $initial 복구, 웹/워커 시작 차단, 데이터 보존"
done

fixture migration-interrupt absent
start migrate_interrupt
wait_for "$CASE_DIR/state/migrate.waiting"
kill -INT "$LAUNCHER"
await_exit 130
assert_no_file "$CASE_DIR/state/db.id"
assert_no_file "$CASE_DIR/state/network.exists"
assert_no_file "$CASE_DIR/state/web.pid"
assert_no_file "$CASE_DIR/state/worker.pid"
assert_file "$CASE_DIR/state/volume.exists"
assert_preserved_data
assert_no_processes
pass '마이그레이션 도중 Ctrl+C: 단발 JVM 종료, 실행 전 상태 복구, DB 내용 보존'

fixture foreign-owner running true
: > "$CASE_DIR/state/foreign-owner"
start ''
await_exit failure
assert_equal "$(< "$CASE_DIR/state/db.id")" aaaaaaaaaaaa '외부 경로 DB ID 유지'
assert_equal "$(< "$CASE_DIR/state/db.status")" running '외부 경로 DB 실행 상태 유지'
assert_no_event '<up>'
assert_no_event '<stop>'
assert_no_event '<rm>'
assert_file "$CASE_DIR/state/network.exists"
assert_preserved_data
pass '동일 project 이름의 다른 working_dir: 어떠한 실행 상태 변경도 없이 거절'

fixture existing-running-lost running true
start gradle_db_lost
await_exit failure
assert_equal "$(< "$CASE_DIR/state/db.id")" aaaaaaaaaaaa '실패 도중 중지된 기존 DB ID 유지'
assert_equal "$(< "$CASE_DIR/state/db.status")" running '처음 실행 중이던 DB를 동일 ID로 재시작'
assert_event '<start> <aaaaaaaaaaaa>'
assert_preserved_data
pass '원래 running DB가 실패 도중 stopped: 동일 컨테이너만 재시작하여 실행 상태 복구'

fixture new-db-existing-network absent true
start gradle
await_exit failure
assert_no_file "$CASE_DIR/state/db.id"
assert_file "$CASE_DIR/state/network.exists"
assert_file "$CASE_DIR/state/volume.exists"
assert_no_event '<network> <rm>'
assert_preserved_data
pass 'DB만 신규이고 network는 기존: 신규 컨테이너만 제거하고 기존 network·volume 보존'

fixture changed-db-id running true
start swapped_db
await_exit failure keep-lock
assert_equal "$(< "$CASE_DIR/state/db.id")" dddddddddddd '외부 교체 컨테이너는 건드리지 않음'
assert_equal "$(< "$CASE_DIR/state/db.status")" running '외부 교체 컨테이너 실행 상태 보존'
assert_no_event '<stop>'
assert_no_event '<rm>'
assert_preserved_data
pass '실행 도중 기존 DB ID 변경: 교체 컨테이너 무변경, 복구 실패 알림과 잠금 유지'

fixture successful-task-left-child absent
/bin/sleep 30 & SENTINEL=$!
start gradle_orphan
await_exit failure
! kill -0 "$SENTINEL" 2>/dev/null || fail '성공 종료된 작업의 분리 자식이 남았습니다.'
wait "$SENTINEL" 2>/dev/null || true
SENTINEL=''
assert_no_file "$CASE_DIR/state/db.id"
assert_file "$CASE_DIR/state/volume.exists"
assert_no_file "$CASE_DIR/state/web.pid"
assert_preserved_data
pass '작업 leader가 성공 종료해도 기록된 분리 자식이 남으면 기동 실패 후 자식·DB 상태 정리'

fixture cleanup-query-failure absent
start cleanup_query
await_exit failure keep-lock
assert_equal "$(< "$CASE_DIR/state/db.status")" running '조회 실패 시 검증할 수 없는 DB는 임의 중지/제거하지 않음'
assert_event '<ps> <-a> <-q> <mysql>'
grep -F '실행 상태 복구가 완료되지 않았습니다' "$CASE_DIR/output" >/dev/null || fail '불완전한 복구 경고가 없습니다.'
! grep -F '실행 전 상태 복구 완료.' "$CASE_DIR/output" >/dev/null || fail '조회에 실패했는데 복구 완료로 안내했습니다.'
assert_preserved_data
pass 'cleanup Docker 조회 실패: 복구 성공을 주장하지 않고 프로젝트 잠금 유지'

fixture preexisting-build-lock absent
mkdir "$CASE_DIR/.local/run/build.lock"
printf 'another-build-owner\n' > "$CASE_DIR/.local/run/build.lock/owner.txt"
start ''
await_exit failure keep-build-lock
assert_no_file "$CASE_DIR/state/db.id"
assert_no_file "$CASE_DIR/state/gradle.pid"
assert_equal "$(< "$CASE_DIR/.local/run/build.lock/owner.txt")" another-build-owner '기존 빌드 잠금 소유자 내용 유지'
assert_preserved_data
pass '기존 공용 빌드 잠금: 다른 빌드 잠금은 유지하고 이번 실행 DB만 초기 상태 복구'

for initial in paused restarting dead; do
  fixture "unsupported-$initial" "$initial" true
  start ''
  await_exit failure
  assert_equal "$(< "$CASE_DIR/state/db.status")" "$initial" '지원하지 않는 초기 상태 불변'
  assert_no_event '<up>'
  assert_no_event '<stop>'
  assert_preserved_data
  pass "초기 DB $initial: 변경 없이 거절"
done

fixture startup-interrupt absent
start gradle_interrupt
wait_for "$CASE_DIR/state/gradle.waiting"
kill -INT "$LAUNCHER"
await_exit 130
assert_no_file "$CASE_DIR/state/db.id"
assert_no_file "$CASE_DIR/state/network.exists"
assert_file "$CASE_DIR/state/volume.exists"
assert_preserved_data
assert_no_processes
state_files=("$CASE_DIR/.local/run/$PROJECT/logs/"*/state.txt)
assert_file "${state_files[0]}"
grep -F 'startup_complete=false' "${state_files[0]}" >/dev/null || fail '중도 Ctrl+C가 준비 완료로 기록됐습니다.'
grep -F 'stop_requested=true' "${state_files[0]}" >/dev/null || fail '중도 Ctrl+C 종료 요청 기록 누락'
pass '기동 도중 Ctrl+C: 새 컨테이너/network 제거, volume 보존, 잠금 해제'

fixture completed-interrupt absent
start ''
wait_for "$CASE_DIR/output" '준비 완료:'
web_jar="$(< "$CASE_DIR/state/web.jar")"; worker_jar="$(< "$CASE_DIR/state/worker.jar")"
assert_equal "$web_jar" "$worker_jar" '웹·워커 동일 JAR'
assert_equal "$(< "$CASE_DIR/state/migrate.jar")" "$web_jar" '단발 마이그레이션도 동일 immutable JAR'
assert_file "$CASE_DIR/state/migrate.credentials-checked"
assert_file "$CASE_DIR/state/web.credentials-checked"
assert_file "$CASE_DIR/state/worker.credentials-checked"
assert_no_event 'fake-root-password'
case "$web_jar" in "$CASE_DIR/.local/run/$PROJECT/logs/"*/app.jar) ;; *) fail "immutable JAR 경로 아님: $web_jar" ;; esac
assert_file "$web_jar"
assert_file "$web_jar.sha256"
original_jar="$(< "$web_jar")"
printf 'new build replaced source JAR\n' > "$CASE_DIR/build/libs/cobweb-0.0.1-SNAPSHOT.jar"
assert_equal "$(< "$web_jar")" "$original_jar" '빌드 산출물 변경이 실행 복사본에 영향 없음'
kill -INT "$LAUNCHER"
await_exit 130
assert_equal "$(< "$CASE_DIR/state/db.status")" exited '준비 완료 이후 DB 중지'
assert_file "$CASE_DIR/state/db.id"
assert_file "$CASE_DIR/state/volume.exists"
assert_file "$CASE_DIR/.local/run/$PROJECT/tls/localhost.p12"
assert_file "$CASE_DIR/.local/run/$PROJECT/tls/localhost.crt"
assert_file "$web_jar"
assert_no_processes
assert_preserved_data
assert_event "<--judge.workspace=${web_jar%/app.jar}/judge>"
state_file="${web_jar%/app.jar}/state.txt"
assert_file "$state_file"
grep -F 'startup_complete=true' "$state_file" >/dev/null || fail '준비 완료 상태 기록 누락'
grep -F 'stop_requested=true' "$state_file" >/dev/null || fail '완료 후 Ctrl+C 종료 요청 기록 누락'
pass '정상 기동 후 Ctrl+C: 웹/워커/DB 종료, 설정/TLS/로그/JAR/volume 보존'

fixture foreground-sleep-interrupt running true
start foreground_interrupt
wait_for "$CASE_DIR/output" '준비 완료:'
wait_for "$CASE_DIR/state/foreground-sleep.pid"
foreground_sleep_pid="$(< "$CASE_DIR/state/foreground-sleep.pid")"
# launcher에는 INT를 보내지 않습니다. foreground 자식의 130 종료만으로
# EXIT cleanup이 정상 사용자 종료를 분류해야 기존 running DB도 중지됩니다.
kill -INT "$foreground_sleep_pid"
await_exit 130
assert_equal "$(< "$CASE_DIR/state/db.id")" aaaaaaaaaaaa '기존 DB ID 보존'
assert_equal "$(< "$CASE_DIR/state/db.status")" exited 'foreground sleep Ctrl+C도 정상 종료로 DB 중지'
assert_no_event '<start> <aaaaaaaaaaaa>'
assert_file "$CASE_DIR/state/network.exists"
assert_file "$CASE_DIR/state/volume.exists"
assert_no_processes
assert_preserved_data
state_files=("$CASE_DIR/.local/run/$PROJECT/logs/"*/state.txt)
assert_file "${state_files[0]}"
grep -F 'startup_complete=true' "${state_files[0]}" >/dev/null || fail 'foreground Ctrl+C의 준비 완료 기록 누락'
grep -F 'stop_requested=true' "${state_files[0]}" >/dev/null || fail 'foreground Ctrl+C 종료 요청 기록 누락'
pass 'foreground sleep만 INT 종료: launcher 130, 정상 종료 기록, 기존 running DB도 중지'

fixture changed-process-identity running true
start ''
wait_for "$CASE_DIR/output" '준비 완료:'
SENTINEL="$(< "$CASE_DIR/state/web.pid")"
# 이 fixture의 웹 PID는 그대로 두고 ps 시작 identity만 바꿔 PID 재사용을 모사합니다.
# 실제 무관한 프로세스나 시스템 PID에는 신호를 보내지 않습니다.
printf '%s\n' "$SENTINEL" > "$CASE_DIR/state/identity-shift.pid"
kill -INT "$LAUNCHER"
await_exit 130 keep-lock
kill -0 "$SENTINEL" 2>/dev/null || fail 'identity가 달라진 PID에 종료 신호를 보냈습니다.'
assert_no_file "$CASE_DIR/state/protected-process.signalled"
assert_equal "$(< "$CASE_DIR/state/db.status")" exited '다른 소유 리소스는 정상 종료'
grep -F '실행 상태 복구가 완료되지 않았습니다' "$CASE_DIR/output" >/dev/null || fail 'PID identity 충돌의 불완전 정리 경고 누락'
assert_preserved_data
# 보존 판정을 확인한 뒤 이 테스트가 직접 만든 fixture 프로세스만 정리합니다.
kill -TERM "$SENTINEL"
for ((attempt=0;attempt<350;attempt++)); do
  kill -0 "$SENTINEL" 2>/dev/null || break
  /bin/sleep 0.02
done
! kill -0 "$SENTINEL" 2>/dev/null || fail '보호 판정 후 fixture 프로세스 정리 실패'
SENTINEL=''
assert_no_processes
pass 'PID 시작 identity 변경: 해당 PID 무신호 보존, 불완전 정리 경고와 잠금 유지'

fixture external-flyway-environment absent
SPRING_FLYWAY_URL=jdbc:mysql://external.invalid/forbidden SPRING_FLYWAY_USER=untrusted-root \
  SPRING_FLYWAY_PASSWORD=untrusted-external-secret start ''
wait_for "$CASE_DIR/output" '준비 완료:'
assert_file "$CASE_DIR/state/migrate.credentials-checked"
assert_file "$CASE_DIR/state/web.credentials-checked"
assert_file "$CASE_DIR/state/worker.credentials-checked"
assert_no_event 'untrusted-external-secret'
assert_no_event 'fake-root-password'
kill -INT "$LAUNCHER"
await_exit 130
assert_equal "$(< "$CASE_DIR/state/db.status")" exited '외부 Flyway 오염 차단 뒤 정상 종료'
assert_preserved_data
assert_no_processes
pass '외부 Flyway URL/계정/비밀번호 무시: 전용 DB와 단발 root/웹·워커 제한 계정 분리'

for blocked_option in SPRING_APPLICATION_JSON JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS \
    SPRING_CONFIG_LOCATION SPRING_CONFIG_ADDITIONAL_LOCATION SPRING_CONFIG_NAME \
    SPRING_FLYWAY_SCHEMAS SPRING_FLYWAY_DEFAULT_SCHEMA SPRING_FLYWAY_LOCATIONS SPRING_FLYWAY_INIT_SQLS; do
  fixture "blocked-$blocked_option" absent
  # 가짜 도구를 호출하기 전 차단되어야 합니다. 값은 출력/argv 어디에도 복사하지 않습니다.
  export "$blocked_option=untrusted-injection-secret"
  start ''
  unset "$blocked_option"
  await_exit failure
  assert_no_file "$CASE_DIR/state/db.id"
  assert_no_file "$CASE_DIR/state/gradle.pid"
  assert_no_file "$CASE_DIR/state/migrate.pid"
  [[ ! -s "$CASE_DIR/state/events" ]] || fail '환경 주입 거절 전에 외부 도구를 실행했습니다.'
  grep -F "$blocked_option 환경변수를 해제" "$CASE_DIR/output" >/dev/null || fail '차단 환경변수 이름 안내 누락'
  ! grep -F 'untrusted-injection-secret' "$CASE_DIR/output" >/dev/null || fail '차단한 환경변수 값이 출력됐습니다.'
  assert_file "$CASE_DIR/.local/run/$PROJECT/config.env"
  pass "$blocked_option 주입: 비밀값 출력 없이 도구/DB 변경 전 거절"
done
printf '1..%s\n' "$PASSED"
