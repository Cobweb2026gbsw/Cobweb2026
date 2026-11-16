import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';
import { JSDOM } from 'jsdom';

const require = createRequire(import.meta.url);
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost:8080/' });
for (const key of ['window', 'document', 'HTMLElement', 'Event']) globalThis[key] = dom.window[key];
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
const React = require('react');
const { render, fireEvent, cleanup } = require('@testing-library/react');
const { MemoryRouter, Route, Routes, useParams } = require('react-router-dom');
const bundle = await build({
  entryPoints: [fileURLToPath(new URL('../src/pages/GroupContestCreate.jsx', import.meta.url))],
  bundle: true, write: false, platform: 'node', format: 'cjs', packages: 'external',
  jsx: 'automatic', loader: { '.css': 'empty' }, plugins: [{
    name: 'contest-create-fixture',
    setup(builder) {
      builder.onResolve({ filter: /\/api(?:\.js)?$/ }, () => ({ path: 'api', namespace: 'fixture' }));
      builder.onResolve({ filter: /\/AuthContext(?:\.jsx)?$/ }, () => ({ path: 'auth', namespace: 'fixture' }));
      builder.onLoad({ filter: /.*/, namespace: 'fixture' }, ({ path }) => ({
        contents: path === 'api' ? 'export const api=(...args)=>globalThis.__contestCreateApi(...args);export const getSessionEpoch=()=>0;'
          : 'export const useAuth=()=>({epoch:0,user:{id:1}});',
      }));
    },
  }],
});
const module = { exports: {} };
new Function('require', 'module', 'exports', bundle.outputFiles[0].text)(require, module, module.exports);
const Page = module.exports.default;
const h = React.createElement;

function mount() {
  function Destination() {
    const { gid, cid } = useParams();
    return h('p', null, '이동 ' + gid + ':' + cid);
  }
  return render(h(MemoryRouter, { initialEntries: ['/groups/7/mkct'] },
    h(Routes, null,
      h(Route, { path: '/groups/:gid/mkct', element: h(Page, { dark: true }) }),
      h(Route, { path: '/groups/:gid/contests/:cid', element: h(Destination) }))));
}

test.afterEach(cleanup);
test.after(() => dom.window.close());

test('the dedicated creator checks group permissions and routes to the newly created contest', async () => {
  const calls = [];
  globalThis.__contestCreateApi = async (path, method = 'GET', body) => {
    calls.push({ path, method, body });
    if (path === '/api/groups/7' && method === 'GET')
      return { group: { groupName: '연습 그룹' }, permissions: ['CREATE_CONTESTS'] };
    if (path === '/api/groups/7/contests' && method === 'POST') return 19;
    throw new Error('Unexpected fixture request');
  };
  const screen = mount();
  await screen.findByText('연습 그룹');
  assert.equal(screen.getByRole('link', { name: '그룹으로' }).getAttribute('href'), '/groups/7');
  const start = '2030-06-12T13:30:00', end = '2030-06-12T15:30:00';
  for (const [label, value] of Object.entries({
    '제목': '연습 코딩 테스트', '시작 (내 시간대)': start, '종료 (내 시간대)': end,
  })) fireEvent.change(screen.getByLabelText(label), { target: { value } });
  fireEvent.change(screen.getByLabelText(/문제 번호/), { target: { value: '1002, 1001' } });
  fireEvent.click(screen.getByLabelText(/다시 보기 허용/));
  fireEvent.click(screen.getByRole('button', { name: '코테 생성' }));
  await screen.findByText('이동 7:19');
  assert.deepEqual(calls[1], {
    path: '/api/groups/7/contests', method: 'POST',
    body: {
      title: '연습 코딩 테스트', startsAt: new Date(start).toISOString(), endsAt: new Date(end).toISOString(),
      problemNumbers: [1002, 1001], replayAllowed: true, captureProtection: false, windowSwitchProtection: false,
    },
  });
});

test('opening the URL without create permission offers a way back and shows no creation form', async () => {
  const requests = [];
  globalThis.__contestCreateApi = async (path, method) => {
    requests.push([path, method]);
    return { group: { groupName: '권한 제한 그룹' }, permissions: ['MANAGE_CONTESTS'] };
  };
  const screen = mount();
  assert.equal((await screen.findByRole('alert')).textContent, '이 그룹에서 코테를 만들 권한이 없습니다.');
  assert.equal(screen.queryByRole('button', { name: '코테 생성' }), null);
  assert.equal(screen.getByRole('link', { name: '그룹으로' }).getAttribute('href'), '/groups/7');
  assert.deepEqual(requests, [['/api/groups/7', 'GET']]);
});
