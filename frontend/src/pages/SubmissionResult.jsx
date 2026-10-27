import {useEffect,useState} from 'react';
import {Link,useLocation,useParams} from 'react-router-dom';
import {useAuth} from '../context/AuthContext';
import {api} from '../lib/api';
import {STATUS,labelStatus,formatDate} from '../lib/problemModel';
import './common.css';
import './SubmissionResult.css';

function ResultPage({dark}){
  const {id}=useParams(),{epoch}=useAuth(),location=useLocation();const [sub,setSub]=useState(null),[error,setError]=useState('');
  useEffect(()=>{let active=true,timer;const controller=new AbortController();setSub(null);setError('');async function poll(){try{const data=await api(`/api/submissions/${id}`,'GET',null,{signal:controller.signal});if(!active)return;setSub(data);if(['QUEUED','RUNNING'].includes(data.status))timer=setTimeout(poll,1500);}catch(e){if(active&&!e.stale&&e.name!=='AbortError')setError(e.message);}}poll();return()=>{active=false;clearTimeout(timer);controller.abort();};},[id,epoch]);
  const verdict=labelStatus(sub?.status),cases=sub?.cases||[],passed=cases.filter(c=>c.status==='ACCEPTED').length,problem=location.state?.problemNumber;
  return <main className={`cw-page sr-page ${dark?'dark':'light'}`}><div className="cw-wrap"><header className="cw-head"><div><p className="cw-eyebrow">SUBMISSION / #{id}</p><h1 className="cw-title">제출 결과</h1><p className="cw-desc">권한 검사를 거친 실제 판정입니다.</p></div><div className="cw-head-actions"><Link className="cw-btn" to="/submissions">내 제출</Link>{problem&&<Link className="cw-btn primary" to={`/problems/${problem}`}>문제로 돌아가기</Link>}</div></header>
    {error&&<p className="cw-status-message error" role="alert">{error}</p>}{!sub&&!error&&<p className="cw-load">판정을 불러오는 중…</p>}{sub&&<><div className="sr-top"><section className={`cw-card sr-verdict ${verdict.tone}`}><span className={`cw-badge ${verdict.tone}`}>{sub.status}</span><h2>{verdict.label}</h2><p>{['QUEUED','RUNNING'].includes(sub.status)?'채점 워커가 처리 중입니다. 자동으로 갱신합니다.':`${cases.length}개 케이스 중 ${passed}개 통과`}</p><div className="sr-score"><span>점수</span><strong>{sub.score??'—'}</strong></div></section><section className="cw-card sr-progress"><h2 className="cw-card-title">제출 정보</h2><dl className="sr-metrics">{[['언어',sub.language],['실행시간',sub.execution_time_ms==null?'—':`${sub.execution_time_ms} ms`],['제출시각',formatDate(sub.submitted_at)],['판정시각',formatDate(sub.judged_at)]].map(([label,value])=><div key={label}><div><small>{label}</small><strong>{value}</strong></div></div>)}</dl><p className="cw-hint">실제 서버에서 제공하지 않는 메모리 사용량·백분위·컴파일 로그는 추정하여 표시하지 않습니다.</p></section></div>
    <section className="cw-card sr-legend"><h2 className="cw-card-title">채점 상태 안내</h2><div className="sr-legend-list">{Object.entries(STATUS).map(([key,s])=><span className={`cw-badge ${s.tone}`} key={key}>{s.label}</span>)}</div></section><section className="cw-card sr-tests" style={{marginTop:20}}><h2 className="cw-card-title">테스트케이스 결과</h2><p className="cw-hint">비공개 입력과 정답은 공개하지 않습니다.</p><div className="sr-test-table"><div className="sr-test-row sr-test-head"><span>케이스</span><span>결과</span><span>실행시간</span></div>{cases.map((c,i)=><div className="sr-test-row" key={i}><span>#{i+1}</span><span className={`cw-badge ${labelStatus(c.status).tone}`}>{labelStatus(c.status).label}</span><span>{c.execution_time_ms} ms</span></div>)}</div></section>
    {sub.sourceCode!=null&&<section className="cw-card sr-code" style={{marginTop:20}}><h2 className="cw-card-title">다시보기 허용된 시험 답안</h2><pre className="cw-code-preview">{sub.sourceCode}</pre></section>}{sub.sourceCode==null&&<p className="cw-hint">일반 제출 소스 코드는 현재 서버 API에서 반환하지 않습니다.</p>}</>}
  </div></main>;
}
export default function SubmissionResult(props){const {id}=useParams(),{epoch}=useAuth();return <ResultPage key={`${id}:${epoch}`} {...props}/>;}
