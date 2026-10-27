#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"

# Use an explicit dedicated test database, never credentials from .env.
# A fresh port/database avoids migrating old test fixtures with duplicate titles.
export SPRING_PROFILES_ACTIVE=test
export TEST_DB_URL="${TEST_DB_URL:-jdbc:mysql://127.0.0.1:13307/cobweb_test}"
export TEST_DB_USERNAME="${TEST_DB_USERNAME:-root}" TEST_DB_PASSWORD="${TEST_DB_PASSWORD:-cobweb-test-only}"
case "$TEST_DB_URL" in
  jdbc:mysql://127.0.0.1:*/cobweb_test|jdbc:mysql://127.0.0.1:*/cobweb_feature_test|jdbc:mysql://127.0.0.1:*/cobweb_revalidation_test) ;;
  *) printf 'Refusing non-dedicated test database URL.\n' >&2; exit 2 ;;
esac
export DB_URL="$TEST_DB_URL" DB_USERNAME="$TEST_DB_USERNAME" DB_PASSWORD="$TEST_DB_PASSWORD"
export JWT_SECRET=test-only-secret-0123456789-0123456789-0123456789
export MAIL_USERNAME=test@example.invalid MAIL_PASSWORD=unused
export GOOGLE_CLIENT_ID=unused GOOGLE_CLIENT_SECRET=unused
export NAVER_CLIENT_ID=unused NAVER_CLIENT_SECRET=unused
export GITHUB_CLIENT_ID=unused GITHUB_CLIENT_SECRET=unused

static_checks() {
  # 런처 복구 계약은 실제 Docker/서버를 건드리지 않는 격리 fixture로 확인합니다.
  bash -n run.sh src/test/bash/run-sh.test.sh src/test/bash/fixtures/run-tool.sh
  bash src/test/bash/run-sh.test.sh
  npm --prefix frontend ci --ignore-scripts --no-audit --no-fund
  npm --prefix frontend test
  node --check src/main/resources/static/app.js
  node --check src/main/resources/static/groups.js
  node --check src/main/resources/static/community.js
  node --check src/main/resources/static/code-editor.js
  node --check src/main/resources/static/markdown.js
  node src/test/js/app-auth.test.cjs
  node src/test/js/app-session.test.cjs
  node src/test/js/problem-editor.test.cjs
  node src/test/js/groups.test.cjs
  node src/test/js/community.test.cjs
  node src/test/js/code-editor.test.cjs
  node src/test/js/markdown.test.cjs
  PYTHONDONTWRITEBYTECODE=1 python3 -I -B -m unittest discover -s src/test/python -p 'test_umjunsik*.py'
  PYTHONDONTWRITEBYTECODE=1 python3 -I -B -m unittest discover -s src/test/python -p 'test_generic_function.py'
  PYTHONDONTWRITEBYTECODE=1 python3 -I -B -m unittest discover -s src/test/python -p 'test_level0_catalog.py'
  PYTHONDONTWRITEBYTECODE=1 python3 -I -B -m unittest discover -s src/test/python -p 'test_algorithm_catalog.py'
  PYTHONDONTWRITEBYTECODE=1 python3 -I -B -m unittest discover -s src/test/python -p 'test_judge_results_guard.py'
  bash scripts/check-compose-config.sh
}

quick_checks() {
  static_checks
  DOCKER_JUDGE_TEST=false GENERIC_CATALOG_DOCKER_TEST=false ./gradlew --no-daemon --console=plain test
}

full_checks() {
  docker build -t cobweb-languages:local judge
  DOCKER_JUDGE_TEST=true GENERIC_CATALOG_DOCKER_TEST=true ./gradlew --no-daemon --console=plain clean build
  GENERIC_NESTED_DOCKER_TEST=true GENERIC_RUNTIME_TIMEOUT_SECONDS=10 GENERIC_JUDGE_IMAGE=cobweb-languages:local python3 -I -B -m unittest discover -s src/test/python -p 'test_generic_nested_docker.py' -v
  python3 scripts/check-judge-results.py build/test-results/test
}

case "${1:-static}" in
  static) static_checks ;;
  quick) quick_checks ;;
  full) full_checks ;;
  all) quick_checks; full_checks ;;
  *) printf 'Usage: bash scripts/verify-ci-local.sh [static|quick|full|all]\n' >&2; exit 2 ;;
esac
