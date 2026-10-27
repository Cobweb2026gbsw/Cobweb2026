import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {build} from 'esbuild';
import {JSDOM} from 'jsdom';
import {installEditorDom,editorValue,setEditorValue} from './editor-dom.mjs';

const require = createRequire(import.meta.url), root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const dom = new JSDOM('<!doctype html><html><body></body></html>', {url: 'http://localhost:8080/'});
Object.defineProperty(globalThis, 'navigator', {value: dom.window.navigator, configurable: true});
for (const key of ['window', 'document', 'HTMLElement', 'Event', 'InputEvent', 'KeyboardEvent', 'MutationObserver', 'FormData']) globalThis[key] = dom.window[key];
installEditorDom(dom.window);
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
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
async function load(name) {
  if (modules.has(name)) return modules.get(name);
  const bundle = await build({entryPoints: [path.join(root, 'src/pages', name + '.jsx')], bundle: true, write: false, platform: 'node', format: 'cjs', packages: 'external', jsx: 'automatic', loader: {'.css': 'empty'}, plugins: [{name: 'group-contracts', setup(builder) {
    builder.onResolve({filter: /(?:^|\/)api(?:\.js)?$/}, () => ({path: 'api', namespace: 'contract'}));
    builder.onResolve({filter: /\/context\/AuthContext(?:\.jsx)?$/}, () => ({path: 'auth', namespace: 'contract'}));
    builder.onLoad({filter: /.*/, namespace: 'contract'}, args => ({contents: args.path === 'api' ? 'export const api=(...args)=>globalThis.__groupApi(...args);export const getSessionEpoch=()=>globalThis.__groupSession;' : 'export const useAuth=()=>globalThis.__groupAuth;'}));
  }}]});
  const module = {exports: {}};
  new Function('require', 'module', 'exports', bundle.outputFiles[0].text)(require, module, module.exports);
  modules.set(name, module.exports); return module.exports;
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
  fireEvent.change(view.container.querySelector('#group-name'), {target: {value: '새 실제 그룹'}});
  fireEvent.change(view.container.querySelector('#group-description'), {target: {value: '새 소개'}});
  fireEvent.click(view.getByRole('button', {name: '그룹 만들기'})); await view.findByText('destination-route');
  assert.deepEqual(calls.find(call => call.path === '/api/groups' && call.method === 'POST').body, {name: '새 실제 그룹', description: '새 소개'}); cleanup();
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
  reset(); const {ContestForm} = await load('GroupDetail'), calls = [], current = {...contest, status:'ENDED', startsAt:'2026-10-21T12:00:10.123456Z', endsAt:'2026-10-21T12:00:10.123457Z', problems:[{problemNumber:1002},{problemNumber:1001}]};
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
