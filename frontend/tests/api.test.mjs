import test from "node:test";
import assert from "node:assert/strict";
import { api, beginSession, getAccessToken, getSessionEpoch, getSessionNotice, logoutSession, restoreSession, waitForLogout } from "../src/lib/api.js";
import { jwtFixture } from "./jwt-fixture.mjs";

const jwt = (marker, subject = "1") => jwtFixture(subject, marker);

const response = (status, data = null, message = null) => ({ ok: status >= 200 && status < 300, status,
  json: async () => ({ success: status >= 200 && status < 300, data, message }) });
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
const tick = () => new Promise(resolve => setImmediate(resolve));

test("API unwraps envelopes, includes credentials and never accepts remote paths", async () => {
  const original = globalThis.fetch;
  beginSession(jwt("test-token"));
  let sent;
  globalThis.fetch = async (path, options) => { sent = { path, options }; return response(200, { id: 9 }); };
  try {
    assert.deepEqual(await api("/api/problems", "POST", { title: "제목" }), { id: 9 });
    assert.equal(sent.options.credentials, "same-origin");
    assert.equal(sent.options.headers.Authorization, `Bearer ${jwt("test-token")}`);
    assert.deepEqual(JSON.parse(sent.options.body), { title: "제목" });
    for (const path of ["https://outside.invalid/api/me", "//outside.invalid/api/me", "/pw.txt"])
      await assert.rejects(api(path), /허용되지/);
  } finally { globalThis.fetch = original; beginSession(null); }
});

test("anonymous or 403 requests never trigger refresh", async () => {
  const original = globalThis.fetch; const calls = [];
  globalThis.fetch = async path => { calls.push(path); return response(401, null, "로그인 필요"); };
  try {
    beginSession(null);
    await assert.rejects(api("/api/groups/1"), error => error.status === 401);
    assert.deepEqual(calls, ["/api/groups/1"]);
    beginSession(jwt("token")); globalThis.fetch = async path => { calls.push(path); return response(403); };
    await assert.rejects(api("/api/groups/2"), error => error.status === 403);
    assert.equal(calls.length, 2);
  } finally { globalThis.fetch = original; beginSession(null); }
});

test("simultaneous expired-token requests use one refresh and replay with fresh token", async () => {
  const original = globalThis.fetch, refresh = deferred(), calls = [];
  beginSession(jwt("old-token"));
  globalThis.fetch = async (path, options) => {
    calls.push(path);
    if (path === "/api/auth/reissue") return refresh.promise;
    return options.headers.Authorization === `Bearer ${jwt("old-token")}` ? response(401) : response(200, path);
  };
  try {
    const one = api("/api/me"), two = api("/api/problems/permissions");
    await tick(); assert.equal(calls.filter(path => path === "/api/auth/reissue").length, 1);
    refresh.resolve(response(200, { accessToken: jwt("fresh-token") }));
    assert.deepEqual(await Promise.all([one, two]), ["/api/me", "/api/problems/permissions"]);
    assert.equal(getAccessToken(), jwt("fresh-token"));
  } finally { globalThis.fetch = original; beginSession(null); }
});

test("old successful responses cannot enter a new account", async () => {
  const original = globalThis.fetch, waiting = deferred();
  beginSession(jwt("account-a")); globalThis.fetch = () => waiting.promise;
  try {
    const result = api("/api/me"); beginSession(jwt("account-b", "2"));
    waiting.resolve(response(200, { username: "account-a" }));
    await assert.rejects(result, error => error.stale === true);
    assert.equal(getAccessToken(), jwt("account-b", "2"));
  } finally { globalThis.fetch = original; beginSession(null); }
});

test("a late refresh cannot replace the new account token", async () => {
  const original = globalThis.fetch, waiting = deferred();
  beginSession(null); globalThis.fetch = () => waiting.promise;
  try {
    const result = restoreSession(); beginSession(jwt("new-account", "2"));
    waiting.resolve(response(200, { accessToken: jwt("old-account") }));
    await assert.rejects(result, error => error.stale === true);
    assert.equal(getAccessToken(), jwt("new-account", "2"));
  } finally { globalThis.fetch = original; beginSession(null); }
});

test("refresh failure invalidates only its own current session", async () => {
  const original = globalThis.fetch;
  beginSession(jwt("expired-token")); const before = getSessionEpoch();
  globalThis.fetch = async () => response(401);
  try {
    await assert.rejects(api("/api/me"), error => error.status === 401);
    assert.equal(getAccessToken(), null); assert.equal(getSessionEpoch(), before + 1);
  } finally { globalThis.fetch = original; beginSession(null); }
});

test("logout clears memory immediately and sends only the captured previous token", async () => {
  const original = globalThis.fetch, waiting = deferred(); let headers;
  beginSession(jwt("previous-token"));
  globalThis.fetch = (_path, options) => { headers = options.headers; return waiting.promise; };
  try {
    const result = logoutSession();
    assert.equal(getAccessToken(), null);
    assert.equal(headers.Authorization, `Bearer ${jwt("previous-token")}`);
    beginSession(jwt("new-token", "2")); waiting.resolve(response(200));
    await assert.rejects(result, error => error.stale === true);
    assert.equal(getAccessToken(), jwt("new-token", "2"));
  } finally { globalThis.fetch = original; beginSession(null); }
});

test("next login waits until the previous logout cookie response settles", async () => {
  const original = globalThis.fetch, waiting = deferred();
  beginSession(jwt("previous-token")); globalThis.fetch = () => waiting.promise;
  try {
    const logout = logoutSession(); let finished = false;
    const queue = waitForLogout().then(() => { finished = true; });
    await tick(); assert.equal(finished, false);
    waiting.resolve(response(200)); await logout; await queue;
    assert.equal(finished, true);
  } finally { globalThis.fetch = original; beginSession(null); }
});

test("expired-token logout refreshes privately then revokes the cookie", async () => {
  const original = globalThis.fetch, calls = [];
  beginSession(jwt("expired-token"));
  globalThis.fetch = async (path, options) => {
    calls.push([path, options.headers.Authorization]);
    if (path === "/api/auth/reissue") return response(200, { accessToken: jwt("logout-only-token") });
    return response(options.headers.Authorization === `Bearer ${jwt("expired-token")}` ? 401 : 200);
  };
  try {
    await logoutSession();
    assert.deepEqual(calls, [["/api/auth/logout", `Bearer ${jwt("expired-token")}`], ["/api/auth/reissue", undefined], ["/api/auth/logout", `Bearer ${jwt("logout-only-token")}`]]);
    assert.equal(getAccessToken(), null);
  } finally { globalThis.fetch = original; beginSession(null); }
});

test("cookie restore cannot start while a logout response is outstanding", async () => {
  const original = globalThis.fetch, waiting = deferred(), calls = [];
  beginSession(jwt("previous-token"));
  globalThis.fetch = async path => { calls.push(path); return path === "/api/auth/logout" ? waiting.promise : response(401); };
  try {
    const logout = logoutSession(), restore = restoreSession();
    const rejected = assert.rejects(restore, error => error.status === 401);
    await tick(); assert.deepEqual(calls, ["/api/auth/logout"]);
    waiting.resolve(response(200)); await logout; await rejected;
    assert.deepEqual(calls, ["/api/auth/logout", "/api/auth/reissue"]);
  } finally { globalThis.fetch = original; beginSession(null); }
});

test("duplicate logout actions share one revocation request", async () => {
  const original = globalThis.fetch, waiting = deferred(); let calls = 0;
  beginSession(jwt("token")); globalThis.fetch = () => { calls++; return waiting.promise; };
  try {
    const first = logoutSession(), second = logoutSession();
    assert.equal(calls, 1); waiting.resolve(response(200)); await Promise.all([first, second]);
    assert.equal(getAccessToken(), null);
  } finally { globalThis.fetch = original; beginSession(null); }
});

test("login cookie response waits for a stale refresh response before writing the new cookie", async () => {
  const original = globalThis.fetch, waiting = deferred(), calls = [];
  let cookie = "account-a-old";
  beginSession(null);
  globalThis.fetch = async path => {
    calls.push(path);
    if (path === "/api/auth/reissue") { await waiting.promise; cookie = "account-a-rotated"; return response(200, { accessToken: jwt("token-a") }); }
    cookie = "account-b-new"; return response(200, { accessToken: jwt("token-b", "2") });
  };
  try {
    const restore = restoreSession();
    const staleRestore = assert.rejects(restore, error => error.stale === true);
    beginSession(null);
    const login = api("/api/auth/login", "POST", { username: "account-b", password: "unused" });
    await tick(); assert.deepEqual(calls, ["/api/auth/reissue"]);
    waiting.resolve(); await staleRestore;
    assert.deepEqual(await login, { accessToken: jwt("token-b", "2") });
    assert.equal(cookie, "account-b-new");
    assert.deepEqual(calls, ["/api/auth/reissue", "/api/auth/login"]);
  } finally { globalThis.fetch = original; beginSession(null); }
});

test("cross-tab refresh subject change clears the session without replaying an old account mutation", async () => {
  const original = globalThis.fetch, calls = [];
  beginSession(jwt("account-a")); const before = getSessionEpoch();
  globalThis.fetch = async (path, options) => {
    calls.push([path, options.headers.Authorization]);
    return path === "/api/auth/reissue" ? response(200, { accessToken: jwt("account-b", "2") }) : response(401);
  };
  try {
    await assert.rejects(api("/api/me", "PATCH", { bio: "A 계정의 초안" }), error => error.sessionChanged === true && error.status === 401 && /다시 로그인/.test(error.message));
    assert.deepEqual(calls, [["/api/me", `Bearer ${jwt("account-a")}`], ["/api/auth/reissue", undefined]]);
    assert.equal(getAccessToken(), null); assert.equal(getSessionEpoch(), before + 1);
    assert.match(getSessionNotice(), /다시 로그인/);
  } finally { globalThis.fetch = original; beginSession(null); }
});

test("unreadable refresh JWT fails closed rather than bypassing account comparison", async () => {
  const original = globalThis.fetch;
  try {
    for (const invalid of ["not-a-jwt", "e30.e30.c2ln", "e30.@@@.c2ln"]) {
      beginSession(jwt("account-a"));
      globalThis.fetch = async path => path === "/api/auth/reissue" ? response(200, { accessToken: invalid }) : response(401);
      await assert.rejects(api("/api/me"), error => error.sessionChanged === true);
      assert.equal(getAccessToken(), null);
    }
  } finally { globalThis.fetch = original; beginSession(null); }
});

test("expired account A logout never revokes a different account B refresh session", async () => {
  const original = globalThis.fetch, calls = [];
  beginSession(jwt("expired-account-a"));
  globalThis.fetch = async (path, options) => {
    calls.push([path, options.headers.Authorization]);
    return path === "/api/auth/reissue" ? response(200, { accessToken: jwt("account-b", "2") }) : response(401);
  };
  try {
    await assert.rejects(logoutSession(), error => error.sessionChanged === true);
    assert.deepEqual(calls, [["/api/auth/logout", `Bearer ${jwt("expired-account-a")}`], ["/api/auth/reissue", undefined]]);
    assert.equal(getAccessToken(), null);
    assert.match(getSessionNotice(), /다시 로그인/);
  } finally { globalThis.fetch = original; beginSession(null); }
});
