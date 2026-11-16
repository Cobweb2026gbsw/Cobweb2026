const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

class FakeNode {
  constructor(tag) { this.tagName = tag.toUpperCase(); this.children = []; this._text = ''; this.classes = []; this.classList = {add: value => this.classes.push(value)}; }
  set textContent(value) { this._text = String(value); this.children = []; }
  get textContent() { return this._text + this.children.map(child => child.textContent).join(''); }
  append(...children) { for(const child of children)child.parent=this;this.children.push(...children); }
  replaceChildren(...children) { this._text = ''; this.children = [];this.append(...children); }
  remove() { if(this.parent)this.parent.children=this.parent.children.filter(child=>child!==this); }
  matches(selector) { if(selector.startsWith('.'))return this.className?.split(' ').includes(selector.slice(1));
    if(selector==='[type=submit]')return this.type==='submit';
    const named=selector.match(/^\[name=([^\]]+)\]$/);if(named)return this.name===named[1];
    return this.tagName.toLowerCase()===selector; }
  querySelector(selector) { for(const child of this.children){if(child.matches?.(selector))return child;const nested=child.querySelector?.(selector);if(nested)return nested;}return null; }
}
global.document = {createElement: tag => new FakeNode(tag)};
const community = require('../../main/resources/static/community.js');
let operator = false, authenticated = true;
community.init({isOperator: () => operator, isAuthenticated: () => authenticated});

const valid = {title: '  하드웨어 질문  ', body: '텍스트 본문', category: 'HARDWARE', problemNumber: ''};
assert.deepEqual(community.postPayload(valid), {title: '하드웨어 질문', body: '텍스트 본문', category: 'HARDWARE', problemNumber: null});
assert.deepEqual(community.postPayload({...valid, problemNumber: '123'}, 4),
  {version: 4, title: '하드웨어 질문', body: '텍스트 본문', category: 'HARDWARE', problemNumber: 123});
assert.equal(community.postPayload(valid,4,true).preserveProblemLink,true,
  'editing a hidden link without a new number must preserve it');
assert.equal(community.postPayload({...valid,problemNumber:'123'},4,true).preserveProblemLink,undefined,
  'an explicit public problem replacement must still be validated');
assert.equal(community.postPayload({...valid,problemNumber:'123'},4,true,123).preserveProblemLink,true,
  'admin edits must preserve an unchanged private number rather than revalidate it as a new link');
assert.equal(community.postPayload({...valid,problemNumber:'123'},4,true,123).problemNumber,null);
assert.equal(community.postPayload(valid).preserveProblemLink,undefined,
  'new posts must not inherit a hidden link');
assert.throws(() => community.postPayload({...valid, title: 'x'.repeat(201)}), /길이/);
assert.throws(() => community.postPayload({...valid, body: 'x'.repeat(20001)}), /길이/);
assert.throws(() => community.postPayload({...valid, category: 'OTHER'}), /카테고리/);
assert.throws(() => community.postPayload({...valid, category: 'NOTICE'}), /운영자만/);
assert.throws(() => community.postPayload({...valid, problemNumber: '-1'}), /문제 번호/);
operator = true;
assert.equal(community.postPayload({...valid, category: 'NOTICE'}).category, 'NOTICE');
assert.equal(community.CATEGORIES.length, 7);

const post = {id: 1, deleted: false};
const comment = {id: 2, parentId: null, depth: 0, body: '<script>alert(1)</script>', deleted: false,
  authorUsername: '<img src=x>', createdAt: '2026-01-01T00:00:00Z', canEdit: false, canDelete: false, replyCount: 1};
assert.equal(community.canReply(post, comment, true), true);
assert.equal(community.canReply(post, {...comment, depth: 2}, true), false);
assert.equal(community.canReply(post, {...comment, deleted: true}, true), false);
assert.equal(community.canReply({...post, deleted: true}, comment, true), false);
assert.equal(community.canReply(post, comment, false), false);
operator = false;
const rendered = community._test.renderComment(post, comment);
assert.match(rendered.textContent, /<script>alert\(1\)<\/script>/);
assert.match(rendered.textContent, /<img src=x>/);
assert.equal(rendered.children.some(child => child.tagName === 'SCRIPT'), false);
assert.match(rendered.textContent, /답글 1개 보기/);
const depthTwo = community._test.renderComment(post, {...comment, depth: 2, replyCount: 0});
assert.doesNotMatch(depthTwo.textContent, /답글 달기|답글 1개 보기/);
const deleted = community._test.renderComment(post, {...comment, deleted: true, replyCount: 1});
assert.match(deleted.textContent, /삭제된 댓글입니다/);
assert.doesNotMatch(deleted.textContent, /<script>/);
assert.match(deleted.textContent, /답글 1개 보기/);

const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/community.js'), 'utf8');
assert.doesNotMatch(source, /\.innerHTML\s*=/);
assert.match(source, /commentPagePath\(post\)/);

function deferred() {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return {promise, resolve};
}

(async () => {
  const shell = new FakeNode('div'), listBox = new FakeNode('div'), pager = new FakeNode('div');
  const detail = new FakeNode('div'), history = new FakeNode('div');
  shell.querySelector = selector => ({'.community-list': listBox, '.community-pager': pager,
    '.community-detail': detail, '.community-history-slot': history})[selector] || null;
  global.document.querySelector = selector => ['#community-app', '#notices-app'].includes(selector) ? shell : null;
  operator = true;
  const listReply = deferred();
  community.init({api: () => listReply.promise, isOperator: () => operator, isAuthenticated: () => authenticated});
  const staleList = community._test.loadList();
  community.clear();
  listReply.resolve({items: [{id: 1, title: '관리자 자료'}], total: 1});
  await staleList;
  assert.equal(listBox.children.length, 0, 'logout must discard a late list response');

  const postReply = deferred();
  community.init({api: () => postReply.promise, isOperator: () => operator, isAuthenticated: () => authenticated});
  const stalePost = community._test.openPost(1);
  community.clear();
  postReply.resolve({id: 1, title: '관리자 상세'});
  await stalePost;
  assert.equal(detail.children.length, 0, 'logout must discard a late post response');

  const historyReply = deferred();
  community.init({api: () => historyReply.promise, isOperator: () => operator, isAuthenticated: () => authenticated});
  const staleHistory = community._test.showHistory('post', 1);
  community.clear();
  historyReply.resolve([{action: 'UPDATE', oldBody: '비공개 이력'}]);
  await staleHistory;
  assert.equal(history.children.length, 0, 'logout must discard a late admin history response');

  const communitySection={hidden:false};
  global.document.querySelector=selector=>({
    '#community-app':shell,'#notices-app':shell,'#community':communitySection
  })[selector]||null;
  operator=false;authenticated=true;
  const commentSave=deferred(),selectedPost=deferred(),requests=[];
  community.init({api:(endpoint,method)=>{requests.push([endpoint,method]);
    if(method==='POST')return commentSave.promise;
    if(endpoint==='/api/community/posts/2')return selectedPost.promise;
    throw Error('unexpected request '+endpoint);},isOperator:()=>operator,isAuthenticated:()=>authenticated,notice:()=>{}});
  const originalFormData=global.FormData;
  global.FormData=class { *[Symbol.iterator]() {yield ['body','새 댓글'];} };
  const commentForm=community._test.commentForm(1,null);
  const saving=commentForm.onsubmit({preventDefault(){}});
  const selecting=community._test.openPost(2);
  commentSave.resolve({});await saving;
  assert.deepEqual(requests,[['/api/community/posts/1/comments','POST'],['/api/community/posts/2',undefined]],
    'a late comment save must not reopen the previously selected post');
  community.clear();selectedPost.resolve({id:2,title:'새로 선택한 글'});await selecting;
  global.FormData=originalFormData;

  const commentsReply=deferred();let pageCalls=0;
  community.init({api:()=>{pageCalls++;return commentsReply.promise;},isOperator:()=>operator,isAuthenticated:()=>authenticated,notice:()=>{}});
  const thread=new FakeNode('div');community._test.renderThreadPage(thread,{id:1,deleted:false},null,[],1,0);
  const more=thread.children[1].children[0],firstClick=more.onclick(),secondClick=more.onclick();
  await Promise.resolve();assert.equal(pageCalls,1,'a double click must issue only one comment page request');
  assert.equal(secondClick,undefined);
  commentsReply.resolve({items:[{id:10,depth:2,body:'한 번만',deleted:false,replyCount:0}],total:1});await firstClick;
  assert.equal(thread.textContent.match(/한 번만/g)?.length,1,'the comment page must render once');
  console.log('community: payload, privilege, plain text, logout, stale mutation and duplicate-page races passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
