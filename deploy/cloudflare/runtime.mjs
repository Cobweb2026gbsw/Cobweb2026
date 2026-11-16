const PROVIDER_KEYS = [
  "GOOGLE_CLIENT_ID", "GOOGLE_CLIENT_SECRET", "NAVER_CLIENT_ID", "NAVER_CLIENT_SECRET",
  "GITHUB_CLIENT_ID", "GITHUB_CLIENT_SECRET", "MAIL_USERNAME", "MAIL_PASSWORD",
];
const OPTIONAL_WEB_VALUES = ["OPENAI_ENABLED", "OPENAI_API_KEY", "OPENAI_MODEL", "OPENAI_ALLOWED_MODELS"];

function requiredValues(env, names) {
  const values = {};
  for (const name of names) {
    if (typeof env[name] !== "string" || !env[name].trim()) {
      throw new Error(`Missing Cobweb configuration: ${name}`);
    }
    values[name] = env[name];
  }
  return values;
}

export function webEnvironment(env) {
  const app = requiredValues(env, ["DATABASE_URL", "DATABASE_URL_UNPOOLED", "JWT_SECRET", "PUBLIC_BASE_URL"]);
  for (const name of PROVIDER_KEYS) app[name] = env[name] ?? "";
  for (const name of OPTIONAL_WEB_VALUES) {
    if (typeof env[name] === "string") app[name] = env[name];
  }
  app.JUDGE_ENABLED = "false";
  return app;
}

export function judgeEnvironment(env) {
  return requiredValues(env, ["DATABASE_URL", "JWT_SECRET"]);
}

export function appRequest(request, publicOrigin) {
  const target = new URL(request.url);
  const origin = new URL(publicOrigin);
  const protocol = target.protocol === "https:" ? "https" : "http";
  target.protocol = "http:";
  target.hostname = origin.hostname;
  target.port = origin.port;
  const upstream = new Request(new Request(target, request), { redirect: "manual" });
  // Spring validates the OAuth host against the canonical URL.
  upstream.headers.set("host", target.host);
  for (const name of ["forwarded", "x-forwarded-host", "x-forwarded-port", "x-forwarded-proto", "x-forwarded-for"]) {
    upstream.headers.delete(name);
  }
  // The public Worker is the only route into the web container. Use the edge
  // address for login throttling and overwrite the private forwarding headers.
  upstream.headers.delete("x-cobweb-client-ip");
  const clientIp = request.headers.get("cf-connecting-ip");
  if (clientIp && /^[a-f0-9.:]{3,45}$/i.test(clientIp) && /[.:]/.test(clientIp)) {
    upstream.headers.set("x-cobweb-client-ip", clientIp);
  }
  upstream.headers.set("x-cobweb-scheme", protocol);
  return upstream;
}

export async function probeReadiness(port) {
  try {
    const response = await port.fetch("http://container/api/ready", { signal: AbortSignal.timeout(1500) });
    await response.body?.cancel();
    return { ready: response.ok, status: response.status };
  } catch {
    return { ready: false, status: null };
  }
}

export async function isReady(port) {
  return (await probeReadiness(port)).ready;
}
