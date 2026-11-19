import {useEffect,useMemo,useRef,useState} from 'react';
import {Link,useLocation,useNavigate,useSearchParams} from 'react-router-dom';
import {useAuth} from '../context/AuthContext';
import useApiData from '../lib/useApiData';
import {rankLabel,RANKS} from '../lib/problemModel';
import {LEVELS,RANK_NAMES,validProblemNumber,problemUrl,rankProblemsUrl} from '../lib/problemNavigation';
import {requestedProblemPage,problemPageData,problemPageButtons} from '../lib/problemPagination';
import Icon from '../components/Icon';
import './common.css';
import './ProblemList.css';

const DIFFICULTIES=RANKS.flatMap(rank=>LEVELS.map(level=>`${rank}:${level}`));

export default function ProblemList({dark,mine=false}){
  const {user,epoch}=useAuth(),navigate=useNavigate(),location=useLocation();
  const [params,setParams]=useSearchParams(),input=useRef(null);
  const search=params.get('q')||'',page=requestedProblemPage(params.get('page'));
  const difficulties=[...new Set(params.getAll('difficulty'))],solved=params.get('solved')||'all';
  const sort=params.get('sort')==='submits'?'submits':'number';
  const [draft,setDraft]=useState(search),[filtersOpen,setFiltersOpen]=useState(false);
  useEffect(()=>setDraft(search),[search,epoch]);
  useEffect(()=>{if(params.get('focus')==='search')input.current?.focus();},[params]);
  const query=new URLSearchParams({page:String(page),search,solved,sort,pagination:'true'});
  for(const difficulty of difficulties)query.append('difficulty',difficulty);
  const {data,error,loading,reload}=useApiData(`${mine?'/api/problems/mine':'/api/problems'}?${query}`);
  const result=useMemo(()=>problemPageData(data,page),[data,page]);
  const resultError=!loading&&!error&&!result?'문제 목록을 불러오지 못했습니다. 다시 시도해주세요.':'';
  const failure=error||resultError,currentPage=result?.page??page,totalPages=result?.totalPages??0;
  useEffect(()=>{if(result&&!result.legacy&&result.page!==page){const next=new URLSearchParams(params);next.set('page',String(result.page));setParams(next,{replace:true});}},[result,page,params,setParams]);
  const rows=result?.items||[];
  const from=location.pathname+location.search;
  const trail={problemTrail:{from,items:rows.map(row=>row.problem_number)}};
  const filtered=Boolean(search||difficulties.length||solved!=='all');
  const changePage=value=>{const next=new URLSearchParams(params);next.set('page',String(value));setParams(next);};
  function updateFilters(change){const next=new URLSearchParams(params);change(next);next.set('page','0');setParams(next);}
  function setDifficulties(selected){updateFilters(next=>{next.delete('difficulty');for(const difficulty of DIFFICULTIES)if(selected.has(difficulty))next.append('difficulty',difficulty);});}
  function toggleDifficulty(value){const selected=new Set(difficulties);if(selected.has(value))selected.delete(value);else selected.add(value);setDifficulties(selected);}
  function reset(){setDraft('');setParams({});}
  function submit(event){
    event.preventDefault();
    if(validProblemNumber(draft.trim()))navigate(problemUrl(Number(draft.trim()),mine?'/problems':from));
    else updateFilters(next=>{if(draft.trim())next.set('q',draft.trim());else next.delete('q');});
  }
  return <main className={`cw-page pl-page ${dark?'dark':'light'}`}><div className="cw-wrap">
    <header className="cw-head"><div><p className="cw-eyebrow">PROBLEMS</p><h1 className="cw-title">{mine?'내가 만든 문제':'문제 목록'}</h1><p className="cw-desc">{mine?'내가 작성한 문제와 검수 상태를 확인하세요.':'제목이나 주제로 검색하거나 문제 번호로 바로 이동하세요.'}</p></div>
      <div className="cw-head-actions">{user&&<><Link className="cw-btn" to={mine?'/problems':'/problems/mine'}>{mine?'공개 문제':'내 제작 문제'}</Link><Link className="cw-btn" to="/problems/new"><Icon name="plus"/> 문제 만들기</Link></>}</div>
    </header>
    {!mine&&<nav className="pl-start" aria-label="문제 풀이 시작"><span>어디서 시작할지 고민된다면</span><Link className="cw-btn sm" to={rankProblemsUrl('INITIATE')}>입문 문제부터</Link><Link className="cw-btn sm" to={user?'/problems?solved=unsolved':'/login'} state={user?undefined:{from:'/problems?solved=unsolved'}}>{user?'아직 안 푼 문제':'로그인하고 미해결 문제 보기'}</Link><Link className="pl-start-guide" to="/steps">난이도 안내 →</Link></nav>}
    <form className="cw-card pl-filter" onSubmit={submit}>
      <div className="pl-search-row"><label>문제 검색<input ref={input} className="cw-input" value={draft} maxLength={200} onChange={event=>setDraft(event.target.value)} placeholder="제목, 주제 또는 문제 번호"/></label><button className="cw-btn primary">검색</button><button type="button" className="cw-btn pl-filter-toggle" aria-expanded={filtersOpen} aria-controls="problem-filters" onClick={()=>setFiltersOpen(value=>!value)}>필터{difficulties.length+(solved!=='all'?1:0)>0?` (${difficulties.length+(solved!=='all'?1:0)})`:''}</button><button type="button" className="cw-btn" onClick={reset}>초기화</button></div>
      {!mine&&<div className="pl-topics" role="group" aria-label="주제 바로 검색"><span>주제 바로 검색</span>
        {['반복문','배열','문자열','동적 계획법'].map(topic=><button type="button" key={topic} className="cw-btn sm" onClick={()=>updateFilters(next=>next.set('q',topic))}>{topic}</button>)}
      </div>}
      <div id="problem-filters" className={`pl-filter-options${filtersOpen?' is-open':''}`}>
        <div className="pl-filter-row">
          <details className="pl-difficulty"><summary className="cw-select">{difficulties.length?`난이도 ${difficulties.length}개 선택`:'난이도 전체'}</summary>
            <fieldset className="pl-difficulty-options"><legend>난이도 다중 선택</legend><p>입문부터 최고 난도 순서입니다. 같은 등급에서는 5 → 1 순으로 어려워집니다.</p>
              <div className="pl-rank-presets" aria-label="등급 빠른 선택">{RANKS.map(rank=><button type="button" className="cw-btn sm" key={rank} aria-pressed={LEVELS.every(level=>difficulties.includes(`${rank}:${level}`))} onClick={()=>setDifficulties(new Set(LEVELS.map(level=>`${rank}:${level}`)))}>{RANK_NAMES[rank]}</button>)}</div>
              <div className="pl-rank-grid">{RANKS.map(rank=><div className="pl-rank-row" key={rank}><strong className="pl-rank-label">{RANK_NAMES[rank]}<small>{rank}</small></strong>{LEVELS.map(level=><label key={level}><input type="checkbox" aria-label={`${rank} ${level}`} checked={difficulties.includes(`${rank}:${level}`)} onChange={()=>toggleDifficulty(`${rank}:${level}`)}/><span>{level}</span></label>)}</div>)}</div>
              <button type="button" className="cw-btn sm" disabled={!difficulties.length} onClick={()=>updateFilters(next=>next.delete('difficulty'))}>난이도 선택 해제</button>
            </fieldset>
          </details>
          <label>풀이 여부<select className="cw-select" value={solved} disabled={!user} onChange={event=>updateFilters(next=>{if(event.target.value==='all')next.delete('solved');else next.set('solved',event.target.value);})}><option value="all">전체</option><option value="solved">이미 푼 문제</option><option value="unsolved">아직 안 푼 문제</option></select></label>
          <label>정렬 기준<select className="cw-select" value={sort} onChange={event=>updateFilters(next=>{if(event.target.value==='number')next.delete('sort');else next.set('sort',event.target.value);})}><option value="number">문제 번호</option><option value="submits">제출 많은 순</option></select></label>
        </div>
        {!user&&<p className="cw-hint pl-login-hint"><Link to="/login" state={{from}}>로그인</Link>하면 이미 푼 문제·아직 안 푼 문제로 검색할 수 있습니다.</p>}
      </div>
      {filtered&&<div className="pl-active-filters" aria-label="적용된 검색 조건">
        {search&&<button type="button" className="cw-btn sm" aria-label={`검색어 ${search} 해제`} onClick={()=>{setDraft('');updateFilters(next=>next.delete('q'));}}>{search} ×</button>}
        {difficulties.map(value=><button type="button" className="cw-btn sm" key={value} aria-label={`${value.replace(':',' ')} 해제`} onClick={()=>toggleDifficulty(value)}>{RANK_NAMES[value.split(':')[0]]||value.split(':')[0]} {value.split(':')[1]} ×</button>)}
        {solved!=='all'&&<button type="button" className="cw-btn sm" onClick={()=>updateFilters(next=>next.delete('solved'))}>{solved==='solved'?'이미 푼 문제':'아직 안 푼 문제'} ×</button>}
      </div>}
    </form>
    <section className="cw-card pl-result" aria-label="문제 검색 결과" aria-busy={loading}>
      {failure?<div className="pl-state"><h2>문제를 불러오지 못했어요</h2><p className="cw-status-message error" role="alert">{failure}</p><button type="button" className="cw-btn primary" onClick={reload}>다시 시도</button></div>:
        loading?<p className="pl-empty" role="status">문제를 불러오는 중…</p>:<>
          <div className="pl-result-head"><h2>{`현재 페이지 ${rows.length}개`}</h2><span className="cw-hint">{result?.totalItems!=null?`전체 ${result.totalItems}개 · `:''}페이지당 최대 30개</span></div>
          {!rows.length?<div className="pl-state"><p>{filtered?'조건에 맞는 문제가 없습니다.':'등록된 문제가 없습니다.'}</p>{filtered&&<button type="button" className="cw-btn" onClick={reset}>검색 조건 초기화</button>}</div>:
            <div className="pl-table"><div className="pl-row pl-row-head"><span>번호</span><span>문제</span><span>난이도 · 배점</span><span>정답률</span><span>제출 수</span><span>{mine?'상태':'공개'}</span></div>
              {rows.map(problem=><Link className="pl-row pl-row-item" key={problem.problem_number} to={mine?`/problems/${problem.problem_number}/edit`:problemUrl(problem.problem_number,from)} state={mine?undefined:trail}><span className="cw-mono">{problem.problem_number}</span><span className="pl-name"><span className="pl-title" title={problem.problem_title}>{problem.problem_title}</span>{user&&problem.solved===true&&<span className="pl-solved"><Icon name="check" size={12}/><span>해결 완료</span></span>}</span><span className="pl-difficulty-cell"><span className="cw-badge mint" title={`${RANK_NAMES[problem.rank_type]||''} · 같은 등급에서는 5가 가장 쉽습니다`}>{rankLabel(problem)}</span>{problem.reward_points != null && <small>{problem.reward_points}점</small>}</span><span>{Number(problem.submission_count)?(100*Number(problem.accepted_count)/Number(problem.submission_count)).toFixed(1)+'%':'—'}</span><span>{problem.submission_count??0}</span><span className="cw-hint">{mine?`${problem.status} · ${problem.visibility}`:'공개'}</span></Link>)}
            </div>}
        </>}
    </section>
    {(loading||failure||rows.length>0)&&<nav className="cw-pager pl-pager" aria-label="문제 페이지"><button type="button" className="cw-btn" disabled={currentPage===0||loading||Boolean(failure)} onClick={()=>changePage(currentPage-1)}>이전</button><div className="pl-page-numbers">{problemPageButtons(currentPage,totalPages).map(value=><button type="button" key={value} className={`cw-btn pl-page-number${value===currentPage?' is-current':''}`} aria-label={`${value+1}페이지`} aria-current={value===currentPage?'page':undefined} disabled={loading||Boolean(failure)||totalPages===0} onClick={()=>{if(value!==currentPage)changePage(value);}}>{value+1}</button>)}</div><button type="button" className="cw-btn" disabled={loading||Boolean(failure)||currentPage>=totalPages-1} onClick={()=>changePage(currentPage+1)}>다음</button></nav>}
  </div></main>;
}
