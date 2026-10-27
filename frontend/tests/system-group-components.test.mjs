import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {readFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import {build} from 'esbuild';
import {JSDOM} from 'jsdom';

const require = createRequire(import.meta.url), root = fileURLToPath(new URL('../', import.meta.url));
const dom = new JSDOM('<!doctype html><html><body></body></html>', {url: 'http://localhost:8080/'});
Object.defineProperty(globalThis, 'navigator', {value: dom.window.navigator, configurable: true});
for (const key of ['window', 'document', 'HTMLElement', 'Event', 'MutationObserver', 'FormData']) globalThis[key] = dom.window[key];
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
const React = require('react'), {render, waitFor, cleanup, act, within} = require('@testing-library/react');
const {MemoryRouter, Routes, Route} = require('react-router-dom');
const bundle = await build({stdin: {contents: "export {default as Groups} from './src/pages/Groups.jsx';export {default as Detail} from './src/pages/GroupDetail.jsx';", resolveDir: root, sourcefile: 'system-group-test.jsx'}, bundle: true, write: false, platform: 'node', format: 'cjs', packages: 'external', jsx: 'automatic', loader: {'.css': 'empty'}, plugins: [{name: 'system-group-contracts', setup(builder) {
  builder.onResolve({filter: /(?:^|\/)api(?:\.js)?$/}, () => ({path: 'api', namespace: 'contract'}));
  builder.onResolve({filter: /\/context\/AuthContext(?:\.jsx)?$/}, () => ({path: 'auth', namespace: 'contract'}));
  builder.onLoad({filter: /.*/, namespace: 'contract'}, args => ({contents: args.path === 'api' ? 'export const api=(...args)=>globalThis.__systemGroupApi(...args);export const getSessionEpoch=()=>globalThis.__systemGroupAuth.epoch;' : 'export const useAuth=()=>globalThis.__systemGroupAuth;'}));
}}]});
const module = {exports: {}};
new Function('require', 'module', 'exports', bundle.outputFiles[0].text)(require, module, module.exports);
const {Groups, Detail} = module.exports;
const allFlags = ['VIEW_PROBLEMS', 'CREATE_PROBLEMS', 'EDIT_PROBLEMS', 'REVIEW_PROBLEMS', 'MANAGE_MEMBERS', 'MANAGE_ROLES', 'MANAGE_GROUP', 'CREATE_CONTESTS', 'MANAGE_CONTESTS', 'VIEW_CONTEST_RESULTS', 'TAKE_CONTESTS'];
const system = {id: 7, groupName: 'COBWEB GROUP', memberCount: 100, systemGroup: true, roleName: '회원'};
const ordinary = id => ({id, groupName: `일반 그룹 ${id}`, memberCount: 2, systemGroup: false, roleName: '회원'});
const tick = () => new Promise(resolve => setTimeout(resolve, 0));
const absent = node => assert.equal(node === null, true, '허용되지 않은 제어가 화면에 없어야 합니다.');
function tree(Page = Groups, url = '/groups', pattern = '/groups') {
  return React.createElement(MemoryRouter, {initialEntries: [url]}, React.createElement(Routes, null, React.createElement(Route, {path: pattern, element: React.createElement(Page, {dark: true})})));
}
function browserApi(mine, items = [system, ordinary(8)]) {
  globalThis.__systemGroupApi = async path => path === '/api/groups/mine' ? mine : items;
}
function detailApi({systemGroup = true, permissions = allFlags} = {}) {
  const detail = {group: {...system, systemGroup, description: '그룹 소개'}, permissions,
    roles: [{id: 1, name: '소유자', builtinKey: 'OWNER', permissions: allFlags}, {id: 2, name: '회원', builtinKey: 'MEMBER', permissions: ['VIEW_PROBLEMS', 'TAKE_CONTESTS']}],
    members: [{userId: 1, username: 'alice', roleId: 2, roleName: '회원', owner: false}, {userId: 2, username: 'bob', roleId: 2, roleName: '회원', owner: false}]};
  globalThis.__systemGroupApi = async path => {
    if (path === '/api/groups/7') return detail;
    if (path.startsWith('/api/groups/7/problems?') || path.startsWith('/api/groups/7/problems/review-queue?') || path === '/api/groups/7/contests') return [];
    throw new Error('Unexpected system group API: ' + path);
  };
}
test.beforeEach(() => {globalThis.__systemGroupAuth = {epoch: 0, user: {username: 'alice', solved_count: 50}, permissions: {canCreate: true, canManage: true}};});
test.afterEach(() => cleanup());
test.after(() => dom.window.close());

test('built-in membership is displayed separately and consumes zero of three ordinary slots', {timeout: 5000}, async () => {
  browserApi([system]); const view = render(tree()); await view.findByText('일반 그룹 0/3 + 기본 그룹');
  assert.equal(view.getAllByText('기본 그룹 · 가입 제한 제외').length, 2);
  assert.equal(view.getByRole('button', {name: '그룹 만들기'}).disabled, false);
  assert.equal(view.getByRole('button', {name: '가입', exact: true}).disabled, false);
  assert.match(view.container.textContent, /기본 그룹은 이 제한에서 제외됩니다/);
});

test('a built-in group plus two ordinary memberships still permits the third ordinary membership', {timeout: 5000}, async () => {
  browserApi([system, ordinary(1), ordinary(2)]); const view = render(tree()); await view.findByText('일반 그룹 2/3 + 기본 그룹');
  assert.equal(view.getByRole('button', {name: '그룹 만들기'}).disabled, false);
  const target = view.getByRole('heading', {name: '일반 그룹 8'}).closest('article');
  assert.equal(within(target).getByRole('button', {name: '가입'}).disabled, false);
});

test('three ordinary memberships block a fourth, while built-in membership remains excluded', {timeout: 5000}, async () => {
  browserApi([system, ordinary(1), ordinary(2), ordinary(3)]); const view = render(tree()); await view.findByText('일반 그룹 3/3 + 기본 그룹');
  assert.equal(view.getByRole('button', {name: '그룹 만들기'}).disabled, true);
  assert.equal(view.getByRole('button', {name: '가입', exact: true}).disabled, true);
});

test('missing built-in membership can be restored without using an ordinary slot', {timeout: 5000}, async () => {
  browserApi([ordinary(1), ordinary(2), ordinary(3)]); const view = render(tree()); await view.findByText('일반 그룹 3/3');
  const target = view.getByRole('heading', {name: 'COBWEB GROUP'}).closest('article');
  assert.equal(within(target).getByRole('button', {name: '가입'}).disabled, false);
  assert.equal(view.getByRole('button', {name: '그룹 만들기'}).disabled, true);
});

test('quota-sensitive actions wait for membership data instead of assuming zero memberships', {timeout: 5000}, async () => {
  let release;
  globalThis.__systemGroupApi = path => path === '/api/groups/mine' ? new Promise(resolve => {release = resolve;}) : Promise.resolve([ordinary(8)]);
  const view = render(tree()); await view.findByText('일반 그룹 8');
  assert.equal(view.getByRole('button', {name: '가입', exact: true}).disabled, true);
  assert.equal(view.getByRole('button', {name: '그룹 만들기'}).disabled, true);
  absent(view.queryByText('일반 그룹 0/3'));
  await act(async () => {release([system]); await tick();}); await view.findByText('일반 그룹 0/3 + 기본 그룹');
  assert.equal(view.getByRole('button', {name: '가입', exact: true}).disabled, false);
});

test('failed membership lookup does not enable creation or publish an incorrect quota', {timeout: 5000}, async () => {
  globalThis.__systemGroupApi = async path => {if (path === '/api/groups/mine') throw new Error('가입 현황 조회 실패'); return [ordinary(8)];};
  const view = render(tree()); await view.findByText('가입 현황 확인 불가');
  assert.equal(view.getByRole('button', {name: '그룹 만들기'}).disabled, true);
  assert.equal(view.getByRole('button', {name: '가입', exact: true}).disabled, true);
  absent(view.queryByText('일반 그룹 0/3')); await view.findByRole('alert');
});

test('name alone or a nonboolean system flag cannot exclude an ordinary membership from the quota', {timeout: 5000}, async () => {
  const impostor = {...system, systemGroup: 'true'};
  browserApi([impostor, ordinary(1), ordinary(2)], [impostor]); const view = render(tree()); await view.findByText('일반 그룹 3/3');
  absent(view.queryByText('기본 그룹 · 가입 제한 제외'));
  assert.equal(view.getByRole('button', {name: '그룹 만들기'}).disabled, true);
});

test('public built-in metadata is visible to guests without claiming a personal membership', {timeout: 5000}, async () => {
  globalThis.__systemGroupAuth.user = null; browserApi([], [system]); const view = render(tree()); await view.findByText('기본 그룹 · 가입 제한 제외');
  assert.equal(view.getByRole('link', {name: '로그인 후 가입'}).getAttribute('href'), '/login');
  absent(view.queryByText('일반 그룹 0/3 + 기본 그룹')); absent(view.queryByRole('button', {name: '그룹 만들기'}));
});

test('built-in structural changes stay hidden for platform operators while permitted tools remain', {timeout: 5000}, async () => {
  detailApi(); const view = render(tree(Detail, '/groups/7', '/groups/:gid')); await view.findByRole('heading', {name: 'COBWEB GROUP'});
  await view.findByText('기본 그룹 · 가입 제한 제외');
  absent(view.queryByRole('button', {name: '그룹 탈퇴'})); absent(view.queryByRole('button', {name: '내보내기'}));
  absent(view.queryByRole('heading', {name: '그룹 설정'})); absent(view.queryByLabelText('그룹 이름'));
  assert.equal(view.getAllByRole('button', {name: '역할 변경'}).length, 2);
  assert.equal(view.getByRole('link', {name: '그룹 문제 만들기'}).getAttribute('href'), '/problems/new?groupId=7');
  assert.equal(Boolean(view.getByRole('heading', {name: '그룹 문제 관리'})), true);
  assert.equal(Boolean(view.getByText('새 코테 만들기', {selector: 'summary'})), true);
  assert.equal(view.container.querySelectorAll('.gp-role form').length, 1);
});

test('ordinary group permissions cannot expose built-in role management without platform privileges', {timeout: 5000}, async () => {
  globalThis.__systemGroupAuth.permissions.canManage = false; detailApi();
  const view = render(tree(Detail, '/groups/7', '/groups/:gid')); await view.findByText('기본 그룹 · 가입 제한 제외');
  absent(view.queryByRole('button', {name: '역할 변경'})); absent(view.queryByRole('button', {name: '역할 저장'}));
  absent(view.queryByRole('button', {name: '역할 삭제'})); absent(view.queryByRole('combobox', {name: 'alice 역할'}));
  assert.equal(Boolean(view.getByRole('link', {name: '그룹 문제 만들기'})), true);
  assert.equal(Boolean(view.getByText('새 코테 만들기', {selector: 'summary'})), true);
});

test('default members see the built-in badge without unsupported management tools', {timeout: 5000}, async () => {
  globalThis.__systemGroupAuth.permissions = {canCreate: false, canManage: false}; detailApi({permissions: ['VIEW_PROBLEMS', 'TAKE_CONTESTS']});
  const view = render(tree(Detail, '/groups/7', '/groups/:gid')); await view.findByText('기본 그룹 · 가입 제한 제외');
  absent(view.queryByRole('link', {name: '그룹 문제 만들기'})); absent(view.queryByRole('heading', {name: '그룹 문제 관리'}));
  absent(view.queryByText('새 코테 만들기', {selector: 'summary'})); absent(view.queryByRole('button', {name: '역할 변경'}));
  absent(view.queryByRole('button', {name: '역할 저장'})); absent(view.queryByRole('button', {name: '그룹 탈퇴'}));
});

test('ordinary groups preserve authorized leave, kick, name edit, and role controls', {timeout: 5000}, async () => {
  detailApi({systemGroup: false}); const view = render(tree(Detail, '/groups/7', '/groups/:gid')); await view.findByRole('heading', {name: 'COBWEB GROUP'});
  absent(view.queryByText('기본 그룹 · 가입 제한 제외'));
  assert.equal(Boolean(view.getByRole('button', {name: '그룹 탈퇴'})), true);
  assert.equal(view.getAllByRole('button', {name: '내보내기'}).length, 2);
  assert.equal(view.getByLabelText('그룹 이름').value, 'COBWEB GROUP');
  assert.equal(view.getAllByRole('button', {name: '역할 변경'}).length, 2);
});

test('account switches clear the previous membership quota before late responses can restore it', {timeout: 5000}, async () => {
  browserApi([system, ordinary(1)]); const view = render(tree()); await view.findByText('일반 그룹 1/3 + 기본 그룹');
  let releaseOld, releaseNew;
  globalThis.__systemGroupApi = path => path === '/api/groups/mine' ? new Promise(resolve => {releaseOld = resolve;}) : Promise.resolve([system]);
  // 사용자 객체가 바뀌면 같은 계정 세대의 회원 조회도 재실행됩니다.
  globalThis.__systemGroupAuth = {...globalThis.__systemGroupAuth, user: {...globalThis.__systemGroupAuth.user}};
  view.rerender(tree()); await waitFor(() => assert.equal(typeof releaseOld, 'function'));
  globalThis.__systemGroupAuth = {...globalThis.__systemGroupAuth, epoch: 1, user: {username: 'bob', solved_count: 50}};
  globalThis.__systemGroupApi = path => path === '/api/groups/mine' ? new Promise(resolve => {releaseNew = resolve;}) : Promise.resolve([system]);
  view.rerender(tree()); absent(view.queryByText('일반 그룹 1/3 + 기본 그룹'));
  await act(async () => {releaseNew([system]); await tick();}); await view.findByText('일반 그룹 0/3 + 기본 그룹');
  await act(async () => {releaseOld([system, ordinary(1), ordinary(2), ordinary(3)]); await tick();});
  absent(view.queryByText('일반 그룹 3/3 + 기본 그룹'));
  assert.equal(view.getByRole('button', {name: '그룹 만들기'}).disabled, false);
});

test('built-in badges and quota labels wrap instead of hiding on narrow group rows', {timeout: 5000}, async () => {
  const style = document.createElement('style'); style.textContent = readFileSync(new URL('../src/pages/Groups.css', import.meta.url), 'utf8'); document.head.append(style);
  try {
    browserApi([system]); const view = render(tree()); const badges = await view.findAllByText('기본 그룹 · 가입 제한 제외');
    await view.findByText('일반 그룹 0/3 + 기본 그룹');
    for (const badge of badges) {const computed = window.getComputedStyle(badge); assert.equal(computed.display, 'inline-flex'); assert.equal(computed.maxWidth, '100%'); assert.equal(computed.whiteSpace, 'normal');}
    assert.equal(window.getComputedStyle(view.getByText('일반 그룹 0/3 + 기본 그룹')).whiteSpace, 'normal');
  } finally {style.remove();}
});
