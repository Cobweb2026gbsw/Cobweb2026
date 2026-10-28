import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';
import {build} from 'esbuild';
import {JSDOM} from 'jsdom';

const dom=new JSDOM('<!doctype html><html><body></body></html>',{url:'http://localhost:8080/'});
for(const key of ['window','document','HTMLElement','Event','MutationObserver'])globalThis[key]=dom.window[key];
Object.defineProperty(globalThis,'navigator',{value:dom.window.navigator,configurable:true});globalThis.IS_REACT_ACT_ENVIRONMENT=true;
const require=createRequire(import.meta.url),React=require('react'),{render,fireEvent,waitFor,cleanup,act}=require('@testing-library/react');
const built=await build({stdin:{contents:"export {default} from './components/TestRunPanel.jsx';",resolveDir:fileURLToPath(new URL('../src/',import.meta.url)),loader:'jsx'},bundle:true,write:false,platform:'node',format:'cjs',packages:'external',jsx:'automatic',loader:{'.css':'empty'},plugins:[{name:'test-run-contracts',setup(builder){
  builder.onResolve({filter:/(?:^|\/)api(?:\.js)?$/},()=>({path:'api',namespace:'contract'}));
  builder.onResolve({filter:/\/context\/AuthContext(?:\.jsx)?$/},()=>({path:'auth',namespace:'contract'}));
  builder.onLoad({filter:/.*/,namespace:'contract'},args=>({contents:args.path==='api'?'export const api=(...args)=>globalThis.__testRunApi(...args);export const getSessionEpoch=()=>globalThis.__testRunAuth.epoch;':'export const useAuth=()=>globalThis.__testRunAuth;'}));
}}]});
const module={exports:{}};new Function('require','module','exports',built.outputFiles[0].text)(require,module,module.exports);const Panel=module.exports.default;
const basic={problem_number:1000,execution_mode:'STDIN',languages:['PYTHON','JAVA'],examples:[{input_text:'input-secret',output_text:'expected-secret'}]};
const generic={...basic,execution_mode:'FUNCTION',functionAbi:'GENERIC_V1',functionSignature:{name:'sol',parameters:[{name:'numbers',type:'int[]'}],returnType:'int'},examples:[{input_text:'{"args":[[1,2]]}',output_text:'3'}]};
const props=(problem=basic)=>({problem,number:1000,language:'PYTHON',code:'print(3)'});
const completed=(results=[{status:'ACCEPTED',timeMs:7},{status:'WRONG_ANSWER',timeMs:3}])=>({id:70,problemNumber:1000,language:'PYTHON',status:'COMPLETED',privateSample:true,testCount:2,results});
const tick=()=>new Promise(resolve=>setTimeout(resolve,0));
const runButton=view=>view.getByRole('button',{name:'테스트 및 실행'});
test.beforeEach(()=>{globalThis.__testRunAuth={epoch:0,user:{id:1,username:'alice'},loading:false};window.localStorage.clear();globalThis.__testRunApi=async()=>{throw Error('Unexpected request');};});
test.afterEach(()=>cleanup());test.after(()=>dom.window.close());

test('two fixed private cases expose no input controls, examples, expected values or add/remove actions',()=>{
  const view=render(React.createElement(Panel,props()));
  assert.equal(view.getAllByRole('article').length,2);assert.ok(view.getByRole('article',{name:'비공개 테스트 1'}));assert.ok(view.getByRole('article',{name:'비공개 테스트 2'}));
  assert.equal(view.queryByRole('textbox'),null);assert.equal(view.container.querySelector('textarea,input,pre'),null);
  assert.equal(view.queryByRole('button',{name:'테스트 추가'}),null);assert.equal(view.queryByRole('button',{name:/삭제/}),null);
  assert.doesNotMatch(view.container.textContent,/input-secret|expected-secret|예상 출력|실제 출력/);
});

test('execution sends only source, language and the explicit private mode and shows pass/fail with time',async()=>{
  const calls=[];globalThis.__testRunApi=async(path,method,body)=>{calls.push({path,method,body});return method==='POST'?70:completed();};
  const view=render(React.createElement(Panel,props(generic)));fireEvent.click(runButton(view));await view.findByRole('region',{name:'테스트 실행 결과'});
  assert.deepEqual(calls[0],{path:'/api/problems/1000/test-runs',method:'POST',body:{language:'PYTHON',sourceCode:'print(3)',privateSample:true}});
  assert.equal(view.getByRole('article',{name:'테스트 1 결과'}).textContent,'테스트 1통과7 ms');assert.equal(view.getByRole('article',{name:'테스트 2 결과'}).textContent,'테스트 2실패3 ms');
  assert.equal(view.container.querySelector('pre'),null);assert.equal(calls.length,2);
});

test('STDIN, SQL, legacy and generic functions use the same server-selected-case contract',async()=>{
  for(const problem of [basic,{...basic,execution_mode:'SQL'}, {...basic,execution_mode:'FUNCTION',functionAbi:'CHAIN_LASER_V1'},generic]){
    cleanup();let body;globalThis.__testRunApi=async(path,method,value)=>method==='POST'?(body=value,70):completed();
    const view=render(React.createElement(Panel,props(problem)));fireEvent.click(runButton(view));await view.findByRole('region',{name:'테스트 실행 결과'});
    assert.deepEqual(Object.keys(body).sort(),['language','privateSample','sourceCode']);assert.equal(body.privateSample,true);assert.equal(view.queryByRole('textbox'),null);
  }
});

test('UTF-8 source byte limits reject oversized code and allow exactly 64KiB without copying test data',async()=>{
  let posts=0;globalThis.__testRunApi=async(path,method)=>method==='POST'?(posts++,70):completed();
  const view=render(React.createElement(Panel,{...props(),code:'가'.repeat(22000)}));fireEvent.click(runButton(view));assert.match((await view.findByRole('alert')).textContent,/64 KiB/);assert.equal(posts,0);
  view.rerender(React.createElement(Panel,{...props(),code:'x'.repeat(65537)}));fireEvent.click(runButton(view));await view.findByRole('alert');assert.equal(posts,0);
  view.rerender(React.createElement(Panel,{...props(),code:'x'.repeat(65536)}));fireEvent.click(runButton(view));await view.findByRole('region',{name:'테스트 실행 결과'});assert.equal(posts,1);
});

test('anonymous and unresolved identity cannot send test jobs',async()=>{
  let calls=0;globalThis.__testRunApi=async()=>{calls++;};globalThis.__testRunAuth={epoch:0,user:null,loading:false};const view=render(React.createElement(Panel,props()));
  fireEvent.click(view.getByRole('button',{name:'로그인 후 테스트'}));assert.match(view.getByRole('alert').textContent,/로그인/);assert.equal(calls,0);
  globalThis.__testRunAuth={epoch:0,user:null,loading:true};view.rerender(React.createElement(Panel,props()));fireEvent.click(view.getByRole('button',{name:'로그인 후 테스트'}));assert.match(view.getByRole('alert').textContent,/확인 중/);assert.equal(calls,0);
});

test('invalid problem/language and empty source never create jobs',()=>{
  let posts=0;globalThis.__testRunApi=async()=>{posts++;};
  for(const change of [{number:0},{number:'oops'},{number:Number.MAX_SAFE_INTEGER+1},{language:'SQL'}]){
    cleanup();const view=render(React.createElement(Panel,{...props(),...change}));fireEvent.click(runButton(view));assert.ok(view.getByRole('alert'));assert.equal(posts,0);
  }
  cleanup();const view=render(React.createElement(Panel,{...props(),code:' '}));assert.equal(runButton(view).disabled,true);
});

test('synchronous repeated clicks create one job and one-second polling reports only the captured status/time',async()=>{
  let release,posts=0,reads=0;globalThis.__testRunApi=async(path,method)=>{if(method==='POST'){posts++;return new Promise(resolve=>{release=resolve;});}reads++;return reads===1?{...completed(),status:'RUNNING',results:[]}:completed();};
  const view=render(React.createElement(Panel,props())),button=runButton(view);fireEvent.click(button);fireEvent.click(button);assert.equal(posts,1);
  await act(async()=>{release(70);await tick();});await view.findByText(/코드를 실행하고 있습니다/);
  await waitFor(()=>view.getByRole('region',{name:'테스트 실행 결과'}),{timeout:2000});assert.equal(reads,2);assert.equal(view.container.querySelector('pre'),null);
});

test('source, language, problem and account changes hide results immediately and discard late responses',async()=>{
  for(const change of [{code:'print(4)'},{language:'JAVA'},{number:1001},{epoch:1}]){
    cleanup();let release;globalThis.__testRunAuth={epoch:0,user:{id:1},loading:false};globalThis.__testRunApi=async(path,method)=>method==='POST'?70:new Promise(resolve=>{release=resolve;});
    const view=render(React.createElement(Panel,props()));fireEvent.click(runButton(view));await waitFor(()=>assert.equal(typeof release,'function'));
    if(change.epoch)globalThis.__testRunAuth={epoch:1,user:{id:2},loading:false};view.rerender(React.createElement(Panel,{...props(),...change}));
    await act(async()=>{release(completed([{status:'ACCEPTED',timeMs:17,actual:'old-account-output'}]));await tick();});
    assert.doesNotMatch(view.container.textContent,/old-account-output|17 ms/);assert.equal(view.queryByRole('region',{name:'테스트 실행 결과'}),null);
  }
});

test('GET failure stops polling and permits explicit retry without replaying the POST',async()=>{
  let posts=0,reads=0;globalThis.__testRunApi=async(path,method)=>{if(method==='POST'){posts++;return 70;}reads++;throw Error('테스트 조회 권한이 없습니다.');};
  const view=render(React.createElement(Panel,props()));fireEvent.click(runButton(view));await view.findByRole('alert');
  assert.equal(posts,1);assert.equal(reads,1);assert.equal(runButton(view).disabled,false);
});

test('invalid created IDs never start result polling',async()=>{
  for(const id of [0,-1,'70',null,Number.MAX_SAFE_INTEGER+1]){
    cleanup();let reads=0;globalThis.__testRunApi=async(path,method)=>method==='POST'?id:(reads++,completed());
    const view=render(React.createElement(Panel,props()));fireEvent.click(runButton(view));await view.findByRole('alert');assert.equal(reads,0);assert.equal(runButton(view).disabled,false);
  }
});

test('mismatched modes, identities, counts, statuses and times are rejected without rendering returned fields',async()=>{
  for(const mutation of [{id:71},{problemNumber:9999},{language:'JAVA'},{privateSample:false},{testCount:3},{results:[{status:'RUNNING',timeMs:1}]},{results:[{status:'ACCEPTED',timeMs:-1}]},{results:[{status:'ACCEPTED',timeMs:1.5}]},{results:[{status:'ACCEPTED',timeMs:'1'}]},{results:[]},{results:[{status:'ACCEPTED',timeMs:1}]},{status:'RUNNING',results:[{status:'ACCEPTED',timeMs:1}]}]){
    cleanup();globalThis.__testRunApi=async(path,method)=>method==='POST'?70:{...completed(),...mutation,actual:'response-secret',diagnostic:{message:'diagnostic-secret'}};
    const view=render(React.createElement(Panel,props()));fireEvent.click(runButton(view));await view.findByRole('alert');assert.equal(view.queryByRole('region',{name:'테스트 실행 결과'}),null);assert.doesNotMatch(view.container.textContent,/response-secret|diagnostic-secret/);
  }
});

test('smuggled input/expected/actual and diagnostic data never reach the DOM or browser storage',async()=>{
  globalThis.__testRunApi=async(path,method)=>method==='POST'?70:{...completed([1,2].map(()=>({status:'COMPILATION_ERROR',timeMs:0,input:'input-secret',expected:'expected-secret',actual:'<script>output-secret()</script>',diagnostic:{message:'diagnostic-secret'}}))),inputCases:['top-level-secret'],diagnostic:{message:'top-diagnostic-secret'}};
  const view=render(React.createElement(Panel,props()));fireEvent.click(runButton(view));await view.findByRole('region',{name:'테스트 실행 결과'});
  assert.doesNotMatch(view.container.textContent,/secret|script|컴파일/);assert.equal(view.container.querySelector('pre,script,img,textarea'),null);assert.deepEqual(Object.keys(window.localStorage),[]);assert.equal(view.getAllByText('실패').length,2);
});

test('system failures never render returned status details or private outputs',async()=>{
  globalThis.__testRunApi=async(path,method)=>method==='POST'?70:{...completed([{status:'RUNTIME_ERROR',timeMs:19,actual:'internal-output'}]),status:'SYSTEM_ERROR',diagnostic:{message:'internal-reason'}};
  const view=render(React.createElement(Panel,props()));fireEvent.click(runButton(view));await view.findByRole('alert');assert.doesNotMatch(view.container.textContent,/internal-output|internal-reason|19 ms/);assert.equal(view.queryByRole('region',{name:'테스트 실행 결과'}),null);
});

test('shared lower action row puts test first and submission last and exposes in-flight state',async()=>{
  let release;globalThis.__testRunApi=async(path,method)=>method==='POST'?new Promise(resolve=>{release=resolve;}):completed();
  const renderSubmitAction=({testing})=>React.createElement('button',{disabled:testing},'제출 및 채점');
  const view=render(React.createElement(Panel,{...props(),renderSubmitAction}));const row=view.getByRole('button',{name:'제출 및 채점'}).parentElement;
  assert.equal(row.className,'cw-test-actions');assert.equal(row.firstElementChild,runButton(view));assert.equal(row.lastElementChild,view.getByRole('button',{name:'제출 및 채점'}));assert.equal(row.closest('.cw-test-scroll'),null);
  fireEvent.click(runButton(view));assert.equal(view.getByRole('button',{name:'제출 및 채점'}).disabled,true);await act(async()=>{release(70);await tick();});await view.findByRole('region',{name:'테스트 실행 결과'});assert.equal(view.getByRole('button',{name:'제출 및 채점'}).disabled,false);
});

test('unmount cancels the active request and ignores its later completion',async()=>{
  let release,signal;globalThis.__testRunApi=async(path,method,body,options)=>{signal=options.signal;return new Promise(resolve=>{release=resolve;});};
  const view=render(React.createElement(Panel,props()));fireEvent.click(runButton(view));assert.equal(signal.aborted,false);view.unmount();assert.equal(signal.aborted,true);
  await act(async()=>{release(70);await tick();});assert.equal(document.body.textContent,'');
});
