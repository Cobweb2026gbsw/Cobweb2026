import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {build} from 'esbuild';
import {JSDOM} from 'jsdom';
import {jwtFixture} from './jwt-fixture.mjs';

const require = createRequire(import.meta.url);
const dom = new JSDOM('<!doctype html><html><body></body></html>', {url: 'http://localhost:8080/login'});
Object.defineProperty(globalThis, 'navigator', {value: dom.window.navigator, configurable: true});
for (const name of ['window', 'document', 'HTMLElement', 'Event', 'MutationObserver']) globalThis[name] = dom.window[name];
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
const React = require('react'), {render, fireEvent, waitFor, act, cleanup} = require('@testing-library/react');
const {MemoryRouter,useLocation} = require('react-router-dom');
const bundle = await build({
  stdin: {contents: "export {default as Login} from './src/pages/Login.jsx'; export {default as AppRoutes} from './src/AppRoutes.jsx'; export {AuthProvider,useAuth} from './src/context/AuthContext.jsx'; export {api,beginSession,getAccessToken} from './src/lib/api.js';", resolveDir: process.cwd(), sourcefile: 'auth-test.jsx'},
  bundle: true, write: false, platform: 'node', format: 'cjs', packages: 'external', jsx: 'automatic', loader: {'.css': 'empty'},
  plugins: [{name:'unrelated-pages', setup(builder) {
    builder.onResolve({filter:/^\.\/pages\/[A-Za-z]+$/}, ({path}) =>
      path === './pages/Login' ? undefined : {path, namespace:'page-placeholder'});
    builder.onLoad({filter:/.*/, namespace:'page-placeholder'}, () => ({contents:'export default function Page() { return null; }'}));
  }}],
});
const module = {exports: {}};
new Function('require', 'module', 'exports', bundle.outputFiles[0].text)(require, module, module.exports);
const {AuthProvider, useAuth, Login, AppRoutes, api, beginSession, getAccessToken} = module.exports;
const deferred = () => {let resolve; const promise = new Promise(done => {resolve = done;}); return {promise, resolve};};
const response = (data, status = 200) => new Response(JSON.stringify({success: status < 400, data}), {status});
const tick = () => new Promise(resolve => setImmediate(resolve));
const tokenA = jwtFixture('1', 'restored-a'), tokenB = jwtFixture('2', 'login-b');

function Probe() {
  const auth = useAuth();
  return React.createElement(React.Fragment, null,
    React.createElement('output', {'data-testid': 'auth-state'}, `${auth.loading}:${auth.user?.username || 'anonymous'}`),
    // Context 자체도 초기 복구를 기다리는지 확인합니다. 실제 로그인 버튼은 그동안 비활성입니다.
    React.createElement('button', {onClick: () => {auth.login({username: 'account-b', password: 'test-password'}).catch(() => {});}}, 'provider-login'),
    React.createElement('button', {onClick: () => {api('/api/me', 'PATCH', {bio: 'A 계정 초안'}).catch(() => {});}}, 'save-current-draft'));
}
function mount() {
  beginSession(null);
  return render(React.createElement(React.StrictMode, null,
    React.createElement(MemoryRouter, {initialEntries: ['/login']},
      React.createElement(AuthProvider, null, React.createElement(Probe), React.createElement(Login, {dark: true, setDark: () => {}})))));
}

test('initial cookie restore blocks normal login and OAuth, including direct form submit', async () => {
  const restore = deferred(), calls = [];
  globalThis.fetch = async path => {calls.push(path); assert.equal(path, '/api/auth/reissue'); return restore.promise;};
  const view = mount();
  fireEvent.change(view.getByLabelText('아이디'), {target: {value: 'account-b'}});
  assert.equal(view.queryByLabelText('비밀번호'), null);
  assert.equal(view.getByRole('button', {name: '로그인 상태 확인 중…'}).disabled, true);
  for (const name of ['Google 로그인', 'GitHub 로그인', '네이버 로그인']) {
    const button = view.getByRole('button', {name}); assert.equal(button.disabled, true); fireEvent.click(button);
  }
  fireEvent.submit(view.getByLabelText('아이디').closest('form'));
  assert.deepEqual(calls, ['/api/auth/reissue']);
  await act(async () => {restore.resolve(response(null, 401)); await tick();});
  await waitFor(() => assert.equal(view.getByRole('button', {name: '다음', exact: true}).disabled, false));
  assert.deepEqual(calls, ['/api/auth/reissue']);
  cleanup();
});

test('a failed social logo shows the provider login text and keeps the same OAuth action', async () => {
  const calls = [];
  globalThis.fetch = async path => { calls.push(path); return response(null, 401); };
  const view = mount();
  try {
    const providers = [['google', 'Google'], ['github', 'GitHub'], ['naver', '네이버']];
    for (const [id, name] of providers) {
      const label = `${name} 로그인`;
      const button = view.getByRole('button', {name: label});
      await waitFor(() => assert.equal(button.disabled, false));
      assert.equal(button.textContent, '');
      fireEvent.error(button.querySelector('img'));
      assert.equal(button.textContent, label);
      assert.equal(button.querySelector('img'), null);
      fireEvent.click(button);
      await waitFor(() => assert.ok(calls.includes(`/api/auth/oauth/${id}/authorize-url`)));
    }
  } finally {
    cleanup();
  }
});

test('delayed account A restore finishes before account B login and cannot overwrite its cookie', async () => {
  const restore = deferred(), calls = [];
  // 브라우저는 fetch 응답을 돌려주기 전에 Set-Cookie를 적용합니다. 이 순서를 모사합니다.
  let cookie = 'account-a-old';
  globalThis.fetch = async (path, options = {}) => {
    calls.push(path);
    if (path === '/api/auth/reissue') {
      await restore.promise;
      cookie = 'account-a-rotated';
      return response({accessToken: tokenA});
    }
    if (path === '/api/auth/login') {cookie = 'account-b-new'; return response({accessToken: tokenB});}
    if (path === '/api/me') return response({username: options.headers.Authorization === `Bearer ${tokenB}` ? 'account-b' : 'account-a'});
    if (path === '/api/problems/permissions') return response({canCreate: false, canManage: false});
    throw new Error(path);
  };
  const view = mount();
  fireEvent.click(view.getByRole('button', {name: 'provider-login'}));
  await act(tick);
  assert.deepEqual(calls, ['/api/auth/reissue']);
  await act(async () => {restore.resolve(); await tick();});
  await waitFor(() => assert.equal(view.getByTestId('auth-state').textContent, 'false:account-b'));
  assert.equal(getAccessToken(), tokenB);
  assert.equal(cookie, 'account-b-new');
  assert.equal(calls.filter(path => path === '/api/auth/reissue').length, 1);
  assert.ok(calls.indexOf('/api/auth/login') > calls.indexOf('/api/me'));
  cleanup();
});

test('a different refresh subject clears rendered identity and explains why login is required', async () => {
  let restores = 0; const mutations = [];
  globalThis.fetch = async (path, options = {}) => {
    if (path === '/api/auth/reissue') return response({accessToken: ++restores === 1 ? tokenA : tokenB});
    if (path === '/api/me' && options.method === 'PATCH') {mutations.push(options.headers.Authorization); return response(null, 401);}
    if (path === '/api/me') return response({username: 'account-a'});
    if (path === '/api/problems/permissions') return response({canCreate: true, canManage: true});
    throw new Error(path);
  };
  const view = mount();
  await waitFor(() => assert.equal(view.getByTestId('auth-state').textContent, 'false:account-a'));
  fireEvent.click(view.getByRole('button', {name: 'save-current-draft'}));
  await waitFor(() => assert.equal(view.getByTestId('auth-state').textContent, 'false:anonymous'));
  assert.equal(getAccessToken(), null);
  assert.deepEqual(mutations, [`Bearer ${tokenA}`]);
  assert.match(view.getByRole('alert').textContent, /다시 로그인/);
  cleanup();
});

test('the real login route keeps a failed attempt visible and resumes the protected link after retry', async () => {
  let attempts = 0;
  globalThis.fetch = async path => {
    if (path === '/api/auth/reissue') return response(null, 401);
    if (path === '/api/auth/login') return ++attempts === 1 ? response(null, 401) : response({accessToken:tokenB});
    if (path === '/api/me') return response({username:'account-b'});
    if (path === '/api/problems/permissions') return response({canManage:false,canCreate:false});
    throw new Error(path);
  };
  function CurrentLocation() {
    const {pathname, search, hash} = useLocation();
    return React.createElement('output', {'data-testid':'location'}, pathname + search + hash);
  }
  beginSession(null);
  const target = '/dashboard?view=recent#activity';
  const view = render(React.createElement(MemoryRouter, {initialEntries:[target]},
    React.createElement(AuthProvider, null, React.createElement(CurrentLocation),
      React.createElement(AppRoutes, {dark:true,setDark(){}}))));
  const username = await view.findByLabelText('아이디');
  fireEvent.change(username, {target:{value:'account-b'}});
  fireEvent.keyDown(username, {key:'Enter'});
  const password = view.getByLabelText('비밀번호');
  assert.equal(document.activeElement, password);
  fireEvent.change(password, {target:{value:'sample-password'}});
  fireEvent.submit(password.form);
  await view.findByRole('alert');
  assert.equal(view.getByLabelText('비밀번호').value, 'sample-password');
  assert.equal(view.getByLabelText('아이디').value, 'account-b');
  fireEvent.click(view.getByRole('button', {name:'로그인',exact:true}));
  await waitFor(() => assert.equal(view.getByTestId('location').textContent, target));
  assert.equal(attempts, 2);
  cleanup();
});

test.after(async () => {cleanup(); await act(tick); dom.window.close();});
