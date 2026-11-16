const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/app.js'), 'utf8');
function section(start, end) {
  const from = source.indexOf(start);
  const to = source.indexOf(end, from);
  assert.ok(from >= 0 && to > from, 'app.js auth section changed');
  return source.slice(from, to);
}
const apiSource = section('async function api(', '\nasync function show(');
const logoutSource = section("$('#logout').onclick=", '\nfunction languageLabel(');

function reply(status, data = {}) {
  return {status, ok: status >= 200 && status < 300, json: async () => data};
}

function fixture(replies) {
  const calls = [];
  const nodes = {'#logout': {}, '#account': {textContent: '내 계정'}, '#notice': {textContent: ''},
    '#author-nav': {hidden: false}, '#admin-nav': {hidden: false}};
  let view = null, cleared = 0;
  const context = {
    document: {querySelector: selector => nodes[selector]},
    fetch: async (url, options) => {
      calls.push({url, options});
      assert.ok(replies.length, 'unexpected request: ' + url);
      return replies.shift();
    },
    show: async id => { view = id; nodes['#notice'].textContent = ''; },
    clearPrivateViews: () => { cleared++; }
  };
  vm.createContext(context);
  vm.runInContext(
    "let token='expired', permissions={canCreate:true,canManage:true}; let sessionEpoch=0, viewEpoch=0, loggingOut=false, permissionsRequest=0; const STALE_REQUEST_MESSAGE='__COBWEB_STALE_REQUEST__'; const $=s=>document.querySelector(s); function notice(text){$('#notice').textContent=text;} function currentSession(epoch){return epoch===sessionEpoch;} function staleRequest(){const error=Error(STALE_REQUEST_MESSAGE);error.stale=true;return error;} function currentView(){return true;}\n" +
    apiSource + '\n' + logoutSource + '\nthis.getToken=()=>token; this.callApi=api; this.getPermissions=()=>permissions;', context);
  return {calls, nodes, context, getView: () => view, getCleared: () => cleared};
}

async function main() {
  const recovered = fixture([reply(401), reply(200, {data: {accessToken: 'fresh'}}), reply(200, {data: null})]);
  await recovered.nodes['#logout'].onclick();
  assert.deepEqual(recovered.calls.map(c => c.url), ['/api/auth/logout', '/api/auth/reissue', '/api/auth/logout']);
  assert.equal(recovered.calls[0].options.headers.Authorization, 'Bearer expired');
  assert.equal(recovered.calls[2].options.headers.Authorization, 'Bearer fresh');
  assert.ok(recovered.calls.every(c => c.options.credentials === 'same-origin'));
  assert.equal(recovered.context.getToken(), null);
  assert.deepEqual(JSON.parse(JSON.stringify(recovered.context.getPermissions())), {canCreate: false, canManage: false});
  assert.equal(recovered.nodes['#author-nav'].hidden, true);
  assert.equal(recovered.nodes['#admin-nav'].hidden, true);
  assert.equal(recovered.getCleared(), 1);
  assert.equal(recovered.nodes['#account'].textContent, '로그인');
  assert.equal(recovered.getView(), 'auth');
  assert.equal(recovered.nodes['#notice'].textContent, '');

  const failed = fixture([reply(401), reply(401, {message: 'refresh expired'})]);
  await failed.nodes['#logout'].onclick();
  assert.deepEqual(failed.calls.map(c => c.url), ['/api/auth/logout', '/api/auth/reissue']);
  assert.equal(failed.context.getToken(), null);
  assert.equal(failed.nodes['#account'].textContent, '로그인');
  assert.equal(failed.getView(), 'auth');
  assert.match(failed.nodes['#notice'].textContent, /서버 로그아웃에 실패/);

  const forbidden = fixture([reply(403, {message: 'Forbidden'})]);
  await forbidden.nodes['#logout'].onclick();
  assert.deepEqual(forbidden.calls.map(c => c.url), ['/api/auth/logout']);
  assert.equal(forbidden.context.getToken(), null);
  assert.match(forbidden.nodes['#notice'].textContent, /서버 로그아웃에 실패/);

  const deniedAction = fixture([reply(403, {message: 'Forbidden'})]);
  await assert.rejects(deniedAction.context.callApi('/api/problems/1000/review', 'POST'), /Forbidden/);
  assert.deepEqual(deniedAction.calls.map(c => c.url), ['/api/problems/1000/review']);

  console.log('app auth flow: 4 cases passed');
}

main().catch(error => { console.error(error); process.exitCode = 1; });
