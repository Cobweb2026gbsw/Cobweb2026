import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {build} from 'esbuild';
import {JSDOM} from 'jsdom';

const require=createRequire(import.meta.url);
const dom=new JSDOM('<!doctype html><html><body></body></html>',{url:'http://localhost/'});
Object.defineProperty(globalThis,'navigator',{value:dom.window.navigator,configurable:true});
for(const key of ['window','document','HTMLElement','Event','MutationObserver'])globalThis[key]=dom.window[key];
globalThis.IS_REACT_ACT_ENVIRONMENT=true;
const React=require('react');
const {render,cleanup,fireEvent}=require('@testing-library/react');
const {MemoryRouter}=require('react-router-dom');
const pkg=await build({entryPoints:['src/pages/AdminJudging.jsx'],bundle:true,write:false,platform:'node',format:'cjs',packages:'external',jsx:'automatic',loader:{'.css':'empty'},
 plugins:[{name:'admin-judge-test',setup(b){
    b.onResolve({filter:/\/context\/AuthContext$/},()=>({path:'auth',namespace:'fixture'}));
    b.onResolve({filter:/\/lib\/api$|^\.\/api$/},()=>({path:'api',namespace:'fixture'}));
    b.onLoad({filter:/.*/,namespace:'fixture'},({path})=>({contents:path==='auth'?'export const useAuth=()=>globalThis.judgeAuth;':
      'export const getSessionEpoch=()=>globalThis.judgeAuth.epoch;export const api=(...args)=>globalThis.judgeApi(...args);'}));
 }}]});
const mod={exports:{}};
new Function('require','module','exports',pkg.outputFiles[0].text)(require,mod,mod.exports);
function mount(path) {
 return render(React.createElement(MemoryRouter,{initialEntries:[path]},React.createElement(mod.exports.default,{dark:true})));
}
test.beforeEach(()=>{globalThis.judgeAuth={epoch:1,permissions:{canManage:true}};});
test.afterEach(cleanup);
test.after(()=>dom.window.close());
test('issue link retains contest scope and applying requires the displayed preview token',async()=>{
 const calls=[];
 const detail={id:12,problemNumber:1011,kind:'REJUDGE',state:'READY',total:1,completed:1,creator:'관리자',reason:'오류 정정',
   jobs:[{id:31,submissionId:18,state:'DONE',language:'PYTHON',previousVerdict:'WRONG_ANSWER',verdict:'ACCEPTED'}],
   previewToken:'reviewed-version',impact:{newlyAccepted:1,gainingUsers:1,lostAccepted:0,changedVerdicts:1,standings:[]}};
 globalThis.judgeApi=async(path,method,body)=>{
    if(method==='POST'){calls.push([path,body]);return 12;}
    return path.endsWith('/12?page=0&groupId=2&contestId=6')?detail:[];
 };
 const view=mount('/admin/judging?problem=1011&submission=18&groupId=2&contestId=6');
 fireEvent.change(view.getByLabelText('재채점 사유'),{target:{value:'오류 정정'}});
 fireEvent.click(view.getByRole('button',{name:'미리보기 생성'}));
 const apply=await view.findByRole('button',{name:'미리본 결과 확정 반영'});
 assert.deepEqual(calls,[['/api/admin/judging',{kind:'REJUDGE',problemNumber:1011,reason:'오류 정정',submissionId:18,groupId:2,contestId:6}]]);
 fireEvent.click(apply);assert.deepEqual(calls.at(-1),['/api/admin/judging/12/apply?groupId=2&contestId=6',{previewToken:'reviewed-version'}]);
 await view.findByText('판정 변화');
});
test('reference review presents both expected and actual output; ordinary accounts cannot fetch stored jobs',async()=>{
 globalThis.judgeApi=async(path,method)=>method==='POST'?8:path.endsWith('/8?page=0')?
   {id:8,kind:'REFERENCE',state:'READY',total:1,completed:1,jobs:[{id:3,language:'PYTHON',verdict:'WRONG_ANSWER',
      cases:[{kind:'HIDDEN',order:2,status:'WRONG_ANSWER',expected:'정답 예시',actual:'실제 예시'}]}]}:[];
 const view=mount('/admin/judging?kind=REFERENCE&problem=1000');
 fireEvent.click(view.getByRole('button',{name:'정답 코드 전체 테스트'}));
 await view.findByText('실제 예시');assert.ok(view.getByText('정답 예시'));
 view.unmount();
 const calls=[];globalThis.judgeAuth={epoch:2,permissions:{canManage:false}};
 globalThis.judgeApi=(...args)=>calls.push(args);
 const other=mount('/admin/judging');
 assert.ok(other.getByRole('alert'));assert.equal(calls.length,0);
});
test('opening a completed job does not fetch its history again',async()=>{
 const reads=[];
 globalThis.judgeApi=async path=>{
   reads.push(path);
   return path.endsWith('/4?page=0')
     ? {id:4,problemNumber:1024,state:'READY',kind:'REFERENCE',jobs:[],total:0,completed:0}
     : [{id:4,problemNumber:1024,state:'READY',kind:'REFERENCE'}];
 };
 const v=mount('/admin/judging');
 fireEvent.click(await v.findByRole('button',{name:/^#4/}));
 await v.findByRole('heading',{name:/^#4/});
 assert.deepEqual(reads,['/api/admin/judging?page=0','/api/admin/judging/4?page=0']);
});
