import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {readFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import {build} from 'esbuild';
import {JSDOM} from 'jsdom';

const require = createRequire(import.meta.url), root = fileURLToPath(new URL('../', import.meta.url));
const dom = new JSDOM('<!doctype html><html><head></head><body></body></html>', {url: 'http://localhost:8080/'});
Object.defineProperty(globalThis, 'navigator', {value: dom.window.navigator, configurable: true});
for (const key of ['window', 'document', 'HTMLElement', 'Event', 'MutationObserver']) globalThis[key] = dom.window[key];
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
const React = require('react'), {render, waitFor, cleanup, act, within} = require('@testing-library/react');
const {MemoryRouter} = require('react-router-dom');
const bundle = await build({entryPoints: [root + 'src/pages/Dashboard.jsx'], bundle: true, write: false, platform: 'node', format: 'cjs', packages: 'external', jsx: 'automatic', loader: {'.css': 'empty'}, plugins: [{name: 'dashboard-group-contracts', setup(builder) {
  builder.onResolve({filter: /\/context\/AuthContext(?:\.jsx)?$/}, () => ({path: 'auth', namespace: 'fixture'}));
  builder.onResolve({filter: /\/lib\/api(?:\.js)?$/}, () => ({path: 'api', namespace: 'fixture'}));
  builder.onLoad({filter: /.*/, namespace: 'fixture'}, args => ({contents: args.path === 'auth' ? 'export const useAuth=()=>globalThis.__dashboardAuth;' : 'export const api=(...args)=>globalThis.__dashboardApi(...args);export const isStale=error=>Boolean(error.stale);'}));
}}]});
const module = {exports: {}};
new Function('require', 'module', 'exports', bundle.outputFiles[0].text)(require, module, module.exports);
const Dashboard = module.exports.default;
const system = {id: 1, groupName: 'COBWEB GROUP', roleName: '회원', systemGroup: true};
const ordinary = id => ({id, groupName: '일반 그룹 ' + id, roleName: '회원', systemGroup: false});
const profile = {solved_count: 0, submit_count: 0, solvedProblems: []};
const tree = () => React.createElement(MemoryRouter, null, React.createElement(Dashboard, {dark: true}));
const tick = () => new Promise(resolve => setTimeout(resolve, 0));
const quota = view => view.getByText('가입 그룹', {selector: 'p'}).parentElement.querySelector('strong');
function api(groups) {globalThis.__dashboardApi = async path => path === '/api/me' ? profile : path === '/api/groups/mine' ? groups : [];}
test.beforeEach(() => {globalThis.__dashboardAuth = {user: {username: 'alice'}, epoch: 0};});
test.afterEach(() => cleanup());
test.after(() => dom.window.close());

test('dashboard shows one default membership as ordinary zero of three plus one built-in group', {timeout: 5000}, async () => {
  api([system]); const view = render(tree()); await view.findByText('가입 그룹', {selector: 'p'});
  assert.equal(quota(view).getAttribute('aria-label'), '일반 0 / 3개 · 기본 1개');
  assert.equal(quota(view).childNodes[1].textContent, '0');
  assert.equal(view.getAllByText('기본 그룹 · 가입 제한 제외').length, 1);
  assert.equal(view.getByRole('link', {name: /COBWEB GROUP/}).getAttribute('href'), '/groups/1');
});

test('three ordinary memberships plus the default display three of three, not four of three', {timeout: 5000}, async () => {
  api([system, ordinary(2), ordinary(3), ordinary(4)]); const view = render(tree()); await view.findByText('가입 그룹', {selector: 'p'});
  assert.equal(quota(view).getAttribute('aria-label'), '일반 3 / 3개 · 기본 1개');
  assert.equal(quota(view).childNodes[1].textContent, '3');
  for (const id of [2, 3, 4]) assert.equal(within(view.getByRole('link', {name: `일반 그룹 ${id} 회원`})).queryByText('기본 그룹 · 가입 제한 제외') === null, true);
});

test('missing, false, string, and numeric flags remain ordinary even when the name matches the default', {timeout: 5000}, async () => {
  for (const flag of [undefined, false, 'true', 1]) {
    api([{...system, systemGroup: flag}]); const view = render(tree()); await view.findByText('가입 그룹', {selector: 'p'});
    assert.equal(quota(view).getAttribute('aria-label'), '일반 1 / 3개 · 기본 0개');
    assert.equal(view.queryByText('기본 그룹 · 가입 제한 제외') === null, true); cleanup();
  }
});

test('no memberships show a zero quota without inventing a built-in membership', {timeout: 5000}, async () => {
  api([]); const view = render(tree()); await view.findByText('가입 그룹', {selector: 'p'});
  assert.equal(quota(view).getAttribute('aria-label'), '일반 0 / 3개 · 기본 0개');
  assert.equal(view.getByRole('link', {name: '그룹 찾아보기'}).getAttribute('href'), '/groups');
});

test('dashboard uses actual strict-flag membership counts rather than assuming one built-in group', {timeout: 5000}, async () => {
  api([system, {...system, id: 2, groupName: '두 번째 기본 그룹'}, ordinary(3)]); const view = render(tree()); await view.findByText('가입 그룹', {selector: 'p'});
  assert.equal(quota(view).getAttribute('aria-label'), '일반 1 / 3개 · 기본 2개');
  assert.equal(view.getAllByText('기본 그룹 · 가입 제한 제외').length, 2);
});

test('an account epoch change clears the old quota and ignores the previous account response', {timeout: 5000}, async () => {
  let releaseOld;
  globalThis.__dashboardApi = async path => path === '/api/groups/mine' ? new Promise(resolve => {releaseOld = resolve;}) : path === '/api/me' ? profile : [];
  const view = render(tree()); await waitFor(() => assert.equal(typeof releaseOld, 'function'));
  globalThis.__dashboardAuth = {user: {username: 'bob'}, epoch: 1}; api([system]); view.rerender(tree()); await view.findByText('가입 그룹', {selector: 'p'});
  assert.equal(quota(view).getAttribute('aria-label'), '일반 0 / 3개 · 기본 1개');
  await act(async () => {releaseOld([system, ordinary(2), ordinary(3), ordinary(4)]); await tick();});
  assert.equal(quota(view).getAttribute('aria-label'), '일반 0 / 3개 · 기본 1개');
  assert.equal(view.queryByRole('link', {name: /일반 그룹 2/}) === null, true);
});

test('quota values and long built-in labels wrap instead of hiding in narrow dashboard cards', {timeout: 5000}, async () => {
  const style = document.createElement('style'); style.textContent = readFileSync(new URL('../src/pages/Dashboard.css', import.meta.url), 'utf8'); document.head.append(style);
  try {
    api([{...system, groupName: '긴 그룹 이름 '.repeat(20)}]); const view = render(tree()); const badge = await view.findByText('기본 그룹 · 가입 제한 제외');
    assert.equal(window.getComputedStyle(quota(view)).flexWrap, 'wrap');
    assert.equal(window.getComputedStyle(badge).display, 'block');
    assert.equal(window.getComputedStyle(badge.parentElement).whiteSpace, 'normal');
    assert.equal(window.getComputedStyle(badge.closest('a')).gridTemplateColumns, 'minmax(0, 1fr)');
  } finally {style.remove();}
});
