import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {build} from 'esbuild';
import {JSDOM} from 'jsdom';
import {profileHref, rankingReturnPath} from '../src/lib/profileRoutes.js';

const require = createRequire(import.meta.url);
const dom = new JSDOM('<!doctype html><html><body></body></html>', {url: 'http://localhost:8080/'});
Object.defineProperty(globalThis, 'navigator', {value: dom.window.navigator, configurable: true});
for (const name of ['window', 'document', 'HTMLElement', 'Event', 'MutationObserver']) globalThis[name] = dom.window[name];
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
const React = require('react'), {render, waitFor, cleanup, act, fireEvent} = require('@testing-library/react');
const {MemoryRouter, Routes, Route} = require('react-router-dom');
const bundle = await build({
  stdin: {contents: "export {default as Profile} from './src/pages/Profile.jsx'; export {default as ProfileEdit} from './src/pages/ProfileEdit.jsx'; export {default as Ranking} from './src/pages/Ranking.jsx'; export {AuthProvider} from './src/context/AuthContext.jsx'; export {beginSession} from './src/lib/api.js';", resolveDir: process.cwd(), sourcefile: 'profile-test.jsx'},
  bundle: true, write: false, platform: 'node', format: 'cjs', packages: 'external', jsx: 'automatic', loader: {'.css': 'empty'},
});
const module = {exports: {}};
new Function('require', 'module', 'exports', bundle.outputFiles[0].text)(require, module, module.exports);
const {Profile, ProfileEdit, Ranking, AuthProvider, beginSession} = module.exports;
const response = (data, status = 200) => new Response(JSON.stringify({success: status < 400, data}), {status});
const profile = username => ({username, bio: '', github_url: '', rank_type: 'INITIATE', rank_int: 5, rank_points: 30, next_rank_points: 100, solved_count: 0, submit_count: 0, solvedProblems: [], topLanguages: []});

function mount(Page, path, pattern) {
  beginSession(null);
  return render(React.createElement(MemoryRouter, {initialEntries: [path]}, React.createElement(AuthProvider, null,
    React.createElement(Routes, null, React.createElement(Route, {path: pattern, element: React.createElement(Page, {dark: true})})))));
}

for (const username of ['..', 'a/b', '기존사용자']) {
  test(`public Profile reads exact query username ${username}`, async () => {
    const calls = [];
    globalThis.fetch = async path => {
      if (path === '/api/auth/reissue') return response(null, 401);
      const url = new URL(path, 'http://localhost:8080'); calls.push(url);
      assert.equal(url.pathname, '/api/profiles');
      assert.equal(url.searchParams.get('username'), username);
      return response(profile(username));
    };
    const view = mount(Profile, profileHref(username), '/users');
    await view.findByRole('heading', {name: username, exact: true});
    assert.equal(calls.length, 1);
    assert.ok(view.getByText('다음 승급까지 70점'));
    cleanup();
  });
}

test('legacy single-segment Profile screen uses the safe query API alias', async () => {
  globalThis.fetch = async path => {
    if (path === '/api/auth/reissue') return response(null, 401);
    assert.equal(path, '/api/profiles?username=plain-user');
    return response(profile('plain-user'));
  };
  const view = mount(Profile, '/users/plain-user', '/users/:nickname');
  await view.findByRole('heading', {name: 'plain-user', exact: true});
  assert.ok(view.getByText('공개 문제를 풀면 사용한 언어가 표시됩니다.'));
  cleanup();
});

test('public profile shows language labels and solved counts in ranked order', async () => {
  globalThis.fetch = async path => {
    if (path === '/api/auth/reissue') return response(null, 401);
    assert.equal(path, '/api/profiles?username=coder');
    return response({...profile('coder'), topLanguages: [
      {language: 'CPP', label: 'C++', solved_count: 12},
      {language: 'PYTHON', label: 'Python', solved_count: 8},
      {language: 'CSHARP', label: 'C#', solved_count: 2},
    ]});
  };
  const view = mount(Profile, profileHref('coder'), '/users');
  const section = await view.findByRole('region', {name: '자주 쓴 언어'});
  assert.deepEqual([...section.querySelectorAll('ol > li')].map(row => row.textContent),
    ['C++ 12문제', 'Python 8문제', 'C# 2문제']);
  cleanup();
});

test('profile edit saves the chosen login name and displays it on my profile', async () => {
  beginSession(null);
  const payload = Buffer.from(JSON.stringify({sub:'73'})).toString('base64url');
  let username = '이전이름';
  const writes = [];
  globalThis.fetch = async (path, options) => {
    if (path === '/api/auth/reissue') return response({accessToken:'eyJ.'+payload+'.sig'});
    if (path === '/api/problems/permissions') return response({});
    assert.equal(path, '/api/me');
    if (options.method === 'PATCH') {
      const edit = JSON.parse(options.body);
      writes.push(edit);
      username = edit.username;
      return response(null);
    }
    return response({...profile(username), id:'73', topLanguages: [{language:'JAVA', label:'Java', solved_count:1}]});
  };
  const view = render(React.createElement(MemoryRouter, {initialEntries:['/me/edit']},
    React.createElement(AuthProvider, null, React.createElement(Routes, null,
      React.createElement(Route, {path:'/me/edit', element:React.createElement(ProfileEdit)}),
      React.createElement(Route, {path:'/me', element:React.createElement(Profile,{isMe:true})})))));
  const input = await view.findByDisplayValue('이전이름');
  fireEvent.change(input, {target:{value:'   '}});
  assert.equal(view.getByRole('button', {name:'저장',exact:true}).disabled, true);
  fireEvent.change(input, {target:{value:'새이름'}});
  fireEvent.click(view.getByRole('button', {name:'저장',exact:true}));
  await view.findByRole('heading', {name:'새이름',exact:true});
  assert.equal(view.getByRole('listitem').textContent, 'Java 1문제');
  assert.deepEqual(writes, [{username:'새이름',bio:'',githubUrl:''}]);
  cleanup();
});

test('Ranking top cards and result rows preserve every public profile name in query links', async () => {
  const usernames = ['..', 'a/b', '기존사용자'];
  globalThis.fetch = async path => {
    if (path === '/api/auth/reissue') return response(null, 401);
    assert.equal(path, '/api/ranking?page=0');
    return response(usernames.map(profile));
  };
  const view = mount(Ranking, '/ranking', '/ranking');
  await waitFor(() => assert.equal(view.getAllByRole('link').length, 6));
  assert.equal(view.getAllByText('30점').length,3);
  for (const username of usernames)
    assert.equal(view.getAllByRole('link').filter(link => link.getAttribute('href') === profileHref(username)).length, 2);
  cleanup();
});

test.after(async () => {cleanup(); await act(async () => {await new Promise(resolve => setImmediate(resolve));}); dom.window.close();});

test('the profile return button restores the ranking page and locally filtered username', async () => {
  const calls = [];
  globalThis.fetch = async path => {
    if (path === '/api/auth/reissue') return response(null, 401);
    calls.push(path);
    if (path === '/api/ranking?page=0') return response(Array.from({length:30}, (_,i)=>profile('user'+i)));
    if (path === '/api/ranking?page=1') return response(['alice','bob'].map(profile));
    assert.equal(path, '/api/profiles?username=alice');
    return response(profile('alice'));
  };
  beginSession(null);
  const view = render(React.createElement(MemoryRouter, {initialEntries:['/ranking']},
    React.createElement(AuthProvider, null, React.createElement(Routes, null,
      React.createElement(Route, {path:'/ranking', element:React.createElement(Ranking)}),
      React.createElement(Route, {path:'/users', element:React.createElement(Profile)})))));
  const next = view.getByRole('button', {name:'다음'});
  await waitFor(()=>assert.equal(next.disabled, false));
  fireEvent.click(next);
  await view.findByText('alice');
  fireEvent.change(view.getByRole('textbox'), {target:{value:'ali'}});
  fireEvent.click(view.getByRole('link', {name:/alice/}));
  const back = await view.findByRole('link', {name:'랭킹으로 돌아가기'});
  assert.equal(back.getAttribute('href'), '/ranking?page=1&q=ali');
  await view.findByRole('heading', {name:'alice'});
  fireEvent.click(back);
  await view.findByText('alice');
  assert.equal(view.getByRole('textbox').value,'ali');
  assert.ok(view.getByText('2 페이지'));
  assert.equal(rankingReturnPath('//example.test/ranking'), null);
  assert.equal(rankingReturnPath('/ranking/../terms'), null);
  assert.equal(view.queryByText('bob'),null);
  assert.equal(calls.filter(path=>path.endsWith('page=1')).length, 2);
  cleanup();
});

test('ranking can go to the previous page after a failed request', async () => {
  const calls = [];
  globalThis.fetch = async path => {
    if (path === '/api/auth/reissue') return response(null, 401);
    calls.push(path);
    return path.endsWith('page=1') ? response(null, 503) : response([profile('alice')]);
  };
  const view = mount(Ranking, '/ranking?page=1&q=ali', '/ranking');
  await view.findByRole('alert');
  const previous = view.getByRole('button', {name:'이전'});
  assert.equal(previous.disabled, false);
  fireEvent.click(previous);
  await view.findByText('30점');
  assert.ok(view.getByText('1 페이지'));
  assert.equal(view.getByRole('textbox').value, 'ali');
  assert.deepEqual(calls, ['/api/ranking?page=1', '/api/ranking?page=0']);
  cleanup();
});
