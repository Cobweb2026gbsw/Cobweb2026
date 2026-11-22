import {useState} from 'react';
import {Link,useSearchParams} from 'react-router-dom';
import {api} from '../lib/api';
import {useAuth} from '../context/AuthContext';
import {BackToContest,contestScope,dateLabel,IssueDetails,issueStatus,Queue,seoulInstant,useOperation} from '../components/ModerationTools';
import './common.css';
import './Moderation.css';

const base='/api/admin/moderation';
const featureName={POST:'게시글 작성·수정',COMMENT:'댓글·답글 작성·수정'};
const states={ACTIVE:'적용 중',EXPIRED:'만료',REVOKED:'해제',PENDING:'예약 대기',PUBLISHED:'공개 완료',CANCELLED:'예약 취소',FAILED:'예약 실패'};

function IssueReview({issue,scope,reload}){
  const [status,setStatus]=useState(issue.status),[response,setResponse]=useState(issue.response),[assignee,setAssignee]=useState(issue.assignee||'');
  const action=useOperation(), {permissions,user}=useAuth();
  const rejudge=new URLSearchParams(scope);rejudge.set('problem',issue.problemNumber);
  if(issue.submissionId)rejudge.set('submission',issue.submissionId);
  function save(e){e.preventDefault();action.run(()=>api(base+'/issues/'+issue.id+'?'+scope,'PATCH',{status,response,assignee:assignee.trim(),version:issue.version}),()=>{reload();return '저장했습니다.';});}
  return <IssueDetails issue={issue}>
    {permissions.canManage&&<Link className="cw-btn sm" to={'/admin/judging?'+rejudge}>재채점 미리보기로</Link>}
    <details className="ops-open"><summary>담당·상태·답변 수정</summary>
      <form className="ops-form" onSubmit={save}><label>처리 상태<select className="cw-select" value={status} onChange={e=>setStatus(e.target.value)}>
        {Object.entries(issueStatus).map(([k,v])=><option key={k} value={k}>{v}</option>)}</select></label>
        <label>담당자 아이디<input className="cw-input" maxLength={16} value={assignee} onChange={e=>setAssignee(e.target.value)}/></label><button type="button" className="cw-btn sm" onClick={()=>setAssignee(user.username)}>내가 담당하기</button>
        <label>신고자에게 보낼 답변<textarea className="cw-textarea" value={response} required={status==='RESOLVED'} maxLength={2000} onChange={e=>setResponse(e.target.value)}/></label>
        {action.feedback}<button className="cw-btn primary" disabled={action.busy}>저장</button>
      </form></details>
  </IssueDetails>;
}

function IssueInbox({scope}) {
  const [status,setStatus]=useState(''),[page,setPage]=useState(0),[refresh,setRefresh]=useState(0);
  const params=new URLSearchParams(scope);if(status)params.set('status',status);
  return <><label className="ops-fields">처리 상태<select className="cw-select" value={status} onChange={e=>{setStatus(e.target.value);setPage(0);}}>
    <option value="">전체</option>{Object.entries(issueStatus).map(([k,v])=><option key={k} value={k}>{v}</option>)}</select></label>
    <Queue url={base+'/issues?'+params} refresh={refresh} page={page} setPage={setPage} render={issue=><IssueReview key={issue.id+':'+issue.version} issue={issue} scope={scope} reload={()=>setRefresh(n=>n+1)}/>}/></>;
}

function Restrictions(){
  const [username,setUsername]=useState(''),[feature,setFeature]=useState('COMMENT'),[expires,setExpires]=useState(''),[reason,setReason]=useState('');
  const [search,setSearch]=useState(''),[filter,setFilter]=useState(''),[page,setPage]=useState(0),[refresh,setRefresh]=useState(0),action=useOperation();
  function saved(){setPage(0);setRefresh(n=>n+1);}
  function create(e){e.preventDefault();action.run(()=>api(base+'/restrictions','POST',{username,feature,reason,expiresAt:seoulInstant(expires)}),()=>{saved();setReason('');return '제한을 등록했습니다.';});}
  return <><form className="cw-card ops-form" onSubmit={create}><h2>작성 제한 등록</h2><div className="ops-fields">
    <label>회원 아이디<input required maxLength={16} className="cw-input" value={username} onChange={e=>setUsername(e.target.value)}/></label>
    <label>제한 기능<select className="cw-select" value={feature} onChange={e=>setFeature(e.target.value)}>{Object.entries(featureName).map(([k,v])=><option key={k} value={k}>{v}</option>)}</select></label>
    <label>종료 시각 (한국)<input required className="cw-input" type="datetime-local" value={expires} onChange={e=>setExpires(e.target.value)}/></label>
  </div><label>회원에게 표시할 사유<textarea className="cw-textarea" required maxLength={500} value={reason} onChange={e=>setReason(e.target.value)}/></label>
  <button className="cw-btn primary" disabled={action.busy||!reason.trim()}>제한 등록</button></form>{action.feedback}
  <h2>제한 내역</h2><form className="ops-fields" onSubmit={e=>{e.preventDefault();setFilter(search.trim());setPage(0);}}><label>회원 아이디 검색<input className="cw-input" value={search} maxLength={16} onChange={e=>setSearch(e.target.value)}/></label><button className="cw-btn">검색</button></form>
  <Queue url={base+'/restrictions?username='+encodeURIComponent(filter)} refresh={refresh} page={page} setPage={setPage} render={r=><article key={r.id} className="ops-item">
    <div className="ops-heading"><h3>{r.username} · {featureName[r.feature]}</h3><span>{states[r.status]}</span></div>
    <p className="ops-text">{r.reason}</p><small>종료 {dateLabel(r.expiresAt)} · 등록 {r.issuer}{r.revoker&&' · 해제 '+r.revoker}</small>
    {r.status==='ACTIVE'&&<button className="cw-btn sm" disabled={action.busy} onClick={()=>action.run(()=>api(base+'/restrictions/'+r.id+'/revoke','POST'),()=>{saved();return '제한을 해제했습니다.';})}>지금 해제</button>}</article>}/>
  </>;
}

function Releases(){
  const [numbers,setNumbers]=useState(''),[when,setWhen]=useState(''),[page,setPage]=useState(0),[refresh,setRefresh]=useState(0),action=useOperation();
  function saved(){setPage(0);setRefresh(n=>n+1);}
  function create(e){e.preventDefault();action.run(()=>{
    const parts=numbers.trim().split(/[\s,]+/);
    if(!parts.length||parts.some(n=>!/^\d+$/.test(n)||!Number.isSafeInteger(Number(n))||Number(n)<1))throw new Error('문제 번호를 쉼표 또는 공백으로 구분해주세요.');
    return api(base+'/releases','POST',{problemNumbers:parts.map(Number),scheduledFor:seoulInstant(when)});
  },id=>{saved();setNumbers('');return '#'+id+'번 예약을 등록했습니다.';});}
  return <><form className="cw-card ops-form" onSubmit={create}><h2>문제 묶음 예약</h2>
    <p>검수 대기 중인 일반 공개 요청은 예약과 함께 승인됩니다. 예약한 문제의 내용이나 상태가 변경되면 묶음 전체의 공개가 중단됩니다.</p>
    <div className="ops-fields"><label>문제 번호 (쉼표 또는 공백으로 구분)<input required className="cw-input" value={numbers} onChange={e=>setNumbers(e.target.value)}/></label>
    <label>공개 시각 (한국)<input required className="cw-input" type="datetime-local" value={when} onChange={e=>setWhen(e.target.value)}/></label></div>
    <button className="cw-btn primary" disabled={action.busy}>검수 승인 및 예약</button></form>{action.feedback}
    <h2>공개 예약 내역</h2><Queue url={base+'/releases'} page={page} refresh={refresh} setPage={setPage} render={r=><article className="ops-item" key={r.id}>
      <div className="ops-heading"><h3>예약 #{r.id} · {dateLabel(r.scheduledFor)}</h3><span>{states[r.status]}</span></div>
      <p>문제 {r.numbers}</p><small>등록 {r.creator}</small>{r.failureReason&&<p>{r.failureReason}</p>}
      {r.status==='PENDING'&&<button disabled={action.busy} className="cw-btn sm" onClick={()=>action.run(()=>api(base+'/releases/'+r.id+'/cancel','POST'),()=>{saved();return '예약을 취소했습니다.';})}>예약 취소</button>}</article>}/>
  </>;
}

export default function AdminModeration({dark,mode}){
  const {epoch,user,permissions,loading}=useAuth(),[params]=useSearchParams(),scope=contestScope(params);
  const exam=mode==='issues'&&scope.has('contestId'),label={issues:exam?'시험 신고·이의제기 관리':'문제 신고·이의제기 관리',restrictions:'회원 기능 제한',releases:'문제 예약 공개'}[mode];
  return <main className={'cw-page moderation-page '+(dark?'dark':'light')}><div className="cw-wrap"><header className="cw-head"><h1>{label}</h1>
    {exam?<BackToContest scope={scope}/>:<Link className="cw-btn" to="/admin">관리자 홈</Link>}</header>
    {loading?<p>불러오는 중…</p>:!user||(!exam&&!permissions.canManage)?<p>관리 권한이 필요합니다.</p>:
      <div key={epoch+':'+mode+':'+scope}>{mode==='issues'?<IssueInbox scope={scope}/>:mode==='restrictions'?<Restrictions/>:<Releases/>}</div>}
  </div></main>;
}
