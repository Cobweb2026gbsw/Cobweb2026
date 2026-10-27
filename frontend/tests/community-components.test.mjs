import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {build} from 'esbuild';
import {JSDOM} from 'jsdom';
import {jwtFixture} from './jwt-fixture.mjs';
const require=createRequire(import.meta.url);
const dom=new JSDOM('<!doctype html><html><body></body></html>',{url:'http://localhost:8080/'});
Object.defineProperty(globalThis,'navigator',{value:dom.window.navigator,configurable:true});
for(const name of ['window','document','HTMLElement','Event','MutationObserver'])globalThis[name]=dom.window[name];
globalThis.IS_REACT_ACT_ENVIRONMENT=true;
window.confirm=()=>true;
const React=require('react'),{render,fireEvent,waitFor,cleanup,act}=require('@testing-library/react');
const {MemoryRouter,Routes,Route}=require('react-router-dom');
const cache=new Map();
async function load(name){
  if(cache.has(name))return cache.get(name);
  const bundle=await build({stdin:{contents:`export {default as Page} from './src/pages/${name}.jsx';export {AuthProvider,useAuth} from './src/context/AuthContext.jsx';export {beginSession} from './src/lib/api.js';`,resolveDir:process.cwd(),sourcefile:'community-test.jsx'},bundle:true,write:false,platform:'node',format:'cjs',packages:'external',jsx:'automatic',loader:{'.css':'empty'}});
  const module={exports:{}};new Function('require','module','exports',bundle.outputFiles[0].text)(require,module,module.exports);cache.set(name,module.exports);return module.exports;
}
function mock(handler){
  const calls=[];
  globalThis.fetch=async(path,options={})=>{
    const method=options.method||'GET',body=options.body?JSON.parse(options.body):null;calls.push({path,method,body});
    let value;
    if(path==='/api/auth/reissue')value={accessToken:jwtFixture('1','community')};
    else if(path==='/api/me')value={username:'alice',bio:'',github_url:'',solved_count:50,submit_count:50};
    else if(path==='/api/problems/permissions')value={canCreate:true,canManage:true};
    else value=await handler(path,method,body);
    if(options.signal?.aborted)throw new DOMException('aborted','AbortError');
    return new Response(JSON.stringify({success:!value?.__error,data:value?.__error?null:value,message:value?.__error}),{status:value?.__error?409:200,headers:{'Content-Type':'application/json'}});
  };
  return calls;
}
async function mount(module,path,pattern,props={}){
  module.beginSession(null);
  function Ready(){const auth=module.useAuth();return React.createElement('output',{'data-testid':'auth-state'},!auth.loading&&auth.user?'ready':'loading');}
  const view=render(React.createElement(MemoryRouter,{initialEntries:[path]},React.createElement(module.AuthProvider,null,React.createElement(Ready),React.createElement(Routes,null,React.createElement(Route,{path:pattern,element:React.createElement(module.Page,{dark:true,...props})}),React.createElement(Route,{path:'*',element:React.createElement('p',null,'saved-route')})))));
  await waitFor(()=>assert.equal(view.getByTestId('auth-state').textContent,'ready'));
  return view;
}
const post={id:7,title:'실제 게시글',body:'실제 본문',category:'GENERAL',authorUsername:'alice',version:3,createdAt:'2026-10-21T00:00:00Z',canEdit:true,canDelete:true,deleted:false,problemNumber:null};
const comment={id:11,depth:0,parentId:null,body:'이전 댓글',authorUsername:'alice',version:4,createdAt:'2026-10-21T00:00:00Z',canEdit:true,canDelete:true,replyCount:0,deleted:false};
test('community lists real category and server pagination data',async()=>{
  cleanup();const module=await load('ForumList');const calls=mock(path=>{assert.match(path,/^\/api\/community\/posts\?/);return {items:[{...post,commentCount:2}],total:21,page:0};});
  const view=await mount(module,'/forum','/forum');await waitFor(()=>view.getByText('실제 게시글'));assert.ok(view.getByRole('button',{name:'하드웨어'}));await waitFor(()=>assert.equal(view.getByRole('button',{name:'다음'}).disabled,false));fireEvent.click(view.getByRole('button',{name:'다음'}));await waitFor(()=>assert.ok(calls.some(call=>call.path.includes('page=1'))));cleanup();
});
test('admin editing hidden linked problem preserves link without resending its private number',async()=>{
  cleanup();const module=await load('ForumWrite');const calls=mock((path,method)=>method==='PUT'?null:{...post,problemNumber:1009,problemLinkHidden:true});
  const view=await mount(module,'/forum/7/edit?admin=1','/forum/:id/edit');const title=await view.findByDisplayValue('실제 게시글');fireEvent.change(title,{target:{value:'제목만 수정'}});fireEvent.click(view.getByRole('button',{name:'저장'}));
  await waitFor(()=>assert.ok(calls.some(call=>call.method==='PUT')));const payload=calls.find(call=>call.method==='PUT').body;assert.equal(payload.version,3);assert.equal(payload.problemNumber,null);assert.equal(payload.preserveProblemLink,true);cleanup();
});
test('deleted parent preserves readable replies but cannot compose a new reply',async()=>{
  cleanup();const module=await load('ForumPost');mock(path=>path.includes('/comments?')?new URL(path,'http://localhost').searchParams.has('parentId')?{items:[{...comment,id:12,depth:1,body:'보존된 답글',canEdit:false}],total:1}:{items:[{...comment,deleted:true,body:null,deletedMessage:'삭제된 댓글입니다.',canEdit:false,canDelete:false,replyCount:1}],total:1}:post);
  const view=await mount(module,'/forum/7','/forum/:id');await view.findByText('삭제된 댓글입니다.');fireEvent.click(view.getByRole('button',{name:'답글 1'}));await view.findByText('보존된 답글');assert.equal(view.queryByRole('textbox',{name:'답글 작성'})===null,true);cleanup();
});
test('comment edit sends expected version and retains truthful server text',async()=>{
  cleanup();const module=await load('ForumPost');let changed=false;const calls=mock((path,method)=>{if(method==='PUT'){changed=true;return null;}return path.includes('/comments?')?{items:[{...comment,body:changed?'변경된 댓글':comment.body,version:changed?5:4}],total:1}:post;});
  const view=await mount(module,'/forum/7','/forum/:id');await view.findByText('이전 댓글');fireEvent.click(view.getByRole('button',{name:'댓글 수정'}));fireEvent.change(view.getByRole('textbox',{name:'댓글 수정'}),{target:{value:'변경된 댓글'}});fireEvent.click(view.getByRole('button',{name:'저장'}));
  await waitFor(()=>assert.ok(calls.some(call=>call.method==='PUT')));assert.deepEqual(calls.find(call=>call.method==='PUT').body,{body:'변경된 댓글',version:4});cleanup();
});
test('post version conflict keeps edit draft and reports server error',async()=>{
  cleanup();const module=await load('ForumWrite');mock((path,method)=>method==='PUT'?{__error:'버전이 달라 새로고침 후 다시 시도해주세요.'}:post);
  const view=await mount(module,'/forum/7/edit','/forum/:id/edit');const title=await view.findByDisplayValue('실제 게시글');fireEvent.change(title,{target:{value:'저장 전 초안'}});fireEvent.click(view.getByRole('button',{name:'저장'}));await view.findByRole('alert');assert.equal(view.getByDisplayValue('저장 전 초안').value,'저장 전 초안');cleanup();
});
test.after(async()=>{cleanup();await act(async()=>{await new Promise(resolve=>setTimeout(resolve,0));});dom.window.close();});
