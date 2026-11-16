import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {readFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import {build} from 'esbuild';
import {JSDOM} from 'jsdom';

const require=createRequire(import.meta.url),root=fileURLToPath(new URL('../',import.meta.url));
const dom=new JSDOM('<!doctype html><html><body></body></html>',{url:'http://localhost:8080/'});
Object.defineProperty(globalThis,'navigator',{value:dom.window.navigator,configurable:true});
for(const key of ['window','document','HTMLElement','Event','MutationObserver'])globalThis[key]=dom.window[key];
globalThis.IS_REACT_ACT_ENVIRONMENT=true;
const React=require('react'),{render,fireEvent,waitFor,act,cleanup,within}=require('@testing-library/react');
const {MemoryRouter,Routes,Route,Link,useLocation}=require('react-router-dom');
const built=await build({stdin:{contents:"export {default as List} from './src/pages/ProblemList.jsx';export {default as Result} from './src/pages/SubmissionResult.jsx';export {default as Header} from './src/components/Header.jsx';",resolveDir:root,sourcefile:'solved-list-test.jsx'},bundle:true,write:false,platform:'node',format:'cjs',packages:'external',jsx:'automatic',loader:{'.css':'empty'},plugins:[{name:'solved-list-contracts',setup(builder){
  builder.onResolve({filter:/(?:^|\/)api(?:\.js)?$/},()=>({path:'api',namespace:'contract'}));
  builder.onResolve({filter:/\/context\/AuthContext(?:\.jsx)?$/},()=>({path:'auth',namespace:'contract'}));
  builder.onLoad({filter:/.*/,namespace:'contract'},args=>({contents:args.path==='api'?'export const api=(...args)=>globalThis.__solvedApi(...args);export const getSessionEpoch=()=>globalThis.__solvedAuth.epoch;':'export const useAuth=()=>globalThis.__solvedAuth;'}));
}}]});
const module={exports:{}};new Function('require','module','exports',built.outputFiles[0].text)(require,module,module.exports);
const {List,Result,Header}=module.exports;
const entry=(problem_number,solved,extra={})=>({problem_number,problem_title:'문제 '+problem_number,rank_type:'ELITE',rank_int:5,submission_count:10,accepted_count:7,status:'PUBLISHED',visibility:'PUBLIC',...extra,...(solved===undefined?{}:{solved})});
const tick=()=>new Promise(resolve=>setTimeout(resolve,0));
function LocationProbe(){const location=useLocation();return React.createElement('output',{'data-testid':'location'},location.pathname+location.search);}
function tree({navigation=false,url='/problems',mine=false}={}){
  return React.createElement(MemoryRouter,{initialEntries:[url]},
    React.createElement(LocationProbe),
    navigation?React.createElement(React.Fragment,null,React.createElement(Header,{dark:true,setDark:()=>{}}),React.createElement(Link,{to:'/submissions/77'},'결과 보기')):null,
    React.createElement(Routes,null,React.createElement(Route,{path:mine?'/problems/mine':'/problems',element:React.createElement(List,{dark:true,mine})}),React.createElement(Route,{path:'/submissions/:id',element:React.createElement(Result,{dark:true})})));
}
test.beforeEach(()=>{globalThis.__solvedAuth={user:{username:'alice'},epoch:0,permissions:{canManage:false,canCreate:false},logout:async()=>{}};});
test.afterEach(()=>cleanup());
test.after(()=>dom.window.close());

test('only an authenticated strict solved boolean creates a personal completion badge',async()=>{
  globalThis.__solvedApi=async()=>[
    entry(1000,true,{accepted_count:0,submission_count:0}),
    entry(1001,false,{accepted_count:999}),entry(1002,'true'),entry(1003,1),entry(1004,undefined)
  ];
  const view=render(tree());await view.findByText('문제 1000');
  assert.equal(view.getAllByText('해결 완료').length,1);
  assert.equal(within(view.getByRole('link',{name:/1000 문제 1000/})).getByText('해결 완료').closest('.pl-name')!==null,true);
  for(const number of [1001,1002,1003,1004])assert.equal(within(view.getByRole('link',{name:new RegExp(number+' 문제 '+number)})).queryByText('해결 완료')===null,true);
});

test('anonymous list never displays a personal badge even if a fixture contains solved true',async()=>{
  globalThis.__solvedAuth={...globalThis.__solvedAuth,user:null};globalThis.__solvedApi=async()=>[entry(1000,true)];
  const view=render(tree());await view.findByText('문제 1000');assert.equal(view.queryByText('해결 완료')===null,true);
});

test('account epoch immediately removes an old badge before the next account response',async()=>{
  globalThis.__solvedApi=async()=>[entry(1000,true)];const view=render(tree());await view.findByText('해결 완료');
  let resolveNext;globalThis.__solvedApi=()=>new Promise(resolve=>{resolveNext=resolve;});
  globalThis.__solvedAuth={...globalThis.__solvedAuth,user:{username:'bob'},epoch:1};view.rerender(tree());
  assert.equal(view.queryByText('해결 완료')===null,true);assert.equal(view.queryByText('문제 1000')===null,true);
  await act(async()=>{resolveNext([entry(1000,false)]);await tick();});
  await view.findByText('문제 1000');assert.equal(view.queryByText('해결 완료')===null,true);
});

test('late old-account list cannot add its badge after the current account has loaded',async()=>{
  let resolveOld;globalThis.__solvedApi=()=>new Promise(resolve=>{resolveOld=resolve;});const view=render(tree());
  await waitFor(()=>assert.equal(typeof resolveOld,'function'));
  globalThis.__solvedAuth={...globalThis.__solvedAuth,user:{username:'bob'},epoch:1};
  globalThis.__solvedApi=async()=>[entry(1001,false)];view.rerender(tree());await view.findByText('문제 1001');
  await act(async()=>{resolveOld([entry(1000,true)]);await tick();});
  assert.equal(view.queryByText('해결 완료')===null,true);assert.equal(view.queryByText('문제 1000')===null,true);
});

test('returning from an accepted result remounts and reloads the list through the real header link',async()=>{
  let solved=false,listReads=0;
  globalThis.__solvedApi=async url=>{
    if(url.startsWith('/api/problems?')){listReads++;return [entry(1000,solved)];}
    if(url==='/api/submissions/77'){solved=true;return {id:77,status:'ACCEPTED',language:'PYTHON',score:100,cases:[{status:'ACCEPTED',execution_time_ms:1}]};}
    throw new Error('Unexpected API '+url);
  };
  const view=render(tree({navigation:true}));await view.findByText('문제 1000');assert.equal(view.queryByText('해결 완료')===null,true);
  fireEvent.click(view.getByRole('link',{name:'결과 보기'}));await view.findByRole('heading',{name:'정답',exact:true});
  fireEvent.click(view.getByRole('link',{name:'문제',exact:true}));await view.findByText('해결 완료');assert.equal(listReads,2);
});

test('completion badge remains inside the mobile-visible title cell with content-driven row height',async()=>{
  const css=readFileSync(new URL('../src/pages/ProblemList.css',import.meta.url),'utf8');
  const style=document.createElement('style');style.textContent=css;document.head.append(style);
  try{
    globalThis.__solvedApi=async()=>[entry(1000,true,{problem_title:'긴 제목 '.repeat(30)})];const view=render(tree());const badge=await view.findByText('해결 완료');
    const parent=badge.closest('.pl-solved'),row=parent.closest('.pl-row-item');
    assert.equal(parent.closest('.pl-name'),row.children[1]);
    assert.equal(window.getComputedStyle(parent).display,'inline-flex');
    assert.equal(window.getComputedStyle(parent).maxWidth,'100%');
    const rules=[...style.sheet.cssRules];const rowRule=rules.find(rule=>rule.selectorText==='.pl-row-item');
    assert.equal(rowRule.style.getPropertyValue('height'),'');assert.equal(rowRule.style.getPropertyValue('min-height'),'64px');
    const mobile=rules.find(rule=>rule.conditionText==='(max-width: 760px)');
    for(const rule of mobile.cssRules){
      if(rule.style?.getPropertyValue('display')!=='none')continue;
      for(let node=parent;node&&node!==row.parentElement;node=node.parentElement)assert.equal(node.matches(rule.selectorText),false);
    }
  }finally{style.remove();}
});

test('all 35 tier-level pairs load from repeated URL filters and paging preserves the exact selection',async()=>{
  const calls=[];globalThis.__solvedApi=async url=>{calls.push(url);return Array.from({length:30},(_,index)=>entry(1000+index,false));};
  const view=render(tree({url:'/problems?q=graph&page=2&difficulty=ELITE%3A1&difficulty=EXPERT%3A5&solved=unsolved'}));
  await view.findByText('문제 1000');fireEvent.click(view.getByText('난이도 2개 선택'));
  assert.equal(view.getAllByRole('checkbox').length,35);
  for(const rank of ['INITIATE','SKILLED','ELITE','EXPERT','LEGEND','MYTHIC','ABSOLUTE'])for(let level=1;level<=5;level++)assert.equal(Boolean(view.getByRole('checkbox',{name:`${rank} ${level}`,exact:true})),true);
  assert.equal(view.getByRole('checkbox',{name:'ELITE 1',exact:true}).checked,true);assert.equal(view.getByRole('checkbox',{name:'EXPERT 5',exact:true}).checked,true);
  assert.equal(view.getByRole('combobox',{name:'풀이 여부'}).value,'unsolved');
  // 서버가 반환한 페이지는 다시 클라이언트 난이도로 잘라내지 않습니다.
  assert.equal(view.getByRole('heading',{name:'현재 페이지 30개'})!==null,true);
  const first=new URL(calls[0],'http://localhost');assert.equal(first.searchParams.get('page'),'2');assert.equal(first.searchParams.get('search'),'graph');assert.deepEqual(first.searchParams.getAll('difficulty'),['ELITE:1','EXPERT:5']);
  fireEvent.click(view.getByRole('button',{name:'다음'}));
  await waitFor(()=>assert.equal(new URL(calls.at(-1),'http://localhost').searchParams.get('page'),'3'));
  const current=new URL(view.getByTestId('location').textContent,'http://localhost');
  assert.equal(current.searchParams.get('q'),'graph');assert.equal(current.searchParams.get('solved'),'unsolved');assert.deepEqual(current.searchParams.getAll('difficulty'),['ELITE:1','EXPERT:5']);
});

test('changing filters resets the page, combines with text search, and reset clears every filter',async()=>{
  const calls=[];globalThis.__solvedApi=async url=>{calls.push(url);return Array.from({length:30},(_,index)=>entry(1000+index,false));};
  const view=render(tree({url:'/problems?q=graph&page=4&difficulty=ELITE%3A1'}));await view.findByText('문제 1000');
  fireEvent.click(view.getByText('난이도 1개 선택'));fireEvent.click(view.getByRole('checkbox',{name:'EXPERT 5',exact:true}));
  await waitFor(()=>assert.deepEqual(new URL(calls.at(-1),'http://localhost').searchParams.getAll('difficulty'),['ELITE:1','EXPERT:5']));
  assert.equal(new URL(calls.at(-1),'http://localhost').searchParams.get('page'),'0');
  fireEvent.click(view.getByRole('button',{name:'다음'}));await waitFor(()=>assert.equal(new URL(calls.at(-1),'http://localhost').searchParams.get('page'),'1'));
  fireEvent.change(view.getByRole('combobox',{name:'풀이 여부'}),{target:{value:'solved'}});
  await waitFor(()=>assert.equal(new URL(calls.at(-1),'http://localhost').searchParams.get('solved'),'solved'));
  assert.equal(new URL(calls.at(-1),'http://localhost').searchParams.get('page'),'0');
  fireEvent.change(view.getByRole('textbox',{name:'문제 검색'}),{target:{value:'tree'}});fireEvent.click(view.getByRole('button',{name:'검색',exact:true}));
  await waitFor(()=>assert.equal(new URL(calls.at(-1),'http://localhost').searchParams.get('search'),'tree'));
  assert.deepEqual(new URL(calls.at(-1),'http://localhost').searchParams.getAll('difficulty'),['ELITE:1','EXPERT:5']);
  fireEvent.change(view.getByRole('combobox',{name:'현재 페이지 정렬'}),{target:{value:'submits'}});fireEvent.click(view.getByRole('button',{name:'초기화'}));
  await waitFor(()=>assert.equal(view.getByTestId('location').textContent,'/problems'));
  const cleared=new URL(calls.at(-1),'http://localhost');assert.equal(cleared.searchParams.get('search'),'');assert.equal(cleared.searchParams.get('solved'),'all');assert.deepEqual(cleared.searchParams.getAll('difficulty'),[]);assert.equal(view.getByRole('combobox',{name:'현재 페이지 정렬'}).value,'number');
});

test('anonymous completion filters explain login and mine uses the same server filter contract',async()=>{
  const calls=[];globalThis.__solvedApi=async url=>{calls.push(url);return [entry(1000,false)];};
  globalThis.__solvedAuth={...globalThis.__solvedAuth,user:null};let view=render(tree());await view.findByText('문제 1000');
  assert.equal(view.getByRole('combobox',{name:'풀이 여부'}).disabled,true);assert.equal(view.getByRole('link',{name:'로그인'}).getAttribute('href'),'/login');cleanup();
  globalThis.__solvedAuth={...globalThis.__solvedAuth,user:{username:'alice'}};
  view=render(tree({mine:true,url:'/problems/mine?difficulty=MYTHIC%3A2&solved=solved'}));await view.findByText('문제 1000');
  const last=new URL(calls.at(-1),'http://localhost');assert.equal(last.pathname,'/api/problems/mine');assert.equal(last.searchParams.get('solved'),'solved');assert.deepEqual(last.searchParams.getAll('difficulty'),['MYTHIC:2']);
});
