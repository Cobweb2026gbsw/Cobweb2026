import {useEffect,useMemo,useState} from 'react';
import {Link} from 'react-router-dom';
import {useAuth} from '../context/AuthContext';
import useApiData from '../lib/useApiData';
import {STATUS,labelStatus,formatDate} from '../lib/problemModel';
import './common.css';
import './MySubmissions.css';
export const RESULTS=STATUS;
const csvCell=value=>'"'+String(value??'').replace(/^[=+@-]/,"'$&").replaceAll('"','""')+'"';
function SubmissionListPage({dark}){
  const {data,error,loading,reload}=useApiData('/api/submissions');const [q,setQ]=useState(''),[result,setResult]=useState(''),[language,setLanguage]=useState(''),[page,setPage]=useState(0);
  useEffect(()=>{if(!(data||[]).some(s=>['QUEUED','RUNNING'].includes(s.status)))return;const timer=setTimeout(reload,3000);return()=>clearTimeout(timer);},[data,reload]);
  const filtered=useMemo(()=>(data||[]).filter(s=>(!q||`${s.problem_number} ${s.problem_title}`.toLowerCase().includes(q.toLowerCase()))&&(!result||s.status===result)&&(!language||s.language===language)),[data,q,result,language]);
  const rows=filtered.slice(page*15,page*15+15),accepted=(data||[]).filter(s=>s.status==='ACCEPTED').length;
  function download(){const csv='\uFEFF'+[['제출','문제번호','문제','언어','결과','시간(ms)','제출시각'],...filtered.map(s=>[s.id,s.problem_number,s.problem_title,s.language,s.status,s.execution_time_ms,s.submitted_at])].map(row=>row.map(csvCell).join(',')).join('\r\n');const url=URL.createObjectURL(new Blob([csv],{type:'text/csv;charset=utf-8'})),a=document.createElement('a');a.href=url;a.download='my-submissions.csv';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}
  return <main className={`cw-page ms-page ${dark?'dark':'light'}`}><div className="cw-wrap"><header className="cw-head"><div><p className="cw-eyebrow">MY ACTIVITY / LIVE</p><h1 className="cw-title">내 제출</h1><p className="cw-desc">실제 일반 제출 최근 최대 50개입니다. 시험 제출은 각 코테에서 별도로 확인하세요.</p></div><button className="cw-btn" onClick={download} disabled={!filtered.length}>CSV 내보내기</button></header>
    <section className="ms-stats">{[['불러온 제출',data?.length??0],['정답 제출',accepted],['대기·진행 중',(data||[]).filter(s=>['QUEUED','RUNNING'].includes(s.status)).length],['검색 결과',filtered.length]].map(([label,value])=><div className="cw-card ms-stat" key={label}><div><p className="ms-stat-label">{label}</p><p className="ms-stat-value"><strong>{value}</strong></p></div></div>)}</section>
    <section className="cw-card ms-filter"><div className="ms-filter-row"><label>문제 검색<input className="cw-input" value={q} onChange={e=>{setQ(e.target.value);setPage(0);}}/></label><label>결과<select className="cw-select" value={result} onChange={e=>{setResult(e.target.value);setPage(0);}}><option value="">전체</option>{Object.entries(STATUS).map(([key,s])=><option value={key} key={key}>{s.label}</option>)}</select></label><label>언어<select className="cw-select" value={language} onChange={e=>{setLanguage(e.target.value);setPage(0);}}><option value="">전체</option>{[...new Set((data||[]).map(s=>s.language))].map(id=><option key={id}>{id}</option>)}</select></label><button className="cw-btn" onClick={reload}>새로고침</button></div></section>
    {error&&<p className="cw-status-message error" role="alert">{error}</p>}<section className="cw-card ms-table"><div className="ms-row ms-row-head"><span>제출</span><span>문제</span><span>언어</span><span>결과</span><span>시간</span><span>점수</span><span>제출시각</span></div>{loading&&<p className="ms-empty">불러오는 중…</p>}{!loading&&!rows.length&&<p className="ms-empty">제출 기록이 없습니다.</p>}{rows.map(s=><Link to={`/submissions/${s.id}`} className="ms-row ms-row-item" key={s.id}><span>#{s.id}</span><span className="ms-title">{s.problem_number}. {s.problem_title}</span><span>{s.language}</span><span className={`cw-badge ${labelStatus(s.status).tone}`}>{labelStatus(s.status).label}</span><span>{s.execution_time_ms??'—'} ms</span><span>{s.score??'—'}</span><span>{formatDate(s.submitted_at)}</span></Link>)}</section><nav className="cw-pager ms-foot"><button className="cw-btn" disabled={!page} onClick={()=>setPage(page-1)}>이전</button><span>{page+1}</span><button className="cw-btn" disabled={(page+1)*15>=filtered.length} onClick={()=>setPage(page+1)}>다음</button></nav>
  </div></main>;
}
export default function MySubmissions(props){const {epoch}=useAuth();return <SubmissionListPage key={epoch} {...props}/>;}
