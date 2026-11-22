import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { build } from "esbuild";
import { JSDOM } from "jsdom";

const require = createRequire(import.meta.url);
const dom = new JSDOM("<!doctype html><html><body></body></html>", { url: "http://localhost:8080/" });
Object.defineProperty(globalThis, "navigator", { value: dom.window.navigator, configurable: true });
for (const name of ["window", "document", "HTMLElement", "Event", "MutationObserver"]) globalThis[name] = dom.window[name];
globalThis.IS_REACT_ACT_ENVIRONMENT = true;

const React = require("react");
const { render, cleanup, fireEvent, within, act } = require("@testing-library/react");
const { MemoryRouter } = require("react-router-dom");
const bundle = await build({
  entryPoints: ["src/pages/LearningReport.jsx"], bundle: true, write: false, platform: "node", format: "cjs",
  packages: "external", jsx: "automatic", loader: { ".css": "empty" },
  plugins: [{ name: "learning-report-fixture", setup(builder) {
    builder.onResolve({ filter: /\/context\/AuthContext(?:\.jsx)?$/ }, () => ({ path: "auth", namespace: "report-fixture" }));
    builder.onResolve({ filter: /\/lib\/api(?:\.js)?$/ }, () => ({ path: "api", namespace: "report-fixture" }));
    builder.onLoad({ filter: /.*/, namespace: "report-fixture" }, ({ path }) => ({
      contents: path === "auth" ? "export const useAuth = () => globalThis.reportAuth;"
        : "export const isStale = error => Boolean(error?.stale); export const api = (...args) => globalThis.reportApi(...args);",
    }));
  } }],
});
const module = { exports: {} };
new Function("require", "module", "exports", bundle.outputFiles[0].text)(require, module, module.exports);
const LearningReport = module.exports.default;

const API = "/api/learning-reports";
const empty = { activity: [], availableBatches: 0, remainingToNextReport: 5, generationState: "idle", reports: [] };
const report = (reportNumber, userSummary) => ({
  schemaVersion: 1, reportNumber, userSummary, createdAt: "2026-11-15T18:00:00Z",
  problemIds: [101, 102, 103, 104, 105],
  problemSolvingScore: 63,
  radar: { firstPassAccuracy: 60, errorRecovery: 50, limitRecovery: null, difficultyChallenge: 80, topicVariety: 60, practiceConsistency: 70 },
});
const page = (dark = true) => React.createElement(MemoryRouter, null, React.createElement(LearningReport, { dark }));
test.beforeEach(() => { globalThis.reportAuth = { user: { username: "tester" }, epoch: 1 }; });
test.afterEach(() => cleanup());
test.after(() => dom.window.close());

test("show 365 selectable KST days and leave generation idle at four solves", async t => {
  // At this instant the UTC date is Nov 15 and the KST date is already Nov 16.
  t.mock.timers.enable({ apis: ["Date"], now: new Date("2026-11-15T15:00:00.000Z") });
  const calls = [];
  globalThis.reportApi = async (path, method) => {
    calls.push([path, method]);
    return { ...empty, remainingToNextReport: 1, activity: [{ date: "2026-11-16", solvedCount: 4 }] };
  };
  const view = render(page(false));
  const calendar = await view.findByRole("group", { name: "날짜별로 처음 맞힌 문제" });
  const dayButtons = within(calendar).getAllByRole("button");
  assert.equal(dayButtons.length, 365);
  assert.equal(dayButtons.at(-1).getAttribute("aria-label"), "2026년 11월 16일 · 4문제 해결");
  assert.equal(dayButtons.at(-1).classList.contains("lr-level-3"), true);
  assert.equal(dayButtons.filter(button => button.tabIndex === 0).length, 1);
  fireEvent.keyDown(dayButtons.at(-1), { key: "ArrowUp" });
  assert.equal(document.activeElement, dayButtons.at(-2));
  assert.equal(dayButtons.at(-2).getAttribute("aria-pressed"), "true");
  assert.equal(view.container.querySelector(".lr-selected-date").textContent, "2026년 11월 15일 · 0문제 해결");
  assert.ok(view.getByRole("progressbar", { name: "다음 회차까지 푼 문제" }));
  assert.ok(view.container.querySelector("main.light"));
  assert.deepEqual(calls, [[API, "GET"]]);
});

test("generate eligible batches serially and keep their saved summaries selectable", async () => {
  const calls = [];
  let requested = 0;
  let reads = 0;
  globalThis.reportApi = async (path, method, body) => {
    calls.push([path, method, body]);
    if (method === "POST") {
      requested++;
      return { generationState: "running" };
    }
    reads++;
    const reports = [report(1, "첫 번째 풀이 요약"), report(2, "두 번째 풀이 요약")].slice(0, requested).reverse();
    return { ...empty, availableBatches: 2 - requested, remainingToNextReport: 3, reports };
  };
  const view = render(page());
  await view.findByText("새 리포트를 만들고 있어요.");
  await view.findByText("두 번째 풀이 요약", {}, { timeout: 6500 });
  assert.equal(reads, 3);
  assert.deepEqual(calls, [
    [API, "GET", null], [API + "/generate", "POST", null], [API, "GET", null],
    [API + "/generate", "POST", null], [API, "GET", null],
  ]);
  assert.ok(view.getByRole("heading", { name: "2회차 리포트" }));
  const radarAxes = within(view.getByRole("region", { name: "2회차 리포트" })).getAllByRole("button");
  assert.equal(radarAxes.length, 6);
  const missing = view.getByRole("button", { name: "제한 대응 측정 기록 없음" });
  fireEvent.click(missing);
  assert.equal(missing.getAttribute("aria-pressed"), "true");
  assert.ok(view.getByText("시간 또는 메모리 제한을 만난 이후의 풀이"));
  assert.equal(view.container.querySelector(".lr-radar-fill"), null);
  assert.equal(view.container.querySelectorAll(".lr-radar-dot").length, 5);
  fireEvent.change(view.getByRole("combobox", { name: "리포트 회차" }), { target: { value: "1" } });
  assert.ok(view.getByText("첫 번째 풀이 요약"));
});

test("preserve saved history when generation is delayed", async () => {
  const calls = [];
  globalThis.reportApi = async (path, method) => {
    calls.push([path, method]);
    return { ...empty, generationState: "retry_later", availableBatches: 1, reports: [report(5, "보관된 풀이 요약")] };
  };
  const view = render(page());
  await view.findByText("보관된 풀이 요약");
  assert.equal(view.getByRole("status").textContent, "리포트 생성이 지연됐어요. 잠시 후 다시 들어와 확인해 주세요.");
  assert.deepEqual(calls, [[API, "GET"]]);
});

test("show observed counts and practice links for the latest report while older reports remain readable", async () => {
  const older = report(3, "이전 요약");
  const recent = {...report(4, "최근 요약"), schemaVersion: 2,
    radar: {...older.radar, firstPassAccuracy: 80},
    evidence: {firstPassCount: 4, observedSubmissionCount: 5, mostFrequentError: "TIME_LIMIT_EXCEEDED", errorCount: 2}};
  globalThis.reportApi = async () => ({...empty, reports: [recent, older], recommendations: [
    {problemNumber: 1105, title: "최소공배수", rankType: "SKILLED", rankInt: 5, sharedTopic: "수학"},
  ]});
  const view = render(page());
  await view.findByText("최근 요약");
  const facts = view.container.querySelector(".lr-evidence").textContent;
  assert.match(facts, /5문제 중 4문제/);
  assert.match(facts, /60% → 80% · 직전 회차보다 20%p 높아요/);
  assert.match(facts, /시간 초과 2회/);
  assert.equal(view.getByRole("link", {name: /1105 최소공배수.*수학/}).getAttribute("href"), "/problems/1105");
  fireEvent.change(view.getByRole("combobox", {name: "리포트 회차"}), {target: {value: "3"}});
  assert.ok(view.getByText("이전 요약"));
  assert.equal(view.container.querySelector(".lr-evidence"), null);
  assert.equal(view.queryByRole("heading", {name: "다음 연습 문제"}), null);
});

test("poll beyond the reservation lease and show a recheck message at the time limit", async t => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const calls = [];
  globalThis.reportApi = async (path, method) => {
    calls.push([path, method]);
    return { ...empty, generationState: "running", availableBatches: 1 };
  };
  const view = render(page());
  await act(async () => {});
  for (let check = 0; check < 61; check++) await act(async () => t.mock.timers.tick(2000));
  assert.equal(view.getByRole("status").textContent, "새 리포트를 만들고 있어요.");
  for (let check = 61; check < 70; check++) await act(async () => t.mock.timers.tick(2000));
  assert.equal(view.getByRole("status").textContent, "리포트 상태를 확인하는 데 시간이 걸리고 있어요. 잠시 후 페이지를 다시 열어 확인해 주세요.");
  assert.equal(calls.length, 71);
  assert.equal(calls.every(([path, method]) => path === API && method === "GET"), true);
});

test("account transitions abort the old view and never request its generation", async () => {
  let replyToFormerAccount;
  let formerSignal;
  const calls = [];
  globalThis.reportApi = (path, method, body, { signal }) => {
    calls.push([globalThis.reportAuth.epoch, path, method]);
    if (globalThis.reportAuth.epoch === 1) {
      formerSignal = signal;
      return new Promise(resolve => { replyToFormerAccount = resolve; });
    }
    return Promise.resolve({ ...empty, reports: [report(1, "새 계정 기록")] });
  };
  const view = render(page());
  globalThis.reportAuth = { user: { username: "new-account" }, epoch: 2 };
  view.rerender(page());
  assert.equal(formerSignal.aborted, true);
  await view.findByText("새 계정 기록");
  await act(async () => replyToFormerAccount({ ...empty, availableBatches: 1, reports: [report(3, "이전 계정 기록")] }));
  assert.equal(view.queryByText("이전 계정 기록"), null);
  assert.deepEqual(calls, [[1, API, "GET"], [2, API, "GET"]]);
});
