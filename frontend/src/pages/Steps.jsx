import {Link,useSearchParams} from 'react-router-dom';
import {RANKS} from '../lib/problemModel';
import {RANK_NAMES,rankProblemsUrl,problemUrl} from '../lib/problemNavigation';
import useApiData from '../lib/useApiData';
import Icon from '../components/Icon';
import './common.css';
import './Steps.css';

const chapters = {
  4: ['최단 경로와 그래프','고급 자료구조','문자열 알고리즘','동적 계획법 심화','수학과 기하'],
  5: ['그래프와 트리','네트워크 유량과 매칭','고급 문자열','구간 자료구조와 오프라인 쿼리','수학과 DP 최적화'],
  6: ['동적 그래프와 트리','매칭과 절단','다항식과 합성곱','복합 최적화'],
};

export default function Steps({dark}){
  const [params,setParams]=useSearchParams();
  const requested=params.get('chapter')||'';
  const selected=/^[4-6]-[1-5]$/.test(requested)?requested:'';
  const [level,chapter]=selected.split('-').map(Number);
  const name=chapters[level]?.[chapter-1];
  const {data,error,loading,reload}=useApiData(name?`/api/problems/chapter?level=${level}&chapter=${chapter}`:null);
  return <main className={'cw-page st-page '+(dark?'dark':'light')}><div className="cw-wrap st-wrap">
    <header className="cw-head"><div><p className="cw-eyebrow">DIFFICULTY</p><h1 className="cw-title">난이도별 문제</h1><p className="cw-desc">처음이라면 입문부터 시작해 보세요. 같은 등급에서는 5 → 1 순서로 난도가 높아집니다.</p></div><Link className="cw-btn" to="/problems">전체 문제</Link></header>
    <ol className="st-list">{RANKS.map((rank,index)=><li className="cw-card st-step" key={rank}>
      <Link className="st-head" to={rankProblemsUrl(rank)}><span className="st-no">Lv.{index}</span><span className="st-info"><strong>{RANK_NAMES[rank]}</strong><small>{rank}</small></span><span className="st-progress">이 난이도 문제 보기</span><Icon name="chevronR"/></Link>
      {chapters[index] && <nav className="st-chapters" aria-label={`Lv.${index} 챕터`}>{chapters[index].map((title,i)=>{
        const key=`${index}-${i+1}`;
        return <button key={key} type="button" className="cw-btn sm" aria-expanded={selected===key}
          onClick={()=>setParams(selected===key?{}:{chapter:key})}>{key} · {title}</button>;
      })}</nav>}
      {name && index===level && <section className="st-chapter" aria-label={name}>
        {error ? <p role="alert">{error} <button className="cw-btn sm" onClick={reload}>다시 시도</button></p> :
          loading ? <p role="status">문제를 불러오는 중입니다…</p> : <>
            <h2>{name} <small>{data?.length??0}문제</small></h2>
            {data?.length ? <ul className="st-problems">{data.map(p=><li key={p.problem_number}>
              <Link to={problemUrl(p.problem_number, '/steps?chapter=' + selected)}>
                <span className="cw-mono">{p.problem_number}</span><span>{p.problem_title}</span><span>{p.reward_points}점</span>
              </Link></li>)}</ul> : <p>현재 공개된 문제가 없습니다.</p>}
          </>}
      </section>}
    </li>)}</ol>
  </div></main>;
}
