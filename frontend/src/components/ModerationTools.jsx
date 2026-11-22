import {useRef,useState} from 'react';
import {Link} from 'react-router-dom';
import useApiData,{useMutationGuard} from '../lib/useApiData';

export const issueStatus={OPEN:'접수',IN_PROGRESS:'처리 중',RESOLVED:'완료'};
export const categoryLabel={PROBLEM_ERROR:'문제 오류',JUDGING_APPEAL:'채점 이의제기'};

export function contestScope(params) {
  const scope=new URLSearchParams();
  for(const key of ['groupId','contestId']) if(params.has(key)) scope.set(key,params.get(key));
  return scope;
}
export function dateLabel(value) {
  if(!value) return '—';
  return new Intl.DateTimeFormat('ko-KR',{dateStyle:'medium',timeStyle:'short',timeZone:'Asia/Seoul'}).format(new Date(value))+' (한국)';
}
// 입력은 브라우저/기기의 시간대와 관계없이 화면에 명시한 한국 시각입니다.
export function seoulInstant(value) {
  const instant=new Date(value+':00+09:00');
  if(!Number.isFinite(instant.getTime())) throw new Error('날짜와 시각을 확인해주세요.');
  return instant.toISOString();
}
export function useOperation() {
  const valid=useMutationGuard(),flight=useRef(false);
  const [busy,setBusy]=useState(false),[message,setMessage]=useState(''),[error,setError]=useState('');
  async function run(action,done) {
    if(flight.current)return;
    const current=valid(); flight.current=true;setBusy(true);setMessage('');setError('');
    try {const value=await action();if(current())setMessage(done(value));}
    catch(e){if(current()&&!e.stale)setError(e.message);}
    finally {flight.current=false;if(current())setBusy(false);}
  }
  return {run,busy,feedback:<>{error&&<p className="cw-status-message error" role="alert">{error}</p>}{message&&<p role="status">{message}</p>}</>};
}
export function Queue({url,page,setPage,refresh,render,empty='등록된 항목이 없습니다.'}) {
  const result=useApiData(url+(url.includes('?')?'&':'?')+new URLSearchParams({page,refresh}));
  return <section className="cw-card ops-list" aria-busy={result.loading}>
    {result.loading?<p role="status">불러오는 중…</p>:result.error?<p className="cw-status-message error" role="alert">{result.error} <button className="cw-btn sm" onClick={result.reload}>다시 시도</button></p>
      :result.data?.length?result.data.map(render):<p>{empty}</p>}
    {(page>0||result.data?.length===30)&&<nav className="cw-pager" aria-label="목록 페이지">
      <button className="cw-btn sm" disabled={page===0} onClick={()=>setPage(page-1)}>이전</button>
      <span>{page+1}</span>
      <button className="cw-btn sm" disabled={result.data?.length!==30} onClick={()=>setPage(page+1)}>다음</button>
    </nav>}
  </section>;
}
export function IssueDetails({issue,children}) {
  return <article className="ops-item"><div className="ops-heading"><h3>#{issue.id} · {categoryLabel[issue.category]}</h3>
    <span>{issueStatus[issue.status]}</span></div>
    <p className="cw-hint">문제 #{issue.problemNumber}{issue.submissionId&&' · 제출 #'+issue.submissionId} · {issue.reporter} · 담당 {issue.assignee||'미지정'}</p>
    <p className="ops-text">{issue.body}</p>
    {issue.response&&<div className="ops-response"><strong>담당자 답변</strong><p className="ops-text">{issue.response}</p></div>}
    <small>{dateLabel(issue.updatedAt)}</small>{children}</article>;
}
export function BackToContest({scope}){
  return scope.has('contestId')&&<Link className="cw-btn" to={'/groups/'+encodeURIComponent(scope.get('groupId'))+'/contests/'+encodeURIComponent(scope.get('contestId'))}>시험으로</Link>;
}
