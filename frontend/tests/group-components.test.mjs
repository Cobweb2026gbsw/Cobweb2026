import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {build} from 'esbuild';
import {JSDOM} from 'jsdom';
import {installEditorDom,editorValue,setEditorValue} from './editor-dom.mjs';

const require = createRequire(import.meta.url), root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const dom = new JSDOM('<!doctype html><html><body></body></html>', {url: 'http://localhost:8080/', pretendToBeVisual: true});
Object.defineProperty(globalThis, 'navigator', {value: dom.window.navigator, configurable: true});
for (const key of ['window', 'document', 'HTMLElement', 'Event', 'InputEvent', 'KeyboardEvent', 'MutationObserver', 'FormData']) globalThis[key] = dom.window[key];
installEditorDom(dom.window);
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
// jsdom does not implement the browser's native modal-dialog lifecycle.
dom.window.HTMLDialogElement.prototype.showModal = function () {this.setAttribute('open', '');};
dom.window.HTMLDialogElement.prototype.close = function () {this.removeAttribute('open');};
window.confirm = () => true;
window.CobwebCodeEditor = require('../../src/main/resources/static/code-editor.js');
const React = require('react'), {render, fireEvent, waitFor, cleanup, act, within} = require('@testing-library/react');
const {MemoryRouter, Routes, Route} = require('react-router-dom');
const modules = new Map();
// Bound every scenario and always unmount, even when an assertion fails midway.
// Otherwise a failed scenario can retain the component's polling intervals.
const scenario = (name, run) => test(name, {timeout: 5000}, run);
// React DOM/Fiber 객체를 assert의 actual로 넘기면 실패 메시지 생성만으로도
// 거대한 객체 그래프를 순회합니다. 부재 검증은 반드시 boolean으로 비교합니다.
const absent = node => assert.equal(node === null, true, '화면에서 해당 요소가 제거되어야 합니다.');
test.afterEach(() => cleanup());
async function load(name, folder = 'pages') {
  const key = folder + '/' + name;
  if (modules.has(key)) return modules.get(key);
  const bundle = await build({entryPoints: [path.join(root, 'src', folder, name + '.jsx')], bundle: true, write: false, platform: 'node', format: 'cjs', packages: 'external', jsx: 'automatic', loader: {'.css': 'empty'}, plugins: [{name: 'group-contracts', setup(builder) {
    builder.onResolve({filter: /(?:^|\/)api(?:\.js)?$/}, () => ({path: 'api', namespace: 'contract'}));
    builder.onResolve({filter: /\/context\/AuthContext(?:\.jsx)?$/}, () => ({path: 'auth', namespace: 'contract'}));
    builder.onLoad({filter: /.*/, namespace: 'contract'}, args => ({contents: args.path === 'api' ? 'export const api=(...args)=>globalThis.__groupApi(...args);export const getSessionEpoch=()=>globalThis.__groupSession;' : 'export const useAuth=()=>globalThis.__groupAuth;'}));
  }}]});
  const module = {exports: {}};
  new Function('require', 'module', 'exports', bundle.outputFiles[0].text)(require, module, module.exports);
  modules.set(key, module.exports); return module.exports;
}
const tick = () => new Promise(resolve => setTimeout(resolve, 0));
function reset() {
  cleanup(); globalThis.__groupSession = 0;
  globalThis.__groupAuth = {epoch: 0, user: {username: 'alice', solved_count: 50}, permissions: {canCreate: true, canManage: true}};
}
function mount(Page, url, pattern) {
  return render(React.createElement(MemoryRouter, {initialEntries: [url]}, React.createElement(Routes, null,
    React.createElement(Route, {path: pattern, element: React.createElement(Page, {dark: true})}),
    React.createElement(Route, {path: '*', element: React.createElement('p', null, 'destination-route')}))));
}
const allFlags = ['VIEW_PROBLEMS','CREATE_PROBLEMS','EDIT_PROBLEMS','REVIEW_PROBLEMS','MANAGE_MEMBERS','MANAGE_ROLES','MANAGE_GROUP','CREATE_CONTESTS','MANAGE_CONTESTS','VIEW_CONTEST_RESULTS','TAKE_CONTESTS'];
const withoutTake = allFlags.filter(permission => permission !== 'TAKE_CONTESTS');
const group = {group: {id: 7, groupName: '실제 그룹', description: '서버 소개', memberCount: 2}, permissions: allFlags,
  roles: [{id: 1, name: '소유자', builtinKey: 'OWNER', permissions: allFlags}, {id: 9, name: '맞춤 역할', builtinKey: null, permissions: ['VIEW_PROBLEMS']}],
  members: [{userId: 1, username: 'alice', roleId: 1, roleName: '소유자', owner: true}, {userId: 2, username: 'bob', roleId: 9, roleName: '맞춤 역할', owner: false}]};
const question = number => ({number, title: '검수 문제 ' + number, status: 'PENDING_REVIEW', canEdit: true, description: '실제 본문', languages: ['PYTHON'], examples: [], tests: [{input: 'private-input-' + number, expected: 'true'}], referenceSolutions: [{language: 'PYTHON', fileName: 'main.py', sourceCode: 'private-answer-' + number}]});
const contest = {id: 3, title: '실제 코테', status: 'ACTIVE', startsAt: '2026-01-01T00:00:00.123456Z', endsAt: '2030-01-01T00:00:00.654321Z', replayAllowed: false, captureProtection: false, windowSwitchProtection: false, canManage: true, canViewResults: true, problemCount: 1, problems: [{problemNumber: 1002, problemTitle: '그룹 코테 문제'}]};
const problem = {problem_number: 1002, problem_title: '그룹 코테 문제', description: '진짜 시험 본문', input_description: '입력', output_description: '출력', constraints_text: '제한', execution_mode: 'FUNCTION', rank_type: 'ELITE', rank_int: 5, time_limit_ms: 1000, memory_limit_mb: 256, languages: ['PYTHON', 'DART'], examples: []};
function standardGroupApi(path, method = 'GET') {
  if (method !== 'GET') return null;
  if (path === '/api/groups/7') return group;
  if (path.startsWith('/api/groups/7/problems/review-queue?')) return [{problemNumber: 1001, problemTitle: '검수 문제 1001', status: 'PENDING_REVIEW', authorUsername: 'alice'}, {problemNumber: 1002, problemTitle: '검수 문제 1002', status: 'PENDING_REVIEW', authorUsername: 'alice'}];
  if (path.startsWith('/api/groups/7/problems?')) return [];
  if (path === '/api/groups/7/contests') return [];
  if (/^\/api\/problems\/\d+\/edit$/.test(path)) return question(Number(path.split('/')[3]));
  throw new Error('Unexpected group API: ' + path);
}
function standardContestApi(path, method = 'GET') {
  if (path === '/api/groups/7') return group;
  if (path === '/api/groups/7/contests/3') return contest;
  if (path === '/api/groups/7/contests/3/problems/1002') return problem;
  if (path === '/api/languages') return [{id:'PYTHON',label:'Python',modes:['STDIN','FUNCTION']},{id:'DART',label:'Dart',modes:['STDIN','FUNCTION']}];
  if (path === '/api/groups/7/contests/3/me') return {submissions: []};
  if (path === '/api/groups/7/contests/3/results') return [{userId: 2, username: 'bob', solvedCount: 1, timeSumSeconds: 10}];
  if (path.startsWith('/api/groups/7/contests/3/events')) return [];
  if (path === '/api/groups/7/contests/3/submissions' && method === 'POST') return 42;
  if (path === '/api/submissions/42') return {id: 42, status: 'ACCEPTED', score: 100, cases: []};
  if (path === '/api/groups/7/contests/3/submissions?userId=2') return {submissions: [{id: 42, problemNumber: 1002, language: 'DART', status: 'ACCEPTED', score: 100, sourceCode: 'protected-staff-answer'}]};
  throw new Error('Unexpected contest API: ' + path);
}

scenario('missing or throwing browser UUID support reports a safe protection-event failure',async()=>{
  const descriptor=Object.getOwnPropertyDescriptor(globalThis,'crypto');
  try{
    for(const capability of [{}, {randomUUID(){throw new Error('private-crypto-detail');}}]){
      reset();Object.defineProperty(globalThis,'crypto',{value:capability,configurable:true});
      const {default:Page}=await load('GroupContest');let events=0;
      globalThis.__groupApi=async(path,method='GET')=>{
        if(path==='/api/groups/7/contests/3')return {...contest,captureProtection:true};
        if(path.endsWith('/events'))events++;
        return standardContestApi(path,method);
      };
      const view=mount(Page,'/groups/7/contests/3','/groups/:gid/contests/:cid');
      await view.findByRole('button',{name:'1. 그룹 코테 문제'});
      await act(async () => {await tick();});
      fireEvent.copy(document);
      await view.findByText(/감시 신호를 기록하지 못했습니다/);
      assert.equal(events,0);assert.doesNotMatch(view.container.textContent,/private-crypto-detail/);cleanup();
    }
  }finally{if(descriptor)Object.defineProperty(globalThis,'crypto',descriptor);else delete globalThis.crypto;}
});

scenario('group browser creates using real DTO and follows returned group ID', async () => {
  reset(); const {default: Page} = await load('Groups'), calls = [];
  globalThis.__groupApi = async (path, method = 'GET', body) => {calls.push({path, method, body}); if (path === '/api/groups' && method === 'POST') return 77; if (path === '/api/groups/mine') return []; return [{id: 5, groupName: '검색 그룹', memberCount: 2}];};
  const view = mount(Page, '/groups', '/groups'); await view.findByText('검색 그룹');
  absent(view.queryByRole('dialog'));
  fireEvent.click(view.getByRole('button', {name: '그룹 만들기'}));
  const dialog = within(view.getByRole('dialog', {name: '그룹 만들기'}));
  fireEvent.change(view.container.querySelector('#group-name'), {target: {value: '새 실제 그룹'}});
  fireEvent.change(view.container.querySelector('#group-description'), {target: {value: '새 소개'}});
  fireEvent.click(dialog.getByRole('button', {name: '그룹 만들기'})); await view.findByText('destination-route');
  assert.deepEqual(calls.find(call => call.path === '/api/groups' && call.method === 'POST').body, {name: '새 실제 그룹', description: '새 소개'}); cleanup();
});

scenario('group search separates initial and searched empty states and preserves the search across tabs', async () => {
  reset(); const {default: Page} = await load('Groups'), calls = [];
  globalThis.__groupApi = async path => {calls.push(path); return [];};
  const view = mount(Page, '/groups', '/groups');
  await view.findByText('그룹 이름으로 검색해 보세요');
  absent(view.queryByRole('button', {name: '이전'})); absent(view.queryByRole('textbox', {name: /소개/}));
  const search = view.getByRole('textbox', {name: '그룹 이름 검색'});
  fireEvent.change(search, {target: {value: '  알고리즘  '}}); fireEvent.submit(search.closest('form'));
  await view.findByText('일치하는 그룹이 없어요');
  assert.equal(calls.some(path => path.includes('search=') && new URL(path, window.location.origin).searchParams.get('search') === '알고리즘'), true);
  absent(view.queryByRole('button', {name: '다음'}));
  fireEvent.click(view.getByRole('button', {name: '검색어 바꾸기'})); assert.equal(document.activeElement === search, true);
  fireEvent.click(view.getByRole('tab', {name: '내 그룹'}));
  await view.findByText('아직 가입한 그룹이 없어요');
  absent(view.queryByRole('textbox', {name: '그룹 이름 검색'}));
  assert.equal(view.getAllByRole('tabpanel').length, 1);
  fireEvent.click(view.getByRole('button', {name: '그룹 탐색하기'}));
  assert.equal(view.getByRole('textbox', {name: '그룹 이름 검색'}).value, '  알고리즘  ');
  assert.equal(document.activeElement === view.getByRole('tab', {name: '그룹 탐색'}), true);
});

scenario('group lists show descriptions and memberships in one panel with keyboard tab switching', async () => {
  reset(); const {default: Page} = await load('Groups');
  globalThis.__groupApi = async path => path === '/api/groups/mine' ? [{id: 9, groupName: '가입 그룹', description: '매주 함께 공부해요', memberCount: 3}] : [{id: 5, groupName: '공개 그룹', description: '매일 한 문제', memberCount: 8}];
  const view = mount(Page, '/groups', '/groups'); await view.findByText('매일 한 문제');
  await view.findByText('일반 그룹 1/3'); absent(view.queryByText('가입 그룹'));
  fireEvent.keyDown(view.getByRole('tab', {name: '그룹 탐색'}), {key: 'ArrowRight'});
  assert.equal(view.getByRole('tab', {name: '내 그룹'}).getAttribute('aria-selected'), 'true');
  assert.equal(new URL(view.getByRole('link', {name: '그룹 열기'}).getAttribute('href'), window.location.origin).pathname, '/groups/9');
  await view.findByText('매주 함께 공부해요'); absent(view.queryByText('공개 그룹'));
  fireEvent.keyDown(view.getByRole('tab', {name: '내 그룹'}), {key: 'Home'});
  await view.findByText('공개 그룹');
});

scenario('a failed group search remains an error instead of a misleading zero-results state', async () => {
  reset(); const {default: Page} = await load('Groups'); let fail = true;
  globalThis.__groupApi = async path => {if (path !== '/api/groups/mine' && fail) throw new Error('그룹 조회 실패'); return [];};
  const view = mount(Page, '/groups', '/groups'); await view.findByText('그룹 조회 실패');
  absent(view.queryByText('일치하는 그룹이 없어요')); absent(view.queryByRole('button', {name: '다음'}));
  fail = false; fireEvent.click(view.getByRole('button', {name: '다시 불러오기'}));
  await view.findByText('그룹 이름으로 검색해 보세요'); absent(view.queryByRole('alert'));
});

scenario('group creation keeps a draft after dismissal and shows submission errors inside the dialog', async () => {
  reset(); const {default: Page} = await load('Groups'); let posts = 0;
  globalThis.__groupApi = async (_path, method) => {if (method === 'POST') {posts++; throw new Error('이미 사용 중인 그룹 이름입니다.');} return [];};
  const view = mount(Page, '/groups', '/groups'); await view.findByText('일반 그룹 0/3');
  const opener = view.getByRole('button', {name: '그룹 만들기'}); opener.focus(); fireEvent.click(opener);
  let dialog = view.getByRole('dialog', {name: '그룹 만들기'});
  const input = within(dialog).getByRole('textbox', {name: /그룹 이름/});
  assert.equal(document.activeElement === input, true);
  assert.equal(within(dialog).getByRole('button', {name: '그룹 만들기', exact: true}).disabled, true);
  fireEvent.change(input, {target: {value: '내 그룹 초안'}});
  fireEvent.change(within(dialog).getByRole('textbox', {name: /소개/}), {target: {value: '매주 함께 공부'}});
  fireEvent.click(within(dialog).getByRole('button', {name: '취소'}));
  absent(view.queryByRole('dialog')); assert.equal(document.activeElement === opener, true); assert.equal(posts, 0);
  fireEvent.click(opener); dialog = view.getByRole('dialog', {name: '그룹 만들기'});
  assert.equal(within(dialog).getByRole('textbox', {name: /그룹 이름/}).value, '내 그룹 초안');
  fireEvent.click(within(dialog).getByRole('button', {name: '그룹 만들기', exact: true}));
  await within(dialog).findByRole('alert'); assert.equal(posts, 1);
  assert.equal(within(dialog).getByRole('textbox', {name: /소개/}).value, '매주 함께 공부');
  fireEvent(dialog, new window.Event('cancel', {bubbles: false, cancelable: true}));
  absent(view.queryByRole('dialog')); assert.equal(document.body.style.overflow, '');
});

scenario('group role editor exposes all eleven switches and sends exact permission IDs', async () => {
  reset(); const {default: Page} = await load('GroupDetail'), calls = [];
  globalThis.__groupApi = async (path, method = 'GET', body) => {calls.push({path, method, body}); return standardGroupApi(path, method);};
  const view = mount(Page, '/groups/7', '/groups/:gid'); await view.findByRole('heading', {name: '실제 그룹'});
  const details = view.getByText('맞춤 역할', {selector: 'summary'}).closest('details'); fireEvent.click(within(details).getByText('맞춤 역할', {selector: 'summary'}));
  const controls = within(details); assert.equal(controls.getAllByRole('checkbox').length, 11);
  fireEvent.change(controls.getByRole('textbox', {name: '역할 이름'}), {target: {value: '수정한 역할'}});
  fireEvent.click(controls.getByRole('checkbox', {name: '문제 만들기'})); fireEvent.click(controls.getByRole('button', {name: '역할 저장'}));
  await waitFor(() => assert.ok(calls.some(call => call.method === 'PUT')));
  assert.deepEqual(calls.find(call => call.method === 'PUT').body, {name: '수정한 역할', permissions: ['VIEW_PROBLEMS', 'CREATE_PROBLEMS']});
  absent(view.queryByRole('button', {name: '그룹 탈퇴'})); cleanup();
});

scenario('group approval refreshes filtered public list without discarding unsaved settings', async () => {
  reset(); const {default: Page} = await load('GroupDetail'), calls = [];
  globalThis.__groupApi = async (path, method = 'GET', body) => {calls.push({path, method, body}); return standardGroupApi(path, method);};
  const view = mount(Page, '/groups/7', '/groups/:gid'); await view.findByRole('heading', {name: '실제 그룹'});
  fireEvent.change(view.getByLabelText('소개'), {target: {value: '저장하지 않은 소개 초안'}});
  const search = view.getByRole('textbox', {name: '그룹 문제 제목 검색'}); fireEvent.change(search, {target: {value: '찾을 문제'}}); fireEvent.submit(search.closest('form'));
  const filtered = call => call.path.includes('/problems?') && new URL(call.path, window.location.origin).searchParams.get('search') === '찾을 문제';
  await waitFor(() => assert.ok(calls.some(filtered)));
  fireEvent.click((await view.findAllByRole('button', {name: '상세'}))[0]); await view.findByText('private-answer-1001');
  fireEvent.click(view.getByRole('button', {name: '검수 처리'}));
  await waitFor(() => assert.ok(calls.filter(filtered).length >= 2));
  assert.equal(view.getByDisplayValue('저장하지 않은 소개 초안').value, '저장하지 않은 소개 초안');
  assert.deepEqual(calls.find(call => call.path === '/api/problems/1001/review').body, {status: 'PUBLISHED', cause: ''}); cleanup();
});

scenario('late review mutation cannot clear a newly selected private problem', async () => {
  reset(); const {default: Page} = await load('GroupDetail'); let release;
  globalThis.__groupApi = async (path, method = 'GET') => method === 'POST' ? new Promise(resolve => {release = resolve;}) : standardGroupApi(path, method);
  const view = mount(Page, '/groups/7', '/groups/:gid'); await view.findByRole('heading', {name: '실제 그룹'});
  fireEvent.click((await view.findAllByRole('button', {name: '상세'}))[0]); await view.findByText('private-answer-1001'); fireEvent.click(view.getByRole('button', {name: '검수 처리'}));
  fireEvent.click(view.getAllByRole('button', {name: '상세'})[1]); await view.findByText('private-answer-1002');
  await act(async () => {release(null); await tick();}); assert.ok(view.getByText('private-answer-1002')); absent(view.queryByText('private-answer-1001')); cleanup();
});

scenario('ended contest form preserves exact timestamps/order while saving options', async () => {
  reset(); const {default: ContestForm} = await load('GroupContestForm', 'components'), calls = [], current = {...contest, status:'ENDED', startsAt:'2026-10-21T12:00:10.123456Z', endsAt:'2026-10-21T12:00:10.123457Z', problems:[{problemNumber:1002},{problemNumber:1001}]};
  globalThis.__groupApi = async (path, method, body) => {calls.push({path, method, body}); return null;};
  const view = render(React.createElement(ContestForm, {gid:7, current}));
  fireEvent.click(view.getByRole('checkbox', {name: '종료 후 참여자 다시 보기 허용'})); fireEvent.click(view.getByRole('button', {name:'변경 저장'}));
  await waitFor(() => assert.equal(calls.length, 1)); assert.equal(calls[0].path, '/api/groups/7/contests/3'); assert.equal(calls[0].method, 'PUT');
  assert.deepEqual(calls[0].body, {title:current.title, startsAt:current.startsAt, endsAt:current.endsAt, problemNumbers:[1002,1001], replayAllowed:true, captureProtection:false, windowSwitchProtection:false}); cleanup();
});

scenario('group contest consumes real snake-case problem and submits through scoped API', async () => {
  reset(); const {default: Page} = await load('GroupContest'), calls = [];
  globalThis.__groupApi = async (path, method = 'GET', body) => {calls.push({path, method, body}); return standardContestApi(path, method);};
  const view = mount(Page, '/groups/7/contests/3', '/groups/:gid/contests/:cid'); await view.findByRole('button', {name:'1. 그룹 코테 문제'});
  fireEvent.click(view.getByRole('button', {name:'1. 그룹 코테 문제'})); await view.findByText('진짜 시험 본문');
  fireEvent.change(view.getByRole('combobox', {name:'제출 언어'}), {target:{value:'DART'}});
  act(()=>setEditorValue(view.getByRole('textbox', {name:'풀이 코드'}),'bool sol(List<List<int>> m,List<int> t)=>true;'));
  fireEvent.click(view.getByRole('button', {name:'시험 답안 제출'})); await view.findByText('제출 #42 · ACCEPTED · 100점');
  assert.deepEqual(calls.find(call => call.path === '/api/groups/7/contests/3/submissions' && call.method === 'POST').body, {problemNumber:1002,sourceCode:'bool sol(List<List<int>> m,List<int> t)=>true;',language:'DART'});
  assert.equal(calls.some(call => call.path === '/api/submissions' && call.method === 'POST'), false); cleanup();
});

scenario('contest submission has a synchronous flight guard against repeated form submits', async () => {
  reset(); const {default: Page} = await load('GroupContest'); let release, posts = 0;
  globalThis.__groupApi = async (path, method = 'GET') => {
    if (path === '/api/groups/7/contests/3/submissions' && method === 'POST') {
      posts++; return new Promise(resolve => {release = resolve;});
    }
    return standardContestApi(path, method);
  };
  const view = mount(Page, '/groups/7/contests/3', '/groups/:gid/contests/:cid');
  fireEvent.click(await view.findByRole('button', {name:'1. 그룹 코테 문제'}));
  const area = await view.findByRole('textbox', {name:'풀이 코드'});
  act(() => setEditorValue(area, 'def sol(matrix,target): return True'));
  const form = view.getByRole('button', {name:'시험 답안 제출'}).closest('form');
  act(() => {fireEvent.submit(form); fireEvent.submit(form);});
  assert.equal(posts, 1); assert.equal(view.getByRole('button', {name:'시험 답안 제출'}).disabled, true);
  fireEvent.submit(form); assert.equal(posts, 1);
  await act(async () => {release(42); await tick();});
  await view.findByText('제출 #42 · ACCEPTED · 100점');
  assert.equal(posts, 1);
});

scenario('late submit failure is discarded after the active time window closes and does not lock a reopened window', async () => {
  reset(); const {default: Page} = await load('GroupContest'); let current = {...contest}, rejectPost, posts = 0;
  globalThis.__groupApi = async (path, method = 'GET') => {
    if (path === '/api/groups/7/contests/3') return current;
    if (path === '/api/groups/7/contests/3/submissions' && method === 'POST') {
      posts++; return new Promise((resolve, reject) => {rejectPost = reject;});
    }
    return standardContestApi(path, method);
  };
  const view = mount(Page, '/groups/7/contests/3', '/groups/:gid/contests/:cid');
  fireEvent.click(await view.findByRole('button', {name:'1. 그룹 코테 문제'}));
  const area = await view.findByRole('textbox', {name:'풀이 코드'});
  act(() => setEditorValue(area, 'preserved-time-window-draft'));
  fireEvent.click(view.getByRole('button', {name:'시험 답안 제출'}));
  await waitFor(() => assert.equal(typeof rejectPost, 'function'));
  current = {...contest, endsAt:new Date(Date.now() - 1000).toISOString()};
  fireEvent.click(view.getByRole('button', {name:'새로고침'}));
  await waitFor(() => assert.equal(view.getByRole('combobox', {name:'제출 언어'}).disabled, true));
  await act(async () => {rejectPost(new Error('late-window-submit-failure')); await tick();});
  assert.doesNotMatch(view.container.textContent, /late-window-submit-failure/);
  fireEvent.submit(view.getByRole('button', {name:'시험 답안 제출'}).closest('form')); assert.equal(posts, 1);
  current = {...contest};fireEvent.click(view.getByRole('button', {name:'새로고침'}));
  await waitFor(() => assert.equal(view.getByRole('button', {name:'시험 답안 제출'}).disabled, false));
  assert.equal(editorValue(view.getByRole('textbox', {name:'풀이 코드'})), 'preserved-time-window-draft');
});

scenario('a pending submit cannot show its old error after the account epoch changes', async () => {
  reset(); const {default: Page} = await load('GroupContest'); let rejectPost;
  globalThis.__groupApi = async (path, method = 'GET') => {
    if (path === '/api/groups/7/contests/3/submissions' && method === 'POST')
      return new Promise((resolve, reject) => {rejectPost = reject;});
    return standardContestApi(path, method);
  };
  const view = mount(Page, '/groups/7/contests/3', '/groups/:gid/contests/:cid');
  fireEvent.click(await view.findByRole('button', {name:'1. 그룹 코테 문제'}));
  const area = await view.findByRole('textbox', {name:'풀이 코드'});
  act(() => setEditorValue(area, 'private-first-account-source'));
  fireEvent.click(view.getByRole('button', {name:'시험 답안 제출'}));
  await waitFor(() => assert.equal(typeof rejectPost, 'function'));
  globalThis.__groupSession = 1; globalThis.__groupAuth = {epoch:1,user:{username:'bob'},permissions:{}};
  globalThis.__groupApi = () => new Promise(() => {});
  view.rerender(React.createElement(MemoryRouter, {initialEntries:['/groups/7/contests/3']}, React.createElement(Routes, null,
    React.createElement(Route, {path:'/groups/:gid/contests/:cid',element:React.createElement(Page, {dark:true})}))));
  await act(async () => {rejectPost(new Error('old-account-submit-failure')); await tick();});
  assert.doesNotMatch(view.container.textContent, /old-account-submit-failure|private-first-account-source/);
  absent(view.queryByRole('textbox', {name:'풀이 코드'}));
});

scenario('active option refresh preserves code but ended replay denial removes source and question', async () => {
  reset(); const {default: Page} = await load('GroupContest'); let current = {...contest};
  globalThis.__groupApi = async (path, method = 'GET') => {if (path === '/api/groups/7/contests/3') return current; if (path.endsWith('/me')) {if (current.status === 'ENDED') throw Object.assign(new Error('다시 보기 권한 없음'), {status:403}); return {submissions:[{id:4,problemNumber:1002,language:'DART',status:'ACCEPTED',score:100,sourceCode:'private-history-answer'}]};} return standardContestApi(path, method);};
  const view = mount(Page, '/groups/7/contests/3', '/groups/:gid/contests/:cid'); await view.findByRole('button', {name:'1. 그룹 코테 문제'}); fireEvent.click(view.getByRole('button', {name:'1. 그룹 코테 문제'}));
  const area = await view.findByRole('textbox', {name:'풀이 코드'}); act(()=>setEditorValue(area,'보존할 작성 코드'));
  current = {...contest, replayAllowed:true}; fireEvent.click(view.getByRole('button', {name:'새로고침'})); await waitFor(() => assert.match(view.container.textContent, /다시 보기 허용/)); assert.equal(editorValue(view.getByRole('textbox',{name:'풀이 코드'})), '보존할 작성 코드');
  current = {...contest, status:'ENDED', replayAllowed:false, problems:[]}; fireEvent.click(view.getByRole('button', {name:'새로고침'})); await view.findByText('시험 종료');
  await waitFor(() => absent(view.queryByText('private-history-answer'))); assert.doesNotMatch(view.container.textContent,/보존할 작성 코드/); absent(view.queryByText('진짜 시험 본문')); cleanup();
});

scenario('revoked result permission discards late staff answer response', async () => {
  reset(); const {default: Page} = await load('GroupContest'); let current = {...contest}, release;
  globalThis.__groupApi = async (path, method = 'GET') => {if (path === '/api/groups/7/contests/3') return current; if (path === '/api/groups/7/contests/3/submissions?userId=2') return new Promise(resolve => {release = resolve;}); return standardContestApi(path, method);};
  const view = mount(Page, '/groups/7/contests/3', '/groups/:gid/contests/:cid'); await view.findByRole('button', {name:'시험 운영'}); fireEvent.click(view.getByRole('button', {name:'시험 운영'})); await view.findByText('bob');
  fireEvent.click(view.getByRole('button', {name:'제출 보기'})); await waitFor(() => assert.equal(typeof release, 'function'));
  current = {...contest, canManage:false, canViewResults:false}; fireEvent.click(view.getByRole('button', {name:'새로고침'})); await waitFor(() => absent(view.queryByRole('button', {name:'시험 운영'})));
  await act(async () => {release({submissions:[{id:42,problemNumber:1002,language:'DART',status:'ACCEPTED',sourceCode:'must-never-render'}]}); await tick();}); assert.doesNotMatch(view.container.textContent, /must-never-render/); cleanup();
});

scenario('account epoch switch removes group review private source before new account response', async () => {
  reset(); const {default: Page} = await load('GroupDetail');
  globalThis.__groupApi = async (path, method = 'GET') => standardGroupApi(path, method);
  const view = mount(Page, '/groups/7', '/groups/:gid'); await view.findByRole('heading', {name:'실제 그룹'}); fireEvent.click((await view.findAllByRole('button', {name:'상세'}))[0]); await view.findByText('private-answer-1001');
  globalThis.__groupSession++; globalThis.__groupAuth = {epoch:1,user:{username:'other'},permissions:{canCreate:false,canManage:false}};
  globalThis.__groupApi = async () => new Promise(() => {});
  view.rerender(React.createElement(MemoryRouter, {initialEntries:['/groups/7']}, React.createElement(Routes,null,React.createElement(Route,{path:'/groups/:gid',element:React.createElement(Page,{dark:true})}))));
  assert.doesNotMatch(view.container.textContent, /private-answer-1001|private-input-1001/); cleanup();
});

scenario('published group problem can be archived without offering illegal republish transitions', async () => {
  reset(); const {default: Page} = await load('GroupDetail'), calls = [];
  globalThis.__groupApi = async (path, method = 'GET', body) => {calls.push({path, method, body}); if (path === '/api/problems/1001/edit') return {...question(1001), status:'PUBLISHED', canEdit:false}; return standardGroupApi(path, method);};
  const view = mount(Page, '/groups/7', '/groups/:gid'); fireEvent.click((await view.findAllByRole('button', {name:'상세'}))[0]); await view.findByText('private-answer-1001');
  const select = view.getByRole('combobox', {name:'처리'});
  assert.deepEqual(Array.from(select.options, option => option.value), ['ARCHIVED']);
  fireEvent.change(view.getByRole('textbox', {name:'사유'}), {target:{value:'공개 회수'}}); fireEvent.click(view.getByRole('button', {name:'검수 처리'}));
  await waitFor(() => assert.equal(calls.some(call => call.path === '/api/problems/1001/review' && call.method === 'POST'), true));
  assert.deepEqual(calls.find(call => call.path === '/api/problems/1001/review').body, {status:'ARCHIVED',cause:'공개 회수'});
});

scenario('denied group review search removes already displayed private tests and solutions', async () => {
  reset(); const {default: Page} = await load('GroupDetail'); let denied = false;
  globalThis.__groupApi = async (path, method = 'GET') => {if (denied && path.startsWith('/api/groups/7/problems/review-queue?')) throw Object.assign(new Error('권한 회수'), {status:403}); if (denied && path === '/api/groups/7') return {...group,permissions:['VIEW_PROBLEMS']}; return standardGroupApi(path, method);};
  const view = mount(Page, '/groups/7', '/groups/:gid'); fireEvent.click((await view.findAllByRole('button', {name:'상세'}))[0]); await view.findByText('private-answer-1001');
  denied = true; const search = view.getByRole('textbox', {name:'그룹 검수 제목 검색'}); fireEvent.change(search, {target:{value:'권한 확인'}}); fireEvent.submit(search.closest('form'));
  await waitFor(() => absent(view.queryByText('private-answer-1001'))); assert.doesNotMatch(view.container.textContent, /private-input-1001/);
});

scenario('denied review mutation invalidates a newer in-flight private detail response', async () => {
  reset(); const {default: Page} = await load('GroupDetail'); let rejectReview, releaseDetail;
  globalThis.__groupApi = async (path, method = 'GET') => {if (path === '/api/problems/1001/review') return new Promise((resolve, reject) => {rejectReview = reject;}); if (path === '/api/problems/1002/edit') return new Promise(resolve => {releaseDetail = resolve;}); return standardGroupApi(path, method);};
  const view = mount(Page, '/groups/7', '/groups/:gid'); fireEvent.click((await view.findAllByRole('button', {name:'상세'}))[0]); await view.findByText('private-answer-1001'); fireEvent.click(view.getByRole('button', {name:'검수 처리'}));
  fireEvent.click(view.getAllByRole('button', {name:'상세'})[1]); await waitFor(() => assert.equal(typeof releaseDetail, 'function'));
  await act(async () => {rejectReview(Object.assign(new Error('권한 회수'), {status:403})); await tick();}); await view.findByText('권한 회수');
  await act(async () => {releaseDetail(question(1002)); await tick();}); absent(view.queryByText('private-answer-1002')); assert.doesNotMatch(view.container.textContent, /private-input-1002/);
});

scenario('role editor allows removing a pre-existing permission but cannot turn it back on', async () => {
  reset(); globalThis.__groupAuth.permissions.canManage = false; const {default: Page} = await load('GroupDetail'), calls = [];
  const limited = {...group,permissions:['VIEW_PROBLEMS','MANAGE_ROLES'],roles:[group.roles[0],{...group.roles[1],permissions:['VIEW_PROBLEMS','CREATE_PROBLEMS']}]};
  globalThis.__groupApi = async (path, method = 'GET', body) => {calls.push({path, method, body}); if (path === '/api/groups/7') return limited; return standardGroupApi(path, method);};
  const view = mount(Page, '/groups/7', '/groups/:gid'); const summary = await view.findByText('맞춤 역할', {selector:'summary'}); fireEvent.click(summary); const controls = within(summary.closest('details'));
  const create = controls.getByRole('checkbox', {name:'문제 만들기'}); assert.equal(create.checked, true); assert.equal(create.disabled, false); fireEvent.click(create);
  assert.equal(create.checked, false); assert.equal(create.disabled, true); fireEvent.click(create); assert.equal(create.checked, false);
  fireEvent.click(controls.getByRole('button', {name:'역할 저장'})); await waitFor(() => assert.equal(calls.some(call => call.method === 'PUT'), true));
  assert.deepEqual(calls.find(call => call.method === 'PUT').body.permissions, ['VIEW_PROBLEMS']);
});

scenario('contest refresh grants and revokes take permission without retaining personal code', async () => {
  reset(); const {default: Page} = await load('GroupContest'); let permissions = withoutTake;
  globalThis.__groupApi = async (path, method = 'GET') => {if (path === '/api/groups/7') return {...group,permissions}; if (path.endsWith('/me')) return {submissions:[{id:4,problemNumber:1002,language:'PYTHON',status:'ACCEPTED',sourceCode:'private-granted-history'}]}; return standardContestApi(path, method);};
  const view = mount(Page, '/groups/7/contests/3', '/groups/:gid/contests/:cid'); fireEvent.click(await view.findByRole('button', {name:'1. 그룹 코테 문제'})); await view.findByText('진짜 시험 본문');
  assert.equal(view.getByRole('textbox', {name:'풀이 코드'}).getAttribute('aria-disabled'), 'true');
  permissions = allFlags; fireEvent.click(view.getByRole('button', {name:'새로고침'})); await view.findByText('private-granted-history');
  await waitFor(() => assert.notEqual(view.getByRole('textbox', {name:'풀이 코드'}).getAttribute('aria-disabled'), 'true')); act(()=>setEditorValue(view.getByRole('textbox', {name:'풀이 코드'}),'private-unsent-draft'));
  permissions = withoutTake; fireEvent.click(view.getByRole('button', {name:'새로고침'})); await view.findByText('시험 참여 권한이 없어 개인 답안을 표시하지 않습니다.');
  absent(view.queryByText('private-granted-history')); assert.doesNotMatch(view.container.textContent,/private-unsent-draft/);
  await waitFor(() => assert.equal(view.getByRole('textbox', {name:'풀이 코드'}).getAttribute('aria-disabled'), 'true'));
});

scenario('revoked take permission discards late personal history even if transport ignores abort', async () => {
  reset(); const {default: Page} = await load('GroupContest'); let permissions = allFlags, release, historySignal;
  globalThis.__groupApi = async (path, method = 'GET', body, options) => {if (path === '/api/groups/7') return {...group,permissions}; if (path.endsWith('/me')) {historySignal = options.signal; return new Promise(resolve => {release = resolve;});} return standardContestApi(path, method);};
  const view = mount(Page, '/groups/7/contests/3', '/groups/:gid/contests/:cid'); await waitFor(() => assert.equal(typeof release, 'function'));
  permissions = withoutTake; fireEvent.click(view.getByRole('button', {name:'새로고침'})); await view.findByText('시험 참여 권한이 없어 개인 답안을 표시하지 않습니다.'); assert.equal(historySignal.aborted, true);
  await act(async () => {release({submissions:[{id:4,problemNumber:1002,language:'PYTHON',status:'ACCEPTED',sourceCode:'late-private-history'}]}); await tick();}); absent(view.queryByText('late-private-history'));
});

test.after(() => {cleanup(); dom.window.close();});

scenario('public group preview shows the full introduction before joining without requesting private details', async () => {
  reset(); const {default: Page} = await load('Groups'), calls = [];
  const introduction = '매주 토요일에 함께 문제를 풉니다.\n초보자도 환영해요. '.repeat(15);
  globalThis.__groupApi = async (path, method = 'GET') => {
    calls.push({path, method});
    if (method === 'POST') return null;
    if (path === '/api/groups/mine') return [];
    return [{id: 5, groupName: '함께 공부', description: introduction, memberCount: 8}];
  };
  const view = mount(Page, '/groups', '/groups');
  const opener = await view.findByRole('button', {name: '함께 공부 미리보기'});
  opener.focus(); fireEvent.click(opener);
  const dialog = view.getByRole('dialog', {name: '함께 공부'});
  assert.equal(dialog.querySelector('.gp-preview-description').textContent, introduction);
  assert.equal(within(dialog).getByText('8명 참여').textContent, '8명 참여');
  assert.equal(calls.some(call => call.path === '/api/groups/5'), false);
  fireEvent.click(within(dialog).getByRole('button', {name: '닫기', exact: true}));
  absent(view.queryByRole('dialog')); assert.equal(document.activeElement === opener, true);
  fireEvent.click(opener);
  fireEvent.click(within(view.getByRole('dialog')).getByRole('button', {name: '가입', exact: true}));
  await view.findByText('destination-route');
  assert.equal(calls.filter(call => call.path === '/api/groups/5/join' && call.method === 'POST').length, 1);
});

scenario('guests can preview a public group while joining still requires login', async () => {
  reset(); globalThis.__groupAuth.user = null; const {default: Page} = await load('Groups');
  globalThis.__groupApi = async () => [{id: 5, groupName: '공개 소개', description: '가입 전에 볼 수 있는 소개', memberCount: 8}];
  const view = mount(Page, '/groups', '/groups');
  fireEvent.click(await view.findByRole('button', {name: '공개 소개 미리보기'}));
  const dialog = within(view.getByRole('dialog', {name: '공개 소개'}));
  assert.equal(dialog.getByRole('link', {name: '로그인 후 가입'}).getAttribute('href'), '/login');
  absent(dialog.queryByRole('button', {name: '가입', exact: true}));
});

scenario('my groups display the actual role including renamed built-in and custom roles', async () => {
  reset(); const {default: Page} = await load('Groups');
  globalThis.__groupApi = async path => path === '/api/groups/mine' ? [
    {id: 1, groupName: '직접 만든 그룹', roleKey: 'OWNER', roleName: '이름을 바꾼 소유자', memberCount: 3},
    {id: 2, groupName: '관리하는 그룹', roleKey: 'MANAGER', roleName: '관리자', memberCount: 8},
    {id: 3, groupName: '함께하는 그룹', roleKey: null, roleName: '스터디 리더', memberCount: 4},
  ] : [];
  const view = mount(Page, '/groups', '/groups'); await view.findByText('일반 그룹 3/3');
  fireEvent.click(view.getByRole('tab', {name: '내 그룹'}));
  assert.deepEqual([...view.container.querySelectorAll('.gp-role-badge')].map(node => node.textContent), ['그룹장', '관리자', '스터디 리더']);
  assert.equal(view.getAllByRole('link', {name: '그룹 열기'}).length, 3);
});

scenario('opening a group and returning preserves the tab, search and page even when route changes remount the page', async () => {
  reset(); const {default: Page} = await load('Groups'), {default: Detail} = await load('GroupDetail');
  const {useLocation} = require('react-router-dom');
  globalThis.__groupApi = async (path, method) => {
    if (path === '/api/groups/mine' || path.startsWith('/api/groups?')) return [{id: 7, groupName: '실제 그룹', memberCount: 2, roleKey: 'OWNER'}];
    return standardGroupApi(path, method);
  };
  function ResetOnLocation({component: Component}) {
    const location = useLocation();
    return React.createElement(React.Fragment, null, React.createElement('output', {'data-testid': 'location'}, location.pathname + location.search), React.createElement(Component, {key: location.key, dark: true}));
  }
  const view = render(React.createElement(MemoryRouter, {initialEntries: ['/groups?tab=mine&q=study&page=2']}, React.createElement(Routes, null,
    React.createElement(Route, {path: '/groups', element: React.createElement(ResetOnLocation, {component: Page})}),
    React.createElement(Route, {path: '/groups/:gid', element: React.createElement(ResetOnLocation, {component: Detail})}))));
  fireEvent.click(await view.findByRole('link', {name: '그룹 열기'}));
  const back = await view.findByRole('link', {name: '← 그룹 목록'});
  assert.equal(back.getAttribute('href'), '/groups?tab=mine&q=study&page=2');
  fireEvent.click(back);
  await view.findByRole('tab', {name: '내 그룹', selected: true});
  fireEvent.click(view.getByRole('tab', {name: '그룹 탐색'}));
  await view.findByRole('textbox', {name: '그룹 이름 검색'});
  assert.equal(view.getByRole('textbox', {name: '그룹 이름 검색'}).value, 'study');
  assert.equal(new URL(view.getByTestId('location').textContent, window.location.origin).searchParams.get('page'), '2');
});

scenario('creation explains both unmet conditions and offers useful destinations', async () => {
  reset(); globalThis.__groupAuth.user.solved_count = 38; const {default: Page} = await load('Groups');
  globalThis.__groupApi = async path => path === '/api/groups/mine' ? [1, 2, 3].map(id => ({id, groupName: '내 그룹 ' + id, memberCount: 2})) : [{id: 8, groupName: '가입할 그룹', memberCount: 2}];
  const view = mount(Page, '/groups', '/groups'); await view.findByText('일반 그룹 3/3');
  assert.equal(view.getByRole('button', {name: '가입', exact: true}).disabled, true);
  await view.findByText('일반 그룹은 최대 3개까지 가입할 수 있어요.');
  fireEvent.click(view.getByRole('button', {name: '그룹 만들기'}));
  const dialog = within(view.getByRole('dialog'));
  await dialog.findByText(/문제 12개를 더 풀면/); await dialog.findByText(/참여 중인 일반 그룹이 3개예요/);
  assert.equal(dialog.getByRole('link', {name: /문제 풀러 가기/}).getAttribute('href'), '/problems?solved=unsolved');
  fireEvent.click(dialog.getByRole('button', {name: /내 그룹 확인하기/}));
  absent(view.queryByRole('dialog')); assert.equal(view.getByRole('tab', {name: '내 그룹'}).getAttribute('aria-selected'), 'true');
});

scenario('failed eligibility lookup can be retried from inside the creation dialog', async () => {
  reset(); const {default: Page} = await load('Groups'); let fail = true;
  globalThis.__groupApi = async path => {if (path === '/api/groups/mine' && fail) throw new Error('가입 현황 오류'); return [];};
  const view = mount(Page, '/groups', '/groups'); await view.findByText('가입 현황 확인 불가');
  fireEvent.click(view.getByRole('button', {name: '그룹 만들기'}));
  const dialog = within(view.getByRole('dialog'));
  await dialog.findByText(/가입 현황을 불러오지 못해/); fail = false;
  fireEvent.click(dialog.getByRole('button', {name: '가입 현황 다시 확인'}));
  await waitFor(() => absent(dialog.queryByText(/가입 현황을 불러오지 못해/)));
  fireEvent.change(dialog.getByRole('textbox', {name: /그룹 이름/}), {target: {value: '다시 만들기'}});
  await waitFor(() => assert.equal(dialog.getByRole('button', {name: '그룹 만들기', exact: true}).disabled, false));
});

scenario('my group news uses server counts and schedules while public previews reveal no member activity', async () => {
  reset(); const {default: Page} = await load('Groups');
  const item = {id: 7, groupName: '새 소식 그룹', memberCount: 3, roleKey: 'MEMBER', activity: {windowDays: 7, recentProblemCount: 3, nextContest: {id: 4, title: '주간 코딩테스트', startsAt: '2030-12-20T01:00:00Z'}}};
  globalThis.__groupApi = async () => [item];
  const view = mount(Page, '/groups', '/groups');
  fireEvent.click(await view.findByRole('button', {name: '새 소식 그룹 미리보기'}));
  absent(within(view.getByRole('dialog')).queryByText(/최근 7일 새 문제/));
  fireEvent.click(within(view.getByRole('dialog')).getByRole('button', {name: '닫기', exact: true}));
  fireEvent.click(view.getByRole('tab', {name: '내 그룹'}));
  const news = view.getByRole('link', {name: /최근 7일 새 문제 3개/});
  assert.equal(news.getAttribute('href').endsWith('#group-problems'), true);
  const next = view.getByRole('link', {name: /예정 코딩테스트 · 주간 코딩테스트/});
  assert.equal(next.querySelector('time').getAttribute('datetime'), '2030-12-20T01:00:00Z');
  assert.equal(next.getAttribute('href').endsWith('#group-contests'), true);
});

scenario('news distinguishes no recent activity from unavailable problem visibility', async () => {
  reset(); const {default: Page} = await load('Groups');
  globalThis.__groupApi = async path => path === '/api/groups/mine' ? [
    {id: 1, groupName: '조용한 그룹', memberCount: 2, activity: {windowDays: 7, recentProblemCount: 0, nextContest: null}},
    {id: 2, groupName: '문제 권한 없는 그룹', memberCount: 2, activity: {windowDays: 7, recentProblemCount: null, nextContest: null}},
  ] : [];
  const view = mount(Page, '/groups?tab=mine', '/groups'); await view.findByText('조용한 그룹');
  assert.equal(view.getAllByText('최근 7일 새 문제 없음').length, 1);
  assert.equal(view.getAllByText('예정된 코딩테스트 없음').length, 2);
});

scenario('contest list preserves available groups on partial failure and retries the missing group', async () => {
  reset();
  const {default:Page} = await load('Contests');
  let fail=true,release;
  const calls=[];
  globalThis.__groupApi = async path => {
    calls.push(path);
    if (path === '/api/groups/mine') return [{id:7,groupName:'A'},{id:8,groupName:'B'}];
    if (path === '/api/groups/7/contests') return [contest];
    if (fail) return new Promise((resolve,reject)=>{release=()=>reject(Error('조회 실패'));});
    return [{...contest,title:'복구된 시험'}];
  };
  const view = mount(Page,'/contests','/contests');
  await view.findByRole('link',{name:/실제 코테/});
  assert.ok(view.getByRole('status'));
  await act(async()=>release());
  await view.findByRole('alert');
  assert.ok(view.getByRole('link',{name:/실제 코테/}));
  absent(view.queryByText(/상태의 시험이 없습니다/));
  fail = false;
  fireEvent.click(view.getByRole('button',{name:'다시 시도'}));
  await view.findByRole('link',{name:/복구된 시험/});
  absent(view.queryByRole('alert'));
  assert.deepEqual(calls,['/api/groups/mine','/api/groups/7/contests','/api/groups/8/contests','/api/groups/8/contests']);
});
