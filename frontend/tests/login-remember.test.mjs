import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {build} from 'esbuild';
import {JSDOM} from 'jsdom';
import {jwtFixture} from './jwt-fixture.mjs';

const require = createRequire(import.meta.url);
const dom = new JSDOM('<!doctype html><html><body></body></html>', {url: 'https://192.0.2.8:8443/login'});
Object.defineProperty(globalThis, 'navigator', {value: dom.window.navigator, configurable: true});
for (const name of ['window', 'document', 'HTMLElement', 'Event', 'MutationObserver']) globalThis[name] = dom.window[name];
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
const React = require('react'), {render, fireEvent, waitFor, act, cleanup} = require('@testing-library/react');
const {MemoryRouter} = require('react-router-dom');
const bundle = await build({
  stdin: {contents: "export {default as Login} from './src/pages/Login.jsx'; export {AuthProvider} from './src/context/AuthContext.jsx'; export {beginSession,getAccessToken,logoutSession} from './src/lib/api.js'; export * from './src/lib/loginTransport.js';", resolveDir: process.cwd(), sourcefile: 'remember-test.jsx'},
  bundle: true, write: false, platform: 'node', format: 'cjs', packages: 'external', jsx: 'automatic', loader: {'.css': 'empty'},
});
const module = {exports: {}};
new Function('require', 'module', 'exports', bundle.outputFiles[0].text)(require, module, module.exports);
const {AuthProvider, Login, beginSession, getAccessToken, logoutSession, requiresHttpsLogin, httpsLoginUrl} = module.exports;
const response = (data, status = 200) => new Response(JSON.stringify({success: status < 400, data}), {status});
const tick = () => new Promise(resolve => setImmediate(resolve));
const token = jwtFixture('7', 'remember-login');

function mount(url = 'https://192.0.2.8:8443/login') {
  dom.reconfigure({url});
  beginSession(null);
  return render(React.createElement(MemoryRouter, {initialEntries: ['/login']},
    React.createElement(AuthProvider, null, React.createElement(Login, {dark: true, setDark: () => {}}))));
}

test('HTTPS and exact loopback exceptions are accepted, ordinary HTTP hosts are not', () => {
  for (const url of ['https://192.0.2.8:8443/login', 'http://localhost:8080/login', 'http://127.0.0.1:8080/login', 'http://[::1]:8080/login']) {
    assert.equal(requiresHttpsLogin(new URL(url)), false, url);
  }
  for (const url of ['http://192.168.0.8:8080/login', 'http://example.com/login', 'http://localhost.evil.example/login', 'http://127.0.0.2/login', 'file:///login']) {
    assert.equal(requiresHttpsLogin(new URL(url)), true, url);
  }
});

test('HTTPS link keeps the exact current host, uses only the configured port, and drops query/hash', () => {
  assert.equal(httpsLoginUrl(new URL('http://192.0.2.8:8080/login?accessToken=not-carried#secret'), 18443), 'https://192.0.2.8:18443/login');
  assert.equal(httpsLoginUrl(new URL('http://[2001:db8::1]:8080/login'), 8443), 'https://[2001:db8::1]:8443/login');
  for (const port of [null, undefined, 0, -1, 65536, '8443', 'https://evil.example']) {
    assert.equal(httpsLoginUrl(new URL('http://192.0.2.8:8080/login'), port), null);
  }
  assert.equal(httpsLoginUrl(new URL('http://name:password@192.0.2.8/login'), 8443), null);
});

for (const remember of [false, true]) test(`password login sends rememberMe=${remember} without persisting tokens in browser storage`, async () => {
  let submitted;
  globalThis.fetch = async (path, options = {}) => {
    assert.equal(options.credentials, 'same-origin');
    if (path === '/api/auth/reissue') return response(null, 401);
    if (path === '/api/auth/login') {submitted = JSON.parse(options.body); return response({accessToken: token});}
    if (path === '/api/me') return response({username: 'sample-user'});
    if (path === '/api/problems/permissions') return response({canCreate: false, canManage: false});
    throw new Error(path);
  };
  const view = mount();
  await waitFor(() => assert.equal(view.getByRole('button', {name: '로그인', exact: true}).disabled, false));
  const checkbox = view.getByRole('checkbox', {name: '로그인 유지'});
  assert.equal(checkbox.checked, false);
  assert.equal(document.getElementById(checkbox.getAttribute('aria-describedby')).textContent, '일반 로그인에 적용됩니다.');
  if (remember) fireEvent.click(checkbox);
  fireEvent.change(view.getByLabelText('아이디'), {target: {value: 'sample-user'}});
  fireEvent.change(view.getByLabelText('비밀번호'), {target: {value: 'test-password'}});
  fireEvent.submit(view.getByLabelText('아이디').closest('form'));
  await waitFor(() => assert.equal(getAccessToken(), token));
  await act(tick);
  assert.deepEqual(submitted, {username: 'sample-user', password: 'test-password', rememberMe: remember});
  assert.equal(window.localStorage.length, 0);
  assert.equal(window.sessionStorage.length, 0);
  cleanup();
});

test('HTTP IP blocks password and OAuth requests and offers only the same-host configured HTTPS link', async () => {
  const calls = [];
  globalThis.fetch = async path => {
    calls.push(path);
    if (path === '/api/auth/reissue') return response(null, 401);
    if (path === '/api/auth/login-options') return response({secureCookieRequired: true, httpsPort: 18443});
    throw new Error(path);
  };
  const view = mount('http://192.0.2.8:18080/login?next=ignored');
  await waitFor(() => assert.equal(view.getByRole('button', {name: '로그인', exact: true}).disabled, false));
  await waitFor(() => assert.equal(view.getByRole('link', {name: 'HTTPS로 로그인하기'}).href, 'https://192.0.2.8:18443/login'));
  fireEvent.change(view.getByLabelText('아이디'), {target: {value: 'sample-user'}});
  fireEvent.change(view.getByLabelText('비밀번호'), {target: {value: 'test-password'}});
  fireEvent.submit(view.getByLabelText('아이디').closest('form'));
  assert.match(view.getByRole('alert').textContent, /HTTPS/);
  fireEvent.click(view.getByRole('button', {name: 'Google 로그인'}));
  assert.match(view.getByRole('alert').textContent, /HTTPS/);
  assert.deepEqual(calls.sort(), ['/api/auth/login-options', '/api/auth/reissue']);
  cleanup();
});

test('missing local HTTPS configuration does not invent a port or hide the secure-login requirement', async () => {
  globalThis.fetch = async path => path === '/api/auth/reissue' ? response(null, 401) : response({secureCookieRequired: true, httpsPort: null});
  const view = mount('http://192.0.2.8:8080/login');
  await waitFor(() => assert.equal(view.getByRole('button', {name: '로그인', exact: true}).disabled, false));
  assert.equal(view.queryByRole('link', {name: 'HTTPS로 로그인하기'}), null);
  assert.match(view.getByRole('status').textContent, /서버의 HTTPS 주소/);
  cleanup();
});

test('HTTPS IP restores identity from its cookie without any localStorage access token', async () => {
  const calls = [];
  globalThis.fetch = async path => {
    calls.push(path);
    if (path === '/api/auth/reissue') return response({accessToken: token});
    if (path === '/api/me') return response({username: 'sample-user'});
    if (path === '/api/problems/permissions') return response({canCreate: false, canManage: false});
    if (path === '/api/auth/logout') return response(null);
    throw new Error(path);
  };
  mount();
  await waitFor(() => assert.equal(getAccessToken(), token));
  await act(tick);
  assert.equal(calls.filter(path => path === '/api/auth/reissue').length, 1);
  assert.equal(calls.includes('/api/auth/login-options'), false);
  assert.equal(window.localStorage.length, 0);
  await act(async () => {await logoutSession();});
  assert.equal(getAccessToken(), null);
  cleanup();
});

test.after(async () => {cleanup(); await act(tick); dom.window.close();});
