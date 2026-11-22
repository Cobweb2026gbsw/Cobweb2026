import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {build} from 'esbuild';
import {JSDOM} from 'jsdom';

const dom=new JSDOM('<html><body></body></html>',{url:'http://localhost/'});
for(const key of ['window','document','HTMLElement','Event','MutationObserver'])globalThis[key]=dom.window[key];
Object.defineProperty(globalThis,'navigator',{value:dom.window.navigator,configurable:true});
globalThis.IS_REACT_ACT_ENVIRONMENT=true;
const require=createRequire(import.meta.url), React=require('react');
const {render,fireEvent,waitFor,cleanup}=require('@testing-library/react');
const {MemoryRouter, Routes, Route, useLocation}=require('react-router-dom');
const bundle=await build({stdin:{contents:"export {default as Mine} from './src/pages/MyIssues';export {default as Admin} from './src/pages/AdminModeration'; export {default as Operations} from './src/pages/AdminOperations'; export {default as Judging} from './src/pages/AdminJudging'; export {default as Review} from './src/pages/ProblemReview';",resolveDir:process.cwd(),loader:'jsx'},
  bundle:true,write:false,platform:'node',format:'cjs',packages:'external',jsx:'automatic',loader:{'.css':'empty'},
  plugins:[{name:'operations-fixtures',setup(b){
    b.onResolve({filter:/\/(api|AuthContext)$/},args=>({path:args.path.endsWith('/api')?'api':'auth',namespace:'fixture'}));
    b.onLoad({filter:/.*/,namespace:'fixture'},args=>({contents:args.path==='api'?
      'export const api=(...args)=>globalThis.opsApi(...args);export const getSessionEpoch=()=>1;':'export const useAuth=()=>globalThis.opsAuth;'}));
  }}]});
const mod={exports:{}};
new Function('require','module','exports',bundle.outputFiles[0].text)(require,mod,mod.exports);
const {Mine,Admin,Operations,Judging,Review}=mod.exports;
const open=(path,Page,props={})=>render(React.createElement(MemoryRouter,{initialEntries:[path]},React.createElement(Page,{dark:true,...props})));
const type=(view,label,value)=>fireEvent.change(view.getByLabelText(label),{target:{value}});
let calls;
test.beforeEach(()=>{
  calls=[];globalThis.opsAuth={epoch:1,user:{username:'admin'},loading:false,permissions:{canManage:true}};
  globalThis.opsApi=async(path,method,body)=>{calls.push([path,method,body]);return method==='GET'?[]:123;};
});
test.afterEach(cleanup);
test.after(()=>dom.window.close());

test('contest appeals carry both scope ids and only the selected submission',async()=>{
  const v=open('/issues?groupId=2&contestId=7&problem=1001&submission=4',Mine);
  assert.equal(v.getByLabelText('문제 번호').value,'1001');
  assert.equal(v.getByLabelText('내 제출 번호').value,'4');
  type(v,'내용','시간 확인');fireEvent.click(v.getByRole('button',{name:'접수하기'}));
  await v.findByText('#123번으로 접수되었습니다.');
  assert.deepEqual(calls.find(c=>c[1]==='POST'),['/api/issues?groupId=2&contestId=7','POST',{category:'JUDGING_APPEAL',problemNumber:1001,submissionId:4,body:'시간 확인'}]);
  assert.equal(v.getByRole('link',{name:'시험으로'}).getAttribute('href'),'/groups/2/contests/7');
});
test('review dispatch preserves version and rejudge links preserve the issue context',async()=>{
  const issue={id:12,problemNumber:1001,submissionId:5,status:'OPEN',category:'JUDGING_APPEAL',version:4,body:'확인',response:'',updatedAt:'2026-11-20T00:00:00Z'};
  globalThis.opsApi=async(path,method,body)=>{calls.push([path,method,body]);return method==='GET'?[issue]:null;};
  const v=open('/issues/manage?groupId=3&contestId=11',Admin,{mode:'issues'});
  const link=await v.findByRole('link',{name:'재채점 미리보기로'});
  assert.equal(link.getAttribute('href'),'/admin/judging?groupId=3&contestId=11&problem=1001&submission=5');
  fireEvent.click(v.getByText('담당·상태·답변 수정'));
  fireEvent.change(v.getAllByLabelText('처리 상태').at(-1),{target:{value:'RESOLVED'}});
  type(v,'신고자에게 보낼 답변','확인했습니다.');type(v,'담당자 아이디','operator');
  fireEvent.click(v.getByRole('button',{name:'저장'}));
  await waitFor(()=>assert.deepEqual(calls.find(c=>c[1]==='PATCH'),['/api/admin/moderation/issues/12?groupId=3&contestId=11','PATCH',{status:'RESOLVED',response:'확인했습니다.',assignee:'operator',version:4}]));
});
test('schedule converts Seoul midnight and collects each selected problem',async()=>{
  const v=open('/admin/releases',Admin,{mode:'releases'});
  type(v,'문제 번호 (쉼표 또는 공백으로 구분)','1001, 1002');type(v,'공개 시각 (한국)','2030-01-02T00:00');
  fireEvent.click(v.getByRole('button',{name:'검수 승인 및 예약'}));
  await v.findByText('#123번 예약을 등록했습니다.');
  assert.deepEqual(calls.find(c=>c[1]==='POST')[2],{problemNumbers:[1001,1002],scheduledFor:'2030-01-01T15:00:00.000Z'});
});
test('feature restriction records a bounded end time and a single feature',async()=>{
  const v=open('/admin/restrictions',Admin,{mode:'restrictions'});
  type(v,'회원 아이디','mira');type(v,'종료 시각 (한국)','2030-03-02T09:00');type(v,'회원에게 표시할 사유','도배');
  fireEvent.click(v.getByRole('button',{name:'제한 등록'}));
  await v.findByText('제한을 등록했습니다.');
  assert.deepEqual(calls.find(c=>c[1]==='POST')[2],{username:'mira',feature:'COMMENT',reason:'도배',expiresAt:'2030-03-02T00:00:00.000Z'});
});
test('personal issue links return to the same submission or problem', async () => {
  const cases = [
    ['?problem=1001&submission=5', {submissionReturn:'/submissions/5?from=%2Fsubmissions%3Fpage%3D2'}, '제출 상세로', '/submissions/5?from=%2Fsubmissions%3Fpage%3D2'],
    ['?problem=1001&submission=5', {submissionReturn:'/submissions/9'}, '제출 상세로', '/submissions/5'],
    ['?problem=1001', {problemReturn:'/groups/2/problems/1001'}, '문제로 돌아가기', '/groups/2/problems/1001'],
    ['?problem=1001', {problemReturn:'https://example.com/problems/1001'}, '문제로 돌아가기', '/problems/1001'],
    ['', {}, '내 활동으로', '/dashboard'],
  ];
  for (const [search,state,label,url] of cases) {
    const v = open({pathname:'/issues',search,state}, Mine);
    assert.equal(v.getByRole('link',{name:label}).getAttribute('href'),url);
    await v.findByText('등록된 항목이 없습니다.');
    v.unmount();
  }
});
test('filtered contest issue inbox survives a visit to rejudging', async () => {
  const issue = {id:12,problemNumber:1001,status:'RESOLVED',response:'완료',category:'PROBLEM_ERROR',version:2};
  globalThis.opsApi = async path => path.startsWith('/api/admin/moderation/issues') ? [issue] : [];
  const path='/issues/manage?groupId=3&contestId=11&status=RESOLVED&page=2';
  const v = render(React.createElement(MemoryRouter,{initialEntries:[path]},
    React.createElement(Routes,null,
      React.createElement(Route,{path:'/issues/manage',element:React.createElement(Admin,{mode:'issues'})}),
      React.createElement(Route,{path:'/admin/judging',element:React.createElement(Judging)}))));
  fireEvent.click(await v.findByRole('link',{name:'재채점 미리보기로'}));
  const back=await v.findByRole('link',{name:'이의제기 목록으로'});
  assert.equal(back.getAttribute('href'),path);
  fireEvent.click(back);
  await v.findByRole('link',{name:'재채점 미리보기로'});
  assert.equal(v.getAllByLabelText('처리 상태')[0].value,'RESOLVED');
  assert.ok(v.getByText('3'));
});
test('operations detail links retain filters and filters only refresh the queue', async () => {
  globalThis.opsApi=async (path,method)=>{
    calls.push([path,method]);
    if(path==='/api/languages') return [{id:'PYTHON',label:'Python'}];
    if(path==='/api/admin/operations') return {oldestSeconds:1,queued:2};
    return {hasNext:true,items:[{id:15,problemNumber:1002,language:'PYTHON'}]};
  };
  function Receipt() {
    const {state}=useLocation();
    return React.createElement('output',null,state.adminQueueReturn);
  }
  const v=render(React.createElement(MemoryRouter,{initialEntries:['/admin']},
    React.createElement(Routes,null,
      React.createElement(Route,{path:'/admin',element:React.createElement(Operations)}),
      React.createElement(Route,{path:'/submissions/:id',element:React.createElement(Receipt)}))));
  await v.findByRole('option',{name:'Python'});
  type(v,'채점 상태','RUNNING');
  type(v,'언어','PYTHON');
  await v.findByRole('link',{name:'#15'});
  fireEvent.click(v.getByRole('button',{name:'다음'}));
  fireEvent.click(await v.findByRole('link',{name:'#15'}));
  await v.findByText('/admin?status=RUNNING&language=PYTHON&page=1');
  assert.equal(calls.filter(([path])=>path==='/api/admin/operations').length,1);
  assert.equal(calls.filter(([path])=>path==='/api/languages').length,1);
});
test('reference verification returns to the selected review, filters and page',async()=>{
  globalThis.opsApi=async (path,method)=>{
    calls.push([path,method]);
    if(path.startsWith('/api/admin/problems?'))return [{problem_number:1001,problem_title:'문제',status:'PUBLISHED',visibility:'PUBLIC'}];
    if(path==='/api/admin/problems/1001')return {number:1001,title:'문제 상세',status:'PUBLISHED',currentVisibility:'PUBLIC',languages:[],tests:[],referenceSolutions:[],examples:[]};
    return [];
  };
  const v=render(React.createElement(MemoryRouter,{initialEntries:['/admin/review?page=1&search=abc&status=PUBLISHED']},
    React.createElement(Routes,null,
      React.createElement(Route,{path:'/admin/review',element:React.createElement(Review)}),
      React.createElement(Route,{path:'/admin/judging',element:React.createElement(Judging)}))));
  fireEvent.click(await v.findByRole('button',{name:/#1001/}));
  fireEvent.click(await v.findByRole('link',{name:'정답 코드 전체 테스트'}));
  fireEvent.click(await v.findByRole('link',{name:'문제 검수로'}));
  await v.findByRole('heading',{name:'문제 상세'});
  assert.equal(v.getByLabelText('제목 검색').value,'abc');
  assert.equal(v.getByLabelText('상태').value,'PUBLISHED');
  assert.ok(v.getByText('2'));
  assert.equal(calls.filter(([path])=>path==='/api/admin/problems?page=1&search=abc&status=PUBLISHED&visibility=').length,2);
});
