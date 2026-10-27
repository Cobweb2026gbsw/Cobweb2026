import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';
import {readFileSync} from 'node:fs';
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
const basic={problem_number:1000,execution_mode:'STDIN',languages:['PYTHON','JAVA'],examples:[{input_text:'1 2\n',output_text:'3\n'}]};
const generic={...basic,execution_mode:'FUNCTION',functionAbi:'GENERIC_V1',functionSignature:{name:'sol',parameters:[{name:'numbers',type:'int[]'},{name:'word',type:'string'}],returnType:'string'},examples:[{input_text:'{"args":[[1,2],"a b"]}',output_text:'"a b"'}]};
const props=(problem=basic)=>({problem,number:1000,language:'PYTHON',code:'print(3)'});
const completed=(results=[{status:'ACCEPTED',actual:'3\n',timeMs:7}])=>({id:70,problemNumber:1000,language:'PYTHON',status:'COMPLETED',results});
const tick=()=>new Promise(resolve=>setTimeout(resolve,0));
test.beforeEach(()=>{globalThis.__testRunAuth={epoch:0,user:{id:1,username:'alice'},loading:false};window.localStorage.clear();});
test.afterEach(()=>cleanup());test.after(()=>dom.window.close());

test('public examples prefill at most three cases and add/remove buttons enforce one to three cases',()=>{
  const view=render(React.createElement(Panel,props()));
  assert.equal(view.getByLabelText('테스트 1 입력').value,'1 2\n');assert.equal(view.getByLabelText('테스트 1 예상 출력').value,'3\n');
  assert.equal(view.getByRole('button',{name:'테스트 1 삭제'}).disabled,true);
  fireEvent.click(view.getByRole('button',{name:'테스트 추가'}));fireEvent.click(view.getByRole('button',{name:'테스트 추가'}));
  assert.equal(view.getByRole('button',{name:'테스트 추가'}).disabled,true);assert.equal(view.getAllByRole('group').length,3);
  fireEvent.click(view.getByRole('button',{name:'테스트 2 삭제'}));assert.equal(view.getByRole('button',{name:'테스트 추가'}).disabled,false);
  cleanup();const full=render(React.createElement(Panel,props({...basic,examples:Array.from({length:10},()=>basic.examples[0])})));
  assert.equal(full.getAllByRole('group').length,3);
});

test('generic parameters expose individual JSON controls and assemble args only in the request',async()=>{
  const calls=[];globalThis.__testRunApi=async(path,method,body)=>{calls.push({path,method,body});return method==='POST'?70:completed([{status:'WRONG_ANSWER',actual:'"other"',timeMs:1}]);};
  const view=render(React.createElement(Panel,props(generic)));
  assert.equal(view.getByLabelText('테스트 1 · numbers (int[])').value,'[1,2]');assert.equal(view.getByLabelText('테스트 1 · word (string)').value,'"a b"');
  assert.equal(view.queryByLabelText('테스트 1 입력')===null,true);assert.doesNotMatch(view.container.textContent,/\{"args"/);
  fireEvent.change(view.getByLabelText('테스트 1 · numbers (int[])'),{target:{value:'[3,4]'}});fireEvent.click(view.getByRole('button',{name:'테스트 실행'}));
  await view.findByRole('region',{name:'테스트 실행 결과'});
  assert.deepEqual(calls[0],{path:'/api/problems/1000/test-runs',method:'POST',body:{language:'PYTHON',sourceCode:'print(3)',cases:[{input:'{"args":[[3,4],"a b"]}',expected:'"a b"'}]}});
  assert.match(view.getByRole('article',{name:'테스트 1 결과'}).textContent,/예상 출력"a b"실제 출력"other"/);
});

test('no-argument functions need no args wrapper input and submit an empty args array',async()=>{
  let payload;globalThis.__testRunApi=async(path,method,body)=>{if(method==='POST'){payload=body;return 70;}return completed([{status:'ACCEPTED',actual:'"Hello World!"',timeMs:1}]);};
  const problem={...generic,functionSignature:{name:'sol',parameters:[],returnType:'string'},examples:[{input_text:'{"args":[]}',output_text:'"Hello World!"'}]};
  const view=render(React.createElement(Panel,props(problem)));assert.ok(view.getByText('인자 없음 — 함수만 호출합니다.'));
  assert.equal(view.getAllByRole('textbox').length,1);fireEvent.click(view.getByRole('button',{name:'테스트 실행'}));await view.findByRole('region',{name:'테스트 실행 결과'});
  assert.equal(payload.cases[0].input,'{"args":[]}');
});

test('all eight generic value types preserve JSON types and enforce finite numbers and ASCII strings',async()=>{
  const groups=[
    {parameters:[{name:'count',type:'int'},{name:'ratio',type:'float'},{name:'enabled',type:'bool'},{name:'text',type:'string'}],args:[3,1.25,true,'a b'],returnType:'float',expected:'1.25'},
    {parameters:[{name:'numbers',type:'int[]'},{name:'lines',type:'string[]'}],args:[[1,-2],['a','b\nc']],returnType:'string[]',expected:'["a","b\\nc"]'},
    {parameters:[{name:'matrix',type:'int[][]'},{name:'words',type:'string[][]'}],args:[[[1,-2],[]],[['a'],[]]],returnType:'string[][]',expected:'[["a","b\\nc"],[]]'}
  ];
  for(const group of groups){
    cleanup();let payload,posts=0;globalThis.__testRunApi=async(path,method,body)=>{if(method==='POST'){posts++;payload=body;return 70;}return completed([{status:'ACCEPTED',actual:group.expected,timeMs:1}]);};
    const problem={...generic,functionSignature:{name:'sol',parameters:group.parameters,returnType:group.returnType},examples:[{input_text:JSON.stringify({args:group.args}),output_text:group.expected}]};
    const view=render(React.createElement(Panel,props(problem)));fireEvent.click(view.getByRole('button',{name:'테스트 실행'}));await view.findByRole('region',{name:'테스트 실행 결과'});
    assert.deepEqual(JSON.parse(payload.cases[0].input).args,group.args);
    if(group.returnType==='float'){
      fireEvent.click(view.getByRole('tab',{name:'테스트 케이스'}));
      for(const [label,value] of [['ratio (float)','1e999'],['enabled (bool)','0'],['text (string)','"한글"']]){
        fireEvent.change(view.getByLabelText('테스트 1 · '+label),{target:{value}});fireEvent.click(view.getByRole('button',{name:'테스트 실행'}));await view.findByRole('alert');assert.equal(posts,1);
        const index=group.parameters.findIndex(parameter=>`${parameter.name} (${parameter.type})`===label);fireEvent.change(view.getByLabelText('테스트 1 · '+label),{target:{value:JSON.stringify(group.args[index])}});
      }
    }
  }
});

test('invalid typed JSON, wrong return type and oversized values never create jobs',async()=>{
  let calls=0;globalThis.__testRunApi=async()=>{calls++;return 70;};const view=render(React.createElement(Panel,props(generic)));
  const argument=view.getByLabelText('테스트 1 · numbers (int[])'),expected=view.getByLabelText('테스트 1 예상 출력');
  for(const value of ['not-json','[1,"2"]','[2147483648]']){fireEvent.change(argument,{target:{value}});fireEvent.click(view.getByRole('button',{name:'테스트 실행'}));await view.findByRole('alert');assert.equal(calls,0);}
  fireEvent.change(argument,{target:{value:'[1]'}});fireEvent.change(expected,{target:{value:'123'}});fireEvent.click(view.getByRole('button',{name:'테스트 실행'}));assert.match(view.getByRole('alert').textContent,/string/);assert.equal(calls,0);
  fireEvent.change(expected,{target:{value:'"'+('a'.repeat(65536))+'"'}});fireEvent.click(view.getByRole('button',{name:'테스트 실행'}));assert.match(view.getByRole('alert').textContent,/64 KiB/);assert.equal(calls,0);
});

test('raw input/expected and source byte limits reject oversized requests while preserving fields',async()=>{
  let calls=0;globalThis.__testRunApi=async()=>{calls++;return 70;};const view=render(React.createElement(Panel,props()));
  fireEvent.change(view.getByLabelText('테스트 1 입력'),{target:{value:'가'.repeat(22000)}});fireEvent.click(view.getByRole('button',{name:'테스트 실행'}));assert.match(view.getByRole('alert').textContent,/64 KiB/);assert.equal(calls,0);
  assert.equal(view.getByLabelText('테스트 1 입력').value.length,22000);
  view.rerender(React.createElement(Panel,{...props(),code:'가'.repeat(22000)}));fireEvent.click(view.getByRole('button',{name:'테스트 실행'}));assert.match(view.getByRole('alert').textContent,/코드는 UTF-8 64 KiB/);assert.equal(calls,0);
});

test('anonymous and unresolved identity cannot send test jobs',async()=>{
  let calls=0;globalThis.__testRunApi=async()=>{calls++;};globalThis.__testRunAuth={epoch:0,user:null,loading:false};const view=render(React.createElement(Panel,props()));
  fireEvent.click(view.getByRole('button',{name:'로그인 후 테스트'}));assert.match(view.getByRole('alert').textContent,/로그인/);assert.equal(calls,0);
  globalThis.__testRunAuth={epoch:0,user:null,loading:true};view.rerender(React.createElement(Panel,props()));fireEvent.click(view.getByRole('button',{name:'로그인 후 테스트'}));assert.match(view.getByRole('alert').textContent,/확인 중/);assert.equal(calls,0);
});

test('synchronous repeated clicks create one job and one-second polling shows the captured expected/actual output',async()=>{
  let release,posts=0,reads=0;globalThis.__testRunApi=async(path,method)=>{if(method==='POST'){posts++;return new Promise(resolve=>{release=resolve;});}reads++;return reads===1?{...completed(),status:'RUNNING',results:[]}:completed();};
  const view=render(React.createElement(Panel,props())),button=view.getByRole('button',{name:'테스트 실행'});
  fireEvent.click(button);fireEvent.click(button);assert.equal(posts,1);
  await act(async()=>{release(70);await tick();});await view.findByText(/코드를 실행하고 있습니다/);
  await waitFor(()=>view.getByRole('region',{name:'테스트 실행 결과'}),{timeout:2000});assert.equal(reads,2);
  const values=[...view.getByRole('article',{name:'테스트 1 결과'}).querySelectorAll('pre')].map(node=>node.textContent);assert.deepEqual(values,['3\n','3\n']);
});

test('source, language, problem or account changes hide results immediately and discard late responses',async()=>{
  for(const change of [{code:'print(4)'},{language:'JAVA'},{number:1001},{epoch:1}]){
    cleanup();let release;globalThis.__testRunAuth={epoch:0,user:{id:1},loading:false};globalThis.__testRunApi=async(path,method)=>method==='POST'?70:new Promise(resolve=>{release=resolve;});
    const view=render(React.createElement(Panel,props()));fireEvent.click(view.getByRole('button',{name:'테스트 실행'}));await waitFor(()=>assert.equal(typeof release,'function'));
    const next={...props(),...change};if(change.epoch)globalThis.__testRunAuth={epoch:1,user:{id:2},loading:false};
    view.rerender(React.createElement(Panel,next));await act(async()=>{release(completed([{status:'WRONG_ANSWER',actual:'old-account-output',timeMs:1}]));await tick();});
    assert.doesNotMatch(view.container.textContent,/old-account-output/);assert.equal(view.queryByRole('region',{name:'테스트 실행 결과'})===null,true);
  }
});

test('GET failure stops polling and offers explicit retry without replaying the POST',async()=>{
  let posts=0,reads=0;globalThis.__testRunApi=async(path,method)=>{if(method==='POST'){posts++;return 70;}reads++;throw Error('테스트 조회 권한이 없습니다.');};
  const view=render(React.createElement(Panel,props()));fireEvent.click(view.getByRole('button',{name:'테스트 실행'}));await view.findByRole('alert');
  assert.equal(posts,1);assert.equal(reads,1);assert.equal(view.getByRole('button',{name:'테스트 실행'}).disabled,false);assert.equal(view.queryByText(/자동 갱신합니다/)===null,true);
});

test('compiler failure shows safe diagnostic and malformed/mismatched responses never display output',async()=>{
  const cause={phase:'COMPILE',message:'문법을 확인하세요.',errorType:'SYNTAX_ERROR',locations:[{file:'main.py',line:2}]};
  globalThis.__testRunApi=async(path,method)=>method==='POST'?70:{...completed([]),diagnostic:cause};
  const view=render(React.createElement(Panel,props()));fireEvent.click(view.getByRole('button',{name:'테스트 실행'}));await view.findByRole('region',{name:'컴파일 오류 원인'});
  globalThis.__testRunApi=async(path,method)=>method==='POST'?70:{...completed([{status:'WRONG_ANSWER',actual:'wrong-owner-output'}]),problemNumber:9999};
  fireEvent.click(view.getByRole('button',{name:'테스트 실행'}));await view.findByRole('alert');assert.doesNotMatch(view.container.textContent,/wrong-owner-output|문법을 확인하세요/);
});

test('actual output is escaped text, null remains absent and test inputs/outputs are never stored locally',async()=>{
  globalThis.__testRunApi=async(path,method)=>method==='POST'?70:completed([{status:'WRONG_ANSWER',actual:'<script>test-secret()</script>\n<img src=x onerror=alert(1)>',timeMs:1}]);
  const view=render(React.createElement(Panel,props()));fireEvent.click(view.getByRole('button',{name:'테스트 실행'}));await view.findByRole('region',{name:'테스트 실행 결과'});
  assert.match(view.container.textContent,/<script>test-secret\(\)<\/script>/);assert.equal(view.container.querySelector('script,img')===null,true);assert.deepEqual(Object.keys(window.localStorage),[]);
  globalThis.__testRunApi=async(path,method)=>method==='POST'?70:completed([{status:'RUNTIME_ERROR',actual:null,timeMs:1,diagnostic:{phase:'RUN',message:'실행 중 예외가 발생했습니다.',errorType:'TYPE_ERROR',locations:[]}}]);
  fireEvent.click(view.getByRole('button',{name:'테스트 실행'}));await view.findByText('실제 출력 없음');assert.ok(view.getByRole('region',{name:'실행 오류 원인'}));
});

test('system failures never render diagnostic or returned output and mobile comparison stacks',async()=>{
  globalThis.__testRunApi=async(path,method)=>method==='POST'?70:{...completed([{status:'RUNTIME_ERROR',actual:'internal-output'}]),status:'SYSTEM_ERROR',diagnostic:{phase:'COMPILE',message:'internal-reason'}};
  const view=render(React.createElement(Panel,props()));fireEvent.click(view.getByRole('button',{name:'테스트 실행'}));await view.findByRole('alert');assert.doesNotMatch(view.container.textContent,/internal-output|internal-reason/);
  const css=readFileSync(new URL('../src/components/TestRunPanel.css',import.meta.url),'utf8');assert.match(css,/@media\(max-width:600px\).*grid-template-columns:1fr/s);
});
