import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {readFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import {build} from 'esbuild';
import {JSDOM} from 'jsdom';
import {emptyProblem,problemPayload} from '../src/lib/problemModel.js';

const require=createRequire(import.meta.url),root=fileURLToPath(new URL('../',import.meta.url));
const dom=new JSDOM('<!doctype html><html><body></body></html>',{url:'http://localhost:8080/'});
Object.defineProperty(globalThis,'navigator',{value:dom.window.navigator,configurable:true});
for(const key of ['window','document','HTMLElement','Event','MutationObserver'])globalThis[key]=dom.window[key];
globalThis.IS_REACT_ACT_ENVIRONMENT=true;
const React=require('react'),{render,fireEvent,waitFor,cleanup,act}=require('@testing-library/react'),{MemoryRouter,Routes,Route}=require('react-router-dom');
const modules=new Map(),catalog=[{id:'PYTHON',label:'Python',modes:['STDIN','FUNCTION']}];
async function component(name){
  if(modules.has(name))return modules.get(name);
  const result=await build({entryPoints:[path.join(root,'src',name)],bundle:true,write:false,platform:'node',format:'cjs',packages:'external',jsx:'automatic',loader:{'.css':'empty'},plugins:[{name:'author-contract',setup(builder){
    builder.onResolve({filter:/(?:^|\/)api(?:\.js)?$/},()=>({path:'api',namespace:'contract'}));
    builder.onResolve({filter:/\/context\/AuthContext(?:\.jsx)?$/},()=>({path:'auth',namespace:'contract'}));
    builder.onResolve({filter:/\/components\/CodeEditor(?:\.jsx)?$/},()=>({path:'editor',namespace:'contract'}));
    builder.onLoad({filter:/.*/,namespace:'contract'},args=>({contents:args.path==='api'?'export const api=(...args)=>globalThis.__authorApi(...args); export const getSessionEpoch=()=>globalThis.__authorSession;':args.path==='auth'?'export const useAuth=()=>globalThis.__authorAuth;':'export default function Editor(){return null;}'}));
  }}]});
  const module={exports:{}};new Function('require','module','exports',result.outputFiles[0].text)(require,module,module.exports);modules.set(name,module.exports.default);return module.exports.default;
}
function reset(){cleanup();window.localStorage.clear();globalThis.__authorSession=0;globalThis.__authorAuth={epoch:0,user:{username:'current-user'},permissions:{}};}
function page(Page,entry='/problems/new',mode){return React.createElement(MemoryRouter,{initialEntries:[entry]},React.createElement(Routes,null,
  React.createElement(Route,{path:'/problems/new',element:React.createElement(Page,{dark:true})}),
  React.createElement(Route,{path:'/problems/:id/edit',element:mode==='edit'?React.createElement(Page,{dark:true,mode:'edit'}):React.createElement('p',null,'saved-route')}),
  React.createElement(Route,{path:'/problems/:id',element:React.createElement(Page,{dark:true})})));
}
const saved=(extra={})=>({...emptyProblem(),title:'원본 문제',description:'본문',languages:['PYTHON'],number:1000,status:'DRAFT',currentVisibility:'PRIVATE',canEdit:true,...extra});

test('author attribution only renders a name after explicit boolean consent and escapes text',async()=>{
  reset();const Attribution=await component('components/ProblemAttribution.jsx');
  const view=render(React.createElement(Attribution,{problem:{rank_type:'ELITE',rank_int:5,authorPublic:true,authorUsername:' <img src=x onerror=alert(1)> '}}));
  assert.match(view.getByLabelText('문제 난이도 및 제작자').textContent,/ELITE 5/);
  assert.match(view.container.textContent,/<img src=x onerror=alert\(1\)>/);assert.equal(view.container.querySelector('img')===null,true);
  for(const authorPublic of [false,undefined,null,1,'true']){
    view.rerender(React.createElement(Attribution,{problem:{rank_type:'ELITE',rank_int:5,authorPublic,authorUsername:'private-author'}}));
    assert.match(view.container.textContent,/제작자 비공개/);assert.doesNotMatch(view.container.innerHTML,/private-author/);
  }
  for(const authorUsername of [null,undefined,'',42,'   ']){
    view.rerender(React.createElement(Attribution,{problem:{rankType:'ELITE',rankInt:5,authorPublic:true,authorUsername}}));
    assert.match(view.container.textContent,/제작자 비공개/);
  }
  view.rerender(React.createElement(Attribution,{problem:null}));assert.equal(view.container.textContent,'');cleanup();
});

test('difficulty and author share a wrapping row with safe long-name layout',async()=>{
  reset();const Attribution=await component('components/ProblemAttribution.jsx'),css=readFileSync(new URL('../src/components/ProblemAttribution.css',import.meta.url),'utf8');
  const view=render(React.createElement('div',null,React.createElement('style',null,css),React.createElement(Attribution,{problem:{rankType:'ELITE',rankInt:5,authorPublic:true,authorUsername:'긴이름'.repeat(30)}})));
  const row=view.getByLabelText('문제 난이도 및 제작자'),style=window.getComputedStyle(row);
  assert.equal(style.display,'flex');assert.equal(style.flexWrap,'wrap');assert.equal(style.gap,'8px 24px');
  assert.equal(window.getComputedStyle(row.querySelector('.pd-author')).overflowWrap,'anywhere');cleanup();
});

test('public and private author settings are independent from problem publication visibility',()=>{
  const form=saved();assert.equal(emptyProblem().authorPublic,false);
  for(const visibility of ['PUBLIC','PRIVATE'])for(const authorPublic of [false,true]){
    const payload=problemPayload({...form,visibility,authorPublic,authorUsername:'must-not-be-submitted',authorId:123,author_id:123,author_username:'hidden'},catalog);
    assert.equal(payload.visibility,visibility);assert.equal(payload.authorPublic,authorPublic);
    for(const key of ['authorUsername','authorId','author_id','author_username'])assert.equal(Object.hasOwn(payload,key),false);
  }
  for(const value of [undefined,null,'true',1])assert.equal(problemPayload({...form,authorPublic:value},catalog).authorPublic,false);
});

test('problem detail places public author next to the requested problem difficulty',async()=>{
  reset();const Page=await component('pages/ProblemDetail.jsx');
  globalThis.__authorApi=async route=>route==='/api/languages'?catalog:{problem_number:1000,problem_title:'제작자 확인',rank_type:'ELITE',rank_int:5,description:'본문',execution_mode:'STDIN',languages:['PYTHON'],authorPublic:true,authorUsername:'문제제작자',examples:[]};
  const view=render(page(Page,'/problems/1000'));await waitFor(()=>view.getByRole('heading',{name:'1000. 제작자 확인'}));
  assert.equal(view.getByLabelText('문제 난이도 및 제작자').textContent,'ELITE 5제작자 문제제작자');cleanup();
});

test('new editor defaults private and writes the explicitly selected author setting',async()=>{
  reset();const Page=await component('pages/ProblemEditor.jsx'),calls=[];
  globalThis.__authorApi=async(route,method='GET',body)=>{calls.push({route,method,body});if(route==='/api/languages')return catalog;if(route==='/api/groups/mine')return [];if(route==='/api/problems'&&method==='POST')return 2001;throw Error('unexpected route');};
  const view=render(page(Page));await waitFor(()=>view.getByRole('checkbox',{name:'Python'}));
  assert.equal(view.getByLabelText('제작자 표시').value,'PRIVATE');
  fireEvent.change(view.getByLabelText('문제 제목'),{target:{value:'제작자 공개 문제'}});fireEvent.change(view.getByLabelText('문제 설명 · Markdown'),{target:{value:'본문'}});
  fireEvent.change(view.getByLabelText('공개 범위'),{target:{value:'PRIVATE'}});fireEvent.change(view.getByLabelText('제작자 표시'),{target:{value:'PUBLIC'}});
  fireEvent.click(view.getByRole('button',{name:'초안 저장'}));await waitFor(()=>view.getByText('saved-route'));
  const body=calls.find(c=>c.route==='/api/problems'&&c.method==='POST').body;assert.equal(body.authorPublic,true);assert.equal(body.visibility,'PRIVATE');cleanup();
});

test('edit reload preserves explicit consent and changing to private is persisted',async()=>{
  reset();const Page=await component('pages/ProblemEditor.jsx'),calls=[];let stored=saved({authorPublic:true});
  globalThis.__authorApi=async(route,method='GET',body)=>{calls.push({route,method,body});if(route==='/api/languages')return catalog;if(route==='/api/groups/mine')return [];if(route==='/api/problems/1000/edit')return stored;if(route==='/api/problems/1000'&&method==='PUT'){stored={...stored,...body};return null;}throw Error('unexpected route');};
  const view=render(page(Page,'/problems/1000/edit','edit'));await waitFor(()=>assert.equal(view.getByLabelText('제작자 표시').value,'PUBLIC'));
  fireEvent.change(view.getByLabelText('제작자 표시'),{target:{value:'PRIVATE'}});fireEvent.click(view.getByRole('button',{name:'초안 저장'}));
  await waitFor(()=>view.getByText('초안이 서버에 저장됐습니다.'));assert.equal(calls.find(c=>c.method==='PUT').body.authorPublic,false);assert.equal(view.getByLabelText('제작자 표시').value,'PRIVATE');cleanup();
});

test('clone never carries the original creator consent into the new account draft',async()=>{
  reset();const Page=await component('pages/ProblemEditor.jsx');
  globalThis.__authorApi=async route=>route==='/api/languages'?catalog:route==='/api/groups/mine'?[]:saved({authorPublic:true});
  const view=render(page(Page,{pathname:'/problems/new',state:{cloneNumber:1000}}));await waitFor(()=>assert.equal(view.getByLabelText('문제 제목').value,'원본 문제 복제'));
  assert.equal(view.getByLabelText('제작자 표시').value,'PRIVATE');cleanup();
});

test('account transition resets an unsaved public-author choice with the draft',async()=>{
  reset();const Page=await component('pages/ProblemEditor.jsx');
  globalThis.__authorApi=async route=>route==='/api/languages'?catalog:[];
  const view=render(page(Page));await waitFor(()=>view.getByRole('checkbox',{name:'Python'}));fireEvent.change(view.getByLabelText('제작자 표시'),{target:{value:'PUBLIC'}});
  globalThis.__authorSession=1;globalThis.__authorAuth={epoch:1,user:{username:'other-user'},permissions:{}};
  await act(async()=>view.rerender(page(Page)));await waitFor(()=>assert.equal(view.getByLabelText('제작자 표시').value,'PRIVATE'));cleanup();
});

test('contest question uses the same author privacy rule as ordinary problem detail',async()=>{
  const Page=await component('pages/GroupContest.jsx'),base='/api/groups/9/contests/2';
  for(const authorPublic of [false,true]){
    reset();globalThis.__authorApi=async route=>{
      if(route==='/api/languages')return catalog;
      if(route==='/api/groups/9')return {permissions:['TAKE_CONTESTS']};
      if(route===base)return {id:2,title:'제작자 시험',status:'ACTIVE',startsAt:new Date(Date.now()-60000).toISOString(),endsAt:new Date(Date.now()+3600000).toISOString(),problems:[{problemNumber:1000,problemTitle:'시험 문제'}],problemCount:1};
      if(route===base+'/me')return {submissions:[]};
      if(route===base+'/problems/1000')return {problem_number:1000,rank_type:'ELITE',rank_int:5,description:'본문',languages:['PYTHON'],execution_mode:'STDIN',authorPublic,authorUsername:'시험제작자'};
      throw Error('unexpected route');
    };
    const view=render(React.createElement(MemoryRouter,{initialEntries:['/groups/9/contests/2']},React.createElement(Routes,null,React.createElement(Route,{path:'/groups/:gid/contests/:cid',element:React.createElement(Page,{dark:true})}))));
    await waitFor(()=>view.getByRole('button',{name:'1. 시험 문제'}));fireEvent.click(view.getByRole('button',{name:'1. 시험 문제'}));
    await waitFor(()=>view.getByLabelText('문제 난이도 및 제작자'));
    assert.equal(view.getByLabelText('문제 난이도 및 제작자').textContent,authorPublic?'ELITE 5제작자 시험제작자':'ELITE 5제작자 비공개');
    if(!authorPublic)assert.doesNotMatch(view.container.innerHTML,/시험제작자/);cleanup();
  }
});
