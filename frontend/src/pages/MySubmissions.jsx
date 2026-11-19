import {Fragment,useEffect,useRef,useState} from 'react';
import {Link,useLocation,useSearchParams} from 'react-router-dom';
import {useAuth} from '../context/AuthContext';
import {api,isStale} from '../lib/api';
import useApiData from '../lib/useApiData';
import {STATUS,labelStatus,formatDate} from '../lib/problemModel';
import {requestedProblemPage} from '../lib/problemPagination';
import {submissionResultUrl} from '../lib/submissionNavigation';
import SubmissionSource from '../components/SubmissionSource';
import './common.css';
import './MySubmissions.css';
export const RESULTS=STATUS;
const csvCell=value=>'"'+String(value??'').replace(/^[=+@-]/,"'$&").replaceAll('"','""')+'"';
function SubmissionListPage({dark}){
  const [params,setParams]=useSearchParams(),location=useLocation();
  const q=(params.get('q')||'').slice(0,200),result=params.get('result')||'',language=params.get('language')||'',requestedPage=requestedProblemPage(params.get('page'));
  const query=new URLSearchParams({q,result,language});
  const filterQuery=query.toString();
  query.set('page',String(requestedPage));
  const {data,error,loading,reload}=useApiData('/api/submissions/history?'+query);
  const [draft,setDraft]=useState(q);
  const [openSource,setOpenSource]=useState(null);
  const [exporting,setExporting]=useState(false),[exportError,setExportError]=useState('');
  const exportRequest=useRef(null);
  useEffect(()=>setDraft(q),[q]);
  useEffect(()=>{
    setExportError('');
    setExporting(false);
    return ()=>{exportRequest.current?.abort();exportRequest.current=null;};
  },[filterQuery]);
  useEffect(()=>{
    if(!data?.pending)return;
    const timer=setTimeout(reload,3000);
    return ()=>clearTimeout(timer);
  },[data,reload]);
  const rows=data?.items??[],page=data?.page??requestedPage,totalPages=data?.totalPages??0;
  const hasFilters=Boolean(q||result||language),from=location.pathname+location.search;
  useEffect(()=>{if(!loading&&!error&&requestedPage!==page){const next=new URLSearchParams(params);next.set('page',String(page));setParams(next,{replace:true});}},[loading,error,requestedPage,page,params,setParams]);
  function update(key,value){const next=new URLSearchParams(params);if(value)next.set(key,value);else next.delete(key);next.delete('page');setParams(next);}
  function changePage(value){const next=new URLSearchParams(params);next.set('page',String(value));setParams(next);}
  function reset(){setDraft('');setParams({});}
  async function download(){
    if(exportRequest.current)return;
    const controller=new AbortController();
    exportRequest.current=controller;
    setExporting(true);setExportError('');
    try {
      const params=new URLSearchParams(filterQuery);
      const lines=[['제출','문제번호','문제','언어','결과','시간(ms)','제출시각'].map(csvCell).join(',')];
      let batch;
      do {
        batch=await api('/api/submissions/history/export?'+params,'GET',null,{signal:controller.signal});
        if(controller.signal.aborted)return;
        for(const s of batch)lines.push([s.id,s.problem_number,s.problem_title,s.language,s.status,s.execution_time_ms,s.submitted_at].map(csvCell).join(','));
        if(batch.length)params.set('before',batch.at(-1).id);
      } while(batch.length===500);
      const url=URL.createObjectURL(new Blob(['\uFEFF',lines.join('\r\n')],{type:'text/csv;charset=utf-8'}));
      const link=document.createElement('a');link.href=url;link.download='my-submissions.csv';link.click();
      setTimeout(()=>URL.revokeObjectURL(url),1000);
    } catch(failure){
      if(!controller.signal.aborted&&!isStale(failure))setExportError('CSV를 내보내지 못했어요. 다시 시도해 주세요.');
    } finally {
      if(exportRequest.current===controller){exportRequest.current=null;setExporting(false);}
    }
  }
  return <main className={`cw-page ms-page ${dark?'dark':'light'}`}><div className="cw-wrap"><header className="cw-head"><div><p className="cw-eyebrow">MY ACTIVITY</p><h1 className="cw-title">내 제출</h1><p className="cw-desc">문제 번호나 제목으로 내 전체 일반 제출을 검색하세요. 코딩테스트 답안은 해당 코딩테스트에서 확인하세요.</p></div><button className="cw-btn" onClick={download} disabled={loading||Boolean(error)||!data?.matches||exporting}>{exporting?'CSV 준비 중…':'CSV 내보내기'}</button></header>
    {exportError&&<p role="alert">{exportError}</p>}
    <section className="ms-stats">{[['전체 제출',data?.total],['정답 제출',data?.accepted],['대기·진행 중',data?.pending],['검색 결과',data?.matches]].map(([label,value])=><div className="cw-card ms-stat" key={label}><div><p className="ms-stat-label">{label}</p><p className="ms-stat-value"><strong>{loading||error?'—':value}</strong></p></div></div>)}</section>
    <form className="cw-card ms-filter" onSubmit={event=>{event.preventDefault();update('q',draft.trim());}}><div className="ms-filter-row"><label>문제 검색<input className="cw-input" value={draft} maxLength={200} placeholder="제목 또는 문제 번호" onChange={event=>setDraft(event.target.value)}/></label><label>결과<select className="cw-select" value={result} onChange={event=>update('result',event.target.value)}><option value="">전체</option>{Object.entries(STATUS).map(([key,s])=><option value={key} key={key}>{s.label}</option>)}</select></label><label>언어<select className="cw-select" value={language} onChange={event=>update('language',event.target.value)}><option value="">전체</option>{[...new Set([...(data?.languages??[]),...(language?[language]:[])])].map(id=><option key={id}>{id}</option>)}</select></label><button className="cw-btn primary" type="submit">검색</button><button className="cw-btn" type="button" onClick={reload} disabled={loading}>새로고침</button></div>{hasFilters&&<div className="ms-quick"><span>검색 조건 적용 중</span><button className="cw-btn sm" type="button" onClick={reset}>검색 조건 초기화</button></div>}</form>
    <section className="cw-card ms-table" aria-label="제출 목록" aria-busy={loading}>
      {error?<div className="ms-empty"><p className="cw-status-message error" role="alert">{error}</p><button type="button" className="cw-btn" onClick={reload}>다시 시도</button></div>:loading?<p className="ms-empty" role="status">제출 기록을 불러오는 중…</p>:!rows.length?<div className="ms-empty"><p>{hasFilters?'조건에 맞는 제출 기록이 없습니다.':'아직 제출한 코드가 없습니다.'}</p>{hasFilters?<button className="cw-btn" type="button" onClick={reset}>조건 지우고 다시 보기</button>:<Link className="cw-btn primary" to="/problems">문제 풀러 가기</Link>}</div>:<>
      <div className="ms-entry ms-entry-head"><div className="ms-row ms-row-head"><span>제출</span><span>문제</span><span>언어</span><span>결과</span><span>시간</span><span>점수</span><span>제출시각</span></div><span>코드</span></div>
      {rows.map(s=><Fragment key={s.id}><div className="ms-entry">
        <Link to={submissionResultUrl(s.id,from)} state={{problemNumber:s.problem_number}} className="ms-row ms-row-item"><span>#{s.id}</span><span className="ms-title">{s.problem_number}. {s.problem_title}</span><span>{s.language}</span><span className={`cw-badge ${labelStatus(s.status).tone}`}>{labelStatus(s.status).label}</span><span>{s.execution_time_ms??'—'} ms</span><span>{s.score??'—'}</span><span>{formatDate(s.submitted_at)}</span></Link>
        <button type="button" className="cw-btn sm" aria-label={`제출 #${s.id} 코드 ${openSource===s.id?'닫기':'보기'}`} aria-expanded={openSource===s.id} onClick={()=>setOpenSource(openSource===s.id?null:s.id)}>{openSource===s.id?'닫기':'코드 보기'}</button>
      </div>{openSource===s.id&&<SubmissionSource id={s.id}/>}</Fragment>)}</>}
    </section>
    {!loading&&!error&&totalPages>1&&<nav className="cw-pager ms-foot" aria-label="제출 페이지"><button className="cw-btn" disabled={!page} onClick={()=>changePage(page-1)}>이전</button><span>{page+1} / {totalPages}</span><button className="cw-btn" disabled={page>=totalPages-1} onClick={()=>changePage(page+1)}>다음</button></nav>}
  </div></main>;
}
export default function MySubmissions(props){const {epoch}=useAuth();return <SubmissionListPage key={epoch} {...props}/>;}
