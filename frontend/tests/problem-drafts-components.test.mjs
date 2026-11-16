import test,{after} from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {build} from 'esbuild';
import {JSDOM} from 'jsdom';
import {installEditorDom,editorValue,setEditorValue} from './editor-dom.mjs';
import {DRAFT_CACHE_KEY,draftContext,loadProblemDrafts,saveProblemDraft} from '../src/lib/problemDraftCache.js';

const require=createRequire(import.meta.url),root=fileURLToPath(new URL('../',import.meta.url));
const dom=new JSDOM('<!doctype html><html><body></body></html>',{url:'http://localhost:8080/'});
Object.defineProperty(globalThis,'navigator',{value:dom.window.navigator,configurable:true});
for(const key of ['window','document','HTMLElement','Event','InputEvent','KeyboardEvent','MutationObserver','FormData'])globalThis[key]=dom.window[key];
installEditorDom(dom.window);globalThis.IS_REACT_ACT_ENVIRONMENT=true;globalThis.confirm=()=>true;
window.CobwebCodeEditor=require('../../src/main/resources/static/code-editor.js');
const React=require('react'),{render,fireEvent,waitFor,cleanup,act}=require('@testing-library/react'),{MemoryRouter,Routes,Route}=require('react-router-dom');
const bundle=await build({entryPoints:[path.join(root,'src/pages/ProblemDetail.jsx')],bundle:true,write:false,platform:'node',format:'cjs',packages:'external',jsx:'automatic',loader:{'.css':'empty'},plugins:[{name:'draft-contracts',setup(builder){
  builder.onResolve({filter:/(?:^|\/)api(?:\.js)?$/},()=>({path:'api',namespace:'contract'}));
  builder.onResolve({filter:/\/context\/AuthContext(?:\.jsx)?$/},()=>({path:'auth',namespace:'contract'}));
  builder.onLoad({filter:/.*/,namespace:'contract'},args=>({contents:args.path==='api'?'export const api=(...args)=>globalThis.__draftApi(...args);export const getSessionEpoch=()=>globalThis.__draftAuth.epoch;':'export const useAuth=()=>globalThis.__draftAuth;'}));
}}]});
const module={exports:{}};new Function('require','module','exports',bundle.outputFiles[0].text)(require,module,module.exports);const Page=module.exports.default;
const catalog=[{id:'PYTHON',label:'Python',modes:['STDIN','FUNCTION']},{id:'JAVASCRIPT',label:'JavaScript',modes:['STDIN','FUNCTION']}];
const problem=number=>({problem_number:number,problem_title:'초안 문제 '+number,execution_mode:'FUNCTION',functionAbi:'GENERIC_V1',functionSignature:{name:'sol',parameters:[{name:'value',type:'int'}],returnType:'int'},languages:['PYTHON','JAVASCRIPT'],examples:[],description:'private-statement-marker',tests:['private-test-marker'],referenceSolutions:['private-reference-marker']});
const scope=(owner='user:1',number=1000)=>({owner,number,context:draftContext(problem(number))});
const route=(number=1000)=>React.createElement(MemoryRouter,{initialEntries:['/problems/'+number]},React.createElement(Routes,null,React.createElement(Route,{path:'/problems/:id',element:React.createElement(Page,{dark:true})})));
const area=view=>view.getByRole('textbox',{name:'풀이 코드'});
const tick=()=>new Promise(resolve=>setTimeout(resolve,0));
function api(path,method='GET'){
  if(path==='/api/languages')return Promise.resolve(catalog);
  if(/^\/api\/problems\/\d+$/.test(path))return Promise.resolve(problem(Number(path.split('/').at(-1))));
  if(path==='/api/submissions'&&method==='POST')return Promise.resolve(42);
  if(path==='/api/submissions/42')return Promise.resolve({id:42,status:'ACCEPTED',score:100,cases:[]});
  throw Error('unexpected '+path);
}
test.beforeEach(()=>{cleanup();window.localStorage.clear();globalThis.__draftAuth={user:{id:1,username:'alice'},epoch:0,loading:false,permissions:{}};globalThis.__draftApi=api;});
test.afterEach(()=>cleanup());after(()=>dom.window.close());

test('typed code autosaves without submission and restores only after a new successful server read',async()=>{
  const calls=[];globalThis.__draftApi=(...args)=>{calls.push(args);return api(...args);};
  const first=render(route());await waitFor(()=>area(first));
  act(()=>setEditorValue(area(first),'def sol(value):\n    return value + 12'));
  await waitFor(()=>assert.equal(loadProblemDrafts(scope(),['PYTHON']).codes.PYTHON,'def sol(value):\n    return value + 12'));
  assert.equal(calls.some(([,method])=>method==='POST'),false);
  assert.doesNotMatch(window.localStorage.getItem(DRAFT_CACHE_KEY),/private-statement-marker|private-test-marker|private-reference-marker|accessToken/);
  first.unmount();let release;
  globalThis.__draftApi=path=>path==='/api/languages'?Promise.resolve(catalog):new Promise(resolve=>{release=resolve;});
  const second=render(route());await waitFor(()=>assert.equal(typeof release,'function'));
  assert.equal(second.queryByRole('textbox',{name:'풀이 코드'})===null,true);assert.doesNotMatch(second.container.textContent,/return value \+ 12/);
  await act(async()=>{release(problem(1000));await tick();});
  await waitFor(()=>assert.equal(editorValue(area(second)),'def sol(value):\n    return value + 12'));
  assert.match(second.container.textContent,/저장 초안을 복원/);
});

test('latest edits flush on pagehide and on navigation before the 300ms debounce',async()=>{
  const first=render(route());await waitFor(()=>area(first));
  act(()=>setEditorValue(area(first),'pagehide-latest'));
  act(()=>window.dispatchEvent(new Event('pagehide')));
  assert.equal(loadProblemDrafts(scope(),['PYTHON']).codes.PYTHON,'pagehide-latest');
  act(()=>setEditorValue(area(first),'navigation-latest'));first.unmount();
  assert.equal(loadProblemDrafts(scope(),['PYTHON']).codes.PYTHON,'navigation-latest');
});

test('languages preserve independent drafts including an empty string and reset deletes only the selected language',async()=>{
  const first=render(route());await waitFor(()=>area(first));
  act(()=>setEditorValue(area(first),'python-preserved'));
  fireEvent.change(first.getByRole('combobox',{name:'언어'}),{target:{value:'JAVASCRIPT'}});
  act(()=>setEditorValue(area(first),''));first.unmount();
  assert.deepEqual(loadProblemDrafts(scope(),['PYTHON','JAVASCRIPT']).codes,{PYTHON:'python-preserved',JAVASCRIPT:''});
  const second=render(route());await waitFor(()=>assert.equal(editorValue(area(second)),''));
  fireEvent.click(second.getByRole('button',{name:'초기화'}));
  assert.match(editorValue(area(second)),/function sol\(value\)/);
  assert.deepEqual(loadProblemDrafts(scope(),['PYTHON','JAVASCRIPT']).codes,{PYTHON:'python-preserved'});
  fireEvent.change(second.getByRole('combobox',{name:'언어'}),{target:{value:'PYTHON'}});
  assert.equal(editorValue(area(second)),'python-preserved');
});

test('account and problem scopes restore their own drafts without inheriting another identity',async()=>{
  saveProblemDraft(scope(),'PYTHON','alice-source');saveProblemDraft(scope('user:2'),'PYTHON','bob-source');saveProblemDraft(scope('guest'),'PYTHON','anonymous-source');saveProblemDraft(scope('user:1',1001),'PYTHON','other-problem-source');
  const view=render(route());await waitFor(()=>assert.equal(editorValue(area(view)),'alice-source'));
  globalThis.__draftAuth={...globalThis.__draftAuth,user:{id:2,username:'bob'},epoch:1};view.rerender(route());
  assert.doesNotMatch(view.container.textContent,/alice-source/);await waitFor(()=>assert.equal(editorValue(area(view)),'bob-source'));
  globalThis.__draftAuth={...globalThis.__draftAuth,user:null,epoch:2};view.rerender(route());
  assert.doesNotMatch(view.container.textContent,/alice-source|bob-source/);await waitFor(()=>assert.equal(editorValue(area(view)),'anonymous-source'));
  view.unmount();globalThis.__draftAuth={...globalThis.__draftAuth,user:{id:1,username:'alice'},epoch:3};
  const other=render(route(1001));await waitFor(()=>assert.equal(editorValue(area(other)),'other-problem-source'));
});

test('authentication loading does not expose an anonymous stored draft and unknown account IDs never use guest storage',async()=>{
  saveProblemDraft(scope('guest'),'PYTHON','anonymous-source');
  globalThis.__draftAuth={...globalThis.__draftAuth,user:null,loading:true};
  const view=render(route());await waitFor(()=>area(view));assert.doesNotMatch(editorValue(area(view)),/anonymous-source/);
  globalThis.__draftAuth={...globalThis.__draftAuth,loading:false};view.rerender(route());
  await waitFor(()=>assert.equal(editorValue(area(view)),'anonymous-source'));view.unmount();
  globalThis.__draftAuth={...globalThis.__draftAuth,user:{username:'unknown-id'},epoch:1};
  const unknown=render(route());await waitFor(()=>area(unknown));assert.doesNotMatch(editorValue(area(unknown)),/anonymous-source/);
  act(()=>setEditorValue(area(unknown),'not-a-guest-source'));unknown.unmount();
  assert.equal(loadProblemDrafts(scope('guest'),['PYTHON']).codes.PYTHON,'anonymous-source');
});

test('same-epoch authentication loading and resolved account identity remount all in-memory drafts',async()=>{
  globalThis.__draftAuth={...globalThis.__draftAuth,user:null};
  saveProblemDraft(scope(),'PYTHON','verified-account-source');
  const view=render(route());await waitFor(()=>area(view));
  act(()=>setEditorValue(area(view),'anonymous-in-memory-source'));
  globalThis.__draftAuth={...globalThis.__draftAuth,loading:true};view.rerender(route());
  assert.doesNotMatch(view.container.textContent,/anonymous-in-memory-source|verified-account-source/);
  await waitFor(()=>area(view));assert.doesNotMatch(editorValue(area(view)),/anonymous-in-memory-source|verified-account-source/);
  globalThis.__draftAuth={...globalThis.__draftAuth,user:{id:1,username:'alice'},loading:false};view.rerender(route());
  assert.doesNotMatch(view.container.textContent,/anonymous-in-memory-source/);
  await waitFor(()=>assert.equal(editorValue(area(view)),'verified-account-source'));
  assert.equal(loadProblemDrafts(scope('guest'),['PYTHON']).codes.PYTHON,'anonymous-in-memory-source');
});

test('a fresh denied problem read cannot reveal an existing local source or protected problem contents',async()=>{
  saveProblemDraft(scope(),'PYTHON','private-stored-answer');
  globalThis.__draftApi=path=>path==='/api/languages'?Promise.resolve(catalog):Promise.reject(Object.assign(new Error('접근 권한 없음'),{status:403}));
  const view=render(route());await view.findByRole('alert');
  assert.equal(view.queryByRole('textbox',{name:'풀이 코드'})===null,true);
  assert.doesNotMatch(view.container.textContent,/private-stored-answer|private-statement-marker/);
});

test('an account epoch change cancels old pending writes and cleanup cannot overwrite a stored source',async()=>{
  saveProblemDraft(scope(),'PYTHON','last-valid-alice-source');
  const view=render(route());await waitFor(()=>assert.equal(editorValue(area(view)),'last-valid-alice-source'));
  act(()=>setEditorValue(area(view),'late-write-must-be-dropped'));
  globalThis.__draftAuth={...globalThis.__draftAuth,user:{id:2,username:'bob'},epoch:1};view.rerender(route());
  await waitFor(()=>area(view));await act(async()=>{await new Promise(resolve=>setTimeout(resolve,350));});
  assert.doesNotMatch(editorValue(area(view)),/last-valid-alice-source|late-write-must-be-dropped/);
  assert.equal(loadProblemDrafts(scope(),['PYTHON']).codes.PYTHON,'last-valid-alice-source');
  assert.deepEqual(loadProblemDrafts(scope('user:2'),['PYTHON']).codes,{});
});

test('oversized edits show an explicit warning and retain the last valid draft without breaking the editor',async()=>{
  saveProblemDraft(scope(),'PYTHON','last-valid-source');
  const view=render(route());await waitFor(()=>assert.equal(editorValue(area(view)),'last-valid-source'));
  act(()=>setEditorValue(area(view),'a'.repeat(65537)));
  assert.match(view.container.textContent,/64 KiB를 초과해 현재 변경은 저장되지 않았습니다/);
  act(()=>window.dispatchEvent(new Event('pagehide')));
  assert.equal(editorValue(area(view)).length,65537);assert.equal(loadProblemDrafts(scope(),['PYTHON']).codes.PYTHON,'last-valid-source');
});

test('a valid pending draft flush cannot replace the current oversized-language warning with a saved message',async()=>{
  const view=render(route());await waitFor(()=>area(view));
  act(()=>setEditorValue(area(view),'python-pending-before-language-change'));
  fireEvent.change(view.getByRole('combobox',{name:'언어'}),{target:{value:'JAVASCRIPT'}});
  act(()=>setEditorValue(area(view),'x'.repeat(65537)));
  assert.match(view.container.textContent,/64 KiB를 초과해 현재 변경은 저장되지 않았습니다/);
  await act(async()=>{await new Promise(resolve=>setTimeout(resolve,350));});
  assert.equal(loadProblemDrafts(scope(),['PYTHON','JAVASCRIPT']).codes.PYTHON,'python-pending-before-language-change');
  assert.equal(Object.hasOwn(loadProblemDrafts(scope(),['PYTHON','JAVASCRIPT']).codes,'JAVASCRIPT'),false);
  assert.equal(editorValue(area(view)).length,65537);
  assert.match(view.container.textContent,/64 KiB를 초과해 현재 변경은 저장되지 않았습니다/);
  assert.doesNotMatch(view.container.textContent,/이 브라우저에 초안을 저장했습니다/);
});

test('successful submission keeps the last written local source and never stores a token or private API data',async()=>{
  const view=render(route());await waitFor(()=>area(view));
  act(()=>setEditorValue(area(view),'def sol(value):\n    return value'));
  fireEvent.click(view.getByRole('button',{name:'제출 및 채점'}));await view.findByText('정답입니다. 해결 완료로 기록되었습니다.');
  act(()=>window.dispatchEvent(new Event('pagehide')));
  assert.equal(loadProblemDrafts(scope(),['PYTHON']).codes.PYTHON,'def sol(value):\n    return value');
});

test('unavailable browser storage reports failure while code editing and submission still work',async()=>{
  const descriptor=Object.getOwnPropertyDescriptor(window,'localStorage');
  Object.defineProperty(window,'localStorage',{get(){throw Error('private-storage-exception');},configurable:true});
  try{
    const view=render(route());await waitFor(()=>area(view));assert.match(view.container.textContent,/브라우저 저장소를 사용할 수 없어/);
    act(()=>setEditorValue(area(view),'def sol(value):\n    return value'));
    act(()=>window.dispatchEvent(new Event('pagehide')));assert.match(view.container.textContent,/브라우저 저장소를 사용할 수 없어/);
    fireEvent.click(view.getByRole('button',{name:'제출 및 채점'}));await view.findByText('정답입니다. 해결 완료로 기록되었습니다.');
    assert.doesNotMatch(view.container.textContent,/private-storage-exception/);cleanup();
  }finally{Object.defineProperty(window,'localStorage',descriptor);}
});
