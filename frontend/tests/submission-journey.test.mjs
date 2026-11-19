import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';
import {build} from 'esbuild';
import {JSDOM} from 'jsdom';
import {submissionListUrl,submissionProblemLink} from '../src/lib/submissionNavigation.js';
const dom=new JSDOM('<!doctype html><html><body></body></html>',{url:'http://localhost:8080/'});
for(const key of ['window','document','HTMLElement','Event','MutationObserver'])globalThis[key]=dom.window[key];
dom.window.scrollTo=()=>{};
Object.defineProperty(globalThis,'navigator',{value:dom.window.navigator,configurable:true});globalThis.IS_REACT_ACT_ENVIRONMENT=true;
const require=createRequire(import.meta.url),React=require('react'),{render,fireEvent,waitFor,cleanup,act}=require('@testing-library/react'),{MemoryRouter,Routes,Route,useLocation}=require('react-router-dom');
const result=await build({stdin:{contents:"export {default as List} from './pages/MySubmissions.jsx';export {default as Result} from './pages/SubmissionResult.jsx';",resolveDir:fileURLToPath(new URL('../src/',import.meta.url)),loader:'jsx'},bundle:true,write:false,platform:'node',format:'cjs',packages:'external',jsx:'automatic',loader:{'.css':'empty'},plugins:[{name:'journey-contract',setup(builder){
  builder.onResolve({filter:/(?:^|\/)api(?:\.js)?$/},()=>({path:'api',namespace:'contract'}));
  builder.onResolve({filter:/\/context\/AuthContext(?:\.jsx)?$/},()=>({path:'auth',namespace:'contract'}));
  builder.onLoad({filter:/.*/,namespace:'contract'},args=>({contents:args.path==='api'?'export const api=(...args)=>globalThis.__journeyApi(...args);export const getSessionEpoch=()=>0;export const isStale=e=>!!e.stale;':'export const useAuth=()=>({epoch:0,user:{id:1}});'}));
}}]});
const module={exports:{}};new Function('require','module','exports',result.outputFiles[0].text)(require,module,module.exports);const {List,Result}=module.exports;
const rows=Array.from({length:35},(_,i)=>({id:i+1,problem_number:1000+i,problem_title:'연습 문제 '+i,language:'PYTHON',status:'WRONG_ANSWER',score:0,submitted_at:'2026-11-17T00:00:00Z'}));
function Screen(){const location=useLocation();return React.createElement(React.Fragment,null,React.createElement('output',{'data-testid':'location'},location.pathname+location.search),React.createElement(Routes,{key:location.key},React.createElement(Route,{path:'/submissions',element:React.createElement(List,{dark:true})}),React.createElement(Route,{path:'/submissions/:id',element:React.createElement(Result,{dark:true})})));}
const tree=url=>React.createElement(MemoryRouter,{initialEntries:[url]},React.createElement(Screen));
function history(path,all=rows) {
  const query=new URL(path,'http://localhost').searchParams;
  const matches=all.filter(row=>(row.problem_number+' '+row.problem_title).includes(query.get('q')||'')&&(!query.get('result')||row.status===query.get('result'))&&(!query.get('language')||row.language===query.get('language')));
  const totalPages=Math.ceil(matches.length/15),page=Math.min(Number(query.get('page')),Math.max(0,totalPages-1));
  return {items:matches.slice(15*page,15*(page+1)),page,totalPages,matches:matches.length,total:all.length,accepted:0,pending:0,languages:['PYTHON']};
}
test.beforeEach(()=>{globalThis.__journeyApi=async path=>path.startsWith('/api/submissions/history?')?history(path):{id:16,problemNumber:1015,status:'WRONG_ANSWER',cases:[]};});
test.afterEach(()=>cleanup());test.after(()=>dom.window.close());

test('submission filters and page survive detail navigation and list remounts',async()=>{
  const from='/submissions?q=연습&result=WRONG_ANSWER&language=PYTHON&page=1',view=render(tree(from));
  const row=await view.findByRole('link',{name:/#16 /});
  assert.equal(new URL(row.getAttribute('href'),'http://localhost').searchParams.get('from'),from);
  fireEvent.click(row);const back=await view.findByRole('link',{name:'문제로 돌아가기'});assert.equal(back.getAttribute('href'),'/problems/1015');
  fireEvent.click(view.getByRole('link',{name:'내 제출'}));
  await view.findByRole('link',{name:/#16 /});assert.equal(view.getByLabelText('문제 검색').value,'연습');assert.equal(view.getByLabelText('결과').value,'WRONG_ANSWER');assert.equal(view.getByLabelText('언어').value,'PYTHON');assert.ok(view.getByText('2 / 3'));
});
test('a direct result URL has a server-derived problem return link and retry only reads the result',async()=>{
  let fail=true;const calls=[];globalThis.__journeyApi=async(path,method='GET')=>{calls.push([path,method]);if(fail)throw Error('조회 연결 실패');return {id:4,problemNumber:1007,groupId:7,status:'TIME_LIMIT_EXCEEDED',cases:[]};};
  const view=render(tree('/submissions/4'));await view.findByRole('alert');assert.equal(view.queryByRole('link',{name:'문제로 돌아가기'}),null);
  fail=false;fireEvent.click(view.getByRole('button',{name:'채점 결과 다시 불러오기'}));
  assert.equal((await view.findByRole('link',{name:'문제로 돌아가기'})).getAttribute('href'),'/groups/7/problems/1007');assert.equal(view.queryByRole('alert'),null);assert.deepEqual(calls,[['/api/submissions/4','GET'],['/api/submissions/4','GET']]);
});
test('my submissions can open saved code inline and switching rows discards a late response',async()=>{
  const source="def sol():\n    return '<script>example</script>'\n";
  let finishFirst;
  const requests=[];
  globalThis.__journeyApi=async path=>{
    requests.push(path);
    if(path.startsWith('/api/submissions/history?'))return history(path);
    if(path==='/api/submissions/1/source')return new Promise(resolve=>{finishFirst=resolve;});
    assert.equal(path,'/api/submissions/2/source');
    return source;
  };
  const view=render(tree('/submissions'));
  fireEvent.click(await view.findByRole('button',{name:'제출 #1 코드 보기'}));
  await waitFor(()=>assert.equal(typeof finishFirst,'function'));
  fireEvent.click(view.getByRole('button',{name:'제출 #2 코드 보기'}));
  const region=view.getByRole('region',{name:'제출 #2 코드'});
  await waitFor(()=>assert.equal(region.querySelector('code')?.textContent,source));
  await act(async()=>finishFirst('previous row'));
  assert.equal(region.querySelector('script'),null);
  assert.equal(view.queryByText('previous row'),null);
  fireEvent.click(view.getByRole('button',{name:'제출 #2 코드 닫기'}));
  assert.equal(view.queryByRole('region',{name:'제출 #2 코드'}),null);
  assert.equal(requests.length,3);
});
test('own result can read the submitted code after retrying a failed source request',async()=>{
  let attempts=0;
  globalThis.__journeyApi=async path=>{
    if(path==='/api/submissions/4')return {id:4,problemNumber:1007,status:'WRONG_ANSWER',cases:[],canReadOwnSource:true};
    assert.equal(path,'/api/submissions/4/source');
    if(++attempts===1)throw Error('코드 조회 실패');
    return 'print(42)\n';
  };
  const view=render(tree('/submissions/4'));
  assert.equal((await view.findByRole('alert')).textContent,'코드 조회 실패');
  fireEvent.click(view.getByRole('button',{name:'코드 다시 불러오기'}));
  await waitFor(()=>assert.equal(view.getByRole('region',{name:'제출 #4 코드'}).querySelector('code')?.textContent,'print(42)\n'));
});
test('no history, no search matches and request failure offer distinct recovery actions',async()=>{
  globalThis.__journeyApi=async path=>history(path,[]);let view=render(tree('/submissions'));await view.findByText('아직 제출한 코드가 없습니다.');assert.ok(view.getByRole('link',{name:'문제 풀러 가기'}));assert.equal(view.queryByRole('navigation',{name:'제출 페이지'}),null);cleanup();
  globalThis.__journeyApi=async path=>history(path);view=render(tree('/submissions?q=없는제목'));await view.findByText('조건에 맞는 제출 기록이 없습니다.');fireEvent.click(view.getByRole('button',{name:'조건 지우고 다시 보기'}));await view.findByRole('link',{name:/#1 /});cleanup();
  globalThis.__journeyApi=async()=>{throw Error('목록 연결 실패');};view=render(tree('/submissions'));await view.findByRole('alert');assert.equal(view.queryByText('아직 제출한 코드가 없습니다.'),null);assert.ok(view.getByRole('button',{name:'다시 시도'}));
});
test('typing a search keeps the field mounted until search and stale pages clamp after loading',async()=>{
  const view=render(tree('/submissions?page=999'));await view.findByRole('link',{name:/#31 /});await waitFor(()=>assert.equal(new URL(view.getByTestId('location').textContent,'http://localhost').searchParams.get('page'),'2'));
  const input=view.getByLabelText('문제 검색');fireEvent.change(input,{target:{value:'연습 문제 34'}});assert.equal(view.getByLabelText('문제 검색'),input);
  fireEvent.click(view.getByRole('button',{name:'검색',exact:true}));await view.findByRole('link',{name:/#35 /});assert.equal(view.queryByRole('navigation',{name:'제출 페이지'}),null);assert.equal(new URL(view.getByTestId('location').textContent,'http://localhost').searchParams.get('page'),null);
});
test('return destinations stay internal and contest answers return to their contest',()=>{
  for(const url of ['https://example.com','//evil','/submissions/42','/submissions?x=1#bad','/submissions\\evil'])assert.equal(submissionListUrl(url),'/submissions');
  assert.equal(submissionListUrl('/submissions?q=a&page=2'),'/submissions?q=a&page=2');
  assert.deepEqual(submissionProblemLink({contest_id:3,contestGroupId:7,problemNumber:1000}),{to:'/groups/7/contests/3',label:'코딩테스트로 돌아가기'});
  assert.equal(submissionProblemLink({contest_id:3,problemNumber:1000}),null);
  for(const problemNumber of [null,0,-1,'bad',Infinity])assert.equal(submissionProblemLink({problemNumber}),null);
});

test('filtered CSV includes both batches when the history exceeds 500 records',async t=>{
  const historyRows=Array.from({length:501},(_,i)=>({...rows[0],id:501-i,problem_title:'따옴표 "예시", 문제'}));
  const requests=[];
  globalThis.__journeyApi=async path=>{
    const url=new URL(path,'http://localhost');
    if(url.pathname==='/api/submissions/history')return history(path,historyRows);
    assert.equal(url.pathname,'/api/submissions/history/export');
    assert.equal(url.searchParams.get('result'),'WRONG_ANSWER');
    const before=url.searchParams.get('before');
    requests.push(before);
    return historyRows.filter(row=>before===null||row.id<Number(before)).slice(0,500);
  };
  let file,downloadName;
  t.mock.method(URL,'createObjectURL',blob=>{file=blob;return 'blob:test';});
  t.mock.method(dom.window.HTMLAnchorElement.prototype,'click',function(){downloadName=this.download;});
  const view=render(tree('/submissions?result=WRONG_ANSWER'));
  const button=view.getByRole('button',{name:'CSV 내보내기'});
  await waitFor(()=>assert.equal(button.disabled,false));
  fireEvent.click(button);
  await waitFor(()=>assert.equal(downloadName,'my-submissions.csv'));
  const lines=(await file.text()).trimStart().split('\r\n');
  assert.deepEqual(requests,[null,'2']);
  assert.equal(lines.length,502);
  assert.deepEqual(lines.slice(1).map(line=>Number(line.match(/^"(\d+)"/)[1])),historyRows.map(row=>row.id));
  assert.match(lines[1],/"따옴표 ""예시"", 문제"/);
});
