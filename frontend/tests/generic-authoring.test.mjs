import test,{after} from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {build} from 'esbuild';
import {JSDOM} from 'jsdom';
import {installEditorDom,editorValue,setEditorValue} from './editor-dom.mjs';

const require=createRequire(import.meta.url),root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const dom=new JSDOM('<!doctype html><html><body></body></html>',{url:'http://localhost:8080/'});
Object.defineProperty(globalThis,'navigator',{value:dom.window.navigator,configurable:true});
for(const key of ['window','document','HTMLElement','Event','InputEvent','KeyboardEvent','MutationObserver'])globalThis[key]=dom.window[key];
installEditorDom(dom.window);
globalThis.IS_REACT_ACT_ENVIRONMENT=true;
const React=require('react'),{render,fireEvent,waitFor,cleanup,act}=require('@testing-library/react');
const {MemoryRouter,Routes,Route}=require('react-router-dom');
window.CobwebCodeEditor=require('../../src/main/resources/static/code-editor.js');
const modules=new Map();
async function component(name){
  if(modules.has(name))return modules.get(name);
  const result=await build({entryPoints:[path.join(root,'src',name)],bundle:true,write:false,platform:'node',format:'cjs',packages:'external',jsx:'automatic',loader:{'.css':'empty'},plugins:[{name:'generic-authoring-contracts',setup(builder){
    builder.onResolve({filter:/(?:^|\/)api(?:\.js)?$/},()=>({path:'api',namespace:'contract'}));
    builder.onResolve({filter:/\/context\/AuthContext(?:\.jsx)?$/},()=>({path:'auth',namespace:'contract'}));
    builder.onLoad({filter:/.*/,namespace:'contract'},args=>({contents:args.path==='api'?'export const api=(...args)=>globalThis.__genericAuthoringApi(...args); export const getSessionEpoch=()=>globalThis.__genericAuthoringSession;':'export const useAuth=()=>globalThis.__genericAuthoringAuth;'}));
  }}]});
  const module={exports:{}};new Function('require','module','exports',result.outputFiles[0].text)(require,module,module.exports);
  modules.set(name,module.exports.default);return module.exports.default;
}
function reset(){cleanup();window.localStorage.clear();globalThis.__genericAuthoringSession=0;globalThis.__genericAuthoringAuth={epoch:0,user:{username:'alice'},permissions:{canCreate:true,canManage:true}};}
const ids=['PYTHON','C','CPP','JAVA','JAVASCRIPT','TYPESCRIPT','CSHARP','GO','RUST','KOTLIN','RUBY','LLVM_IR','PASCAL','PHP','DART'];
const catalog=ids.map(id=>({id,label:id,modes:['STDIN','FUNCTION']}));
function editorRoute(Page,{entry='/problems/new',mode='new'}={}){
  return React.createElement(MemoryRouter,{initialEntries:[entry],future:{v7_startTransition:true,v7_relativeSplatPath:true}},React.createElement(Routes,null,
    React.createElement(Route,{path:'/problems/new',element:React.createElement(Page,{dark:true})}),
    React.createElement(Route,{path:'/problems/:id/edit',element:mode==='edit'?React.createElement(Page,{dark:true,mode:'edit'}):React.createElement('p',null,'generic-saved-route')})));
}
const fixture=()=>({number:1500,title:'범용 함수 원본',description:'배열과 문자열을 받는 함수입니다.',inputDescription:'JSON args',outputDescription:'JSON string[]',constraints:'ASCII only',rankType:'INITIATE',rankInt:1,timeLimitMs:2000,memoryLimitMb:256,executionMode:'FUNCTION',functionAbi:'GENERIC_V1',
  functionSignature:{name:'solution',parameters:[{name:'numbers',type:'int[]'},{name:'text',type:'string'}],returnType:'string[]'},
  visibility:'PUBLIC',currentVisibility:'PRIVATE',status:'DRAFT',groupId:null,canEdit:true,usedInContest:false,authorPublic:true,authorUsername:'source-owner',
  languages:['PYTHON','JAVASCRIPT'],tags:['Lv.0','함수'],sourceName:'교육 자료',sourceUrl:'https://example.com/level0',
  examples:[{input:'{"args":[[1,2],"a b"]}',output:'["a b"]',explanation:'공백 보존'}],
  tests:[{input:'{"args":[[],"a\\nb"]}',expected:'["a\\nb"]'}],
  starterCodes:[{language:'PYTHON',sourceCode:'def solution(numbers: list[int], text: str) -> list[str]:\n    return []\n'}],
  referenceSolutions:[{language:'PYTHON',fileName:'solution.py',sourceCode:'def solution(numbers, text):\n    return [text]\n'}]});
after(()=>{cleanup();dom.window.close();});

test('controlled signature editor starts with no arguments and preserves names/types across add/remove/return changes',async()=>{
  reset();const Signature=await component('components/FunctionSignatureEditor.jsx');
  function Harness(){const [value,setValue]=React.useState(null);return React.createElement(React.Fragment,null,React.createElement(Signature,{value,onChange:setValue}),React.createElement('output',{'data-testid':'signature'},JSON.stringify(value)));}
  const view=render(React.createElement(Harness));
  fireEvent.click(view.getByRole('button',{name:'범용 함수 규격 사용'}));
  assert.deepEqual(JSON.parse(view.getByTestId('signature').textContent),{name:'sol',parameters:[],returnType:'int'});
  assert.equal(view.queryByLabelText('매개변수 1 이름'),null);
  fireEvent.change(view.getByLabelText('함수 이름'),{target:{value:'solution'}});
  fireEvent.change(view.getByLabelText('반환형'),{target:{value:'string'}});
  for(const [name,type] of [['numbers','int[]'],['text','string'],['ratio','float'],['enabled','bool']]){
    fireEvent.click(view.getByRole('button',{name:'매개변수 추가'}));
    const count=JSON.parse(view.getByTestId('signature').textContent).parameters.length;
    fireEvent.change(view.getByLabelText(`매개변수 ${count} 이름`),{target:{value:name}});
    fireEvent.change(view.getByLabelText(`매개변수 ${count} 자료형`),{target:{value:type}});
  }
  assert.equal(view.getByRole('button',{name:'매개변수 추가'}).disabled,false);
  for(let i=0;i<2;i++)fireEvent.click(view.getByRole('button',{name:'매개변수 추가'}));
  assert.equal(view.getByRole('button',{name:'매개변수 추가'}).disabled,true);
  fireEvent.click(view.getByRole('button',{name:'매개변수 6 삭제'}));
  fireEvent.click(view.getByRole('button',{name:'매개변수 5 삭제'}));
  fireEvent.click(view.getByRole('button',{name:'매개변수 2 삭제'}));
  assert.equal(view.getByRole('button',{name:'매개변수 추가'}).disabled,false);
  assert.equal(view.getByLabelText('매개변수 2 이름').value,'ratio');
  fireEvent.change(view.getByLabelText('반환형'),{target:{value:'string[]'}});
  assert.deepEqual(JSON.parse(view.getByTestId('signature').textContent),{name:'solution',parameters:[{name:'numbers',type:'int[]'},{name:'ratio',type:'float'},{name:'enabled',type:'bool'}],returnType:'string[]'});
  assert.match(view.container.textContent,/ASCII/);cleanup();
});

test('new generic function authoring saves the edited signature and JSON examples/tests without read-only ABI metadata',async()=>{
  reset();const Page=await component('pages/ProblemEditor.jsx'),calls=[];
  globalThis.__genericAuthoringApi=async(path,method='GET',body)=>{calls.push({path,method,body});if(path==='/api/languages')return catalog;if(path==='/api/groups/mine')return [];if(path==='/api/problems'&&method==='POST')return 1501;throw Error('unexpected '+path);};
  const view=render(editorRoute(Page));await waitFor(()=>view.getByRole('checkbox',{name:'PYTHON'}));
  fireEvent.change(view.getByLabelText('문제 제목'),{target:{value:'문자열 반복 함수'}});
  fireEvent.change(view.getByLabelText('문제 설명 · Markdown'),{target:{value:'주어진 문자열을 반복하세요.'}});
  fireEvent.change(view.getByLabelText('실행 형식'),{target:{value:'FUNCTION'}});
  fireEvent.click(view.getByRole('button',{name:'범용 함수 규격 사용'}));
  assert.equal(view.queryByText(/기존 FUNCTION 입력:/),null);
  fireEvent.change(view.getByLabelText('함수 이름'),{target:{value:'solution'}});
  fireEvent.change(view.getByLabelText('반환형'),{target:{value:'string'}});
  for(const [index,name,type] of [[1,'text','string'],[2,'count','int']]){
    fireEvent.click(view.getByRole('button',{name:'매개변수 추가'}));
    fireEvent.change(view.getByLabelText(`매개변수 ${index} 이름`),{target:{value:name}});
    fireEvent.change(view.getByLabelText(`매개변수 ${index} 자료형`),{target:{value:type}});
  }
  fireEvent.change(view.getByLabelText('예제 입력'),{target:{value:'{"args":["a b",2]}'}});
  fireEvent.change(view.getByLabelText('예제 출력'),{target:{value:'"a ba b"'}});
  fireEvent.click(view.getByRole('button',{name:'테스트케이스 추가'}));
  fireEvent.change(view.getByLabelText('입력'),{target:{value:'{"args":["a\\nb",2]}'}});
  fireEvent.change(view.getByLabelText('기대 출력'),{target:{value:'"a\\nba\\nb"'}});
  fireEvent.click(view.getByRole('button',{name:'시작 코드 추가'}));
  assert.match(editorValue(view.getByRole('textbox',{name:'공개 시작 코드 1'})),/def solution\(text: str, count: int\) -> str:/);
  fireEvent.change(view.getByLabelText('태그 (쉼표로 구분, 최대 10개)'),{target:{value:'Lv.0, 문자열'}});
  fireEvent.click(view.getByRole('button',{name:'초안 저장'}));await waitFor(()=>view.getByText('generic-saved-route'));
  const payload=calls.find(call=>call.path==='/api/problems'&&call.method==='POST').body;
  assert.deepEqual(payload.functionSignature,{name:'solution',parameters:[{name:'text',type:'string'},{name:'count',type:'int'}],returnType:'string'});
  assert.equal(payload.executionMode,'FUNCTION');assert.equal(Object.hasOwn(payload,'functionAbi'),false);
  assert.deepEqual(payload.tags,['Lv.0','문자열']);assert.deepEqual(payload.tests,[{input:'{"args":["a\\nb",2]}',expected:'"a\\nba\\nb"'}]);
  assert.equal(payload.examples[0].input,'{"args":["a b",2]}');assert.match(payload.starterCodes[0].sourceCode,/solution\(text: str, count: int\)/);cleanup();
});

for(const cloning of [false,true])test(`loaded generic ${cloning?'clone':'edit'} preserves signature, custom starters and private authoring content`,async()=>{
  reset();const Page=await component('pages/ProblemEditor.jsx'),calls=[],source=fixture();
  if(cloning){source.status='PUBLISHED';source.currentVisibility='PUBLIC';source.canEdit=false;source.usedInContest=true;}
  globalThis.__genericAuthoringApi=async(path,method='GET',body)=>{calls.push({path,method,body});if(path==='/api/languages')return catalog;if(path==='/api/groups/mine')return [];if(path==='/api/problems/1500/edit')return source;if(method==='PUT')return null;if(path==='/api/problems'&&method==='POST')return 1501;throw Error('unexpected '+path);};
  const entry=cloning?{pathname:'/problems/new',state:{cloneNumber:1500}}:'/problems/1500/edit';
  const view=render(editorRoute(Page,{entry,mode:cloning?'new':'edit'}));
  await waitFor(()=>assert.equal(view.getByLabelText('문제 제목').value,source.title+(cloning?' 복제':'')));
  assert.equal(view.getByLabelText('함수 이름').value,'solution');assert.equal(view.getByLabelText('반환형').value,'string[]');
  assert.equal(view.getByLabelText('매개변수 1 이름').value,'numbers');assert.equal(view.getByLabelText('매개변수 1 자료형').value,'int[]');
  assert.equal(view.getByLabelText('매개변수 2 이름').value,'text');assert.equal(view.getByLabelText('매개변수 2 자료형').value,'string');
  assert.equal(editorValue(view.getByRole('textbox',{name:'공개 시작 코드 1'})),source.starterCodes[0].sourceCode);
  assert.equal(editorValue(view.getByRole('textbox',{name:'정답 코드 1'})),source.referenceSolutions[0].sourceCode);
  fireEvent.click(view.getByRole('button',{name:'초안 저장'}));
  await waitFor(()=>assert.ok(calls.some(call=>call.method===(cloning?'POST':'PUT'))));
  const payload=calls.find(call=>call.method===(cloning?'POST':'PUT')).body;
  for(const field of ['functionSignature','starterCodes','tests','examples','referenceSolutions','tags'])assert.deepEqual(payload[field],source[field],field);
  assert.equal(payload.executionMode,'FUNCTION');assert.equal(payload.authorPublic,!cloning);
  for(const field of ['functionAbi','number','canEdit','usedInContest','authorUsername'])assert.equal(Object.hasOwn(payload,field),false,field);
  if(cloning)await waitFor(()=>view.getByText('generic-saved-route'));cleanup();
});

const arrayDeclarations={
  PYTHON:/def solution\(numbers: list\[int\], lines: list\[str\]\) -> str:/,
  C:/char \* solution\(int \* numbers, int numbers_length, CWString \* lines, int lines_length, int \*result_length\)/,
  CPP:/std::string solution\(std::vector<int> numbers, std::vector<std::string> lines\)/,
  JAVA:/public static String solution\(int\[\] numbers, String\[\] lines\)/,
  JAVASCRIPT:/function solution\(numbers, lines\)/,
  TYPESCRIPT:/export function solution\(numbers: number\[\], lines: string\[\]\): string/,
  CSHARP:/public static string solution\(int\[\] numbers, string\[\] lines\)/,
  GO:/func solution\(numbers \[\]int, lines \[\]string\) string/,
  RUST:/fn solution\(numbers: Vec<i32>, lines: Vec<String>\) -> String/,
  KOTLIN:/fun solution\(numbers: IntArray, lines: Array<String>\): String/,
  RUBY:/def solution\(numbers, lines\)/,
  LLVM_IR:/define ptr @solution\(ptr %numbers, i32 %numbers_length, ptr %lines, i32 %lines_length, ptr %result_length\)/,
  PASCAL:/function solution\(numbers: TIntArray; lines: TStringArray\): AnsiString;/,
  PHP:/function solution\(array \$numbers, array \$lines\): string/,
  DART:/String solution\(List<int> numbers, List<String> lines\)/,
};
test('generic problem detail switches all 15 languages using its published parameters and preserves each language draft',async()=>{
  reset();const Page=await component('pages/ProblemDetail.jsx');
  const problem={problem_number:1500,problem_title:'배열 함수',description:'본문',execution_mode:'FUNCTION',rank_type:'INITIATE',rank_int:1,time_limit_ms:2000,memory_limit_mb:256,languages:ids,examples:[],functionAbi:'GENERIC_V1',functionSignature:{name:'solution',parameters:[{name:'numbers',type:'int[]'},{name:'lines',type:'string[]'}],returnType:'string'}};
  globalThis.__genericAuthoringApi=async path=>{if(path==='/api/languages')return catalog;if(path==='/api/problems/1500')return problem;throw Error('unexpected '+path);};
  const view=render(React.createElement(MemoryRouter,{initialEntries:['/problems/1500'],future:{v7_startTransition:true,v7_relativeSplatPath:true}},React.createElement(Routes,null,React.createElement(Route,{path:'/problems/:id',element:React.createElement(Page,{dark:true})}))));
  await waitFor(()=>view.getByRole('heading',{name:'1500. 배열 함수'}));
  for(const language of ids){
    fireEvent.change(view.getByLabelText('언어'),{target:{value:language}});
    await waitFor(()=>assert.match(editorValue(view.getByRole('textbox',{name:'풀이 코드'})),arrayDeclarations[language]));
    assert.doesNotMatch(view.container.textContent,/solution\(numbers: int\[\], lines: string\[\]\) → string/);
    assert.doesNotMatch(editorValue(view.getByRole('textbox',{name:'풀이 코드'})),/matrix|target/);
    if(language==='PYTHON')act(()=>setEditorValue(view.getByRole('textbox',{name:'풀이 코드'}),'def solution(numbers, lines):\n    return "draft"\n'));
  }
  fireEvent.change(view.getByLabelText('언어'),{target:{value:'PYTHON'}});
  assert.equal(editorValue(view.getByRole('textbox',{name:'풀이 코드'})),'def solution(numbers, lines):\n    return "draft"\n');cleanup();
});

test('generic problem detail displays a no-argument string function without legacy matrix parameters',async()=>{
  reset();const Page=await component('pages/ProblemDetail.jsx');
  globalThis.__genericAuthoringApi=async path=>path==='/api/languages'?catalog:{problem_number:1000,problem_title:'Hello World!',description:'문자열 반환',execution_mode:'FUNCTION',functionAbi:'GENERIC_V1',functionSignature:{name:'sol',parameters:[],returnType:'string'},languages:['PYTHON','JAVASCRIPT'],examples:[]};
  const view=render(React.createElement(MemoryRouter,{initialEntries:['/problems/1000'],future:{v7_startTransition:true,v7_relativeSplatPath:true}},React.createElement(Routes,null,React.createElement(Route,{path:'/problems/:id',element:React.createElement(Page,{dark:true})}))));
  await waitFor(()=>assert.match(editorValue(view.getByRole('textbox',{name:'풀이 코드'})),/def sol\(\) -> str:/));
  assert.doesNotMatch(view.container.textContent,/sol\(\) → string/);
  fireEvent.change(view.getByLabelText('언어'),{target:{value:'JAVASCRIPT'}});
  assert.match(editorValue(view.getByRole('textbox',{name:'풀이 코드'})),/function sol\(\)/);assert.doesNotMatch(view.container.textContent,/matrix|target/);cleanup();
});
