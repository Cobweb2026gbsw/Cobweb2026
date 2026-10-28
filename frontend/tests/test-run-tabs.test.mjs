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
const require=createRequire(import.meta.url),React=require('react'),{render,fireEvent,waitFor,cleanup,act,within}=require('@testing-library/react');
const built=await build({stdin:{contents:"export {default} from './components/TestRunPanel.jsx';",resolveDir:fileURLToPath(new URL('../src/',import.meta.url)),loader:'jsx'},bundle:true,write:false,platform:'node',format:'cjs',packages:'external',jsx:'automatic',loader:{'.css':'empty'},plugins:[{name:'test-run-tab-contracts',setup(builder){
  builder.onResolve({filter:/(?:^|\/)api(?:\.js)?$/},()=>({path:'api',namespace:'contract'}));
  builder.onResolve({filter:/\/context\/AuthContext(?:\.jsx)?$/},()=>({path:'auth',namespace:'contract'}));
  builder.onLoad({filter:/.*/,namespace:'contract'},args=>({contents:args.path==='api'?'export const api=(...args)=>globalThis.__testRunTabsApi(...args);export const getSessionEpoch=()=>globalThis.__testRunTabsAuth.epoch;':'export const useAuth=()=>globalThis.__testRunTabsAuth;'}));
}}]});
const module={exports:{}};new Function('require','module','exports',built.outputFiles[0].text)(require,module,module.exports);const Panel=module.exports.default;
const problem={problem_number:1000,execution_mode:'STDIN',languages:['PYTHON','JAVA'],examples:[{input_text:'input-secret',output_text:'output-secret'}]};
const props=()=>({problem,number:1000,language:'PYTHON',code:'print(3)'});
const completed=()=>({id:70,problemNumber:1000,language:'PYTHON',status:'COMPLETED',privateSample:true,testCount:2,results:[{status:'ACCEPTED',timeMs:7},{status:'ACCEPTED',timeMs:8}]});
const tick=()=>new Promise(resolve=>setTimeout(resolve,0));
const tab=(view,name)=>view.getByRole('tab',{name});
const selected=(view,name)=>assert.equal(tab(view,name).getAttribute('aria-selected'),'true');
const runButton=view=>view.getByRole('button',{name:'테스트 및 실행'});
test.beforeEach(()=>{globalThis.__testRunTabsAuth={epoch:0,user:{id:1,username:'alice'},loading:false};globalThis.__testRunTabsApi=async()=>{throw Error('Unexpected API request');};});
test.afterEach(()=>cleanup());test.after(()=>dom.window.close());

test('two ARIA tabs control mounted panels in one shared viewport and leave actions outside it',()=>{
  const view=render(React.createElement(Panel,props())),list=view.getByRole('tablist',{name:'코드 테스트 보기'}),tabs=within(list).getAllByRole('tab');
  assert.equal(tabs.length,2);selected(view,'테스트 케이스');
  const panels=tabs.map(button=>{const panel=document.getElementById(button.getAttribute('aria-controls'));assert.ok(panel);assert.equal(panel.getAttribute('role'),'tabpanel');assert.equal(panel.getAttribute('aria-labelledby'),button.id);assert.equal(button.tabIndex,button.getAttribute('aria-selected')==='true'?0:-1);return panel;});
  assert.equal(panels[0].hidden,false);assert.equal(panels[1].hidden,true);assert.equal(view.getAllByRole('tabpanel').length,1);assert.equal(view.getAllByRole('tabpanel',{hidden:true}).length,2);
  assert.equal(panels[0].parentElement,panels[1].parentElement);const viewport=panels[0].parentElement;assert.equal(viewport.className,'cw-test-scroll');assert.equal(view.container.querySelectorAll('.cw-test-scroll').length,1);assert.equal(viewport.contains(runButton(view)),false);
  fireEvent.click(tab(view,'실행 출력'));selected(view,'실행 출력');assert.equal(panels[0].hidden,true);assert.equal(panels[1].hidden,false);assert.ok(view.getByText('실행 후 통과 여부와 실행 시간을 확인할 수 있습니다.'));assert.equal(view.queryByRole('textbox'),null);
});

test('multiple panel instances generate unique tab and panel IDs',()=>{
  const view=render(React.createElement(React.Fragment,null,React.createElement(Panel,props()),React.createElement(Panel,props())));const elements=[...view.container.querySelectorAll('[role=tab],[role=tabpanel]')];
  assert.equal(elements.length,8);assert.equal(new Set(elements.map(element=>element.id)).size,8);for(const button of view.getAllByRole('tab'))assert.equal(document.getElementById(button.getAttribute('aria-controls')).getAttribute('aria-labelledby'),button.id);
});

test('arrow keys wrap focus and selection, Home/End jump, and unrelated keys are not intercepted',()=>{
  const view=render(React.createElement(Panel,props())),cases=tab(view,'테스트 케이스'),output=tab(view,'실행 출력');cases.focus();
  for(const [key,next] of [['ArrowRight',output],['ArrowRight',cases],['ArrowLeft',output],['Home',cases],['End',output]]){fireEvent.keyDown(document.activeElement,{key});assert.equal(document.activeElement,next);assert.equal(next.tabIndex,0);assert.equal(next.getAttribute('aria-selected'),'true');assert.equal((next===cases?output:cases).tabIndex,-1);}
  assert.equal(fireEvent.keyDown(output,{key:'Tab'}),true);selected(view,'실행 출력');assert.equal(document.activeElement,output);assert.equal(fireEvent.keyDown(output,{key:'ArrowDown'}),true);selected(view,'실행 출력');
});

test('switching tabs preserves the two fixed private-case cards without reading or revealing input',()=>{
  let requests=0;globalThis.__testRunTabsApi=async()=>{requests++;};const view=render(React.createElement(Panel,props())),first=view.getByRole('article',{name:'비공개 테스트 1'});
  fireEvent.click(tab(view,'실행 출력'));assert.equal(first.isConnected,true);fireEvent.click(tab(view,'테스트 케이스'));assert.equal(view.getByRole('article',{name:'비공개 테스트 1'}),first);assert.equal(view.getAllByRole('article').length,2);assert.equal(requests,0);assert.doesNotMatch(view.container.textContent,/input-secret|output-secret/);assert.equal(view.queryByRole('textbox'),null);
});

test('execution opens output and tab switches do not cancel the job or erase its status results',async()=>{
  let release,posts=0,reads=0;globalThis.__testRunTabsApi=async(path,method)=>{if(method==='POST'){posts++;return new Promise(resolve=>{release=resolve;});}reads++;return completed();};
  const view=render(React.createElement(Panel,props()));fireEvent.click(runButton(view));selected(view,'실행 출력');assert.ok(view.getByRole('status'));assert.equal(view.getByRole('tabpanel',{name:'실행 출력'}).getAttribute('aria-busy'),'true');
  fireEvent.click(tab(view,'테스트 케이스'));assert.ok(view.getByRole('article',{name:'비공개 테스트 1'}));fireEvent.click(tab(view,'실행 출력'));await act(async()=>{release(70);await tick();});
  const result=await view.findByRole('region',{name:'테스트 실행 결과'});assert.match(result.textContent,/테스트 1통과7 ms테스트 2통과8 ms/);assert.equal(posts,1);assert.equal(reads,1);assert.equal(view.getByRole('tabpanel',{name:'실행 출력'}).getAttribute('aria-busy'),'false');
  fireEvent.click(tab(view,'테스트 케이스'));assert.equal(view.queryByRole('region',{name:'테스트 실행 결과'}),null);assert.equal(result.isConnected,true);fireEvent.click(tab(view,'실행 출력'));assert.equal(view.getByRole('region',{name:'테스트 실행 결과'}),result);
});

test('validation from output selects cases and focuses its visible alert without posting',async()=>{
  let posts=0;globalThis.__testRunTabsApi=async()=>{posts++;};const view=render(React.createElement(Panel,{...props(),code:'가'.repeat(22000)}));fireEvent.click(tab(view,'실행 출력'));fireEvent.click(runButton(view));selected(view,'테스트 케이스');
  const alert=await view.findByRole('alert');assert.match(alert.textContent,/64 KiB/);assert.equal(document.activeElement,alert);assert.equal(alert.closest('[role=tabpanel]').hidden,false);assert.equal(posts,0);
  fireEvent.click(tab(view,'실행 출력'));assert.equal(view.queryByRole('alert'),null);assert.ok(view.getByText('실행 후 통과 여부와 실행 시간을 확인할 수 있습니다.'));
});

test('request failure is exposed in output even if cases were inspected during the request',async()=>{
  let rejectRead;globalThis.__testRunTabsApi=async(path,method)=>method==='POST'?70:new Promise((resolve,reject)=>{rejectRead=reject;});const view=render(React.createElement(Panel,props()));fireEvent.click(runButton(view));
  await waitFor(()=>assert.equal(typeof rejectRead,'function'));fireEvent.click(tab(view,'테스트 케이스'));await act(async()=>{rejectRead(Error('실행 결과를 불러오지 못했습니다.'));await tick();});selected(view,'실행 출력');assert.match(view.getByRole('alert').textContent,/불러오지 못했습니다/);assert.equal(runButton(view).disabled,false);
});

test('scope changes reset cases and discard late status data after tab navigation',async()=>{
  let release;globalThis.__testRunTabsApi=async(path,method)=>method==='POST'?70:new Promise(resolve=>{release=resolve;});const view=render(React.createElement(Panel,props()));fireEvent.click(runButton(view));
  await waitFor(()=>assert.equal(typeof release,'function'));fireEvent.click(tab(view,'테스트 케이스'));fireEvent.click(tab(view,'실행 출력'));view.rerender(React.createElement(Panel,{...props(),code:'print(4)'}));selected(view,'테스트 케이스');
  await act(async()=>{release({...completed(),results:[{status:'WRONG_ANSWER',timeMs:55,actual:'stale-secret'}]});await tick();});fireEvent.click(tab(view,'실행 출력'));assert.doesNotMatch(view.container.textContent,/stale-secret|55 ms/);assert.equal(view.queryByRole('region',{name:'테스트 실행 결과'}),null);
});

test('the shared viewport remains bounded, hidden panels stay hidden and lower actions span left/right',()=>{
  const css=readFileSync(new URL('../src/components/TestRunPanel.css',import.meta.url),'utf8'),viewport=css.match(/\.cw-test-scroll\{([^}]+)\}/)?.[1];assert.ok(viewport);assert.match(viewport,/height:clamp\(240px,42vh,440px\)/);assert.match(viewport,/min-height:0/);assert.match(viewport,/overflow-y:auto/);assert.match(viewport,/overscroll-behavior:contain/);assert.match(viewport,/scrollbar-gutter:stable/);
  assert.match(css,/\.cw-test-tabpanel\[hidden\]\{display:none\}/);assert.match(css,/\[role=tab\]:focus-visible/);assert.match(css,/\.cw-test-actions\{[^}]*justify-content:space-between/);assert.match(css,/\.cw-test-actions>.cw-btn\{[^}]*min-width:0;[^}]*max-width:calc\(50% - 6px\)/);assert.match(css,/@media\(max-width:600px\).*\.cw-test-actions>.cw-btn\{[^}]*flex:1/s);
});
