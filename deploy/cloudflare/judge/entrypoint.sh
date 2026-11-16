#!/bin/sh
set -eu

app_dir=/opt/cobweb
daemon_dir=/run/cobweb-docker
workspace=/tmp/cobweb-judge
image_tag=cobweb-languages:cloudflare-amd64
probe_name=cobweb-startup-probe
probe_volume=cobweb-startup-artifact
daemon_pid=
worker_pid=
log_pid=
health_pid=

fail() {
    printf '[Cobweb judge] %s\n' "$1" >&2
    exit 1
}

cleanup() {
    trap - EXIT
    # The JVM gets the same chance to release its lease as on a normal shutdown.
    for pid in "$health_pid" "$worker_pid"; do
        [ -z "$pid" ] || kill -TERM "$pid" 2>/dev/null || true
    done
    if [ -n "$worker_pid" ]; then
        remaining=15
        until ! kill -0 "$worker_pid" 2>/dev/null || [ "$remaining" -eq 0 ]; do
            sleep 1
            remaining=$((remaining - 1))
        done
        kill -KILL "$worker_pid" 2>/dev/null || true
    fi
    for pid in "$log_pid" "$daemon_pid"; do
        [ -z "$pid" ] || kill -TERM "$pid" 2>/dev/null || true
    done
    wait || true
}

trap cleanup EXIT
trap 'exit 143' TERM INT

[ -n "${DATABASE_URL:-}" ] || fail 'Worker DATABASE_URL is required.'
worker_secret=${JWT_SECRET:-}
[ "${#worker_secret}" -ge 32 ] || fail 'Worker JWT_SECRET is too short.'
unset worker_secret

# Only the outer worker can use the socket. An explicit dockerd command, rather
# than the image's default arguments, also prevents enabling a TCP listener.
install -d -m 0700 "$daemon_dir"
install -d -m 0755 "$workspace"
export DOCKER_HOST="unix://$daemon_dir/docker.sock"
unset DOCKER_CONTEXT DOCKER_TLS_VERIFY DOCKER_CERT_PATH
dockerd-entrypoint.sh dockerd --host="$DOCKER_HOST" --group=root \
    --data-root=/var/lib/cobweb-docker --exec-root="$daemon_dir/exec" --pidfile="$daemon_dir/dockerd.pid" \
    --iptables=false --ip6tables=false --ip-forward=false --bridge=none &
daemon_pid=$!

attempt=0
until timeout 2 docker info >/dev/null 2>&1; do
    kill -0 "$daemon_pid" 2>/dev/null || fail 'Docker daemon stopped during startup.'
    attempt=$((attempt + 1))
    [ "$attempt" -lt 30 ] || fail 'Docker daemon did not become ready.'
    sleep 1
done

test "$(timeout 5 docker info --format '{{.CgroupVersion}}')" = '2' \
    || fail 'Sandbox requires cgroup v2 with resource controls.'
timeout 300 sh -o pipefail -c 'cat "$1"/part-* | docker load' sh "$app_dir/judge-image" >/dev/null \
    || fail 'Could not load the packaged judge image.'
image_metadata=$(timeout 5 docker image inspect --format '{{.Os}}/{{.Architecture}} {{index .Config.Labels "org.cobweb.judge.language-set"}} {{.Id}}' "$image_tag") \
    || fail 'Packaged judge image metadata is missing.'
case "$image_metadata" in
    'linux/amd64 17-v3-generic-nested sha256:'*) image_id=${image_metadata##* } ;;
    *) fail 'Packaged judge image must be the current linux/amd64 language image.' ;;
esac
printf '%s\n' "$image_id" | grep -Eq '^sha256:[0-9a-f]{64}$' || fail 'Invalid judge image ID.'

sandbox_run() {
    timeout 30 docker run --pull=never --network=none --read-only --cap-drop=ALL \
        --security-opt=no-new-privileges --pids-limit=128 --memory=128m --memory-swap=128m --cpus=1 \
        --ulimit cpu=5:6 --ulimit fsize=67108864:67108864 --user=65534:65534 --log-driver=none \
        --tmpfs /tmp:rw,noexec,nosuid,size=32m,mode=1777 "$@"
}

timeout 5 docker volume create --driver local --opt type=tmpfs --opt device=tmpfs \
    --opt o=size=256m,uid=65534,gid=65534,mode=0700 "$probe_volume" >/dev/null \
    || fail 'Sandbox could not provision its tmpfs artifact volume.'
sandbox_run --detach --name "$probe_name" --entrypoint /bin/sleep \
    --mount "type=bind,source=$app_dir/check-sandbox.sh,target=/cobweb-check.sh,readonly" \
    --mount "type=volume,source=$probe_volume,target=/artifact" "$image_id" 120 >/dev/null \
    || fail 'Sandbox could not start with the required controls.'
timeout 15 docker exec "$probe_name" /bin/sh /cobweb-check.sh \
    || fail 'Sandbox isolation and resource verification failed.'
sandbox_run --rm --entrypoint /bin/sh --mount "type=volume,source=$probe_volume,target=/artifact,readonly" \
    "$image_id" -ec 'test "$(cat /artifact/startup-check)" = cobweb-fixture' \
    || fail 'Sandbox artifact could not be read by a separate test container.'
timeout 15 docker rm --force "$probe_name" >/dev/null
timeout 5 docker volume rm "$probe_volume" >/dev/null
printf '%s\n' '[Cobweb judge] Nested sandbox checks passed.'

# Offer only a static readiness endpoint. The Java application remains non-web.
# Flyway and startup catalog runners are off, so the Spring startup marker is
# emitted after creation of the worker's database and scheduled task beans.
status_dir=$(mktemp -d /run/cobweb-judge-status.XXXXXX)
mkdir -p "$status_dir/www/api"
mkfifo -m 0600 "$status_dir/worker.log.fifo"
(
    while IFS= read -r line; do
        printf '%s\n' "$line"
        case "$line" in
            *'Started Cobweb2026Application in '*) printf 'ready\n' > "$status_dir/www/api/ready" ;;
        esac
    done < "$status_dir/worker.log.fifo"
) &
log_pid=$!

# Non-servlet Spring still constructs shared OAuth and mail beans. They need
# defined placeholders, but the worker does not use the web service credentials.
GOOGLE_CLIENT_ID='' GOOGLE_CLIENT_SECRET='' NAVER_CLIENT_ID='' NAVER_CLIENT_SECRET='' \
GITHUB_CLIENT_ID='' GITHUB_CLIENT_SECRET='' MAIL_USERNAME='' MAIL_PASSWORD='' OPENAI_API_KEY='' \
    java -Xms64m -Xmx512m -XX:+ExitOnOutOfMemoryError -jar "$app_dir/app.jar" \
    --spring.profiles.active=prod,neon --spring.main.web-application-type=none --spring.flyway.enabled=false \
    --spring.main.log-startup-info=true --openai.enabled=false --openai.api-key= \
    --curriculum.level0.enabled=false --demo.seed=false --judge.enabled=true \
    --judge.image="$image_id" --judge.workspace="$workspace" > "$status_dir/worker.log.fifo" 2>&1 &
worker_pid=$!

attempt=0
until [ -s "$status_dir/www/api/ready" ]; do
    kill -0 "$worker_pid" "$daemon_pid" "$log_pid" 2>/dev/null || fail 'Worker stopped before it was ready.'
    attempt=$((attempt + 1))
    [ "$attempt" -lt 120 ] || fail 'Worker did not become ready.'
    sleep 1
done

busybox-extras httpd -f -p 0.0.0.0:8080 -h "$status_dir/www" &
health_pid=$!
while kill -0 "$worker_pid" "$daemon_pid" "$log_pid" "$health_pid" 2>/dev/null; do sleep 1; done
fail 'Worker, Docker daemon, or readiness server exited.'
