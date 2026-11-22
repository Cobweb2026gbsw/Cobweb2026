#!/usr/bin/env bash
# 기존 Cobweb 운영 환경의 DB 마이그레이션, 웹과 채점 워커를 같은 JAR로 배포합니다.
set -euo pipefail
cd "$(dirname "$0")/../.."
case "${1:-}" in
  '') dry_run=false ;;
  --dry-run) dry_run=true ;;
  *) echo "Usage: $0 [--dry-run]" >&2; exit 2 ;;
esac
[[ $# -le 1 ]] || exit 2

if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 21)"
  export JAVA_HOME
fi
docker info >/dev/null
npm ci --no-audit --no-fund
wrangler=./node_modules/.bin/wrangler
./gradlew --console=plain bootJar

tag=cobweb-languages:cloudflare-amd64
archive=build/cloudflare/judge/cobweb-languages-amd64.tar.gz
mkdir -p "$(dirname "$archive")"
archive_current() {
  local image_id newer
  [[ -s "$archive" && "${COBWEB_REBUILD_LANGUAGES:-0}" != 1 ]] || return 1
  newer="$(find judge \( -name __pycache__ -o -name .DS_Store \) -prune -o -newer "$archive" -print -quit)"
  [[ -z "$newer" ]] || return 1
  image_id="$(docker image inspect --format '{{if eq .Architecture "amd64"}}{{.Id}}{{end}}' "$tag" 2>/dev/null)" || return 1
  [[ -n "$image_id" ]] || return 1
  # containerd는 OCI 인덱스 digest, 구형 Docker는 config digest를 이미지 ID로 사용합니다.
  if tar -xzOf "$archive" index.json 2>/dev/null | node -e '
    const input = require("node:fs").readFileSync(0, "utf8");
    const manifests = input ? JSON.parse(input).manifests : [];
    process.exit(manifests.some(m => m.digest === process.argv[1] &&
      m.annotations?.["org.opencontainers.image.ref.name"] === process.argv[2].split(":")[1]) ? 0 : 1);
  ' "$image_id" "$tag"; then return 0; fi
  tar -xzOf "$archive" manifest.json | node -e '
  const rows = JSON.parse(require("node:fs").readFileSync(0, "utf8"));
  const digest = process.argv[1].replace(/^sha256:/, "");
  process.exit(rows.some(r => r.RepoTags?.includes(process.argv[2]) &&
    [digest + ".json", "blobs/sha256/" + digest].includes(r.Config)) ? 0 : 1);
' "$image_id" "$tag"
}
if ! archive_current; then
  docker build --platform linux/amd64 --tag "$tag" ./judge
  tmp="$(mktemp "${archive}.XXXXXX")"
  trap 'rm -f "$tmp"' EXIT
  docker image save "$tag" | gzip -1 > "$tmp"
  mv "$tmp" "$archive"
  trap - EXIT
fi

deploy_worker() {
  local worker="$1" secret_file="$2"
  local args=(deploy --config "deploy/cloudflare/wrangler.${worker}.jsonc")
  if "$dry_run"; then
    args+=(--dry-run --outdir "build/cloudflare/check-${worker}")
  else
    args+=(--tag "$release" --keep-vars)
    if [[ -n "$secret_file" ]]; then args+=(--secrets-file "$secret_file"); fi
  fi
  "$wrangler" "${args[@]}"
}

release="cw-$(node -p "require('crypto').randomBytes(8).toString('hex')")"
for path in "${COBWEB_WEB_SECRETS_FILE:-}" "${COBWEB_JUDGE_SECRETS_FILE:-}"; do
  if [[ -n "$path" && ! -r "$path" ]]; then echo "지정한 Worker 시크릿 파일을 읽을 수 없습니다." >&2; exit 1; fi
done
deploy_worker web "${COBWEB_WEB_SECRETS_FILE:-}"
if ! "$dry_run"; then
  # 첫 요청이 새 웹 컨테이너를 기동합니다. Flyway와 시드가 끝난 해당 버전의 응답만 인정합니다.
  echo "Neon 마이그레이션과 새 웹 버전의 준비 상태를 확인합니다."
  node --input-type=module - "$release" <<'NODE'
const release = process.argv[2];
const deadline = Date.now() + 300_000;
let ready = false;
while (!ready && Date.now() < deadline) {
  try {
    const response = await fetch("https://cobweb.kr/api/ready", {
      cache: "no-store", redirect: "error", signal: AbortSignal.timeout(65_000)
    });
    ready = response.ok && response.headers.get("x-cobweb-release") === release &&
      (await response.json()).data?.ready === true;
    if (!response.bodyUsed) await response.body?.cancel();
  } catch { /* 컨테이너 기동과 Worker 전파 중 일시 오류는 재시도합니다. */ }
  if (!ready) await new Promise(resolve => setTimeout(resolve, 3000));
}
if (!ready) { console.error("새 웹 버전의 준비 확인 시간이 지났습니다. 채점기 배포를 중단합니다."); process.exit(1); }
NODE
fi
deploy_worker judge "${COBWEB_JUDGE_SECRETS_FILE:-}"
