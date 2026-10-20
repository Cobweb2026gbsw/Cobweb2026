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

for file in compose.dev.yaml compose.prod.yaml compose.worker.yaml; do
  printf 'Checking %s\n' "$file"
  env -i HOME="${HOME:-/tmp}" PATH="$PATH" \
    DEV_DB_PASSWORD=ci-test-only DEV_ROOT_PASSWORD=ci-test-only \
    DB_PASSWORD=ci-test-only MYSQL_ROOT_PASSWORD=ci-test-only \
    SITE_DOMAIN=ci.example.invalid \
    WORKER_DB_URL='jdbc:mysql://db.example.invalid:3306/cobweb_test?sslMode=REQUIRED' \
    WORKER_DB_USERNAME=ci-worker WORKER_DB_PASSWORD=ci-test-only \
    docker compose --project-directory "$work_dir" --env-file "$work_dir/.env" \
      -f "$work_dir/$file" config --quiet
done

printf 'All Compose configurations are valid.\n'
