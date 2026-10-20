const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/app.js'), 'utf8');
function section(start, end) {
  const from = source.indexOf(start), to = source.indexOf(end, from);
  assert.ok(from >= 0 && to > from, 'app.js section changed: ' + start);
  return source.slice(from, to);
}
function deferred() {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return {promise, resolve};
}
function reply(status, data = {}) {
  return {status, ok: status >= 200 && status < 300, json: async () => data};
}
class FakeNode {
  constructor() { this.children = []; this.textContent = ''; this.value = ''; this.hidden = false; }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.children = [...children]; this.textContent = ''; }
  reset() { this.value = ''; }
}
function nodes() {
  const map = new Map();
  const get = selector => {
    if(!map.has(selector))map.set(selector, new FakeNode());
    return map.get(selector);
  };
  return {get, map};
}
function evaluate(prefix, code, globals = {}) {
  const context = vm.createContext(globals);
  vm.runInContext(prefix + '\n' + code, context);
  return context;
}
const guards = section('const STALE_REQUEST_MESSAGE=', 'async function api(');

async function testLateReissueCannotReplaceNewSession() {
  const refresh = deferred(), refreshStarted = deferred(), calls = [];
  const context = evaluate("let token='account-A', sessionEpoch=1, viewEpoch=0;", guards + section('async function api(', 'function setPermissions('), {
    document: {querySelector: () => new FakeNode()},
    fetch: async url => {
      calls.push(url);
      if(url === '/api/private')return reply(401);
      if(url === '/api/auth/reissue'){refreshStarted.resolve();return refresh.promise;}
      throw Error('unexpected fetch: ' + url);
    }
  });
  const oldRequest = context.api('/api/private');
  await refreshStarted.promise;
  vm.runInContext("sessionEpoch++;token='account-B'", context);
  refresh.resolve(reply(200, {data: {accessToken: 'account-A-new'}}));
  await assert.rejects(oldRequest, error => error.stale === true);
  assert.equal(vm.runInContext('token', context), 'account-B');
  assert.deepEqual(calls, ['/api/private', '/api/auth/reissue']);
}

async function testOldPermissionsCannotRestoreAdminNav() {
  const {get} = nodes(), response = deferred();
  get('#author-nav').hidden = true;
  get('#admin-nav').hidden = true;
  const context = evaluate(
    "let token='account-A',sessionEpoch=1,permissionsRequest=0,permissions={canCreate:false,canManage:false}; const $=s=>document.querySelector(s); function currentSession(epoch){return epoch===sessionEpoch;}",
    section('function setPermissions(', 'async function show('),
    {document: {querySelector: get}, api: () => response.promise}
  );
  const oldPermissions = context.refreshPermissions();
  vm.runInContext("sessionEpoch++;token='account-B'", context);
  response.resolve({canCreate: true, canManage: true});
  await oldPermissions;
  assert.equal(get('#author-nav').hidden, true);
  assert.equal(get('#admin-nav').hidden, true);
}

function testPrivateStateCleanup() {
  const {get} = nodes();
  get('#source').value = 'PRIVATE_SOURCE_MARKER';
  get('#profile-info').textContent = 'PRIVATE_PROFILE_MARKER';
  get('#profile-form [name=bio]').value = 'PRIVATE_BIO_MARKER';
  get('#login [name=username]').value = 'ACCOUNT_A_MARKER';
  get('#login [name=password]').value = 'PASSWORD_MARKER';
  get('#reset-confirm [name=newPassword]').value = 'RESET_PASSWORD_MARKER';
  get('#login').reset = () => {
    get('#login [name=username]').value = '';
    get('#login [name=password]').value = '';
  };
  get('#profile-form').reset = () => { get('#profile-form [name=bio]').value = ''; };
  get('#reset-confirm').reset = () => { get('#reset-confirm [name=newPassword]').value = ''; };
  get('#admin-preview').append(new FakeNode());
  const context = evaluate(
    "let sessionEpoch=1,viewEpoch=2,permissionsRequest=0,problemListRequest=0,problemRequest=0,mineRequest=0,adminListRequest=0,editorRequest=0,adminProblemRequest=0,groupDestinationsRequest=0,pollRequest=0,polling=null,resetToken='RESET_TOKEN_MARKER',currentProblem=1000,executionMode='FUNCTION',editorLoaded=true,editorCanSave=false,editorCanReview=true,adminProblem={},minePage=2,adminPage=3; const $=s=>document.querySelector(s);",
    section('function clearPrivateViews(){', 'function setGroupDestinationOption('),
    {document: {querySelector: get}, CobwebGroups: {clear() {}}, CobwebCommunity: {clear() {}}, setPermissions() {}, clearTimeout}
  );
  context.clearPrivateViews();
  assert.equal(get('#source').value, '');
  assert.equal(get('#profile-info').textContent, '');
  assert.equal(get('#profile-form [name=bio]').value, '');
  assert.equal(get('#login [name=username]').value, '');
  assert.equal(get('#login [name=password]').value, '');
  assert.equal(get('#reset-confirm [name=newPassword]').value, '');
  assert.equal(get('#admin-preview').children.length, 0);
  assert.equal(vm.runInContext('resetToken', context), null);
  assert.equal(vm.runInContext('currentProblem', context), null);
}

async function testLatePrivateListAndDetail() {
  const {get} = nodes(), mine = deferred(), editor = deferred(), admin = deferred(), adminList = deferred();
  get('#author').hidden = false;
  get('#admin').hidden = false;
  const opened = [];
  const context = evaluate(
    "let sessionEpoch=1,viewEpoch=5,mineRequest=0,minePage=0,adminListRequest=0,adminPage=0,editorRequest=0,adminProblemRequest=0,permissions={canCreate:true,canManage:true}; const $=s=>document.querySelector(s);",
    section('function currentSession(', 'async function api(') +
      section('function problemNumber(item){', 'async function listAdmin(){') +
      section('async function listAdmin(){', 'function addReviewSection(') +
      section('async function openEditor(', 'async function startGroupProblem(') +
      section('async function openAdminProblem(', "$('#new-problem').onclick="),
    {document: {querySelector: get, createElement: () => new FakeNode()}, URLSearchParams,
      FormData: class { *[Symbol.iterator]() { yield ['search',''];yield ['status',''];yield ['visibility','']; } },
      api: url => url.startsWith('/api/problems/mine?') ? mine.promise :
        url.startsWith('/api/admin/problems?') ? adminList.promise :
        url.startsWith('/api/admin/problems/') ? admin.promise : editor.promise,
      loadEditorData: value => opened.push(['editor', value]), renderAdminProblem: value => opened.push(['admin', value]),
      show: async () => 6, notice() {}}
  );
  get('#mine-search [name=search]').value = '';
  const oldMine = context.listMine();
  vm.runInContext('sessionEpoch++', context);
  get('#mine-list').replaceChildren();
  mine.resolve([{problem_number: 7, problem_title: 'PRIVATE_DRAFT_MARKER'}]);
  await oldMine;
  assert.equal(get('#mine-list').children.length, 0);

  const oldEditor = context.openEditor(7);
  vm.runInContext('viewEpoch++', context);
  editor.resolve({number: 7, tests: [{expected: 'PRIVATE_TEST_MARKER'}]});
  await oldEditor;

  const oldAdmin = context.openAdminProblem(7);
  vm.runInContext('sessionEpoch++', context);
  admin.resolve({number: 7, tests: [{expected: 'PRIVATE_ADMIN_MARKER'}]});
  await oldAdmin;

  const oldAdminList = context.listAdmin();
  vm.runInContext('sessionEpoch++', context);
  adminList.resolve([{problem_number: 7, problem_title: 'PRIVATE_ADMIN_LIST_MARKER'}]);
  await oldAdminList;
  assert.equal(get('#admin-list').children.length, 0);
  assert.deepEqual(opened, []);
}

async function testViewChangeDiscardsProblemProfileAndSubmissions() {
  const {get} = nodes(), problem = deferred(), profile = deferred(), submissions = deferred();
  const viewNodes = ['problems','problem','profile','submissions','auth'].map(id => {
    const item = get('#' + id);item.id = id;item.hidden = true;return item;
  });
  const document = {querySelector: get, querySelectorAll: selector => selector === '.view' ? viewNodes : [], createElement: () => new FakeNode()};
  const shown = [];
  const context = evaluate(
    "let sessionEpoch=1,viewEpoch=3,problemRequest=0,problemListRequest=0,pollRequest=0,polling=null,permissions={canCreate:true,canManage:true},token='account-A',currentProblem=null,languages=[],executionMode='STDIN'; const $=s=>document.querySelector(s); function notice(text){$('#notice').textContent=text;}",
    section('function currentSession(', 'async function api(') + section('async function show(', 'function rows(') + section('async function openProblem(', 'function form('),
    {document, clearTimeout, CobwebGroups: {leaveContest() {}}, CobwebCommunity: {open: async () => {}},
      api: url => url.startsWith('/api/problems/') ? problem.promise : url === '/api/me' ? profile.promise : submissions.promise,
      list: async () => {}, rows: (selector, data) => shown.push([selector, data]), updateLanguageHelp() {}}
  );
  const oldProblem = context.openProblem(7);
  await context.show('auth');
  problem.resolve({problem_title: 'PRIVATE_GROUP_MARKER', examples: [], languages: []});
  await oldProblem;
  assert.equal(get('#problem-title').textContent, '');
  assert.equal(viewNodes.find(item => item.id === 'auth').hidden, false);

  const oldProfile = context.show('profile');
  await context.show('auth');
  profile.resolve({username: 'ACCOUNT_A_MARKER', bio: 'PRIVATE_BIO_MARKER'});
  await oldProfile;
  assert.equal(get('#profile-info').textContent, '');

  const oldSubmissions = context.show('submissions');
  await context.show('auth');
  submissions.resolve([{problem_number: 7, problem_title: 'PRIVATE_SUBMISSION_MARKER'}]);
  await oldSubmissions;
  assert.deepEqual(shown, []);
}

async function testLatePollDoesNotUpdateNewView() {
  const {get} = nodes(), result = deferred();
  get('#problem').hidden = false;
  const context = evaluate(
    "let sessionEpoch=1,viewEpoch=3,pollRequest=4,polling=null; const $=s=>document.querySelector(s); function notice(){}",
    section('function currentSession(', 'async function api(') + section('async function poll(', 'const MAX_EXAMPLES='),
    {document: {querySelector: get}, api: () => result.promise, languageLabel: value => value, setTimeout}
  );
  const oldPoll = context.poll(7, 4, 1, 3);
  vm.runInContext('viewEpoch++;pollRequest++', context);
  get('#problem').hidden = true;
  result.resolve({language: 'PYTHON', status: 'ACCEPTED', score: 100});
  await oldPoll;
  assert.equal(get('#judge-result').textContent, '');
}

async function testContestLinkedAdminEditDoesNotArchive() {
  const {get} = nodes(), calls = [], opened = [], notices = [];
  get('#review [name=status]').options = [{value: 'PUBLISHED'}, {value: 'ARCHIVED'}];
  const context = evaluate(
    "let sessionEpoch=1,viewEpoch=1,adminProblem=null;const $=s=>document.querySelector(s);",
    section('function currentSession(', 'async function api(') +
      section('function problemTitle(item)', 'function listItems(data)') +
      section('function addReviewSection(', 'async function openAdminProblem(') +
      section("$('#admin-edit').onclick=async()=>{", "form('review',"),
    {document: {querySelector: get, createElement: () => new FakeNode()},
      confirm: () => true, api: async (path, method, body) => { calls.push({path, method, body}); },
      openEditor: async (number, asAdmin) => opened.push([number, asAdmin]),
      notice: message => notices.push(message)}
  );
  context.renderAdminProblem({number: 42, title: '시험 문제', groupId: 7,
    status: 'PUBLISHED', currentVisibility: 'GROUP', usedInContest: true});
  await get('#admin-edit').onclick(); // Direct invocation also checks the handler guard.
  assert.equal(get('#admin-edit').disabled, true);
  assert.match(get('#admin-detail-meta').textContent, /시험.*복제/);
  assert.deepEqual(calls, []);
  assert.deepEqual(opened, []);
  assert.match(notices.at(-1), /시험.*복제/);

  context.renderAdminProblem({number: 43, title: '편집 가능 문제', groupId: 7,
    status: 'PUBLISHED', currentVisibility: 'GROUP', usedInContest: false});
  await get('#admin-edit').onclick();
  assert.equal(get('#admin-edit').disabled, false);
  assert.deepEqual(calls.map(call => [call.method, call.path]), [
    ['PATCH', '/api/admin/problems/43/visibility']
  ]);
  assert.deepEqual(opened, [[43, true]]);
}

async function testLoginKeepsDeepLinkView() {
  for (const [pathname, expectedView] of [
    ['/groups', 'groups'], ['/community', 'community'], ['/notices', 'notices']
  ]) {
    const {get} = nodes(), shown = [];
    const context = evaluate(
      "let loggingOut=false,sessionEpoch=0,token=null;const $=s=>document.querySelector(s);function currentSession(epoch){return epoch===sessionEpoch;}",
      section('function form(', "form('email-send',"),
      {document: {querySelector: get, querySelectorAll: () => []}, location: {pathname},
        FormData: class { *[Symbol.iterator]() { yield ['username', 'alice']; yield ['password', 'secret']; } },
        api: async path => { assert.equal(path, '/api/auth/login'); return {accessToken: 'access-token'}; },
        clearPrivateViews() {}, refreshPermissions: async () => {},
        show: async view => shown.push(view), notice: message => { throw Error(message); }}
    );
    const submit = new FakeNode();
    await get('#login').onsubmit({preventDefault() {}, target: {querySelector: () => submit}});
    assert.deepEqual(shown, [expectedView], pathname);
    assert.equal(vm.runInContext('token', context), 'access-token');
  }
}

(async () => {
  await testLateReissueCannotReplaceNewSession();
  await testOldPermissionsCannotRestoreAdminNav();
  testPrivateStateCleanup();
  await testLatePrivateListAndDetail();
  await testViewChangeDiscardsProblemProfileAndSubmissions();
  await testLatePollDoesNotUpdateNewView();
  await testLoginKeepsDeepLinkView();
  await testContestLinkedAdminEditDoesNotArchive();
  console.log('app session: stale reissue/permissions, private cleanup, account/view races, admin edit and deep links passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
