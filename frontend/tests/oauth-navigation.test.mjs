import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createRequire} from 'node:module';
import {build} from 'esbuild';
import {JSDOM} from 'jsdom';
import {oauthNavigationUrl, navigateOAuth} from '../src/lib/oauthTransport.js';
import {ProtocolError} from '../src/lib/errors.js';

const accepted = [
  'https://accounts.google.com/o/oauth2/v2/auth?state=fixture-only',
  'https://login.example.invalid/api/auth/oauth/github/start',
  'https://192.0.2.8:8443/api/auth/oauth/naver/start',
  'http://localhost:8080/api/auth/oauth/google/start',
  'http://127.0.0.1:8080/api/auth/oauth/google/start',
  'http://[::1]:8080/api/auth/oauth/google/start',
];
const rejected = [
  null, undefined, {}, '', '/api/auth/oauth/google/start', '//login.example.invalid/start',
  'javascript:alert(1)', 'data:text/html,fixture', 'file:///login', 'ftp://login.example.invalid/',
  'http://192.0.2.8:8080/api/auth/oauth/google/start', 'http://localhost.evil.example/start',
  'https://name:password@login.example.invalid/start', 'https://name@login.example.invalid/start',
  'https://name%40login.example.invalid@evil.example/start',
  ' https://login.example.invalid/start', 'https://login.example.invalid/with space',
  'https://login.example.invalid/\nstart', 'https://login.example.invalid\\@evil.example/start',
];

test('OAuth destinations allow provider URLs and explicit canonical top-level start handoffs', () => {
  for (const value of accepted) assert.equal(oauthNavigationUrl(value), new URL(value).href);
});

test('OAuth destinations reject credentials, unsafe protocols and non-loopback HTTP without echoing them', () => {
  for (const value of rejected) {
    assert.throws(() => oauthNavigationUrl(value), error => {
      assert.ok(error instanceof ProtocolError);
      assert.equal(error.message, '인증 주소가 올바르지 않습니다.');
      return true;
    }, String(value));
  }
});

test('a canonical handoff uses full navigation rather than a cross-origin fetch or invented state cookie', () => {
  const destinations = [];
  const canonical = 'https://login.example.invalid/api/auth/oauth/google/start';
  assert.equal(navigateOAuth(canonical, value => destinations.push(value)), canonical);
  assert.deepEqual(destinations, [canonical]);
  assert.throws(() => navigateOAuth('javascript:fixture', value => destinations.push(value)), ProtocolError);
  assert.deepEqual(destinations, [canonical]);
});

const html = readFileSync(new URL('../../src/main/resources/static/index.html', import.meta.url), 'utf8');
const legacy = readFileSync(new URL('../../src/main/resources/static/app.js', import.meta.url), 'utf8');
const tick = () => new Promise(resolve => setImmediate(resolve));

async function legacyPage(url, authorizeUrl = 'javascript:fixture') {
  const dom = new JSDOM(html, {url, runScripts: 'outside-only'});
  const calls = [];
  dom.window.CobwebGroups = {init() {}, leaveContest() {}};
  dom.window.CobwebCommunity = {init() {}};
  dom.window.fetch = async (path, options = {}) => {
    calls.push({path, options});
    if (path === '/api/auth/reissue') return {ok: false, status: 401, json: async () => ({success: false, data: null})};
    const data = path.includes('/authorize-url') ? {authorizeUrl} : [];
    return {ok: true, status: 200, json: async () => ({success: true, data})};
  };
  dom.window.eval(legacy);
  await tick();
  return {dom, calls};
}

test('legacy OAuth cannot request state over an HTTP LAN IP', async () => {
  const {dom, calls} = await legacyPage('http://192.0.2.8:8080/legacy');
  try {
    await dom.window.document.querySelector('[data-oauth="google"]').onclick();
    assert.equal(calls.filter(call => call.path.includes('/authorize-url')).length, 0);
    assert.match(dom.window.document.getElementById('notice').textContent, /HTTPS/);
  } finally {dom.window.close();}
});

test('legacy OAuth validates the same destinations and keeps authorize requests same-origin', async () => {
  const {dom, calls} = await legacyPage('https://192.0.2.8:8443/legacy');
  try {
    for (const value of accepted) assert.equal(dom.window.legacyOAuthNavigationUrl(value), oauthNavigationUrl(value));
    for (const value of rejected) assert.throws(() => dom.window.legacyOAuthNavigationUrl(value));
    await dom.window.document.querySelector('[data-oauth="google"]').onclick();
    const oauth = calls.filter(call => call.path.includes('/authorize-url'));
    assert.equal(oauth.length, 1);
    assert.equal(oauth[0].options.credentials, 'same-origin');
    assert.equal(dom.window.location.href, 'https://192.0.2.8:8443/legacy');
    assert.equal(dom.window.document.getElementById('notice').textContent, '인증 주소가 올바르지 않습니다.');
    assert.equal(dom.window.document.querySelector('[data-oauth="google"]').disabled, false);
  } finally {dom.window.close();}
});

const require = createRequire(import.meta.url);
const dom = new JSDOM('<!doctype html><html><body></body></html>', {url: 'https://192.0.2.8:8443/login'});
Object.defineProperty(globalThis, 'navigator', {value: dom.window.navigator, configurable: true});
for (const name of ['window', 'document', 'HTMLElement', 'Event', 'MutationObserver']) globalThis[name] = dom.window[name];
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
const React = require('react'), {render, fireEvent, waitFor, act, cleanup} = require('@testing-library/react');
const {MemoryRouter} = require('react-router-dom');
const bundled = await build({
  stdin: {contents: "export {default as Login} from './src/pages/Login.jsx'; export {AuthProvider} from './src/context/AuthContext.jsx'; export {beginSession} from './src/lib/api.js';", resolveDir: process.cwd(), sourcefile: 'oauth-navigation-test.jsx'},
  bundle: true, write: false, platform: 'node', format: 'cjs', packages: 'external', jsx: 'automatic', loader: {'.css': 'empty'},
});
const module = {exports: {}};
new Function('require', 'module', 'exports', bundled.outputFiles[0].text)(require, module, module.exports);
const {Login, AuthProvider, beginSession} = module.exports;
const response = (data, status = 200) => new Response(JSON.stringify({success: status < 400, data}), {status});

test('React OAuth rejects an unsafe server destination without navigating or exposing it', async () => {
  const calls = [];
  globalThis.fetch = async (path, options = {}) => {
    calls.push({path, options});
    if (path === '/api/auth/reissue') return response(null, 401);
    if (path === '/api/auth/oauth/google/authorize-url') return response({authorizeUrl: 'https://name:private-fixture@login.example.invalid/start'});
    throw new Error(path);
  };
  beginSession(null);
  const view = render(React.createElement(MemoryRouter, {initialEntries: ['/login']},
    React.createElement(AuthProvider, null, React.createElement(Login, {dark: true, setDark() {}}))));
  await waitFor(() => assert.equal(view.getByRole('button', {name: 'Google 로그인'}).disabled, false));
  fireEvent.click(view.getByRole('button', {name: 'Google 로그인'}));
  await waitFor(() => assert.equal(view.getByRole('alert').textContent, '인증 주소가 올바르지 않습니다.'));
  assert.equal(view.getByRole('button', {name: 'Google 로그인'}).disabled, false);
  assert.equal(window.location.href, 'https://192.0.2.8:8443/login');
  assert.equal(calls.find(call => call.path.includes('/authorize-url')).options.credentials, 'same-origin');
  assert.equal(view.container.textContent.includes('private-fixture'), false);
  cleanup();
});

test('React shows fixed recovery guidance after an OAuth account conflict redirect', async () => {
  const calls = [];
  globalThis.fetch = async path => {
    calls.push(path);
    assert.equal(path, '/api/auth/reissue');
    return response(null, 401);
  };
  beginSession(null);
  const view = render(React.createElement(MemoryRouter, {initialEntries: ['/login?oauthError=account-conflict']},
    React.createElement(AuthProvider, null, React.createElement(Login, {dark: true, setDark() {}}))));
  await waitFor(() => assert.equal(view.getByRole('button', {name: '다음', exact: true}).disabled, false));
  assert.match(view.getByRole('alert').textContent, /처음 가입할 때 사용한 방식으로 로그인/);
  assert.equal(view.getByRole('link', {name: '비밀번호 찾기'}).getAttribute('href'), '/password-reset');
  assert.equal(view.getByRole('button', {name: 'Google 로그인'}).disabled, false);
  assert.deepEqual(calls, ['/api/auth/reissue']);
  cleanup();
});

test('React ignores unknown and repeated OAuth error parameters without reflecting their values', async () => {
  globalThis.fetch = async path => {
    assert.equal(path, '/api/auth/reissue');
    return response(null, 401);
  };
  for (const query of ['oauthError=private-fixture', 'oauthError=account-conflict&oauthError=private-fixture']) {
    beginSession(null);
    const view = render(React.createElement(MemoryRouter, {initialEntries: ['/login?' + query]},
      React.createElement(AuthProvider, null, React.createElement(Login, {dark: false, setDark() {}}))));
    await waitFor(() => assert.equal(view.getByRole('button', {name: '다음', exact: true}).disabled, false));
    assert.equal(view.queryByRole('alert'), null);
    assert.equal(view.container.textContent.includes('private-fixture'), false);
    cleanup();
  }
});

test.after(async () => {cleanup(); await act(tick); dom.window.close();});
