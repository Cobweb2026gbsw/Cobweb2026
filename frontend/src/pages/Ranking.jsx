import { useMemo } from "react";
import { Link, useLocation, useSearchParams } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import useApiData from "../lib/useApiData";
import { profileHref } from "../lib/profileRoutes";
import "./common.css";
import "./Ranking.css";

export default function Ranking({ dark }) {
  const {user} = useAuth();
  const {search} = useLocation();
  const [params, setParams] = useSearchParams();
  const profileOrigin = {rankingReturn: '/ranking' + search};
  const requested = Number(params.get('page'));
  const page = Number.isSafeInteger(requested) ? Math.min(10000, Math.max(0, requested)) : 0;
  const query = params.get('q') || '';
  const {data: rows, loading, error, reload} = useApiData('/api/ranking?page=' + page);
  function update(key, value, replace = false) {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    setParams(next, {replace});
  }
  const shown = useMemo(() => (rows || []).map((item, index) => ({ ...item, position: page * 30 + index + 1 })).filter(item => item.username.toLowerCase().includes(query.toLowerCase())), [rows, page, query]);
  return <main className={"cw-page rk-page " + (dark ? "dark" : "light")}><div className="cw-wrap">
    <header className="cw-head"><div><p className="cw-eyebrow">RANKING</p><h1 className="cw-title">랭킹</h1><p className="cw-desc">누적 점수가 높은 순입니다. 점수가 같으면 해결한 문제가 많은 사용자, 제출 횟수가 적은 사용자가 앞섭니다.</p></div></header>
    {page === 0 && rows?.length > 0 && <section className="rk-top3" aria-label="상위 3명">{rows.slice(0, 3).map((item, index) => <Link className={"cw-card rk-top rk-top-" + (index + 1)} to={profileHref(item.username)} state={profileOrigin} key={item.username}><span className="rk-top-rank">{index + 1}</span><div><strong>{item.username}</strong><span className="cw-badge mint">{item.rank_type} {item.rank_int}</span></div><p>{item.rank_points?.toLocaleString("ko-KR")}점</p></Link>)}</section>}
    <div className="cw-card rk-filter"><div className="rk-search"><label className="cw-label" htmlFor="rank-query">현재 페이지에서 아이디 찾기</label><input id="rank-query" className="cw-input" value={query} onChange={event => update('q', event.target.value, true)} /></div></div>
    {error && <p role="alert">{error} <button className="cw-btn sm" onClick={reload}>다시 시도</button></p>}{loading && <p role="status">랭킹을 불러오고 있습니다…</p>}
    <section className="cw-card rk-table" aria-label="랭킹 목록"><div className="rk-row rk-row-head"><span>순위</span><span>사용자</span><span>랭크</span><span className="right">누적 점수</span><span className="right">해결</span><span className="right">제출</span><span className="right">해결 / 제출</span></div>
      {shown.map(item => <Link className={"rk-row rk-row-item " + (item.username === user?.username ? "me" : "")} to={profileHref(item.username)} state={profileOrigin} key={item.username}><span>{item.position}</span><span className="rk-name">{item.username}{item.username === user?.username && <em>나</em>}</span><span className="cw-badge blue">{item.rank_type} {item.rank_int}</span><span className="right rk-points">{item.rank_points?.toLocaleString("ko-KR")}</span><span className="right">{item.solved_count}</span><span className="right">{item.submit_count}</span><span className="right">{item.submit_count ? (100 * item.solved_count / item.submit_count).toFixed(1) : "0.0"}%</span></Link>)}
      {rows && !shown.length && <p className="rk-empty">표시할 사용자가 없습니다.</p>}
    </section><nav className="cw-pager rk-pager" aria-label="페이지 이동"><button className="cw-btn" disabled={page === 0} onClick={() => update('page', page - 1)}>이전</button><span>{page + 1} 페이지</span><button className="cw-btn" disabled={!rows || rows.length < 30 || page >= 10000} onClick={() => update('page', page + 1)}>다음</button></nav>
  </div></main>;
}
