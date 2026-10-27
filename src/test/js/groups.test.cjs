const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const groups = require('../../main/resources/static/groups.js');

const start = '2099-01-02T03:04';
const end = '2099-01-02T05:04';
const input = {title: '  모의 코테  ', startsAt: start, endsAt: end,
  problemNumbers: '101, 202,303', replayAllowed: 'on', captureProtection: 'on'};
const result = groups.contestPayload(input);
assert.equal(result.title, '모의 코테');
assert.deepEqual(result.problemNumbers, [101, 202, 303]);
assert.equal(result.startsAt, new Date(start).toISOString());
assert.equal(result.endsAt, new Date(end).toISOString());
assert.equal(result.replayAllowed, true);
assert.equal(result.captureProtection, true);
assert.equal(result.windowSwitchProtection, false);
assert.equal(groups.localDatetime(result.startsAt), start);
assert.throws(() => groups.contestPayload({...input, problemNumbers: '101,101'}), /중복 없는/);
assert.throws(() => groups.contestPayload({...input, problemNumbers: ''}), /1~50개/);
assert.throws(() => groups.contestPayload({...input, problemNumbers: Array.from({length: 51}, (_, i) => i + 1).join(',')}), /1~50개/);
assert.throws(() => groups.contestPayload({...input, endsAt: start}), /시작·종료/);
assert.throws(() => groups.contestPayload({...input, startsAt: 'invalid'}), /시작·종료/);

const active = {status: 'ACTIVE', endsAt: '2099-01-02T06:00:00Z', captureProtection: true, windowSwitchProtection: true};
assert.equal(groups.shouldRecordEvent(active, 'COPY_BLOCKED', 0), true);
assert.equal(groups.shouldRecordEvent(active, 'WINDOW_BLUR', 0), true);
assert.equal(groups.shouldRecordEvent({...active, captureProtection: false}, 'CAPTURE_KEY', 0), false);
assert.equal(groups.shouldRecordEvent({...active, windowSwitchProtection: false}, 'FULLSCREEN_EXIT', 0), false);
assert.equal(groups.shouldRecordEvent({...active, status: 'UPCOMING'}, 'WINDOW_BLUR', 0), false);
assert.equal(groups.shouldRecordEvent({...active, status: 'ENDED'}, 'COPY_BLOCKED', 0), false);
assert.equal(groups.shouldRecordEvent(active, 'UNKNOWN', 0), false);
assert.equal(groups.shouldRecordEvent(active, 'COPY_BLOCKED', new Date(active.endsAt).getTime()), false);
assert.equal(groups.FLAGS.length, 11);
assert.equal(new Set(groups.FLAGS.map(([key]) => key)).size, 11);

const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/groups.js'), 'utf8');
const functionNames = [...source.matchAll(/\b(?:async\s+)?function\s+([A-Za-z_$][\w$]*)\s*\(/g)].map(match => match[1]);
assert.equal(new Set(functionNames).size, functionNames.length, 'groups.js function declarations must have unique names');
assert.match(source, /await renderGroupMine\(minePanel\)/);
assert.match(source, /await renderContestMine\(root\)/);
assert.doesNotMatch(source, /\.innerHTML\s*=/);
assert.match(source, /removeEventListener/);
assert.match(source, /status==='UPCOMING'/);

class RaceNode {
  constructor(tag='div', className='') { this.tagName=tag.toUpperCase(); this.className=className; this.children=[]; this._text=''; this.hidden=false; }
  set textContent(value) { this._text=String(value); this.children=[]; }
  get textContent() { return this._text+this.children.map(child=>child.textContent).join(''); }
  append(...children) { this.children.push(...children); }
  prepend(...children) { this.children.unshift(...children); }
  setAttribute(name,value) { this.attributes??={};this.attributes[name]=String(value); }
  replaceChildren(...children) { this._text=''; this.children=[...children]; }
  insertBefore(child,before) { const index=this.children.indexOf(before);this.children.splice(index<0?this.children.length:index,0,child); }
  remove() { if(this.parent) this.parent.children=this.parent.children.filter(child=>child!==this); }
  matches(selector) { if(selector.startsWith('.'))return this.className.split(' ').includes(selector.slice(1));
    if(selector.startsWith('#'))return this.id===selector.slice(1);
    if(selector==='[type=submit]')return this.type==='submit';
    const named=selector.match(/^\[name=([^\]]+)\]$/);if(named)return this.name===named[1];
    return this.tagName.toLowerCase()===selector; }
  querySelector(selector) { for(const child of this.children){if(child.matches?.(selector))return child;const nested=child.querySelector?.(selector);if(nested)return nested;}return null; }
}
function deferred() { let resolve;const promise=new Promise(done=>{resolve=done;});return {promise,resolve}; }

(async () => {
  let resolveDetail;
  const pendingDetail = new Promise(resolve => { resolveDetail = resolve; });
  const requested = [];
  const root = {children: [], replaceChildren() { this.children = []; }};
  global.document = {querySelector: selector => ['#groups-app', '#contest-app'].includes(selector) ? root : null};
  groups.init({api: endpoint => { requested.push(endpoint); return pendingDetail; }});
  const staleGroup = groups.openGroup(7);
  groups.clear();
  resolveDetail({group: {id: 7, groupName: '탈퇴한 그룹'}, permissions: [], members: [], roles: []});
  await staleGroup;
  assert.deepEqual(requested, ['/api/groups/7'], 'logout must stop before loading member identity');
  assert.equal(root.children.length, 0, 'logout must discard a late group detail response');

  const section={hidden:false},publicBox=new RaceNode('div','group-public-list'),pager=new RaceNode('div','pager');
  const previous=new RaceNode('button'),next=new RaceNode('button');pager.append(previous,next);
  const publicPanel=new RaceNode('section');publicPanel.append(publicBox,pager);
  publicPanel.querySelectorAll=selector=>selector==='.pager button'?[previous,next]:[];
  const output=new RaceNode('pre'),rankings=new RaceNode('div','contest-rankings'),contestApp=new RaceNode('div');
  global.document={createElement:tag=>new RaceNode(tag),querySelector:selector=>({
    '#groups':section,'#contest':{hidden:false},'#contest-question .contest-result':output,
    '#contest-app .contest-rankings':rankings,'#contest-app':contestApp,
    '#contest-clock':contestApp.querySelector('#contest-clock'),
    '#contest-submit':contestApp.querySelector('#contest-submit'),
    '#contest-question':contestApp.querySelector('#contest-question')
  })[selector]||null};
  const listA=deferred(),listB=deferred();let listCalls=0;
  groups.init({api:()=>++listCalls===1?listA.promise:listB.promise,isAuthenticated:()=>false,notice:()=>{}});
  const oldList=groups._test.renderPublic(publicPanel),newList=groups._test.renderPublic(publicPanel);
  listB.resolve([{id:2,groupName:'새 결과',memberCount:2}]);await newList;
  listA.resolve([{id:1,groupName:'오래된 결과',memberCount:1}]);await oldList;
  assert.match(publicBox.textContent,/새 결과/);
  assert.doesNotMatch(publicBox.textContent,/오래된 결과/,'late search result must not replace the latest group list');

  const problemPanel=new RaceNode('section'),problemBox=new RaceNode('div','group-problem-list');problemPanel.append(problemBox);
  const problemsA=deferred(),problemsB=deferred();let problemCalls=0;
  groups.init({api:()=>++problemCalls===1?problemsA.promise:problemsB.promise,notice:()=>{}});
  const oldProblems=groups._test.renderGroupProblems(problemPanel,7,0,'old');
  const newProblems=groups._test.renderGroupProblems(problemPanel,7,0,'new');
  problemsB.resolve([{problemNumber:2,problemTitle:'새 문제'}]);await newProblems;
  problemsA.resolve([{problemNumber:1,problemTitle:'오래된 문제'}]);await oldProblems;
  assert.match(problemBox.textContent,/새 문제/);
  assert.doesNotMatch(problemBox.textContent,/오래된 문제/,'late problem page must not replace the latest page');

  const openedProblems=[];
  groups.init({api:async()=>[
    {problemNumber:10,problemTitle:'시험 연동 문제',usedInContest:true},
    {problemNumber:11,problemTitle:'일반 그룹 문제',usedInContest:false}
  ],openProblem:number=>openedProblems.push(number),notice:()=>{}});
  await groups._test.renderGroupProblems(problemPanel,7);
  const [contestOnly,ordinary]=problemBox.children;
  assert.match(contestOnly.textContent,/코테 전용.*코테 목록에서 응시\/다시 보기/);
  assert.equal(contestOnly.querySelector('button'),null,'contest-linked problem must not open through the ordinary problem API');
  await ordinary.querySelector('button').onclick();
  assert.deepEqual(openedProblems,[11],'ordinary group problem keeps its solve action');

  // 실제 검수 버튼을 눌렀을 때 공개 목록의 검색/페이지도 함께 갱신되어야 합니다.
  const browserFormData=global.FormData;
  global.FormData=class {
    constructor(form) { this.form=form; }
    *[Symbol.iterator]() {
      function* controls(parent) { for(const child of parent.children||[]){
        if(child.name&&!child.disabled&&(child.type!=='checkbox'||child.checked))yield [child.name,child.value??'on'];
        yield* controls(child);
      } }
      yield* controls(this.form);
    }
  };
  const groupRoot=new RaceNode('div');groupRoot.scrollIntoView=()=>{};
  const querySelector=global.document.querySelector;
  global.document.querySelector=selector=>selector==='#groups-app .group-detail-slot'?groupRoot:querySelector(selector);
  const reviewed=new Set();let reviewHold=null;const reviewCalls=[];
  groups.init({api:async(endpoint,method,body)=>{
    reviewCalls.push({endpoint,method,body});
    if(endpoint==='/api/groups/7')return {group:{id:7,groupName:'검수 그룹'},permissions:['REVIEW_PROBLEMS'],members:[],roles:[]};
    if(endpoint==='/api/me')return {username:'owner'};
    if(endpoint.startsWith('/api/groups/7/problems/review-queue?'))return [77,78,79].map(number=>({problemNumber:number,problemTitle:'승인 문제',status:reviewed.has(number)?'PUBLISHED':'PENDING_REVIEW'}));
    if(endpoint.startsWith('/api/groups/7/problems?'))return [...reviewed].map(number=>({problemNumber:number,problemTitle:'승인 문제'}));
    if(endpoint==='/api/groups/7/contests')return [];
    const edit=endpoint.match(/^\/api\/problems\/(\d+)\/edit$/);if(edit)return {title:'승인 문제',status:reviewed.has(Number(edit[1]))?'PUBLISHED':'PENDING_REVIEW'};
    const review=endpoint.match(/^\/api\/problems\/(\d+)\/review$/);if(review){if(reviewHold)await reviewHold.promise;if(body.status==='PUBLISHED')reviewed.add(Number(review[1]));return {};}
    throw Error('unexpected review request '+endpoint);
  },notice:()=>{}});
  await groups.openGroup(7);
  const reviewProblemBox=groupRoot.querySelector('.group-problem-list');
  // 패널은 다른 폼/상태를 버리지 않고 그 자리에서 갱신합니다.
  const findParent=(parent,target)=>parent.children.includes(target)?parent:parent.children.map(child=>findParent(child,target)).find(Boolean);
  const reviewProblemPanel=findParent(groupRoot,reviewProblemBox);
  await groups._test.renderGroupProblems(reviewProblemPanel,7,2,'승인');
  const settingsDraft=new RaceNode('input');settingsDraft.value='보존할 초안';groupRoot.append(settingsDraft);
  await groupRoot.querySelector('.group-review-items').querySelector('button').onclick();
  await groupRoot.querySelector('.group-review-detail').querySelector('form').onsubmit({preventDefault(){}});
  assert.match(reviewProblemBox.textContent,/#77 · 승인 문제/,'approval must refresh the visible public problem list immediately');
  assert.equal(reviewCalls.filter(call=>call.endpoint==='/api/groups/7/problems?page=2&search=%EC%8A%B9%EC%9D%B8').length,2,
    'approval refresh must retain the current public-list search and page');
  assert.equal(groupRoot.children.includes(settingsDraft),true,'approval must not reopen the entire group or discard other drafts');

  reviewHold=deferred();
  await groupRoot.querySelector('.group-review-items').children[1].querySelector('button').onclick();
  const changedDetailReview=groupRoot.querySelector('.group-review-detail').querySelector('form').onsubmit({preventDefault(){}});
  await groupRoot.querySelector('.group-review-items').children[0].querySelector('button').onclick();
  const newDetail=groupRoot.querySelector('.group-review-detail').querySelector('h4');
  await groups._test.renderGroupProblems(reviewProblemPanel,7,3,'새 검색');
  reviewHold.resolve();await changedDetailReview;
  assert.equal(groupRoot.querySelector('.group-review-detail').querySelector('h4'),newDetail,
    'approval completed while viewing another detail must not clear that detail');
  assert.match(reviewProblemBox.textContent,/#78 · 승인 문제/,'changing review detail must not suppress the public-list refresh');
  assert.equal(reviewCalls.filter(call=>call.endpoint==='/api/groups/7/problems?page=3&search=%EC%83%88+%EA%B2%80%EC%83%89').length,2,
    'refresh must use the page/search chosen while the approval was pending');

  // 이탈/로그아웃 뒤 완료된 검수는 새 화면을 다시 조회하거나 채우면 안 됩니다.
  reviewHold=deferred();
  await groupRoot.querySelector('.group-review-items').children[2].querySelector('button').onclick();
  const staleReview=groupRoot.querySelector('.group-review-detail').querySelector('form').onsubmit({preventDefault(){}});
  const beforeLeave=reviewCalls.length;section.hidden=true;
  reviewHold.resolve();await staleReview;
  assert.equal(reviewCalls.length,beforeLeave,'a review completed after navigation must not refresh the old group');
  reviewHold=deferred();
  const logoutReview=groupRoot.querySelector('.group-review-detail').querySelector('form').onsubmit({preventDefault(){}});
  const beforeLogout=reviewCalls.length;section.hidden=false;groups.clear();
  reviewHold.resolve();await logoutReview;
  assert.equal(reviewCalls.length,beforeLogout,'a review completed after logout must not request private group data');
  section.hidden=false;global.document.querySelector=querySelector;

  // 표시값은 같은 분이어도 ACTIVE/ENDED의 옵션 저장은 원본 초/소수초를 보존합니다.
  const contestNotices=[];
  for(const status of ['ACTIVE','ENDED']){
    const shortContest={id:4,status,title:'  원본 제목  ',startsAt:'2026-10-16T08:04:10.123456Z',endsAt:'2026-10-16T08:04:20.654321Z',problems:[{problemNumber:202},{problemNumber:101}]};
    const submitted=[];
    groups._test.setContestState(shortContest,7);
    groups.init({api:async(endpoint,method,body)=>{submitted.push({endpoint,method,body});section.hidden=true;return {};},notice:message=>contestNotices.push(message)});
    const form=groups._test.contestForm(7,shortContest);
    assert.equal(form.querySelector('[name=startsAt]').value,form.querySelector('[name=endsAt]').value);
    form.querySelector('[name=replayAllowed]').checked=status==='ACTIVE';
    form.querySelector('[name=captureProtection]').checked=status==='ENDED';
    form.querySelector('[name=windowSwitchProtection]').checked=status==='ACTIVE';
    // Immutable fields must come from the server snapshot, even if the DOM was changed.
    form.querySelector('[name=title]').value='바뀐 표시';form.querySelector('[name=problemNumbers]').value='999';
    // 그룹 컨텍스트에서 저장해 재조회 없이 요청 본문 자체를 검증합니다.
    groups._test.setContestState(null,null);
    await form.onsubmit({preventDefault(){}});
    assert.equal(submitted.length,1,status+' short contest must issue its PUT');
    assert.deepEqual(submitted[0],{endpoint:'/api/groups/7/contests/4',method:'PUT',body:{
      title:shortContest.title,startsAt:shortContest.startsAt,endsAt:shortContest.endsAt,problemNumbers:[202,101],
      replayAllowed:status==='ACTIVE',captureProtection:status==='ENDED',windowSwitchProtection:status==='ACTIVE'
    }},status+' option-only edits must preserve exact immutable values and problem order');
    section.hidden=false;
  }
  groups.init({api:async()=>{throw Error('invalid upcoming contest must not be sent');},notice:message=>contestNotices.push(message)});
  const invalidUpcoming=groups._test.contestForm(7,{id:5,status:'UPCOMING',title:'아직 시작 전',startsAt:'2099-01-02T03:04:10Z',endsAt:'2099-01-02T03:04:20Z',problems:[{problemNumber:101}]});
  await invalidUpcoming.onsubmit({preventDefault(){}});
  assert.match(contestNotices.at(-1),/시작·종료/,'upcoming minute input still requires a strictly increasing time range');
  global.FormData=browserFormData;
  const submillisecond={status:'ENDED',title:'정밀 시각',startsAt:'2026-10-16T08:04:10.123456Z',endsAt:'2026-10-16T08:04:10.123457Z',problems:[{problemNumber:101}]};
  assert.equal(groups.contestPayload({},submillisecond).endsAt,submillisecond.endsAt,
    'immutable server timestamps must not be rounded to JavaScript milliseconds');

  const refreshA=deferred(),refreshB=deferred();let refreshCalls=0;
  groups.init({api:endpoint=>{if(endpoint==='/api/groups/7/contests/3')return ++refreshCalls===1?refreshA.promise:refreshB.promise;
    if(endpoint.endsWith('/problems'))return Promise.resolve([]);
    if(endpoint.endsWith('/me'))return Promise.resolve({submissions:[]});
    throw Error('unexpected contest request '+endpoint);},notice:()=>{}});
  groups._test.setContestState({id:3,status:'UPCOMING',title:'초기',startsAt:'2099-01-01T00:00:00Z'},7);
  const oldRefresh=groups._test.refreshContest(),newRefresh=groups._test.refreshContest();
  refreshB.resolve({id:3,status:'ACTIVE',title:'최신',startsAt:'2020-01-01T00:00:00Z',endsAt:'2099-01-01T00:00:00Z'});await newRefresh;
  refreshA.resolve({id:3,status:'UPCOMING',title:'과거',startsAt:'2099-01-01T00:00:00Z'});await oldRefresh;
  assert.equal(groups._test.getContestState().status,'ACTIVE','late contest refresh must not rewind state');
  assert.match(contestApp.textContent,/시험 문제/);
  assert.match(contestApp.querySelector('#contest-clock').textContent,/진행 중/,'contest countdown must render in its target node');

  const poll=deferred();groups.init({api:()=>poll.promise,notice:()=>{}});
  groups._test.setContestState({id:3,status:'ACTIVE',endsAt:'2099-01-01T00:00:00Z'},7);
  const oldPoll=groups._test.pollSubmission(101);groups._test.advanceQuestion();
  poll.resolve({status:'ACCEPTED',score:100});await oldPoll;
  assert.equal(output.textContent,'','an old question result must not appear on a newly selected question');

  const staffA=deferred(),staffB=deferred();let staffCalls=0;
  groups.init({api:()=>++staffCalls===1?staffA.promise:staffB.promise,notice:()=>{},languageLabel:id=>id});
  groups._test.setContestState({id:3,status:'ENDED'},7);
  const oldStaff=groups._test.showStaffSubmissions(1),newStaff=groups._test.showStaffSubmissions(2);
  staffB.resolve({submissions:[]});await newStaff;
  staffA.resolve({submissions:[]});await oldStaff;
  assert.match(rankings.textContent,/회원 #2 제출/);
  assert.doesNotMatch(rankings.textContent,/회원 #1 제출/,'late staff response must not replace the latest member');

  const originalFormData=global.FormData,save=deferred(),saveCalls=[];
  global.FormData=class { *[Symbol.iterator]() {yield ['name','변경'];yield ['description','설명'];} };
  groups.init({api:(endpoint,method)=>{saveCalls.push([endpoint,method]);return save.promise;},notice:()=>{}});
  const settingsRoot=new RaceNode('div');groups._test.renderGroupSettings(settingsRoot,7,{groupName:'기존',description:''});
  const settingsForm=settingsRoot.querySelector('form');const saving=settingsForm.onsubmit({preventDefault(){}});
  section.hidden=true;save.resolve({});await saving;global.FormData=originalFormData;
  assert.deepEqual(saveCalls,[['/api/groups/7','PATCH']],'save completed after navigation must not reopen the old group');

  const activeContest={id:3,status:'ACTIVE',title:'진행 시험',startsAt:'2020-01-01T00:00:00Z',endsAt:'2099-01-01T00:00:00Z',captureProtection:false};
  let latestDetail={...activeContest,captureProtection:true},denyMine=false,heldProblems=null;
  const question={problem_number:101,problem_title:'현재 문제',languages:['PYTHON','JAVA'],examples:[]};
  groups.init({api:endpoint=>{
    if(endpoint==='/api/groups/7/contests/3')return Promise.resolve(latestDetail);
    if(endpoint==='/api/groups/7/contests/3/problems')return heldProblems?.promise||Promise.resolve([{problemNumber:101,problemTitle:'현재 문제'}]);
    if(endpoint==='/api/groups/7/contests/3/me')return denyMine?Promise.reject(Error('권한 없음')):Promise.resolve({submissions:[]});
    if(endpoint==='/api/groups/7/contests/3/problems/101')return Promise.resolve(question);
    throw Error('unexpected draft request '+endpoint);
  },notice:()=>{},languageLabel:id=>id});
  const installDraft=()=>{groups._test.setContestState(activeContest,7);groups._test.setContestProblem({problem_number:101});
    const oldQuestion=new RaceNode('section'),oldForm=new RaceNode('form'),language=new RaceNode('select'),source=new RaceNode('textarea');
    oldQuestion.id='contest-question';oldForm.id='contest-submit';language.name='language';language.value='PYTHON';source.name='sourceCode';source.value='print(42)';
    oldForm.append(language,source);oldQuestion.append(oldForm);contestApp.replaceChildren(oldQuestion);};
  installDraft();await groups._test.refreshContest();
  assert.equal(contestApp.querySelector('#contest-submit').querySelector('[name=sourceCode]').value,'print(42)',
    'changing protection flags must preserve the active question source');
  assert.equal(contestApp.querySelector('#contest-submit').querySelector('[name=language]').value,'PYTHON',
    'changing protection flags must preserve the active question language');
  assert.equal(groups._test.getContestDraft(),null,'restored source must not remain cached');

  latestDetail={...activeContest,status:'ENDED',captureProtection:true};
  await groups._test.refreshContest();
  assert.equal(contestApp.querySelector('#contest-submit'),null,'ended contests must not restore the old question');
  assert.equal(groups._test.getContestDraft(),null,'ended contests must discard the source snapshot');

  installDraft();denyMine=true;latestDetail={...activeContest,captureProtection:true};
  await groups._test.refreshContest();
  assert.equal(contestApp.querySelector('#contest-submit'),null,'lost take permission must not restore the question');
  assert.equal(groups._test.getContestDraft(),null,'denied access must discard the source snapshot');

  installDraft();denyMine=false;latestDetail={...activeContest,captureProtection:true};heldProblems=deferred();
  const interrupted=groups._test.refreshContest();await Promise.resolve();
  assert.equal(groups._test.getContestDraft()?.sourceCode,'print(42)');
  groups.clear();assert.equal(groups._test.getContestDraft(),null,'logout or leave must discard in-memory source');
  heldProblems.resolve([{problemNumber:101,problemTitle:'현재 문제'}]);await interrupted;
  assert.equal(contestApp.children.length,0,'a late redraw must not repopulate the logged-out contest');
  console.log('groups: payload, approval refresh, short-contest option save, permissions, logout and out-of-order UI races passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
