import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {build} from 'esbuild';
import {JSDOM} from 'jsdom';
import {installEditorDom,editorValue,setEditorValue} from './editor-dom.mjs';

const require=createRequire(import.meta.url),root=fileURLToPath(new URL('../',import.meta.url));
const dom=new JSDOM('<!doctype html><html><body></body></html>',{url:'http://localhost:8080/'});
Object.defineProperty(globalThis,'navigator',{value:dom.window.navigator,configurable:true});
for(const key of ['window','document','HTMLElement','Event','InputEvent','KeyboardEvent','MutationObserver','FormData'])globalThis[key]=dom.window[key];
installEditorDom(dom.window);
globalThis.IS_REACT_ACT_ENVIRONMENT=true;
globalThis.confirm=()=>true;
const React=require('react'),{render,fireEvent,waitFor,cleanup,act}=require('@testing-library/react');
const {MemoryRouter,Routes,Route}=require('react-router-dom');
const modules=new Map();
async function load(name){
  if(modules.has(name))return modules.get(name);
  const result=await build({entryPoints:[path.join(root,'src/pages',name+'.jsx')],bundle:true,write:false,platform:'node',format:'cjs',packages:'external',jsx:'automatic',loader:{'.css':'empty'},plugins:[{name:'starter-contracts',setup(builder){
    builder.onResolve({filter:/(?:^|\/)api(?:\.js)?$/},()=>({path:'api',namespace:'contract'}));
    builder.onResolve({filter:/\/context\/AuthContext(?:\.jsx)?$/},()=>({path:'auth',namespace:'contract'}));
    builder.onLoad({filter:/.*/,namespace:'contract'},args=>({contents:args.path==='api'?'export const api=(...args)=>globalThis.__starterApi(...args);export const getSessionEpoch=()=>globalThis.__starterAuth.epoch;':'export const useAuth=()=>globalThis.__starterAuth;'}));
  }}]});
  const module={exports:{}};new Function('require','module','exports',result.outputFiles[0].text)(require,module,module.exports);
  modules.set(name,module.exports.default);return module.exports.default;
}
function mount(Page,url='/problems/1000',pattern='/problems/:id'){
  return React.createElement(MemoryRouter,{initialEntries:[url]},React.createElement(Routes,null,React.createElement(Route,{path:pattern,element:React.createElement(Page,{dark:true})}),React.createElement(Route,{path:'/submissions/:id',element:React.createElement('p',null,'submitted-result')})));
}
const catalog=[{id:'PYTHON',label:'Python',modes:['STDIN','FUNCTION']},{id:'JAVASCRIPT',label:'JavaScript',modes:['STDIN','FUNCTION']}];
const starters=[{language:'PYTHON',sourceCode:'def sol(x: int, y: int):\n    pass\n'},{language:'JAVASCRIPT',sourceCode:'function sol(x, y) {\n    // TODO\n}\n'}];
const problem={problem_number:1000,problem_title:'시작 코드 확인',execution_mode:'FUNCTION',functionAbi:null,languages:catalog.map(row=>row.id),examples:[],starterCodes:starters};
test.beforeEach(()=>{window.localStorage.clear();globalThis.__starterAuth={user:{username:'alice'},epoch:0,permissions:{}};});
test.afterEach(()=>cleanup());
test.after(()=>dom.window.close());

test('ordinary language switching fills public parameters, preserves edits and intentional empty drafts', {timeout:5000},async()=>{
  const Page=await load('ProblemDetail');
  globalThis.__starterApi=async url=>url==='/api/languages'?catalog:problem;
  const view=render(mount(Page));await waitFor(()=>assert.equal(editorValue(view.getByRole('textbox',{name:'풀이 코드'})),starters[0].sourceCode));
  act(()=>setEditorValue(view.getByRole('textbox',{name:'풀이 코드'}),'def sol(x: int, y: int):\n    return x + y'));
  fireEvent.change(view.getByRole('combobox',{name:'언어'}),{target:{value:'JAVASCRIPT'}});
  assert.equal(editorValue(view.getByRole('textbox',{name:'풀이 코드'})),starters[1].sourceCode);
  act(()=>setEditorValue(view.getByRole('textbox',{name:'풀이 코드'}),''));
  fireEvent.change(view.getByRole('combobox',{name:'언어'}),{target:{value:'PYTHON'}});
  assert.match(editorValue(view.getByRole('textbox',{name:'풀이 코드'})),/return x \+ y/);
  fireEvent.change(view.getByRole('combobox',{name:'언어'}),{target:{value:'JAVASCRIPT'}});
  assert.equal(editorValue(view.getByRole('textbox',{name:'풀이 코드'})),'');
  fireEvent.click(view.getByRole('button',{name:'초기화'}));
  assert.equal(editorValue(view.getByRole('textbox',{name:'풀이 코드'})),starters[1].sourceCode);
});

test('untouched starter submission uses the visible source, not an empty draft map', {timeout:5000},async()=>{
  const Page=await load('ProblemDetail'),calls=[];
  globalThis.__starterApi=async(url,method='GET',body)=>{calls.push({url,method,body});return url==='/api/languages'?catalog:url==='/api/submissions'?88:url==='/api/submissions/88'?{id:88,status:'ACCEPTED',score:100,cases:[]}:problem;};
  const view=render(mount(Page));await waitFor(()=>assert.equal(editorValue(view.getByRole('textbox',{name:'풀이 코드'})),starters[0].sourceCode));
  fireEvent.click(view.getByRole('button',{name:'제출 및 채점'}));await view.findByText('정답입니다. 해결 완료로 기록되었습니다.');assert.equal(view.queryByText('submitted-result'),null);
  assert.deepEqual(calls.find(row=>row.method==='POST').body,{problemNumber:1000,language:'PYTHON',sourceCode:starters[0].sourceCode});
});

test('account epoch clears all language drafts and the editor view before the next response', {timeout:5000},async()=>{
  const Page=await load('ProblemDetail');globalThis.__starterApi=async url=>url==='/api/languages'?catalog:problem;
  const view=render(mount(Page));const area=await view.findByRole('textbox',{name:'풀이 코드'});
  act(()=>setEditorValue(area,'private-unsent-alice-source'));
  globalThis.__starterAuth={user:{username:'bob'},epoch:1,permissions:{}};
  globalThis.__starterApi=()=>new Promise(()=>{});view.rerender(mount(Page));
  assert.equal(view.queryByRole('textbox',{name:'풀이 코드'})===null,true);
  assert.doesNotMatch(view.container.textContent,/private-unsent-alice-source/);
  assert.equal([...Object.keys(window.localStorage)].some(key=>window.localStorage.getItem(key)?.includes('private-unsent-alice-source')),false);
});

test('contest editor uses scoped public starters and keeps separate language drafts', {timeout:5000},async()=>{
  const Page=await load('GroupContest');
  const contest={id:3,title:'시작 코드 시험',status:'ACTIVE',startsAt:'2020-01-01T00:00:00Z',endsAt:'2035-01-01T00:00:00Z',problems:[{problemNumber:1000,problemTitle:problem.problem_title}],problemCount:1,replayAllowed:false,captureProtection:false,windowSwitchProtection:false};
  globalThis.__starterApi=async url=>{
    if(url==='/api/languages')return catalog;if(url==='/api/groups/7')return {permissions:['TAKE_CONTESTS']};
    if(url.endsWith('/problems/1000'))return problem;if(url.endsWith('/me'))return {submissions:[]};return contest;
  };
  const view=render(mount(Page,'/groups/7/contests/3','/groups/:gid/contests/:cid'));
  fireEvent.click(await view.findByRole('button',{name:'1. 시작 코드 확인'}));
  await waitFor(()=>assert.equal(editorValue(view.getByRole('textbox',{name:'풀이 코드'})),starters[0].sourceCode));
  act(()=>setEditorValue(view.getByRole('textbox',{name:'풀이 코드'}),'my-python-contest-draft'));
  fireEvent.change(view.getByRole('combobox',{name:'제출 언어'}),{target:{value:'JAVASCRIPT'}});
  assert.equal(editorValue(view.getByRole('textbox',{name:'풀이 코드'})),starters[1].sourceCode);
  fireEvent.change(view.getByRole('combobox',{name:'제출 언어'}),{target:{value:'PYTHON'}});
  assert.equal(editorValue(view.getByRole('textbox',{name:'풀이 코드'})),'my-python-contest-draft');
});
