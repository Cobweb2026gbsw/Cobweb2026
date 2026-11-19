import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';
import {build} from 'esbuild';
import {JSDOM} from 'jsdom';

const dom=new JSDOM('<!doctype html><html><body></body></html>',{url:'http://localhost:8080/'});
for(const key of ['window','document','HTMLElement','Event','MutationObserver'])globalThis[key]=dom.window[key];
Object.defineProperty(globalThis,'navigator',{value:dom.window.navigator,configurable:true});globalThis.IS_REACT_ACT_ENVIRONMENT=true;
const require=createRequire(import.meta.url),React=require('react'),{render,fireEvent,waitFor,cleanup,act,within}=require('@testing-library/react');
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
const selectPrivate=view=>fireEvent.click(view.getByRole('button',{name:'비공개 테스트'}));
function renderPrivate(panelProps=props()){
  const view=render(React.createElement(Panel,panelProps));
  selectPrivate(view);
  return view;
}
test.beforeEach(()=>{globalThis.__testRunAuth={epoch:0,user:{id:1,username:'alice'},loading:false};window.localStorage.clear();globalThis.__testRunApi=async()=>{throw Error('Unexpected request');};});
test.afterEach(()=>cleanup());test.after(()=>dom.window.close());

test('defaults to two valid published examples, showing plain submitted and returned values separately',async()=>{
  const literal='<img src=x>';
  const published={...basic,examples:[
    {input:'missing expected'}, {input:'가'.repeat(22000),output:'too large'},
    {input:'',output:' '}, {input_text:'2\n',output_text:literal}, {input:'third',output:'ignored'}
  ]};
  const cases=[{input:'',expected:' '},{input:'2\n',expected:literal}];
  let request;
  globalThis.__testRunApi=async(path,method,body)=>{
    if(method==='POST'){request=body;return 70;}
    return {...completed(),privateSample:false,results:[
      {...cases[0],actual:'',status:'WRONG_ANSWER',timeMs:3},
      {...cases[1],actual:literal,status:'ACCEPTED',timeMs:7}
    ]};
  };
  const view=render(React.createElement(Panel,props(published)));
  assert.equal(view.getByRole('button',{name:'공개 예제'}).getAttribute('aria-pressed'),'true');
  const first=within(view.getByRole('article',{name:'공개 예제 1'}));
  assert.ok(first.getByText('빈 값'));
  assert.equal(first.getByLabelText('예상 출력').textContent,' ');
  const second=within(view.getByRole('article',{name:'공개 예제 2'}));
  assert.equal(second.getByLabelText('입력값').textContent,'2\n');
  assert.equal(second.getByLabelText('예상 출력').textContent,literal);
  assert.equal(second.queryByLabelText('실제 출력'),null);
  assert.doesNotMatch(view.container.textContent,/third|too large/);

  fireEvent.click(runButton(view));
  await view.findByRole('region',{name:'테스트 실행 결과'});
  assert.deepEqual(request,{language:'PYTHON',sourceCode:'print(3)',privateSample:false,cases});
  const firstResult=within(view.getByRole('article',{name:'테스트 1 결과'}));
  assert.ok(firstResult.getByText('빈 출력'));
  assert.ok(firstResult.getByText('실패 · 오답'));
  const secondResult=within(view.getByRole('article',{name:'테스트 2 결과'}));
  assert.equal(secondResult.getByLabelText('입력값').textContent,'2\n');
  assert.equal(secondResult.getByLabelText('예상 출력').textContent,literal);
  assert.equal(secondResult.getByLabelText('실제 출력').textContent,literal);
  assert.ok(secondResult.getByText('통과'));
  assert.ok(secondResult.getByText('7 ms'));
  assert.equal(view.container.querySelector('img'),null);
});

test('function examples show typed input but send raw JSON, and missing output differs from an empty output',async()=>{
  const raw=generic.examples[0].input_text;
  let submitted;
  globalThis.__testRunApi=async(path,method,body)=>{
    if(method==='POST'){submitted=body.cases;return 70;}
    return {...completed(),privateSample:false,testCount:1,
      results:[{input:raw,expected:'3',actual:null,status:'COMPILATION_ERROR',timeMs:0,
        diagnostic:{phase:'COMPILE',message:'퍼블릭 예제의 컴파일 원인'}}]};
  };
  const view=render(React.createElement(Panel,props(generic)));
  assert.equal(view.getByLabelText('입력값').textContent,'[1,2]');
  fireEvent.click(runButton(view));
  await view.findByRole('region',{name:'테스트 실행 결과'});
  assert.deepEqual(submitted,[{input:raw,expected:'3'}]);
  const result=within(view.getByRole('article',{name:'테스트 1 결과'}));
  assert.equal(result.getByLabelText('입력값').textContent,'[1,2]');
  assert.equal(result.getByLabelText('예상 출력').textContent,'3');
  assert.ok(result.getByText('출력 확인 불가'));
  assert.ok(result.getByText('퍼블릭 예제의 컴파일 원인'));
  assert.equal(result.queryByText('빈 출력'),null);
  assert.doesNotMatch(view.container.textContent,/"args"/);
});

test('no runnable public examples gives an informative empty state and keeps private testing available',()=>{
  const view=render(React.createElement(Panel,props({...basic,examples:[]})));
  assert.match(view.getByRole('tabpanel',{name:'테스트 케이스'}).textContent,/실행할 수 있는 공개 예제가 없습니다/);
  assert.equal(runButton(view).disabled,true);
  selectPrivate(view);
  assert.equal(runButton(view).disabled,false);
  assert.equal(view.getAllByRole('article').length,2);
});

test('mode changes abort previous public runs; an account switch resets the mode to public',async()=>{
  let release;
  let publicSignal;
  globalThis.__testRunApi=async(path,method,body,options)=>{
    if(method==='POST')return 70;
    publicSignal=options.signal;
    return new Promise(resolve=>{release=resolve;});
  };
  const view=render(React.createElement(Panel,props()));
  fireEvent.click(runButton(view));
  await waitFor(()=>assert.equal(typeof release,'function'));
  selectPrivate(view);
  assert.ok(publicSignal.aborted);
  await act(async()=>{
    release({...completed(),privateSample:false,testCount:1,results:[
      {input:basic.examples[0].input_text,expected:basic.examples[0].output_text,actual:'old-result',status:'ACCEPTED',timeMs:45}
    ]});
    await tick();
  });
  assert.doesNotMatch(view.container.textContent,/old-result|45 ms/);
  assert.equal(view.queryByRole('region',{name:'테스트 실행 결과'}),null);
  globalThis.__testRunAuth={epoch:1,user:{id:2},loading:false};
  view.rerender(React.createElement(Panel,props()));
  assert.equal(view.getByRole('button',{name:'공개 예제'}).getAttribute('aria-pressed'),'true');
});

test('a result mismatching the captured public examples is rejected before showing any output',async()=>{
  globalThis.__testRunApi=async(path,method)=>method==='POST'?70:{
    ...completed(),privateSample:false,testCount:1,
    results:[{input:'unexpected-input',expected:basic.examples[0].output_text,actual:'unexpected-output',status:'ACCEPTED',timeMs:4}]
  };
  const view=render(React.createElement(Panel,props()));
  fireEvent.click(runButton(view));
  assert.ok(await view.findByRole('alert'));
  assert.doesNotMatch(view.container.textContent,/unexpected-input|unexpected-output/);
});

test('already verified public results survive a later system error',async()=>{
  const two={...basic,examples:[{input:'1',output:'2'},{input:'3',output:'4'}]};
  globalThis.__testRunApi=async(path,method)=>method==='POST'?70:{
    ...completed(),privateSample:false,status:'SYSTEM_ERROR',
    results:[{input:'1',expected:'2',actual:'2',status:'ACCEPTED',timeMs:5}]
  };
  const view=render(React.createElement(Panel,props(two)));
  fireEvent.click(runButton(view));
  assert.match((await view.findByRole('alert')).textContent,/완료하지 못했습니다/);
  const result=within(view.getByRole('article',{name:'테스트 1 결과'}));
  assert.equal(result.getByLabelText('실제 출력').textContent,'2');
  assert.equal(view.queryByRole('article',{name:'테스트 2 결과'}),null);
});

test('two fixed private cases expose no input controls, examples, expected values or add/remove actions',()=>{
  const view=renderPrivate();
  assert.equal(view.getAllByRole('article').length,2);assert.ok(view.getByRole('article',{name:'비공개 테스트 1'}));assert.ok(view.getByRole('article',{name:'비공개 테스트 2'}));
  assert.equal(view.queryByRole('textbox'),null);assert.equal(view.container.querySelector('textarea,input,pre'),null);
  assert.equal(view.queryByRole('button',{name:'테스트 추가'}),null);assert.equal(view.queryByRole('button',{name:/삭제/}),null);
  assert.doesNotMatch(view.container.textContent,/input-secret|expected-secret|예상 출력|실제 출력/);
});

test('execution sends only source, language and the explicit private mode and shows pass/fail with time',async()=>{
  const calls=[];globalThis.__testRunApi=async(path,method,body)=>{calls.push({path,method,body});return method==='POST'?70:completed();};
  const view=renderPrivate(props(generic));fireEvent.click(runButton(view));await view.findByRole('region',{name:'테스트 실행 결과'});
  assert.deepEqual(calls[0],{path:'/api/problems/1000/test-runs',method:'POST',body:{language:'PYTHON',sourceCode:'print(3)',privateSample:true}});
  assert.equal(view.getByRole('article',{name:'테스트 1 결과'}).textContent,'테스트 1통과7 ms');assert.equal(view.getByRole('article',{name:'테스트 2 결과'}).textContent,'테스트 2실패3 ms');
  assert.equal(view.container.querySelector('pre'),null);assert.equal(calls.length,2);
});

test('STDIN, SQL, legacy and generic functions use the same server-selected-case contract',async()=>{
  for(const problem of [basic,{...basic,execution_mode:'SQL'}, {...basic,execution_mode:'FUNCTION',functionAbi:'CHAIN_LASER_V1'},generic]){
    cleanup();let body;globalThis.__testRunApi=async(path,method,value)=>method==='POST'?(body=value,70):completed();
    const view=renderPrivate(props(problem));fireEvent.click(runButton(view));await view.findByRole('region',{name:'테스트 실행 결과'});
    assert.deepEqual(Object.keys(body).sort(),['language','privateSample','sourceCode']);assert.equal(body.privateSample,true);assert.equal(view.queryByRole('textbox'),null);
  }
});

test('UTF-8 source byte limits reject oversized code and allow exactly 64KiB without copying test data',async()=>{
  let posts=0;globalThis.__testRunApi=async(path,method)=>method==='POST'?(posts++,70):completed();
  const view=renderPrivate({...props(),code:'가'.repeat(22000)});fireEvent.click(runButton(view));assert.match((await view.findByRole('alert')).textContent,/64 KiB/);assert.equal(posts,0);
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
  const view=renderPrivate(),button=runButton(view);fireEvent.click(button);fireEvent.click(button);assert.equal(posts,1);
  await act(async()=>{release(70);await tick();});await view.findByText(/코드를 실행하고 있습니다/);
  await waitFor(()=>view.getByRole('region',{name:'테스트 실행 결과'}),{timeout:2000});assert.equal(reads,2);assert.equal(view.container.querySelector('pre'),null);
});

test('source, language, problem and account changes hide results immediately and discard late responses',async()=>{
  for(const change of [{code:'print(4)'},{language:'JAVA'},{number:1001},{epoch:1}]){
    cleanup();let release;globalThis.__testRunAuth={epoch:0,user:{id:1},loading:false};globalThis.__testRunApi=async(path,method)=>method==='POST'?70:new Promise(resolve=>{release=resolve;});
    const view=renderPrivate();fireEvent.click(runButton(view));await waitFor(()=>assert.equal(typeof release,'function'));
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
    const view=renderPrivate();fireEvent.click(runButton(view));await view.findByRole('alert');assert.equal(view.queryByRole('region',{name:'테스트 실행 결과'}),null);assert.doesNotMatch(view.container.textContent,/response-secret|diagnostic-secret/);
  }
});

test('smuggled input/expected/actual and diagnostic data never reach the DOM or browser storage',async()=>{
  globalThis.__testRunApi=async(path,method)=>method==='POST'?70:{...completed([1,2].map(()=>({status:'COMPILATION_ERROR',timeMs:0,input:'input-secret',expected:'expected-secret',actual:'<script>output-secret()</script>',diagnostic:{message:'diagnostic-secret'}}))),inputCases:['top-level-secret'],diagnostic:{message:'top-diagnostic-secret'}};
  const view=renderPrivate();fireEvent.click(runButton(view));await view.findByRole('region',{name:'테스트 실행 결과'});
  assert.doesNotMatch(view.container.textContent,/secret|script|컴파일/);assert.equal(view.container.querySelector('pre,script,img,textarea'),null);assert.deepEqual(Object.keys(window.localStorage),[]);assert.equal(view.getAllByText('실패').length,2);
});

test('system failures never render returned status details or private outputs',async()=>{
  globalThis.__testRunApi=async(path,method)=>method==='POST'?70:{...completed([{status:'RUNTIME_ERROR',timeMs:19,actual:'internal-output'}]),status:'SYSTEM_ERROR',diagnostic:{message:'internal-reason'}};
  const view=renderPrivate();fireEvent.click(runButton(view));await view.findByRole('alert');assert.doesNotMatch(view.container.textContent,/internal-output|internal-reason|19 ms/);assert.equal(view.queryByRole('region',{name:'테스트 실행 결과'}),null);
});

test('shared lower action row puts test first and submission last and exposes in-flight state',async()=>{
  let release;globalThis.__testRunApi=async(path,method)=>method==='POST'?new Promise(resolve=>{release=resolve;}):completed();
  const renderSubmitAction=({testing})=>React.createElement('button',{disabled:testing},'제출 및 채점');
  const view=renderPrivate({...props(),renderSubmitAction});const row=view.getByRole('button',{name:'제출 및 채점'}).parentElement;
  assert.equal(row.className,'cw-test-actions');assert.equal(row.firstElementChild,runButton(view));assert.equal(row.lastElementChild,view.getByRole('button',{name:'제출 및 채점'}));assert.equal(row.closest('.cw-test-scroll'),null);
  fireEvent.click(runButton(view));assert.equal(view.getByRole('button',{name:'제출 및 채점'}).disabled,true);await act(async()=>{release(70);await tick();});await view.findByRole('region',{name:'테스트 실행 결과'});assert.equal(view.getByRole('button',{name:'제출 및 채점'}).disabled,false);
});

test('unmount cancels the active request and ignores its later completion',async()=>{
  let release,signal;globalThis.__testRunApi=async(path,method,body,options)=>{signal=options.signal;return new Promise(resolve=>{release=resolve;});};
  const view=render(React.createElement(Panel,props()));fireEvent.click(runButton(view));assert.equal(signal.aborted,false);view.unmount();assert.equal(signal.aborted,true);
  await act(async()=>{release(70);await tick();});assert.equal(document.body.textContent,'');
});

test('successful sample execution states its scope and never claims final acceptance',async()=>{
  globalThis.__testRunApi=async(path,method)=>method==='POST'?70:{...completed(),results:[{status:'ACCEPTED',timeMs:1},{status:'ACCEPTED',timeMs:2}]};
  const view=renderPrivate();fireEvent.click(runButton(view));
  await view.findByRole('region',{name:'테스트 실행 결과'});
  assert.ok(view.getByText('2개 중 2개 통과'));
  assert.ok(view.getByText('선택한 테스트를 통과했습니다. 해결 완료로 기록하려면 제출 및 채점을 진행하세요.'));
  assert.doesNotMatch(view.container.textContent,/정답입니다|해결 완료로 기록되었습니다/);
});
