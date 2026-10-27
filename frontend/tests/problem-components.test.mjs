import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {readFileSync} from 'node:fs';
import {build} from 'esbuild';
import {JSDOM} from 'jsdom';
import {installEditorDom,editorValue,setEditorValue,selectEditor} from './editor-dom.mjs';
const require=createRequire(import.meta.url),root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const dom=new JSDOM('<!doctype html><html><body></body></html>',{url:'http://localhost:8080/'});
Object.defineProperty(globalThis,'navigator',{value:dom.window.navigator,configurable:true});
for(const key of ['window','document','HTMLElement','Event','InputEvent','KeyboardEvent','MutationObserver'])globalThis[key]=dom.window[key];
installEditorDom(dom.window);
globalThis.IS_REACT_ACT_ENVIRONMENT=true;
const React=require('react'),{render,fireEvent,waitFor,cleanup,act}=require('@testing-library/react'),{MemoryRouter,Routes,Route,useLocation,useNavigate}=require('react-router-dom');
window.CobwebCodeEditor=require('../../src/main/resources/static/code-editor.js');
const modules=new Map();
async function component(name){
  if(modules.has(name))return modules.get(name);
  const result=await build({entryPoints:[path.join(root,'src',name)],bundle:true,write:false,platform:'node',format:'cjs',packages:'external',jsx:'automatic',loader:{'.css':'empty'},plugins:[{name:'test-contracts',setup(builder){
    builder.onResolve({filter:/(?:^|\/)api(?:\.js)?$/},()=>({path:'api',namespace:'contract'}));
    builder.onResolve({filter:/\/context\/AuthContext(?:\.jsx)?$/},()=>({path:'auth',namespace:'contract'}));
    builder.onLoad({filter:/.*/,namespace:'contract'},args=>({contents:args.path==='api'?'export const api=(...args)=>globalThis.__problemApi(...args); export const getSessionEpoch=()=>globalThis.__problemSession;':'export const useAuth=()=>globalThis.__problemAuth;'}));
  }}]});
  const module={exports:{}};new Function('require','module','exports',result.outputFiles[0].text)(require,module,module.exports);modules.set(name,module.exports.default);return module.exports.default;
}
const tick=()=>new Promise(resolve=>setTimeout(resolve,0));
function reset(){cleanup();window.localStorage.clear();globalThis.__problemSession=0;globalThis.__problemAuth={epoch:0,user:{username:'alice'},permissions:{canCreate:true,canManage:true}};}
function route(Page,url='/problems/1000',pattern='/problems/:id'){
  return React.createElement(MemoryRouter,{initialEntries:[url],future:{v7_startTransition:true,v7_relativeSplatPath:true}},React.createElement(Routes,null,React.createElement(Route,{path:pattern,element:React.createElement(Page,{dark:true})}),React.createElement(Route,{path:'/submissions/:id',element:React.createElement('p',null,'real-result-route')})));
}

test('controlled editor uses four spaces, Enter indentation, ShiftTab outdent and Esc focus escape',async()=>{
  reset();const Editor=await component('components/CodeEditor.jsx');
  function Harness(){const [value,setValue]=React.useState('def sol():');return React.createElement(Editor,{value,onChange:setValue,language:'PYTHON'});}
  const view=render(React.createElement(Harness)),area=view.getByRole('textbox');act(()=>selectEditor(area,editorValue(area).length));
  fireEvent.keyDown(area,{key:'Enter'});assert.equal(editorValue(area),'def sol():\n    ');
  fireEvent.keyDown(area,{key:'Tab'});assert.equal(editorValue(area),'def sol():\n        ');
  fireEvent.keyDown(area,{key:'Tab',shiftKey:true});assert.equal(editorValue(area),'def sol():\n    ');
  fireEvent.keyDown(area,{key:'Escape'});assert.equal(fireEvent.keyDown(area,{key:'Tab'}),true);assert.equal(editorValue(area),'def sol():\n    ');
  fireEvent.keyDown(area,{key:'Enter',isComposing:true,keyCode:229});assert.equal(editorValue(area),'def sol():\n    ');cleanup();
});

test('contest capture protection can remove the explicit copy action',async()=>{
  reset();const Editor=await component('components/CodeEditor.jsx');const view=render(React.createElement(Editor,{value:'private answer',onChange:()=>{},allowCopy:false}));
  assert.equal(view.queryByRole('button',{name:'코드 복사'})===null,true);assert.equal(view.getByRole('textbox').hasAttribute('allowcopy'),false);cleanup();
});

test('problem statement shows persisted tags and only safe HTTP/HTTPS source links',async()=>{
  reset();const Statement=await component('components/ProblemStatement.jsx');const view=render(React.createElement(Statement,{problem:{tags:['BFS','<script>literal</script>'],sourceName:'공식 출처',sourceUrl:'https://example.com/problems/1000'}}));
  const link=view.getByRole('link',{name:'공식 출처'});assert.equal(link.href,'https://example.com/problems/1000');assert.equal(link.target,'_blank');assert.equal(link.rel,'noopener noreferrer');assert.equal(view.getByRole('list',{name:'문제 태그'}).children.length,2);assert.equal(view.container.querySelector('script')===null,true);
  view.rerender(React.createElement(Statement,{problem:{sourceUrl:'http://example.com/source'}}));assert.equal(view.getByRole('link',{name:'http://example.com/source'}).href,'http://example.com/source');
  for(const sourceUrl of ['javascript:alert(1)','data:text/html,<script>alert(1)</script>','//example.com/source','https://user:secret@example.com/source','https://example.com/\nsource']){
    view.rerender(React.createElement(Statement,{problem:{sourceName:'출처 이름은 보존',sourceUrl}}));assert.equal(view.queryByRole('link')===null,true);assert.match(view.container.textContent,/출처 이름은 보존/);
  }
  cleanup();
});

test('problem content is a labelled keyboard-scrollable panel with independently bounded height',async()=>{
  reset();const Statement=await component('components/ProblemStatement.jsx'),css=readFileSync(new URL('../src/components/ProblemStatement.css',import.meta.url),'utf8');
  const view=render(React.createElement('div',null,React.createElement('style',null,css),React.createElement(Statement,{problem:{description:'문제 본문',inputDescription:'입력 안내',outputDescription:'출력 안내',constraints:'제한 안내',tags:['탐색']}})));
  const panel=view.getByRole('article',{name:'문제 설명 스크롤 영역'});assert.equal(panel.tabIndex,0);act(()=>panel.focus());assert.equal(document.activeElement,panel);
  const style=window.getComputedStyle(panel);assert.equal(style.overflow,'auto');assert.equal(style.overscrollBehaviorY,'contain');assert.equal(style.scrollbarGutter,'stable');assert.match(style.maxHeight,/clamp\(260px/);
  for(const text of ['문제 본문','입력 안내','출력 안내','제한 안내','탐색'])assert.ok(panel.textContent.includes(text));
  assert.match(css,/@media\s*\(max-width:\s*1100px\)/);assert.match(css,/60dvh/);cleanup();
});

test('problem detail uses requested real problem and posts exact submission DTO',async()=>{
  reset();const Page=await component('pages/ProblemDetail.jsx'),calls=[];
  globalThis.__problemApi=async(path,method='GET',body)=>{calls.push({path,method,body});if(path==='/api/languages')return [{id:'PYTHON',label:'Python',modes:['STDIN','FUNCTION']},{id:'DART',label:'Dart',modes:['STDIN','FUNCTION']}];if(path==='/api/submissions')return 77;if(path==='/api/submissions/77')return {id:77,status:'ACCEPTED',score:100,cases:[{status:'ACCEPTED',execution_time_ms:1}]};return {problem_number:10828,problem_title:'실제 스택 문제',description:'본문',execution_mode:'STDIN',rank_type:'ELITE',rank_int:5,time_limit_ms:1000,memory_limit_mb:256,languages:['PYTHON','DART'],examples:[]};};
  const view=render(route(Page,'/problems/10828'));await waitFor(()=>view.getByRole('heading',{name:'10828. 실제 스택 문제'}));
  assert.ok(calls.some(c=>c.path==='/api/problems/10828'));assert.equal(view.getByLabelText('언어').options.length,2);
  act(()=>setEditorValue(view.getByRole('textbox',{name:'풀이 코드'}),'print(1)'));fireEvent.click(view.getByRole('button',{name:'제출 및 채점'}));await waitFor(()=>view.getByText('정답입니다. 해결 완료로 기록되었습니다.'));
  assert.equal(view.queryByText('real-result-route'),null);assert.equal(editorValue(view.getByRole('textbox',{name:'풀이 코드'})),'print(1)');assert.ok(view.getByRole('heading',{name:'10828. 실제 스택 문제'}));
  assert.deepEqual(calls.find(c=>c.path==='/api/submissions'),{path:'/api/submissions',method:'POST',body:{problemNumber:10828,language:'PYTHON',sourceCode:'print(1)'}});cleanup();
});

test('late problem response is aborted/ignored and cannot enter a new route',async()=>{
  reset();const Page=await component('pages/ProblemDetail.jsx');let resolveOld;
  globalThis.__problemApi=path=>path==='/api/languages'?Promise.resolve([{id:'PYTHON',label:'Python',modes:['STDIN']}]) : new Promise(resolve=>{resolveOld=resolve;});
  const view=render(route(Page,'/problems/1000'));await act(tick);view.unmount();
  await act(async()=>{resolveOld({problem_number:1000,problem_title:'늦은 비공개 자료',languages:['PYTHON'],examples:[]});await tick();});
  assert.doesNotMatch(document.body.textContent,/늦은 비공개 자료/);cleanup();
});

test('ordinary submission renders real system-error result without fabricating source or private tests',async()=>{
  reset();const Page=await component('pages/SubmissionResult.jsx');globalThis.__problemApi=async()=>({id:42,status:'SYSTEM_ERROR',language:'DART',score:0,cases:[{status:'SYSTEM_ERROR',execution_time_ms:0}]});
  const view=render(React.createElement(MemoryRouter,{initialEntries:['/submissions/42'],future:{v7_startTransition:true,v7_relativeSplatPath:true}},React.createElement(Routes,null,React.createElement(Route,{path:'/submissions/:id',element:React.createElement(Page,{dark:true})}))));
  await waitFor(()=>view.getByRole('heading',{name:'시스템 오류'}));assert.match(view.container.textContent,/일반 제출 소스 코드는 현재 서버 API에서 반환하지 않습니다/);assert.doesNotMatch(view.container.textContent,/정답입니다|상위 18%|gcc 12/);cleanup();
});

test('new problem saves a real draft DTO without requiring invented score settings',async()=>{
  reset();const Page=await component('pages/ProblemEditor.jsx'),calls=[];
  const catalog=[{id:'PYTHON',label:'Python',modes:['STDIN','FUNCTION']},{id:'SQL',label:'SQL',modes:['SQL']},{id:'UMJUNSIK',label:'엄랭',modes:['STDIN']}];
  globalThis.__problemApi=async(path,method='GET',body)=>{calls.push({path,method,body});if(path==='/api/languages')return catalog;if(path==='/api/groups/mine')return [];if(path==='/api/problems'&&method==='POST')return 2001;throw Error('unexpected '+path);};
  const view=render(React.createElement(MemoryRouter,{initialEntries:['/problems/new'],future:{v7_startTransition:true,v7_relativeSplatPath:true}},React.createElement(Routes,null,React.createElement(Route,{path:'/problems/new',element:React.createElement(Page,{dark:true})}),React.createElement(Route,{path:'/problems/:id/edit',element:React.createElement('p',null,'saved-edit-route')}))));
  await waitFor(()=>view.getByRole('checkbox',{name:'엄랭'}));fireEvent.change(view.getByLabelText('문제 제목'),{target:{value:'실제 새 문제'}});fireEvent.change(view.getByLabelText('문제 설명 · Markdown'),{target:{value:'**본문**'}});
  fireEvent.click(view.getByRole('button',{name:'초안 저장'}));await waitFor(()=>view.getByText('saved-edit-route'));
  const body=calls.find(call=>call.path==='/api/problems'&&call.method==='POST').body;
  assert.equal(body.title,'실제 새 문제');assert.deepEqual(body.languages,['PYTHON','UMJUNSIK']);assert.deepEqual(body.tests,[{input:'',expected:''}]);assert.equal(body.compare,undefined);assert.equal(body.scoring,undefined);assert.deepEqual(body.referenceSolutions,[]);cleanup();
});

test('author starter rows are public, unique and removed together with deselected languages',async()=>{
  reset();const Page=await component('pages/ProblemEditor.jsx'),calls=[];
  const catalog=[{id:'PYTHON',label:'Python',modes:['STDIN','FUNCTION']},{id:'JAVASCRIPT',label:'JavaScript',modes:['STDIN','FUNCTION']}];
  globalThis.__problemApi=async(path,method='GET',body)=>{calls.push({path,method,body});if(path==='/api/languages')return catalog;if(path==='/api/groups/mine')return [];if(path==='/api/problems'&&method==='POST')return 2001;throw Error('unexpected '+path);};
  const view=render(React.createElement(MemoryRouter,{initialEntries:['/problems/new']},React.createElement(Routes,null,React.createElement(Route,{path:'/problems/new',element:React.createElement(Page,{dark:true})}),React.createElement(Route,{path:'/problems/:id/edit',element:React.createElement('p',null,'starter-saved')}))));
  await waitFor(()=>view.getByRole('checkbox',{name:'JavaScript'}));
  fireEvent.change(view.getByLabelText('문제 제목'),{target:{value:'공개 시작 코드 문제'}});fireEvent.change(view.getByLabelText('문제 설명 · Markdown'),{target:{value:'본문'}});
  fireEvent.click(view.getByRole('button',{name:'시작 코드 추가'}));
  const python=view.getByRole('textbox',{name:'공개 시작 코드 1'}),source='def sol(x: int, y: int):\n    pass\n';
  assert.match(editorValue(python),/import sys/);act(()=>setEditorValue(python,source));
  fireEvent.click(view.getByRole('button',{name:'시작 코드 추가'}));assert.equal(view.getByRole('button',{name:'시작 코드 추가'}).disabled,true);
  assert.equal(view.getByLabelText('시작 코드 1 언어').value,'PYTHON');assert.equal(view.getByLabelText('시작 코드 2 언어').value,'JAVASCRIPT');
  assert.equal(view.getByLabelText('시작 코드 1 언어').options.length,1);assert.match(view.container.textContent,/비공개 정답 코드나 테스트를 넣지 마세요/);
  fireEvent.click(view.getByRole('checkbox',{name:'JavaScript'}));assert.equal(view.queryByRole('textbox',{name:'공개 시작 코드 2'})===null,true);
  assert.equal(editorValue(view.getByRole('textbox',{name:'공개 시작 코드 1'})),source);
  fireEvent.click(view.getByRole('button',{name:'초안 저장'}));await waitFor(()=>view.getByText('starter-saved'));
  const body=calls.find(call=>call.path==='/api/problems'&&call.method==='POST').body;
  assert.deepEqual(body.starterCodes,[{language:'PYTHON',sourceCode:source}]);assert.deepEqual(body.referenceSolutions,[]);cleanup();
});

test('author explicitly creates the current FUNCTION ABI default but does not send read-only ABI metadata',async()=>{
  reset();const Page=await component('pages/ProblemEditor.jsx'),calls=[];
  const catalog=[{id:'PYTHON',label:'Python',modes:['STDIN','FUNCTION']},{id:'UMJUNSIK',label:'엄랭',modes:['STDIN']}];
  globalThis.__problemApi=async(path,method='GET',body)=>{calls.push({path,method,body});if(path==='/api/languages')return catalog;if(path==='/api/groups/mine')return [];if(path==='/api/problems'&&method==='POST')return 2001;throw Error('unexpected '+path);};
  const view=render(React.createElement(MemoryRouter,{initialEntries:['/problems/new']},React.createElement(Routes,null,React.createElement(Route,{path:'/problems/new',element:React.createElement(Page,{dark:true})}),React.createElement(Route,{path:'/problems/:id/edit',element:React.createElement('p',null,'function-starter-saved')}))));
  await waitFor(()=>view.getByRole('checkbox',{name:'엄랭'}));fireEvent.change(view.getByLabelText('실행 형식'),{target:{value:'FUNCTION'}});
  fireEvent.change(view.getByLabelText('문제 제목'),{target:{value:'함수 기본형 문제'}});fireEvent.change(view.getByLabelText('문제 설명 · Markdown'),{target:{value:'본문'}});
  fireEvent.click(view.getByRole('button',{name:'시작 코드 추가'}));
  assert.match(editorValue(view.getByRole('textbox',{name:'공개 시작 코드 1'})),/def sol\(matrix: list\[list\[int\]\], target: tuple\[int, int\]\)/);
  assert.equal(view.queryByRole('checkbox',{name:'엄랭'})===null,true);
  fireEvent.click(view.getByRole('button',{name:'초안 저장'}));await waitFor(()=>view.getByText('function-starter-saved'));
  const body=calls.find(call=>call.path==='/api/problems'&&call.method==='POST').body;
  assert.equal(body.executionMode,'FUNCTION');assert.equal(body.starterCodes.length,1);assert.equal(body.functionAbi,undefined);cleanup();
});

test('editable ordinary draft can change requested publication scope without changing its group',async()=>{
  reset();const Page=await component('pages/ProblemEditor.jsx'),calls=[];
  const starterCodes=[{language:'PYTHON',sourceCode:'def sol(x: int, y: int):\n    pass\n'}];
  globalThis.__problemApi=async(path,method='GET',body)=>{calls.push({path,method,body});if(path==='/api/languages')return [{id:'PYTHON',label:'Python',modes:['STDIN','FUNCTION']}];if(path==='/api/groups/mine')return [];if(method==='PUT')return null;return {number:2001,title:'초안',description:'본문',rankType:'INITIATE',rankInt:5,timeLimitMs:1000,memoryLimitMb:256,executionMode:'STDIN',visibility:'PUBLIC',currentVisibility:'PRIVATE',status:'DRAFT',groupId:null,canEdit:true,usedInContest:false,languages:['PYTHON'],examples:[],tests:[],tags:[],referenceSolutions:[],starterCodes};};
  const view=render(React.createElement(MemoryRouter,{initialEntries:['/problems/2001/edit']},React.createElement(Routes,null,React.createElement(Route,{path:'/problems/:id/edit',element:React.createElement(Page,{dark:true,mode:'edit'})}))));
  await waitFor(()=>assert.equal(view.getByLabelText('문제 제목').value,'초안'));
  const scope=view.getByLabelText('공개 범위');assert.equal(scope.disabled,false);fireEvent.change(scope,{target:{value:'PRIVATE'}});fireEvent.click(view.getByRole('button',{name:'초안 저장'}));
  await waitFor(()=>assert.ok(calls.some(call=>call.method==='PUT')));const payload=calls.find(call=>call.method==='PUT').body;assert.equal(payload.visibility,'PRIVATE');assert.equal(payload.groupId,null);assert.equal(payload.canEdit,undefined);assert.deepEqual(payload.starterCodes,starterCodes);cleanup();
});

function EditorHistory({Page}){
  const location=useLocation(),navigate=useNavigate();
  return React.createElement(React.Fragment,null,
    React.createElement('pre',{'data-testid':'history-state'},JSON.stringify(location.state)),
    React.createElement('button',{onClick:()=>navigate(-1)},'history-back'),React.createElement('button',{onClick:()=>navigate(1)},'history-forward'),
    React.createElement(Routes,null,React.createElement(Route,{path:'/problems/:id/edit',element:React.createElement(Page,{dark:true,mode:'edit'})}),React.createElement(Route,{path:'/problems/new',element:React.createElement(Page,{dark:true})})));
}
const cloneSource=()=>({number:1000,title:'그룹 원본',description:'비공개 그룹 본문',rankType:'ELITE',rankInt:5,timeLimitMs:1000,memoryLimitMb:256,executionMode:'STDIN',visibility:'GROUP',currentVisibility:'GROUP',status:'PUBLISHED',groupId:7,canEdit:false,usedInContest:true,languages:['PYTHON'],examples:[],tests:[{input:'private-test-input',expected:'private-test-answer'}],tags:['그룹'],referenceSolutions:[{language:'PYTHON',fileName:'main.py',sourceCode:'private-reference-source'}]});
function cloneApiSupport(path){if(path==='/api/languages')return [{id:'PYTHON',label:'Python',modes:['STDIN','FUNCTION']}];if(path==='/api/groups/mine')return [{id:7,groupName:'원본 그룹',permissions:['CREATE_PROBLEMS']}];return null;}

test('clone history stores only its source number and rechecks permission on account and back/forward navigation',async()=>{
  reset();const Page=await component('pages/ProblemEditor.jsx'),calls=[];
  globalThis.__problemApi=async path=>{const support=cloneApiSupport(path);if(support)return support;calls.push({path,epoch:globalThis.__problemSession});if(globalThis.__problemSession!==0)throw Error('원본 접근 권한이 없습니다.');return cloneSource();};
  const tree=()=>React.createElement(MemoryRouter,{initialEntries:['/problems/1000/edit']},React.createElement(EditorHistory,{Page}));
  const view=render(tree());await waitFor(()=>view.getByRole('button',{name:'새 문제로 복제'}));fireEvent.click(view.getByRole('button',{name:'새 문제로 복제'}));
  await waitFor(()=>assert.equal(view.getByLabelText('문제 제목').value,'그룹 원본 복제'));
  assert.deepEqual(JSON.parse(view.getByTestId('history-state').textContent),{cloneNumber:1000});assert.equal(calls.filter(call=>call.path==='/api/problems/1000/edit').length,2);
  assert.equal(view.getByLabelText('그룹 문제로 추가').value,'7');assert.equal(view.getByLabelText('공개 범위').value,'GROUP');assert.equal(editorValue(view.getByLabelText('정답 코드 1')),'private-reference-source');
  globalThis.__problemSession=1;globalThis.__problemAuth={...globalThis.__problemAuth,epoch:1,user:{username:'bob'}};view.rerender(tree());
  await waitFor(()=>view.getByRole('alert'));assert.match(view.getByRole('alert').textContent,/원본 접근 권한/);assert.equal(view.queryByLabelText('문제 제목')===null,true);
  fireEvent.click(view.getByRole('button',{name:'history-back'}));await waitFor(()=>assert.ok(calls.filter(call=>call.epoch===1).length>=2));
  fireEvent.click(view.getByRole('button',{name:'history-forward'}));await waitFor(()=>assert.ok(calls.filter(call=>call.epoch===1).length>=3));
  assert.deepEqual(JSON.parse(view.getByTestId('history-state').textContent),{cloneNumber:1000});assert.doesNotMatch(view.container.textContent,/비공개 그룹 본문|private-test-input|private-test-answer|private-reference-source/);cleanup();
});

test('legacy clone payload is removed from history and cannot bypass a fresh denied source read',async()=>{
  reset();const Page=await component('pages/ProblemEditor.jsx');
  globalThis.__problemApi=async path=>{const support=cloneApiSupport(path);if(support)return support;throw Error('원본 접근 거부');};
  const view=render(React.createElement(MemoryRouter,{initialEntries:[{pathname:'/problems/new',state:{clone:cloneSource()}}]},React.createElement(EditorHistory,{Page})));
  await waitFor(()=>assert.deepEqual(JSON.parse(view.getByTestId('history-state').textContent),{cloneNumber:1000}));await waitFor(()=>view.getByRole('alert'));
  assert.doesNotMatch(view.container.textContent,/비공개 그룹 본문|private-test-input|private-test-answer|private-reference-source/);assert.equal(view.queryByLabelText('문제 제목')===null,true);cleanup();
});

test('late clone source from the previous account is discarded after the session epoch changes',async()=>{
  reset();const Page=await component('pages/ProblemEditor.jsx');let resolveOld;
  globalThis.__problemApi=path=>{const support=cloneApiSupport(path);if(support)return Promise.resolve(support);if(globalThis.__problemSession===0)return new Promise(resolve=>{resolveOld=resolve;});return Promise.reject(Error('새 계정 접근 거부'));};
  const tree=()=>React.createElement(MemoryRouter,{initialEntries:[{pathname:'/problems/new',state:{cloneNumber:1000}}]},React.createElement(EditorHistory,{Page}));
  const view=render(tree());await waitFor(()=>assert.equal(typeof resolveOld,'function'));
  globalThis.__problemSession=1;globalThis.__problemAuth={...globalThis.__problemAuth,epoch:1,user:{username:'bob'}};view.rerender(tree());await waitFor(()=>view.getByRole('alert'));
  await act(async()=>{resolveOld(cloneSource());await tick();});assert.equal(view.queryByLabelText('문제 제목')===null,true);assert.doesNotMatch(view.container.textContent,/비공개 그룹 본문|private-reference-source/);cleanup();
});

test('opening admin problem is read-only and cannot archive a contest-used question',async()=>{
  reset();const Page=await component('pages/ProblemReview.jsx'),calls=[];
  globalThis.__problemApi=async(path,method='GET',body)=>{calls.push({path,method,body});if(path.startsWith('/api/admin/problems?'))return [{problem_number:1000,problem_title:'시험 사용 문제',status:'PUBLISHED',visibility:'PUBLIC',author_username:'author'}];return {number:1000,title:'시험 사용 문제',status:'PUBLISHED',currentVisibility:'PUBLIC',usedInContest:true,canEdit:false,languages:['PYTHON'],tests:[{input:'private-input',expected:'private-answer'}],referenceSolutions:[],examples:[]};};
  const view=render(React.createElement(MemoryRouter,{initialEntries:['/admin/review'],future:{v7_startTransition:true,v7_relativeSplatPath:true}},React.createElement(Page,{dark:true})));
  await waitFor(()=>view.getByRole('button',{name:/#1000/}));fireEvent.click(view.getByRole('button',{name:/#1000/}));await waitFor(()=>view.getByText(/시험에 사용된 원본입니다/));
  assert.equal(view.queryByRole('link',{name:'편집기로 불러오기'})===null,true);assert.ok(calls.every(call=>call.method==='GET'));cleanup();
});

test('late admin mutation cannot replace a newly selected problem',async()=>{
  reset();const Page=await component('pages/ProblemReview.jsx');let release;
  const detail=number=>({number,title:`문제 ${number} 상세`,status:'PUBLISHED',currentVisibility:'PUBLIC',usedInContest:false,canEdit:false,languages:['PYTHON'],tests:[],referenceSolutions:[],examples:[]});
  globalThis.__problemApi=async(path,method='GET')=>{if(method==='PATCH')return new Promise(resolve=>{release=resolve;});if(path.startsWith('/api/admin/problems?'))return [1000,1001].map(number=>({problem_number:number,problem_title:`문제 ${number}`,status:'PUBLISHED',visibility:'PUBLIC'}));return detail(Number(path.split('/').at(-1)));};
  const view=render(React.createElement(MemoryRouter,{initialEntries:['/admin/review'],future:{v7_startTransition:true,v7_relativeSplatPath:true}},React.createElement(Page,{dark:true})));
  await waitFor(()=>view.getByRole('button',{name:/#1000/}));fireEvent.click(view.getByRole('button',{name:/#1000/}));await waitFor(()=>view.getByRole('heading',{name:'문제 1000 상세'}));fireEvent.click(view.getByRole('button',{name:'비공개로 전환'}));
  fireEvent.click(view.getByRole('button',{name:/#1001/}));await waitFor(()=>view.getByRole('heading',{name:'문제 1001 상세'}));await act(async()=>{release(null);await tick();});
  assert.ok(view.getByRole('heading',{name:'문제 1001 상세'}));assert.equal(view.queryByRole('heading',{name:'문제 1000 상세'})===null,true);assert.equal(view.getByRole('button',{name:'비공개로 전환'}).disabled,false);cleanup();
});
