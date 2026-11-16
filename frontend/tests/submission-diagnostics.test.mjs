import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';
import {build} from 'esbuild';
import {JSDOM} from 'jsdom';
import {installEditorDom} from './editor-dom.mjs';

const dom=new JSDOM('<!doctype html><html><body></body></html>',{url:'http://localhost:8080/'});
for(const key of ['window','document','HTMLElement','Event','KeyboardEvent','MutationObserver'])globalThis[key]=dom.window[key];
Object.defineProperty(globalThis,'navigator',{value:dom.window.navigator,configurable:true});
installEditorDom(dom.window);globalThis.IS_REACT_ACT_ENVIRONMENT=true;
const require=createRequire(import.meta.url),React=require('react'),{render,fireEvent,waitFor,cleanup,act}=require('@testing-library/react');
const {MemoryRouter,Routes,Route}=require('react-router-dom');
window.CobwebCodeEditor=require('../../src/main/resources/static/code-editor.js');
const result=await build({stdin:{contents:"export {default as Diagnostic} from './components/SubmissionDiagnostic.jsx';export {default as Feedback} from './components/SubmissionFeedback.jsx';export {default as Result} from './pages/SubmissionResult.jsx';export {default as Contest} from './pages/GroupContest.jsx';",resolveDir:fileURLToPath(new URL('../src/',import.meta.url)),loader:'jsx'},bundle:true,write:false,platform:'node',format:'cjs',packages:'external',jsx:'automatic',loader:{'.css':'empty'},plugins:[{name:'diagnostic-contracts',setup(builder){
  builder.onResolve({filter:/(?:^|\/)api(?:\.js)?$/},()=>({path:'api',namespace:'contract'}));
  builder.onResolve({filter:/\/context\/AuthContext(?:\.jsx)?$/},()=>({path:'auth',namespace:'contract'}));
  builder.onLoad({filter:/.*/,namespace:'contract'},args=>({contents:args.path==='api'?'export const api=(...args)=>globalThis.__diagnosticApi(...args);export const getSessionEpoch=()=>globalThis.__diagnosticAuth.epoch;':'export const useAuth=()=>globalThis.__diagnosticAuth;'}));
}}]});
const module={exports:{}};new Function('require','module','exports',result.outputFiles[0].text)(require,module,module.exports);
const {Diagnostic,Feedback,Result,Contest}=module.exports;
const diagnostic=(phase='COMPILE')=>({phase,message:'제출 코드의 문법을 확인하세요.',errorType:'SyntaxError',locations:[{file:'main.py',line:3,column:7}]});
const submission=(status='COMPILATION_ERROR')=>({id:42,status,language:'PYTHON',score:0,cases:[],diagnostic:diagnostic(status==='RUNTIME_ERROR'?'RUN':'COMPILE')});
const tick=()=>new Promise(resolve=>setTimeout(resolve,0));
const route=(Page,url,pattern)=>React.createElement(MemoryRouter,{initialEntries:[url]},React.createElement(Routes,null,React.createElement(Route,{path:pattern,element:React.createElement(Page,{dark:true})})));
test.beforeEach(()=>{globalThis.__diagnosticAuth={epoch:0,user:{id:1,username:'alice'},permissions:{}};window.localStorage.clear();});
test.afterEach(()=>cleanup());test.after(()=>dom.window.close());

test('compile and runtime causes include safe type and submitted-file line/column as plain text',()=>{
  const view=render(React.createElement(Diagnostic,{submission:submission()}));
  assert.ok(view.getByRole('region',{name:'컴파일 오류 원인'}));assert.ok(view.getByText('SyntaxError'));
  assert.match(view.getByRole('list',{name:'제출 코드 오류 위치'}).textContent,/main.py · 3행 7열/);
  view.rerender(React.createElement(Diagnostic,{submission:submission('RUNTIME_ERROR')}));
  assert.ok(view.getByRole('region',{name:'실행 오류 원인'}));assert.match(view.container.textContent,/원문 로그는 표시하지 않습니다/);
});

test('diagnostic payload cannot inject HTML and invalid locations/types are ignored',()=>{
  const view=render(React.createElement(Diagnostic,{submission:{status:'RUNTIME_ERROR',diagnostic:{phase:'RUN',message:'<img src=x onerror="alert(1)">\n<script>secret()</script>',errorType:'/private/server/path',locations:[{file:'/private/server/Main.java',line:4},{file:'main.py',line:-2},{file:'main.py',line:2,column:0},null,{file:'main.py',line:9}]}}}));
  assert.match(view.container.textContent,/<script>secret\(\)<\/script>/);assert.equal(view.container.querySelector('script,img')===null,true);
  assert.doesNotMatch(view.container.textContent,/private\/server/);assert.equal(view.getAllByRole('listitem').length,1);
  assert.match(view.getByRole('listitem').textContent,/main.py · 9행/);
});

test('diagnostic text and locations are bounded independently of untrusted response size',()=>{
  const view=render(React.createElement(Diagnostic,{submission:{status:'COMPILATION_ERROR',diagnostic:{phase:'COMPILE',message:'x'.repeat(10000),locations:Array.from({length:50},(_,i)=>({file:'main.c',line:i+1}))}}}));
  assert.equal(view.container.querySelector('pre').textContent.length,2048);assert.equal(view.getAllByRole('listitem').length,5);
});

test('legacy/malformed diagnostics and wrong phases do not fabricate compiler information',()=>{
  const view=render(React.createElement(Diagnostic,{submission:{status:'COMPILATION_ERROR'}}));
  for(const value of [null,{},[],{phase:'COMPILE',message:{}},{phase:'COMPILE',message:' '},{phase:'RUN',message:'stale-runtime-cause'}]){
    view.rerender(React.createElement(Diagnostic,{submission:{status:'COMPILATION_ERROR',diagnostic:value}}));
    assert.equal(view.container.textContent,'');
  }
});

test('all unrelated verdicts hide even a supplied old diagnostic',()=>{
  const view=render(React.createElement(Diagnostic,{submission:submission()}));
  for(const status of ['QUEUED','RUNNING','ACCEPTED','WRONG_ANSWER','SYSTEM_ERROR','TIME_LIMIT_EXCEEDED','MEMORY_LIMIT_EXCEEDED']){
    view.rerender(React.createElement(Diagnostic,{submission:{...submission(),status}}));assert.equal(view.container.textContent,'',status);
  }
});

test('inline feedback displays an actual compiler cause and discards it on next submission',async()=>{
  globalThis.__diagnosticApi=async()=>submission();const view=render(React.createElement(Feedback,{id:42}));
  await view.findByRole('region',{name:'컴파일 오류 원인'});
  globalThis.__diagnosticApi=async()=>({...submission('ACCEPTED'),id:43});view.rerender(React.createElement(Feedback,{id:43}));
  await view.findByText('정답입니다. 해결 완료로 기록되었습니다.');assert.equal(view.queryByRole('region',{name:'컴파일 오류 원인'})===null,true);
});

test('late diagnostic response cannot enter another account or submission',async()=>{
  let release;globalThis.__diagnosticApi=()=>new Promise(resolve=>{release=resolve;});const view=render(React.createElement(Feedback,{id:42}));
  await waitFor(()=>assert.equal(typeof release,'function'));
  globalThis.__diagnosticAuth={epoch:1,user:{id:2,username:'bob'},permissions:{}};globalThis.__diagnosticApi=async()=>submission('WRONG_ANSWER');view.rerender(React.createElement(Feedback,{id:43}));
  await view.findByText('오답');await act(async()=>{release(submission());await tick();});assert.equal(view.queryByRole('region',{name:'컴파일 오류 원인'})===null,true);
});

test('submission detail shows the server runtime classification without raw stderr fields',async()=>{
  globalThis.__diagnosticApi=async()=>({...submission('RUNTIME_ERROR'),stderr:'private-stdin-echo',diagnostic:{phase:'RUN',message:'0으로 나누는 연산을 확인하세요.',errorType:'ZeroDivisionError',locations:[{file:'main.py',line:2}]}});
  const view=render(route(Result,'/submissions/42','/submissions/:id'));await view.findByRole('region',{name:'실행 오류 원인'});
  assert.ok(view.getByText('ZeroDivisionError'));assert.doesNotMatch(view.container.textContent,/private-stdin-echo/);
});

test('contest submission shows the authorized runtime cause without persisting it locally',async()=>{
  const contest={id:3,title:'진단 시험',status:'ACTIVE',startsAt:'2026-01-01T00:00:00Z',endsAt:'2030-01-01T00:00:00Z',replayAllowed:false,captureProtection:false,windowSwitchProtection:false,problemCount:1,problems:[{problemNumber:1002,problemTitle:'시험 문제'}]};
  globalThis.__diagnosticApi=async(path,method='GET')=>{
    if(path==='/api/groups/7/contests/3')return contest;
    if(path==='/api/groups/7')return {permissions:['TAKE_CONTESTS']};
    if(path==='/api/groups/7/contests/3/problems/1002')return {problem_number:1002,problem_title:'시험 문제',execution_mode:'STDIN',languages:['PYTHON'],examples:[]};
    if(path==='/api/languages')return [{id:'PYTHON',label:'Python',modes:['STDIN']}];
    if(path.endsWith('/me'))return {submissions:[]};
    if(path.endsWith('/submissions')&&method==='POST')return 42;
    if(path==='/api/submissions/42')return submission('RUNTIME_ERROR');
    throw new Error('unexpected diagnostic test request '+path);
  };
  const view=render(route(Contest,'/groups/7/contests/3','/groups/:gid/contests/:cid'));
  fireEvent.click(await view.findByRole('button',{name:'1. 시험 문제'}));await view.findByRole('textbox',{name:'풀이 코드'});
  fireEvent.click(view.getByRole('button',{name:'시험 답안 제출'}));await view.findByRole('region',{name:'실행 오류 원인'});
  for(const key of Object.keys(window.localStorage))assert.doesNotMatch(window.localStorage.getItem(key),/SyntaxError|제출 코드의 문법/);
});
