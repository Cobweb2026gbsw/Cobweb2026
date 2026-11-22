import {useEffect,useState} from 'react';
import {Link,useSearchParams} from 'react-router-dom';
import {useAuth} from '../context/AuthContext';
import {api} from '../lib/api';
import useApiData,{useMutationGuard} from '../lib/useApiData';
import {labelStatus} from '../lib/problemModel';
import './common.css';
import './AdminJudging.css';

const names={REFERENCE:'정답 코드 검증',REJUDGE:'재채점',RUNNING:'실행 중',READY:'완료',FAILED:'검증 실패',APPLIED:'반영됨',CANCELLED:'취소됨'};
const judgePath='/api/admin/judging';

function Changes({impact}) {
  const rows=impact.points??impact.standings??[];
  return <section><h3>판정 변화</h3><p>정답으로 변경 {impact.newlyAccepted}건 ({impact.gainingUsers}명) · 정답에서 변경 {impact.lostAccepted}건 · 전체 변경 {impact.changedVerdicts}건</p>
    <h4>{impact.points?'개인 점수 변화':'시험 순위 변화'}</h4>
    {rows.length===0?<p>변동 없음</p>:<div className="aj-scroll"><table><thead><tr><th>참가자</th><th>이전</th><th>변경 후</th></tr></thead>
    <tbody>{rows.map(row=><tr key={row.username}><td>{row.username}</td><td>{impact.points?`${row.beforePoints}점 · ${row.beforeRank}`:`${row.beforeRank}위 · ${row.beforeSolved}문제 · ${row.beforeSeconds}초`}</td>
      <td>{impact.points?`${row.afterPoints}점 · ${row.afterRank}`:`${row.afterRank}위 · ${row.afterSolved}문제 · ${row.afterSeconds}초`}</td></tr>)}</tbody></table></div>}
  </section>;
}

function Page({dark}) {
  const {permissions,epoch}=useAuth();
  const [params]=useSearchParams();
  const [problem,setProblem]=useState(params.get('problem')||''),[submission,setSubmission]=useState(params.get('submission')||'');
  const [group,setGroup]=useState(params.get('groupId')||''),[contest,setContest]=useState(params.get('contestId')||'');
  const [kind,setKind]=useState(params.get('kind')==='REFERENCE'?'REFERENCE':'REJUDGE'),[reason,setReason]=useState('');
  const [id,setId]=useState(null),[page,setPage]=useState(0),[historyPage,setHistoryPage]=useState(0);
  const [busy,setBusy]=useState(false),[error,setError]=useState('');
  const reference=kind==='REFERENCE';
  const completeScope=reference||Boolean(group)===Boolean(contest);
  const scope=!reference&&group&&contest?'groupId='+encodeURIComponent(group)+'&contestId='+encodeURIComponent(contest):'';
  const history=useApiData(permissions.canManage&&completeScope?`${judgePath}?page=${historyPage}${scope?'&'+scope:''}`:null);
  const detail=useApiData(permissions.canManage&&completeScope&&id?`${judgePath}/${id}?page=${page}${scope?'&'+scope:''}`:null);
  const current=useMutationGuard(epoch,id,group,contest,kind);
  const d=detail.data;
  useEffect(()=>{
    if(d?.state!=='RUNNING')return;
    const timer=setTimeout(detail.reload,3000);return()=>clearTimeout(timer);
  },[d,detail.reload]);
  useEffect(()=>{if(d?.state && d.state!=='RUNNING') history.reload();},[d?.state,history.reload]);
  function select(next){setId(next);setPage(0);setError('');}
  async function submit(event) {
    event.preventDefault();const valid=current();setBusy(true);setError('');
    try {
      const result=await api(judgePath,'POST',{kind,problemNumber:Number(problem),reason,
        submissionId:!reference&&submission?Number(submission):null,groupId:!reference&&group?Number(group):null,contestId:!reference&&contest?Number(contest):null});
      if(valid()){setBusy(false);select(result);history.reload();detail.reload();}
    } catch(e){if(valid()&&!e.stale)setError(e.message);} finally{if(valid())setBusy(false);}
  }
  async function decide(action) {
    const valid=current();setBusy(true);setError('');
    try {await api(`${judgePath}/${id}/${action}${scope?'?'+scope:''}`,'POST',action==='apply'?{previewToken:d.previewToken}:null);
      if(valid()){detail.reload();history.reload();}}
    catch(e){if(valid()&&!e.stale){setError(e.message);detail.reload();}}finally{if(valid())setBusy(false);}
  }
  if(!permissions.canManage)return <main className="cw-page"><p role="alert">운영자 권한이 필요합니다.</p></main>;
  return <main className={'cw-page aj-page '+(dark?'dark':'light')}><div className="cw-wrap">
    <header className="cw-head"><h1 className="cw-title">정답 검증 · 재채점</h1><Link className="cw-btn" to="/admin">운영 현황</Link></header>
    <form className="cw-card aj-form" onSubmit={submit}>
      <div className="cw-form-grid"><label>작업<select disabled={busy} className="cw-select" value={kind} onChange={e=>{select(null);setHistoryPage(0);setKind(e.target.value);}}>
        <option value="REFERENCE">정답 코드 전체 검증</option><option value="REJUDGE">재채점 미리보기</option></select></label>
      <label>문제 번호<input className="cw-input" type="number" min="1" required value={problem} onChange={e=>setProblem(e.target.value)}/></label>
      {!reference&&<><label>제출 번호 (비우면 해당 범위 전체)<input type="number" min="1" className="cw-input" value={submission} onChange={e=>setSubmission(e.target.value)}/></label>
      <label>시험 그룹 번호<input type="number" min="1" disabled={busy} required={Boolean(contest)} className="cw-input" value={group} onChange={e=>{select(null);setHistoryPage(0);setGroup(e.target.value);}}/></label>
      <label>시험 번호 (비우면 일반 제출)<input type="number" min="1" disabled={busy} required={Boolean(group)} className="cw-input" value={contest} onChange={e=>{select(null);setHistoryPage(0);setContest(e.target.value);}}/></label>
      <label>재채점 사유<input className="cw-input" value={reason} required maxLength={500} onChange={e=>setReason(e.target.value)}/></label></>}</div>
      <button className="cw-btn primary" disabled={busy}>{reference?'정답 코드 전체 테스트':'미리보기 생성'}</button>
    </form>
    {(error||history.error||detail.error)&&<p role="alert" className="cw-status-message error">{error||history.error||detail.error}</p>}
    <div className="aj-layout"><section className="cw-card"><h2>작업 이력</h2>
      {history.data?.map(row=><button className="cw-btn aj-history" type="button" disabled={busy} key={row.id} onClick={()=>select(row.id)}>
        #{row.id} · {row.problemNumber}번 {names[row.kind]} · {names[row.state]}</button>)}
      {!history.loading&&!history.data?.length&&<p>등록된 작업이 없습니다.</p>}
      <div className="cw-pager"><button className="cw-btn" disabled={historyPage===0} onClick={()=>setHistoryPage(n=>n-1)}>이전</button>
      <span>{historyPage+1}</span><button className="cw-btn" disabled={history.data?.length!==30} onClick={()=>setHistoryPage(n=>n+1)}>다음</button></div></section>
    <section className="cw-card" aria-live="polite">{!d?<p>{detail.loading?'불러오는 중…':'작업을 선택하면 결과가 표시됩니다.'}</p>:<>
      <h2>#{d.id} {names[d.kind]} · {d.problemNumber}번</h2><p>{names[d.state]} · {d.completed}/{d.total} · {d.creator}</p><p>{d.reason}</p>
      {d.jobs.map(job=><details key={job.id} className="cw-status-message"><summary>{job.submissionId?'제출 #'+job.submissionId:'정답 코드'} · {job.language} · {job.previousVerdict&&labelStatus(job.previousVerdict).label+' → '}{job.verdict?labelStatus(job.verdict).label:job.state==='QUEUED'?'대기':'실행 중'}</summary>
        {job.timeMs!=null&&<p>최대 실행 시간 {job.timeMs}ms</p>}
        {job.cases?.map((c,i)=><details key={i}><summary>{({EXAMPLE:'공개 예제',HIDDEN:'채점 케이스',COMPILE:'컴파일',WORKER:'워커'})[c.kind]} {c.order||''} · {labelStatus(c.status).label}</summary>
          {c.expected!=null&&<><h4>예상 출력</h4><pre>{c.expected}</pre></>}{c.actual!=null&&<><h4>실제 출력</h4><pre>{c.actual}</pre></>}</details>)}</details>)}
      {d.total>30&&<div className="cw-pager"><button className="cw-btn" disabled={!page} onClick={()=>setPage(n=>n-1)}>이전 결과</button>
        <span>{page+1}</span><button className="cw-btn" disabled={(page+1)*30>=d.total} onClick={()=>setPage(n=>n+1)}>다음 결과</button></div>}
      {d.impact&&<Changes impact={d.impact}/>}
      <div className="cw-actions">{d.kind==='REJUDGE'&&d.state==='READY'&&<button type="button" disabled={busy} className="cw-btn primary" onClick={()=>decide('apply')}>미리본 결과 확정 반영</button>}
      {d.state==='RUNNING'||(d.kind==='REJUDGE'&&d.state==='READY')?<button disabled={busy} type="button" className="cw-btn" onClick={()=>decide('cancel')}>작업 취소</button>:null}</div>
    </>}</section></div>
  </div></main>;
}
export default function AdminJudging(props){const {epoch}=useAuth();const [params]=useSearchParams();return <Page key={epoch+':'+params.toString()} {...props}/>;}
