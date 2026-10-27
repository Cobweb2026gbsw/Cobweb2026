import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {build} from 'esbuild';
import {JSDOM} from 'jsdom';
import {profileHref} from '../src/lib/profileRoutes.js';

const require = createRequire(import.meta.url);
const dom = new JSDOM('<!doctype html><html><body></body></html>', {url: 'http://localhost:8080/'});
Object.defineProperty(globalThis, 'navigator', {value: dom.window.navigator, configurable: true});
for (const name of ['window', 'document', 'HTMLElement', 'Event', 'MutationObserver']) globalThis[name] = dom.window[name];
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
const React = require('react'), {render, waitFor, cleanup, act} = require('@testing-library/react');
const {MemoryRouter, Routes, Route} = require('react-router-dom');
const bundle = await build({
  stdin: {contents: "export {default as Profile} from './src/pages/Profile.jsx'; export {default as Ranking} from './src/pages/Ranking.jsx'; export {AuthProvider} from './src/context/AuthContext.jsx'; export {beginSession} from './src/lib/api.js';", resolveDir: process.cwd(), sourcefile: 'profile-test.jsx'},
  bundle: true, write: false, platform: 'node', format: 'cjs', packages: 'external', jsx: 'automatic', loader: {'.css': 'empty'},
});
const module = {exports: {}};
new Function('require', 'module', 'exports', bundle.outputFiles[0].text)(require, module, module.exports);
const {Profile, Ranking, AuthProvider, beginSession} = module.exports;
const response = (data, status = 200) => new Response(JSON.stringify({success: status < 400, data}), {status});
const profile = username => ({username, bio: '', github_url: '', rank_type: 'INITIATE', rank_int: 5, solved_count: 0, submit_count: 0, solvedProblems: []});

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
  for (const username of usernames)
    assert.equal(view.getAllByRole('link').filter(link => link.getAttribute('href') === profileHref(username)).length, 2);
  cleanup();
});

test.after(async () => {cleanup(); await act(async () => {await new Promise(resolve => setImmediate(resolve));}); dom.window.close();});
