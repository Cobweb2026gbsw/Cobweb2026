import {useEffect,useMemo,useState} from 'react';
import {Link,useLocation,useNavigate,useSearchParams} from 'react-router-dom';
import {useAuth} from '../context/AuthContext';
import useApiData from '../lib/useApiData';
import {rankLabel,RANKS} from '../lib/problemModel';
import Icon from '../components/Icon';
import './common.css';
import './ProblemList.css';

const DIFFICULTIES=RANKS.flatMap(rank=>[1,2,3,4,5].map(level=>`${rank}:${level}`));

export default function ProblemList({dark,mine=false}){
  const {user,epoch}=useAuth(),navigate=useNavigate(),location=useLocation();const [params,setParams]=useSearchParams();
  const search=params.get('q')||'',page=Math.max(0,Number(params.get('page'))||0),difficulties=params.getAll('difficulty'),solved=params.get('solved')||'all';
  const [draft,setDraft]=useState(search),[sort,setSort]=useState('number');
  useEffect(()=>setDraft(search),[search,epoch]);
  // 난이도·풀이 여부는 서버가 페이지를 나누기 전에 검색합니다. URL의 반복
  // difficulty 값을 보존해 새로고침·뒤로 가기·다음 페이지도 같은 범위를 사용합니다.
  const query=new URLSearchParams({page:String(page),search,solved});
  for(const difficulty of difficulties)query.append('difficulty',difficulty);
  const {data,error,loading}=useApiData(`${mine?'/api/problems/mine':'/api/problems'}?${query}`);
  // 개인 해결 표시는 서버의 solved===true만 사용합니다. accepted_count는 전체 통계입니다.
  const rows=useMemo(()=>[...(data||[])].sort((a,b)=>sort==='submits'?Number(b.submission_count)-Number(a.submission_count):Number(a.problem_number)-Number(b.problem_number)),[data,sort]);
  const changePage=value=>{const next=new URLSearchParams(params);next.set('page',String(value));setParams(next);};
  function updateFilters(change){const next=new URLSearchParams(params);change(next);next.set('page','0');setParams(next);}
  function toggleDifficulty(value){
    const selected=new Set(difficulties);if(selected.has(value))selected.delete(value);else selected.add(value);
    updateFilters(next=>{next.delete('difficulty');for(const difficulty of DIFFICULTIES)if(selected.has(difficulty))next.append('difficulty',difficulty);});
  }
  function submit(e){e.preventDefault();if(/^\d+$/.test(draft.trim()))navigate(`/problems/${Number(draft.trim())}`);else updateFilters(next=>{if(draft.trim())next.set('q',draft.trim());else next.delete('q');});}
  return <main className={`cw-page pl-page ${dark?'dark':'light'}`}><div className="cw-wrap"><header className="cw-head"><div><p className="cw-eyebrow">PROBLEMS / LIVE</p><h1 className="cw-title">{mine?'내가 만든 문제':'문제 목록'}</h1><p className="cw-desc">서버에 등록된 {mine?'초안과 검수 결과':'공개 문제'}입니다. 숫자 검색은 해당 문제로 이동합니다.</p></div><div className="cw-head-actions">{user&&<><Link className="cw-btn" to={mine?'/problems':'/problems/mine'}>{mine?'공개 문제':'내 제작 문제'}</Link><Link className="cw-btn primary" to="/problems/new"><Icon name="plus"/> 문제 만들기</Link></>}</div></header>
    <form className="cw-card pl-filter" onSubmit={submit}><div className="pl-filter-row">
      <label>문제 검색<input className="cw-input" value={draft} onChange={e=>setDraft(e.target.value)} placeholder="제목 또는 바로 이동할 문제 번호"/></label>
      <details className="pl-difficulty"><summary className="cw-select">{difficulties.length?`난이도 ${difficulties.length}개 선택`:'난이도 전체'}</summary>
        <fieldset className="pl-difficulty-options"><legend>난이도 다중 선택</legend><p>선택하지 않으면 모든 난이도를 검색합니다.</p>
          <div className="pl-rank-grid">{RANKS.map(rank=><div className="pl-rank-row" key={rank}><strong className="pl-rank-label">{rank}</strong>{[1,2,3,4,5].map(level=><label key={level}><input type="checkbox" aria-label={`${rank} ${level}`} checked={difficulties.includes(`${rank}:${level}`)} onChange={()=>toggleDifficulty(`${rank}:${level}`)}/><span>{level}</span></label>)}</div>)}</div>
          <button type="button" className="cw-btn sm" disabled={!difficulties.length} onClick={()=>updateFilters(next=>next.delete('difficulty'))}>난이도 선택 해제</button>
        </fieldset>
      </details>
      <label>풀이 여부<select className="cw-select" value={solved} disabled={!user} onChange={e=>updateFilters(next=>{if(e.target.value==='all')next.delete('solved');else next.set('solved',e.target.value);})}><option value="all">전체</option><option value="solved">이미 푼 문제</option><option value="unsolved">아직 안 푼 문제</option></select></label>
      <label>현재 페이지 정렬<select className="cw-select" value={sort} onChange={e=>setSort(e.target.value)}><option value="number">문제 번호</option><option value="submits">제출 많은 순</option></select></label>
      <button className="cw-btn primary">검색</button><button type="button" className="cw-btn" onClick={()=>{setDraft('');setSort('number');setParams({});}}>초기화</button>
    </div>{!user&&<p className="cw-hint pl-login-hint"><Link to="/login" state={{from:location.pathname+location.search}}>로그인</Link>하면 이미 푼 문제·아직 안 푼 문제로 검색할 수 있습니다.</p>}</form>
    {error&&<p className="cw-status-message error" role="alert">{error}</p>}<section className="cw-card pl-result"><div className="pl-result-head"><h2>{loading?'불러오는 중…':`현재 페이지 ${rows.length}개`}</h2><span className="cw-hint">서버 페이지당 최대 30개</span></div><div className="pl-table"><div className="pl-row pl-row-head"><span>번호</span><span>문제</span><span>난이도</span><span>정답률</span><span>제출 수</span><span>{mine?'상태':'공개'}</span></div>
    {!loading&&!rows.length&&<p className="pl-empty">등록된 문제가 없습니다.</p>}{rows.map(p=><Link className="pl-row pl-row-item" key={p.problem_number} to={mine?`/problems/${p.problem_number}/edit`:`/problems/${p.problem_number}`}><span className="cw-mono">{p.problem_number}</span><span className="pl-name"><span className="pl-title" title={p.problem_title}>{p.problem_title}</span>{user&&p.solved===true&&<span className="pl-solved"><Icon name="check" size={12}/><span>해결 완료</span></span>}</span><span className="cw-badge mint">{rankLabel(p)}</span><span>{Number(p.submission_count)?(100*Number(p.accepted_count)/Number(p.submission_count)).toFixed(1)+'%':'—'}</span><span>{p.submission_count??0}</span><span className="cw-hint">{mine?`${p.status} · ${p.visibility}`:'PUBLIC'}</span></Link>)}</div></section>
    <nav className="cw-pager pl-pager" aria-label="문제 페이지"><button className="cw-btn" disabled={page===0||loading} onClick={()=>changePage(page-1)}>이전</button><span>{page+1}</span><button className="cw-btn" disabled={loading||(data||[]).length<30} onClick={()=>changePage(page+1)}>다음</button></nav>
  </div></main>;
}
