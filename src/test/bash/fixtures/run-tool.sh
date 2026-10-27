#!/usr/bin/env bash
# run-sh.test.sh 전용 가짜 도구입니다. Docker/JDK/네트워크를 실행하지 않습니다.
set -eu
tool="${0##*/}"
event="$tool"; for argument in "$@"; do event="$event <$argument>"; done
printf '%s\n' "$event" >> "$MOCK_STATE/events"

hold() {
  trap 'if [[ -f "$MOCK_STATE/identity-shift.pid" && "$$" == "$(< "$MOCK_STATE/identity-shift.pid")" ]]; then : > "$MOCK_STATE/protected-process.signalled"; fi; exit 0' INT TERM HUP
  while :; do /bin/sleep 1 & wait "$!" || true; done
}
stage_fail() { [[ "${MOCK_FAIL_STAGE:-}" != "$1" ]] || exit 23; }

case "$tool" in
  node|npm|javac) exit 0 ;;
  lsof) exit 1 ;;
  ps)
    # PID 시작 시각/그룹 identity는 격리 fixture 프로세스 자체를 조회합니다.
    if [[ "${1:-}" == -p ]]; then
      # 실제 다른 프로세스의 PID를 빌리지 않고 시작 identity 조회 결과만 바꿉니다.
      if [[ " $* " == *' lstart= '* && -f "$MOCK_STATE/identity-shift.pid" && "${2:-}" == "$(< "$MOCK_STATE/identity-shift.pid")" ]]; then
        printf 'fixture-reused-pid-different-start\n'
        exit 0
      fi
      exec /bin/ps "$@"
    fi
    [[ ! -f "$MOCK_STATE/ps.rows" ]] || while IFS= read -r row; do printf '%s\n' "$row"; done < "$MOCK_STATE/ps.rows"
    exit 0 ;;
  sleep)
    # 실제 TTY Ctrl+C가 launcher 대신 foreground sleep만 종료하는 경로를 재현합니다.
    # 준비 완료 뒤 첫 main-loop sleep만 길게 대기하고 cleanup의 sleep은 막지 않습니다.
    if [[ "${MOCK_FAIL_STAGE:-}" == foreground_interrupt && "${1:-}" == 1 && ! -e "$MOCK_STATE/foreground-sleep.claimed" ]]; then
      for state_file in "${MOCK_STATE%/state}/.local/run/$COBWEB_RUN_PROJECT/logs/"*/state.txt; do
        if [[ -f "$state_file" ]] && grep -Fx 'startup_complete=true' "$state_file" >/dev/null; then
          : > "$MOCK_STATE/foreground-sleep.claimed"
          printf '%s\n' "$$" > "$MOCK_STATE/foreground-sleep.pid"
          exec /bin/sleep 30
        fi
      done
    fi
    exec /bin/sleep 0.02 ;;
  openssl)
    if [[ "${1:-}" == rand ]]; then length=$(( ${3:-24} * 2 )); printf "%0${length}d\n" 0;
    elif [[ "${1:-}" == dgst ]]; then printf 'SHA2-256(%s)= %064d\n' "${3:-artifact}" 0;
    else stage_fail certificate; fi
    exit 0 ;;
  keytool)
    stage_fail tls
    previous=''; for argument in "$@"; do
      if [[ "$previous" == -keystore || "$previous" == -file ]]; then printf 'fake TLS artifact\n' > "$argument"; fi
      previous="$argument"
    done
    exit 0 ;;
  gradlew|gradle)
    printf '%s\n' "$$" > "$MOCK_STATE/gradle.pid"
    if [[ "${MOCK_FAIL_STAGE:-}" == gradle_interrupt ]]; then : > "$MOCK_STATE/gradle.waiting"; hold; fi
    if [[ "${MOCK_FAIL_STAGE:-}" == gradle_db_lost ]]; then printf 'exited\n' > "$MOCK_STATE/db.status"; exit 23; fi
    if [[ "${MOCK_FAIL_STAGE:-}" == cleanup_query ]]; then : > "$MOCK_STATE/cleanup-query-failed"; exit 23; fi
    if [[ "${MOCK_FAIL_STAGE:-}" == gradle_orphan ]]; then
      printf '%s %s\n' "$MOCK_CHILD_PID" "$$" > "$MOCK_STATE/ps.rows"
      /bin/sleep 0.2
      exit 0
    fi
    stage_fail gradle
    mkdir -p build/libs
    printf 'immutable fixture JAR\n' > build/libs/cobweb-0.0.1-SNAPSHOT.jar
    exit 0 ;;
  java)
    if [[ "${1:-}" != -jar ]]; then printf '    java.specification.version = 21\n' >&2; exit 0; fi
    jar="$2"; shift 2; kind=web
    for argument in "$@"; do [[ "$argument" != --spring.main.web-application-type=none ]] || kind=worker; done
    for argument in "$@"; do [[ "$argument" != --local.migrate-only=true ]] || kind=migrate; done
    printf '%s\n' "$jar" > "$MOCK_STATE/$kind.jar"
    printf '%s\n' "$$" > "$MOCK_STATE/$kind.pid"
    # fake credential의 값은 진단에 출력하지 않고 역할 분리 계약만 확인합니다.
    [[ "${SPRING_DATASOURCE_USERNAME:-}" == cobweb && "${SPRING_DATASOURCE_PASSWORD:-}" == fake-db-password ]] || exit 94
    [[ "${SPRING_FLYWAY_URL:-}" == "${SPRING_DATASOURCE_URL:-}" && -z "${DEV_ROOT_PASSWORD+x}" ]] || exit 94
    runtime_config="${jar%/app.jar}/runtime.properties"
    [[ -f "$runtime_config" ]] || exit 94
    ! grep -F 'DEV_ROOT_PASSWORD' "$runtime_config" >/dev/null || exit 94
    ! grep -F 'fake-root-password' "$runtime_config" >/dev/null || exit 94
    ! grep -F 'NOT_AN_APP_KEY' "$runtime_config" >/dev/null || exit 94
    grep -Fx 'MAIL_USERNAME=fixture-mail@example.invalid' "$runtime_config" >/dev/null || exit 94
    [[ "$(ls -ld "$runtime_config" | awk '{print substr($1,1,10)}')" == -rw------- ]] || exit 94
    if [[ "$kind" == migrate ]]; then
      [[ "${SPRING_CONFIG_IMPORT:-}" == "file:$runtime_config[.properties]" ]] || exit 94
      [[ "${SPRING_FLYWAY_USER:-}" == root && "${SPRING_FLYWAY_PASSWORD:-}" == fake-root-password ]] || exit 94
      [[ " $* " == *' --spring.profiles.active=local '* && " $* " == *' --spring.main.web-application-type=none '* \
        && " $* " == *' --spring.flyway.enabled=true '* && " $* " == *' --judge.enabled=false '* \
        && " $* " == *' --demo.seed=false '* ]] || exit 94
      : > "$MOCK_STATE/migrate.credentials-checked"
      if [[ "${MOCK_FAIL_STAGE:-}" == migrate_interrupt ]]; then : > "$MOCK_STATE/migrate.waiting"; hold; fi
      stage_fail migrate
      : > "$MOCK_STATE/migrate.completed"
      exit 0
    fi
    [[ "${SPRING_CONFIG_IMPORT:-}" == "file:$runtime_config[.properties],optional:file:${MOCK_STATE%/state}/.env[.properties]" ]] || exit 94
    [[ -f "$MOCK_STATE/migrate.completed" ]] || exit 94
    [[ "${SPRING_FLYWAY_USER:-}" == cobweb && "${SPRING_FLYWAY_PASSWORD:-}" == fake-db-password ]] || exit 94
    : > "$MOCK_STATE/$kind.credentials-checked"
    stage_fail "$kind"
    [[ "$kind" != worker ]] || printf 'Started Cobweb2026Application\n'
    hold ;;
  curl)
    # readiness는 가짜 웹 프로세스가 시작된 뒤에만 성공합니다.
    [[ -f "$MOCK_STATE/web.pid" ]] || exit 7
    [[ "${MOCK_FAIL_STAGE:-}" != readiness ]] || exit 7
    exit 0 ;;
  docker) ;;
  *) printf '허용하지 않은 가짜 명령: %s\n' "$tool" >&2; exit 96 ;;
esac

command="${1:-}"; shift || true
case "$command" in
  info) stage_fail docker; exit 0 ;;
  compose)
    while (( $# )); do
      case "$1" in
        --ansi|--progress|--project-directory|--env-file|-p|-f) shift 2 ;;
        *) break ;;
      esac
    done
    command="${1:-}"; shift || true
    case "$command" in
      version) exit 0 ;;
      config) stage_fail config; exit 0 ;;
      ps)
        [[ ! -f "$MOCK_STATE/cleanup-query-failed" ]] || exit 23
        [[ -f "$MOCK_STATE/db.id" ]] || exit 0
        if [[ " $* " == *' --status running '* ]]; then [[ "$(< "$MOCK_STATE/db.status")" == running ]] || exit 0; fi
        printf '%s\n' "$(< "$MOCK_STATE/db.id")"
        exit 0 ;;
      up)
        stage_fail db_before
        [[ -f "$MOCK_STATE/db.id" ]] || printf 'bbbbbbbbbbbb\n' > "$MOCK_STATE/db.id"
        [[ "${MOCK_FAIL_STAGE:-}" != swapped_db ]] || printf 'dddddddddddd\n' > "$MOCK_STATE/db.id"
        printf 'running\n' > "$MOCK_STATE/db.status"
        : > "$MOCK_STATE/network.exists"
        : > "$MOCK_STATE/volume.exists"
        stage_fail db
        exit 0 ;;
      stop)
        [[ ! -f "$MOCK_STATE/db.id" ]] || printf 'exited\n' > "$MOCK_STATE/db.status"
        exit 0 ;;
      *) printf '허용하지 않은 compose: %s\n' "$command" >&2; exit 96 ;;
    esac ;;
  inspect)
    id="${!#}"
    [[ -f "$MOCK_STATE/db.id" && "$id" == "$(< "$MOCK_STATE/db.id")" ]] || exit 1
    if [[ " $* " == *'.State.Status'* ]]; then [[ -f "$MOCK_STATE/db.id" ]] || exit 1; printf '%s\n' "$(< "$MOCK_STATE/db.status")";
    elif [[ " $* " == *'.State.Running'* ]]; then [[ "$(< "$MOCK_STATE/db.status")" == running ]] && printf 'true\n' || printf 'false\n';
    elif [[ " $* " == *'com.docker.compose.project.working_dir'* ]]; then
      owner_root="${MOCK_STATE%/state}"; [[ ! -f "$MOCK_STATE/foreign-owner" ]] || owner_root=/different/project/root
      printf '%s|mysql|%s\n' "$COBWEB_RUN_PROJECT" "$owner_root"
    fi
    exit 0 ;;
  ps) exit 0 ;;
  stop)
    [[ -f "$MOCK_STATE/db.id" && "${!#}" == "$(< "$MOCK_STATE/db.id")" ]] || exit 1
    [[ ! -f "$MOCK_STATE/db.id" ]] || printf 'exited\n' > "$MOCK_STATE/db.status"
    exit 0 ;;
  start)
    [[ -f "$MOCK_STATE/db.id" && "${!#}" == "$(< "$MOCK_STATE/db.id")" ]] || exit 1
    printf 'running\n' > "$MOCK_STATE/db.status"
    exit 0 ;;
  rm)
    [[ " $* " != *' -v '* && " $* " != *' --volumes '* ]] || exit 97
    for argument in "$@"; do
      if [[ -f "$MOCK_STATE/db.id" && "$argument" == "$(< "$MOCK_STATE/db.id")" ]]; then /bin/rm "$MOCK_STATE/db.id"; fi
    done
    exit 0 ;;
  network)
    case "${1:-}" in
      ls) [[ ! -f "$MOCK_STATE/network.exists" ]] || printf 'cccccccccccc\n'; exit 0 ;;
      inspect)
        [[ -f "$MOCK_STATE/network.exists" && "${!#}" == cccccccccccc ]] || exit 1
        if [[ " $* " == *'.Labels'* ]]; then printf '%s\n' "$COBWEB_RUN_PROJECT";
        elif [[ " $* " == *'.Containers'* ]]; then [[ -f "$MOCK_STATE/db.id" ]] && printf '1\n' || printf '0\n';
        else printf 'cccccccccccc\n'; fi
        exit 0 ;;
      rm) [[ "${!#}" == cccccccccccc ]] || exit 1; /bin/rm -f "$MOCK_STATE/network.exists"; exit 0 ;;
      *) exit 96 ;;
    esac ;;
  volume)
    [[ "${1:-}" != rm ]] || exit 97
    [[ "${1:-}" != inspect || -f "$MOCK_STATE/volume.exists" ]]
    exit $? ;;
  image) if [[ " $* " == *'org.cobweb.judge.language-set'* ]]; then printf '17-v1\n'; else printf 'sha256:%064d\n' 0; fi; exit 0 ;;
  build) stage_fail judge; exit 0 ;;
  *) printf '허용하지 않은 docker: %s\n' "$command" >&2; exit 96 ;;
esac
