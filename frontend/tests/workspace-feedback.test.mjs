import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';
import {readFileSync} from 'node:fs';
import {build} from 'esbuild';
import {JSDOM} from 'jsdom';

const dom=new JSDOM('<!doctype html><html><body></body></html>',{url:'http://localhost/'});
for(const key of ['window','document','HTMLElement','Event','KeyboardEvent','MutationObserver'])globalThis[key]=dom.window[key];
Object.defineProperty(globalThis,'navigator',{value:dom.window.navigator,configurable:true});globalThis.IS_REACT_ACT_ENVIRONMENT=true;
dom.window.PointerEvent=dom.window.MouseEvent;
const require=createRequire(import.meta.url),React=require('react'),{render,fireEvent,waitFor,cleanup,act}=require('@testing-library/react');
const built=await build({stdin:{contents:"export {default as Workspace,WORKSPACE_SPLIT_KEY} from './components/ProblemWorkspace.jsx';export {default as Feedback} from './components/SubmissionFeedback.jsx';",resolveDir:fileURLToPath(new URL('../src/',import.meta.url)),loader:'jsx'},bundle:true,write:false,platform:'node',format:'cjs',packages:'external',jsx:'automatic',loader:{'.css':'empty'},plugins:[{name:'feedback-api',setup(builder){
  builder.onResolve({filter:/(?:^|\/)api(?:\.js)?$/},()=>({path:'api',namespace:'contract'}));builder.onResolve({filter:/\/context\/AuthContext(?:\.jsx)?$/},()=>({path:'auth',namespace:'contract'}));builder.onLoad({filter:/.*/,namespace:'contract'},args=>({contents:args.path==='api'?'export const api=(...args)=>globalThis.__feedbackApi(...args);':'export const useAuth=()=>globalThis.__feedbackAuth;'}));
}}]});
const module={exports:{}};new Function('require','module','exports',built.outputFiles[0].text)(require,module,module.exports);const {Workspace,Feedback,WORKSPACE_SPLIT_KEY}=module.exports;
const tick=()=>new Promise(resolve=>setTimeout(resolve,0));
const workspace=()=>React.createElement(Workspace,{statement:React.createElement('article',null,'설명')},React.createElement('section',null,React.createElement('input',{'aria-label':'초안',defaultValue:'preserved source'})));
test.beforeEach(()=>{window.localStorage.clear();globalThis.__feedbackAuth={epoch:0};});test.afterEach(()=>cleanup());test.after(()=>dom.window.close());
const feedbackView = props => React.createElement(require('react-router-dom').MemoryRouter, null,
  React.createElement(Feedback, props));
test('keyboard resize bounds persist without replacing the code side and reset is accessible',()=>{
  const view=render(workspace()),separator=view.getByRole('separator',{name:'문제 설명과 코드 영역 너비 조절'}),draft=view.getByRole('textbox',{name:'초안'});
  assert.equal(separator.getAttribute('aria-valuenow'),'50');fireEvent.keyDown(separator,{key:'ArrowRight'});assert.equal(separator.getAttribute('aria-valuenow'),'52');
  fireEvent.keyDown(separator,{key:'End'});assert.equal(separator.getAttribute('aria-valuenow'),'75');fireEvent.keyDown(separator,{key:'ArrowRight'});assert.equal(separator.getAttribute('aria-valuenow'),'75');
  fireEvent.keyDown(separator,{key:'Home'});assert.equal(separator.getAttribute('aria-valuenow'),'25');fireEvent.keyDown(separator,{key:'Enter'});assert.equal(separator.getAttribute('aria-valuenow'),'50');
  assert.equal(view.getByRole('textbox',{name:'초안'}),draft);assert.equal(draft.value,'preserved source');assert.equal(window.localStorage.getItem(WORKSPACE_SPLIT_KEY),'50');
  cleanup();const reopened=render(workspace());assert.equal(reopened.getByRole('separator').getAttribute('aria-valuenow'),'50');
});
test('pointer resize is bounded, cancelled safely, and double click restores default',()=>{
  const view=render(workspace()),separator=view.getByRole('separator'),grid=view.container.querySelector('.pd-resizable');grid.getBoundingClientRect=()=>({left:0,width:1000});
  fireEvent.pointerDown(separator,{button:0,clientX:500});fireEvent.pointerMove(separator,{clientX:10000});assert.equal(separator.getAttribute('aria-valuenow'),'75');fireEvent.pointerCancel(separator);assert.equal(grid.classList.contains('is-resizing'),false);assert.equal(window.localStorage.getItem(WORKSPACE_SPLIT_KEY),'75');
  fireEvent.doubleClick(separator);assert.equal(separator.getAttribute('aria-valuenow'),'50');
  const css=readFileSync(new URL('../src/components/ProblemWorkspace.css',import.meta.url),'utf8');assert.match(css,/@media\(max-width:1100px\)/);assert.match(css,/\.pd-resizable>\.pd-splitter\{display:none\}/);
});
test('feedback polls pending status in place and stops on the real terminal verdict',async()=>{
  let reads=0;const busy=[];globalThis.__feedbackApi=async()=>++reads===1?{status:'QUEUED',cases:[]}:{status:'ACCEPTED',score:100,execution_time_ms:7,reusedFromSubmissionId:11,cases:[{status:'ACCEPTED',execution_time_ms:7}]};
  const view=render(feedbackView({id:22,onPendingChange:value=>busy.push(value)}));await view.findByText('대기 중');
  assert.equal(view.getByRole('link', {name:'상세 판정 보기'}).getAttribute('href'), '/submissions/22');
  assert.equal(document.querySelector('.accepted-fireworks'),null);
  await waitFor(()=>view.getByText('정답입니다. 해결 완료로 기록되었습니다.'),{timeout:2500});assert.deepEqual(busy,[true,false]);assert.equal(reads,2);assert.ok(view.getByText('이미 제출한 코드입니다. 기존 판정을 불러왔습니다. (이전 제출 #11)'));assert.match(view.container.textContent,/1개 케이스 중 1개 통과/);
  const fireworks = document.body.querySelector(':scope > .accepted-fireworks');
  assert.ok(fireworks);
  assert.equal(fireworks.getAttribute('aria-hidden'),'true');
  await waitFor(()=>assert.equal(fireworks.isConnected,false),{timeout:2500});

});
test('feedback discards late responses when identity changes or the old submission unmounts',async()=>{
  let resolveOld;globalThis.__feedbackApi=()=>new Promise(resolve=>{resolveOld=resolve;});const view=render(feedbackView({id:1}));await waitFor(()=>assert.equal(typeof resolveOld,'function'));
  globalThis.__feedbackAuth={epoch:1};globalThis.__feedbackApi=async()=>({status:'WRONG_ANSWER',score:0,cases:[]});view.rerender(feedbackView({id:2}));await view.findByText('오답');
  await act(async()=>{resolveOld({status:'ACCEPTED',cases:[]});await tick();});assert.equal(view.queryByText('정답입니다. 해결 완료로 기록되었습니다.'),null);assert.ok(view.getByRole('heading',{name:'제출 #2'}));assert.equal(document.querySelector('.accepted-fireworks'),null);
});
test('feedback errors stop the busy state and offer an explicit safe retry',async()=>{
  const busy = [];
  const replies = [{status: 'QUEUED'}, new Error('조회 권한이 없습니다.'), {status: 'SYSTEM_ERROR', score: 0, cases: []}];
  globalThis.__feedbackApi = async () => {
    const reply = replies.shift();
    if (reply instanceof Error) throw reply;
    return reply;
  };
  const view = render(feedbackView({id: 9, onPendingChange: value => busy.push(value)}));
  await view.findByText(/이 화면에서 자동 갱신/);
  await view.findByRole('alert', {}, {timeout: 3000});
  assert.equal(view.queryByText(/이 화면에서 자동 갱신/), null);
  assert.deepEqual(busy, [true, false]);

  fireEvent.click(view.getByRole('button', {name: '채점 결과 다시 불러오기'}));
  await view.findByText('시스템 오류');
  assert.deepEqual(busy, [true, false, true, false]);
  assert.doesNotMatch(view.container.textContent, /정답입니다/);
});

test('an immediate accepted verdict respects the reduced-motion preference', async t => {
  const previous = window.matchMedia;
  window.matchMedia = query => ({matches: query === '(prefers-reduced-motion: reduce)'});
  t.after(() => {window.matchMedia = previous;});
  globalThis.__feedbackApi = async () => ({status: 'ACCEPTED', cases: []});
  const view = render(feedbackView({id: 31}));
  await view.findByText('정답입니다. 해결 완료로 기록되었습니다.');
  assert.equal(document.querySelector('.accepted-fireworks'), null);
});
