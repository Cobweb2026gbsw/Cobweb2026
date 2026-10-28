#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
work_dir="$(mktemp -d "${TMPDIR:-/tmp}/cobweb-compose-ci.XXXXXX")"
trap 'rm -r "$work_dir"' EXIT

# A private copy prevents Compose from reading a developer's real .env file.
cp "$repo_dir/.env.example" "$work_dir/.env"
cp "$repo_dir"/compose.*.yaml "$work_dir/"
mkdir -p "$work_dir/deploy"
cp "$repo_dir/deploy/Caddyfile" "$work_dir/deploy/Caddyfile"
mkdir -p "$work_dir/deploy/postgres"
cp "$repo_dir/deploy/postgres/init-app.sh" "$work_dir/deploy/postgres/init-app.sh"
cp "$repo_dir/deploy/postgres/entrypoint.sh" "$work_dir/deploy/postgres/entrypoint.sh"

# env_file 상속 경계도 실값 대신 이 임시 가짜 값으로 검사합니다.
printf '\n%s\n' \
  'POSTGRES_ADMIN_PASSWORD=ci-test-only' 'DEV_ADMIN_PASSWORD=ci-test-only' \
  'DEV_ROOT_PASSWORD=ci-legacy-only' 'MYSQL_ROOT_PASSWORD=ci-legacy-only' \
  'JWT_SECRET=ci-jwt-only' 'GOOGLE_CLIENT_SECRET=ci-google-only' 'MAIL_PASSWORD=ci-mail-only' \
  >> "$work_dir/.env"

render_config() {
  local file="$1"; shift
  env -i HOME="${HOME:-/tmp}" PATH="$PATH" \
    DEV_DB_PASSWORD=ci-test-only DEV_ADMIN_PASSWORD=ci-test-only \
    DB_PASSWORD=ci-test-only POSTGRES_ADMIN_PASSWORD=ci-test-only \
    SITE_DOMAIN=ci.example.invalid \
    WORKER_DB_URL='jdbc:postgresql://db.example.invalid:5432/cobweb_test?sslmode=verify-full' \
    WORKER_DB_USERNAME=ci-worker WORKER_DB_PASSWORD=ci-test-only \
    docker compose --project-directory "$work_dir" --env-file "$work_dir/.env" \
      -f "$work_dir/$file" config "$@"
}

for file in compose.dev.yaml compose.prod.yaml compose.worker.yaml; do
  printf 'Checking %s\n' "$file"
  render_config "$file" --quiet
done

for target in 'compose.prod.yaml:app' 'compose.worker.yaml:worker'; do
  file="${target%%:*}"; service="${target#*:}"
  render_config "$file" --format json > "$work_dir/$file.json"
  node --input-type=module - "$work_dir/$file.json" "$service" <<'NODE'
import {readFileSync} from 'node:fs';
const config = JSON.parse(readFileSync(process.argv[2], 'utf8'));
const service = process.argv[3], environment = config.services[service].environment;
for (const key of ['POSTGRES_ADMIN_PASSWORD', 'DEV_ADMIN_PASSWORD', 'DEV_ROOT_PASSWORD', 'MYSQL_ROOT_PASSWORD']) {
  if (environment[key] !== '') throw new Error(service + ' inherits database administrator secret: ' + key);
}
for (const [key, value] of Object.entries({JWT_SECRET: 'ci-jwt-only', GOOGLE_CLIENT_SECRET: 'ci-google-only', MAIL_PASSWORD: 'ci-mail-only'})) {
  if (environment[key] !== value) throw new Error(service + ' lost required application configuration: ' + key);
}
if (service === 'app' && config.services.postgres.environment.POSTGRES_PASSWORD !== 'ci-test-only') {
  throw new Error('Database administrator configuration was incorrectly removed from PostgreSQL');
}
console.log(service + ': administrator secrets excluded; application auth/mail settings preserved.');
NODE
done

printf 'All Compose configurations are valid.\n'
