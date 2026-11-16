import assert from "node:assert/strict";
import { test } from "node:test";
import { appRequest, isReady, judgeEnvironment, probeReadiness, webEnvironment } from "./runtime.mjs";

test("forwards OAuth, API and static paths through the trusted authority", async () => {
  const payload = '{"sourceCode":"공백 + 1"}';
  const request = new Request("https://localhost:8787/api/auth/oauth/%67oogle/start?from=%2Fproblems%2F1000", {
    method: "POST",
    body: payload,
    headers: {
      Cookie: "oauthState=sample", "X-Forwarded-Host": "invalid.example", Forwarded: "host=invalid.example",
      "CF-Connecting-IP": "2001:db8::1", "X-Cobweb-Client-IP": "198.51.100.1", "X-Cobweb-Scheme": "http",
    },
  });

  const upstream = appRequest(request, "https://cobweb.kr");

  assert.equal(upstream.url, "http://cobweb.kr/api/auth/oauth/%67oogle/start?from=%2Fproblems%2F1000");
  assert.equal(upstream.headers.get("host"), "cobweb.kr");
  assert.equal(upstream.headers.get("cookie"), "oauthState=sample");
  assert.equal(upstream.headers.get("x-forwarded-host"), null);
  assert.equal(upstream.headers.get("forwarded"), null);
  assert.equal(upstream.headers.get("x-cobweb-client-ip"), "2001:db8::1");
  assert.equal(upstream.headers.get("x-cobweb-scheme"), "https");
  assert.equal(upstream.redirect, "manual");
  assert.equal(upstream.method, "POST");
  assert.equal(await upstream.text(), payload);
  const withoutEdgeAddress = new Request("http://localhost/", { headers: { "X-Cobweb-Client-IP": "198.51.100.1" } });
  assert.equal(appRequest(withoutEdgeAddress, "https://cobweb.kr").headers.get("x-cobweb-client-ip"), null);
});

test("only sends role-specific text values into its container", () => {
  const env = {
    DATABASE_URL: "pooled-fixture", DATABASE_URL_UNPOOLED: "direct-fixture",
    JWT_SECRET: "jwt-fixture", PUBLIC_BASE_URL: "https://cobweb.kr",
    GOOGLE_CLIENT_SECRET: "google-fixture", OPENAI_API_KEY: "openai-fixture", JUDGE_ENABLED: "true",
    WEB_SECRET_UNUSED: "never-pass-this", DEPLOYMENT: { id: "test" },
  };

  const web = webEnvironment(env);
  assert.equal(web.JUDGE_ENABLED, "false");
  assert.equal(web.GOOGLE_CLIENT_SECRET, "google-fixture");
  assert.equal(web.MAIL_USERNAME, "");
  assert.equal(web.OPENAI_API_KEY, "openai-fixture");
  assert.equal(web.WEB_SECRET_UNUSED, undefined);
  assert.equal(web.DEPLOYMENT, undefined);

  assert.deepEqual(judgeEnvironment(env), { DATABASE_URL: "pooled-fixture", JWT_SECRET: "jwt-fixture" });
  assert.throws(() => webEnvironment({ ...env, DATABASE_URL: " " }), /DATABASE_URL$/);
});

test("checks the readiness endpoint and treats unreachable ports as unready", async () => {
  const port = { async fetch(url) {
    assert.equal(url, "http://container/api/ready");
    return new Response("ready", { status: 200 });
  } };

  assert.equal(await isReady(port), true);
  const unreachable = { async fetch() { throw new Error("still booting"); } };
  const starting = { async fetch() { return new Response(null, { status: 503 }); } };
  assert.equal(await isReady(unreachable), false);
  assert.equal(await isReady(starting), false);
  assert.deepEqual(await probeReadiness(unreachable), { ready: false, status: null });
  assert.deepEqual(await probeReadiness(starting), { ready: false, status: 503 });
});
