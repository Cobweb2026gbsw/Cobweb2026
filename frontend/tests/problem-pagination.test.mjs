import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';
import {build} from 'esbuild';
import {JSDOM} from 'jsdom';
import {requestedProblemPage,problemPageData,problemPageButtons} from '../src/lib/problemPagination.js';

const require=createRequire(import.meta.url),root=fileURLToPath(new URL('../',import.meta.url));
const dom=new JSDOM('<!doctype html><html><body></body></html>',{url:'http://localhost:8080/'});
Object.defineProperty(globalThis,'navigator',{value:dom.window.navigator,configurable:true});
for(const key of ['window','document','HTMLElement','Event','MutationObserver'])globalThis[key]=dom.window[key];
globalThis.IS_REACT_ACT_ENVIRONMENT=true;
const React=require('react'),{render,fireEvent,waitFor,act,cleanup,within}=require('@testing-library/react');
const {MemoryRouter,Routes,Route,useLocation}=require('react-router-dom');
const built=await build({stdin:{contents:"export {default} from './src/pages/ProblemList.jsx';",resolveDir:root,sourcefile:'pagination-test.jsx'},bundle:true,write:false,platform:'node',format:'cjs',packages:'external',jsx:'automatic',loader:{'.css':'empty'},plugins:[{name:'pagination-contracts',setup(builder){
  builder.onResolve({filter:/(?:^|\/)api(?:\.js)?$/},()=>({path:'api',namespace:'contract'}));
  builder.onResolve({filter:/\/context\/AuthContext(?:\.jsx)?$/},()=>({path:'auth',namespace:'contract'}));
  builder.onLoad({filter:/.*/,namespace:'contract'},args=>({contents:args.path==='api'?'export const api=(...args)=>globalThis.__pageApi(...args);export const getSessionEpoch=()=>globalThis.__pageAuth.epoch;':'export const useAuth=()=>globalThis.__pageAuth;'}));
}}]});
const module={exports:{}};new Function('require','module','exports',built.outputFiles[0].text)(require,module,module.exports);
const List=module.exports.default;
function response(requested=0,totalItems=266){
  const totalPages=Math.ceil(totalItems/30),page=Math.min(Math.max(0,requested),Math.max(0,totalPages-1));
  return {page,pageSize:30,totalItems,totalPages,items:Array.from({length:Math.min(30,totalItems-page*30)},(_,index)=>({problem_number:1000+page*30+index,problem_title:`문제 ${1000+page*30+index}`,rank_type:'ELITE',rank_int:1,submission_count:0,accepted_count:0,solved:false}))};
}
function LocationProbe(){const location=useLocation();return React.createElement('output',{'data-testid':'location'},location.pathname+location.search);}
function tree(url='/problems',mine=false){return React.createElement(MemoryRouter,{initialEntries:[url]},React.createElement(LocationProbe),React.createElement(Routes,null,React.createElement(Route,{path:mine?'/problems/mine':'/problems',element:React.createElement(List,{dark:true,mine})})));}
const requested=url=>Number(new URL(url,'http://localhost').searchParams.get('page'));
test.beforeEach(()=>{globalThis.__pageAuth={user:{username:'alice'},epoch:0};globalThis.__pageApi=async url=>response(requested(url));});
test.afterEach(()=>cleanup());test.after(()=>dom.window.close());

test('URL pages reject fractional, nonfinite and out-of-int values without an unsafe request',()=>{
  for(const value of ['-1','1.5','Infinity','NaN','2147483648','9007199254740993','abc'])assert.equal(requestedProblemPage(value),0);
  assert.equal(requestedProblemPage(null),0);assert.equal(requestedProblemPage('6'),6);assert.equal(requestedProblemPage('2147483647'),2147483647);
});
test('six-number groups and short/empty last groups do not allocate unbounded buttons',()=>{
  assert.deepEqual(problemPageButtons(0,9),[0,1,2,3,4,5]);assert.deepEqual(problemPageButtons(5,9),[0,1,2,3,4,5]);
  assert.deepEqual(problemPageButtons(6,9),[6,7,8]);assert.deepEqual(problemPageButtons(8,9),[6,7,8]);
  assert.deepEqual(problemPageButtons(0,0),[0]);assert.equal(problemPageButtons(50000000,71582789).length,6);
});
test('metadata validation distinguishes exact multiples, empty results and transitional array responses',()=>{
  assert.equal(problemPageData(response(1,60),1).totalPages,2);assert.equal(problemPageData(response(8,266),8).items.length,26);
  assert.equal(problemPageData(response(0,0),0).totalPages,0);assert.equal(problemPageData(Array(30).fill({}),2).totalItems,null);
  for(const invalid of [{...response(),totalPages:10},{...response(),page:99},{...response(),pageSize:20},{...response(),totalItems:-1},{...response(),items:[]},Array(31).fill({})])assert.equal(problemPageData(invalid,0),null);
});
test('initial pager has previous, six direct page buttons and next with the current page identified',async()=>{
  const calls=[];globalThis.__pageApi=async url=>{calls.push(url);return response(requested(url));};
  const view=render(tree());await view.findByText('문제 1000');const nav=view.getByRole('navigation',{name:'문제 페이지'});
  assert.deepEqual(within(nav).getAllByRole('button').map(button=>button.textContent),['이전','1','2','3','4','5','6','다음']);
  assert.equal(within(nav).getByRole('button',{name:'1페이지'}).getAttribute('aria-current'),'page');
  assert.equal(within(nav).getByRole('button',{name:'이전'}).disabled,true);assert.equal(within(nav).getByRole('button',{name:'다음'}).disabled,false);
  assert.equal(new URL(calls[0],'http://localhost').searchParams.get('pagination'),'true');assert.ok(view.getByText('전체 266개 · 페이지당 최대 30개'));
});
test('direct page selection preserves search and all exact difficulty and solved filters',async()=>{
  const calls=[];globalThis.__pageApi=async url=>{calls.push(url);return response(requested(url));};
  const view=render(tree('/problems?q=graph&difficulty=ELITE%3A1&difficulty=EXPERT%3A5&solved=unsolved'));await view.findByText('문제 1000');
  fireEvent.click(view.getByRole('button',{name:'6페이지'}));await view.findByText('문제 1150');
  const url=new URL(calls.at(-1),'http://localhost'),location=new URL(view.getByTestId('location').textContent,'http://localhost');
  assert.equal(url.searchParams.get('page'),'5');assert.equal(url.searchParams.get('search'),'graph');assert.equal(url.searchParams.get('solved'),'unsolved');assert.equal(location.searchParams.get('q'),'graph');
  assert.deepEqual(location.searchParams.getAll('difficulty'),['ELITE:1','EXPERT:5']);assert.equal(view.getByRole('button',{name:'6페이지'}).getAttribute('aria-current'),'page');
});
test('crossing page six switches the number group and previous crosses back one page',async()=>{
  const view=render(tree('/problems?page=5'));await view.findByText('문제 1150');
  fireEvent.click(view.getByRole('button',{name:'다음'}));await view.findByText('문제 1180');
  assert.deepEqual(within(view.getByRole('navigation',{name:'문제 페이지'})).getAllByRole('button').map(button=>button.textContent),['이전','7','8','9','다음']);
  fireEvent.click(view.getByRole('button',{name:'이전'}));await view.findByText('문제 1150');assert.ok(view.getByRole('button',{name:'6페이지'}));
});
test('a full final page of thirty items does not enable a nonexistent next page',async()=>{
  globalThis.__pageApi=async url=>response(requested(url),60);const view=render(tree('/problems?page=1'));await view.findByText('문제 1030');
  assert.equal(view.getByRole('button',{name:'다음'}).disabled,true);assert.equal(view.queryByRole('button',{name:'3페이지'}),null);assert.equal(view.getByRole('button',{name:'2페이지'}).getAttribute('aria-current'),'page');
});
test('empty results leave both navigation directions and the sole page placeholder disabled',async()=>{
  globalThis.__pageApi=async()=>response(0,0);const view=render(tree());await view.findByText('등록된 문제가 없습니다.');
  assert.equal(view.getByRole('button',{name:'이전'}).disabled,true);assert.equal(view.getByRole('button',{name:'다음'}).disabled,true);assert.equal(view.getByRole('button',{name:'1페이지'}).disabled,true);
});
test('a stale URL is replaced with the actual final page without dropping filters',async()=>{
  const view=render(tree('/problems?page=999&q=graph&difficulty=ELITE%3A1'));await view.findByText('문제 1240');
  await waitFor(()=>assert.equal(new URL(view.getByTestId('location').textContent,'http://localhost').searchParams.get('page'),'8'));
  const location=new URL(view.getByTestId('location').textContent,'http://localhost');assert.equal(location.searchParams.get('q'),'graph');assert.deepEqual(location.searchParams.getAll('difficulty'),['ELITE:1']);
});
test('account transition removes the previous private list count before the next response',async()=>{
  const view=render(tree('/problems/mine',true));await view.findByText('전체 266개 · 페이지당 최대 30개');
  let resolve;globalThis.__pageApi=()=>new Promise(done=>{resolve=done;});globalThis.__pageAuth={user:{username:'bob'},epoch:1};view.rerender(tree('/problems/mine',true));
  assert.equal(view.queryByText('전체 266개 · 페이지당 최대 30개'),null);assert.equal(view.queryByRole('button',{name:'6페이지'}),null);
  await waitFor(()=>assert.equal(typeof resolve,'function'));await act(async()=>resolve(response(0,1)));
  await view.findByText('전체 1개 · 페이지당 최대 30개');assert.equal(view.getByRole('button',{name:'다음'}).disabled,true);
});
test('invalid metadata shows a safe error and disables navigation instead of guessing totals',async()=>{
  globalThis.__pageApi=async()=>({...response(),totalPages:999});const view=render(tree());await view.findByRole('alert');
  assert.equal(view.queryByText('문제 1000'),null);assert.equal(view.getByRole('button',{name:'다음'}).disabled,true);
});
test('my-authored list requests pagination metadata from the same authenticated route',async()=>{
  let path;globalThis.__pageApi=async url=>{path=url;return response(requested(url),1);};const view=render(tree('/problems/mine?solved=solved',true));await view.findByText('문제 1000');
  const url=new URL(path,'http://localhost');assert.equal(url.pathname,'/api/problems/mine');assert.equal(url.searchParams.get('pagination'),'true');assert.equal(url.searchParams.get('solved'),'solved');
});
