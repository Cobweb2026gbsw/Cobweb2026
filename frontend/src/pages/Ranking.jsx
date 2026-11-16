import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { api, isStale } from "../lib/api";
import { profileHref } from "../lib/profileRoutes";
import "./common.css";
import "./Ranking.css";

export default function Ranking({ dark }) {
  const { user, epoch } = useAuth();
  const [rows, setRows] = useState(null), [page, setPage] = useState(0), [query, setQuery] = useState(""), [error, setError] = useState("");
  useEffect(() => {
    const control = new AbortController(); setRows(null); setError("");
    api("/api/ranking?page=" + page, "GET", null, { signal: control.signal }).then(data => { if (!control.signal.aborted) setRows(data); })
      .catch(failure => { if (!control.signal.aborted && !isStale(failure)) setError(failure.message); });
    return () => control.abort();
  }, [page, epoch]);
  const shown = useMemo(() => (rows || []).map((item, index) => ({ ...item, position: page * 30 + index + 1 })).filter(item => item.username.toLowerCase().includes(query.toLowerCase())), [rows, page, query]);
  return <main className={"cw-page rk-page " + (dark ? "dark" : "light")}><div className="cw-wrap">
    <header className="cw-head"><div><p className="cw-eyebrow">RANKING</p><h1 className="cw-title">랭킹</h1><p className="cw-desc">해결 수 내림차순, 같은 해결 수에서는 제출 수 오름차순입니다. 서버가 계산한 순서를 표시합니다.</p></div></header>
    {page === 0 && rows?.length > 0 && <section className="rk-top3" aria-label="상위 3명">{rows.slice(0, 3).map((item, index) => <Link className={"cw-card rk-top rk-top-" + (index + 1)} to={profileHref(item.username)} key={item.username}><span className="rk-top-rank">{index + 1}</span><div><strong>{item.username}</strong><span className="cw-badge mint">{item.rank_type} {item.rank_int}</span></div><p>{item.solved_count} 해결</p></Link>)}</section>}
    <div className="cw-card rk-filter"><div className="rk-search"><label className="cw-label" htmlFor="rank-query">현재 페이지에서 아이디 찾기</label><input id="rank-query" className="cw-input" value={query} onChange={event => setQuery(event.target.value)} /></div></div>
    {error && <p role="alert">{error}</p>}{rows === null && !error && <p role="status">랭킹을 불러오고 있습니다…</p>}
    <section className="cw-card rk-table" aria-label="랭킹 목록"><div className="rk-row rk-row-head"><span>순위</span><span>사용자</span><span>랭크</span><span className="right">해결</span><span className="right">제출</span><span className="right">해결 / 제출</span></div>
      {shown.map(item => <Link className={"rk-row rk-row-item " + (item.username === user?.username ? "me" : "")} to={profileHref(item.username)} key={item.username}><span>{item.position}</span><span className="rk-name">{item.username}{item.username === user?.username && <em>나</em>}</span><span className="cw-badge blue">{item.rank_type} {item.rank_int}</span><span className="right">{item.solved_count}</span><span className="right">{item.submit_count}</span><span className="right">{item.submit_count ? (100 * item.solved_count / item.submit_count).toFixed(1) : "0.0"}%</span></Link>)}
      {rows && !shown.length && <p className="rk-empty">표시할 사용자가 없습니다.</p>}
    </section><nav className="cw-pager rk-pager" aria-label="페이지 이동"><button className="cw-btn" disabled={page === 0 || rows === null} onClick={() => setPage(value => value - 1)}>이전</button><span>{page + 1} 페이지</span><button className="cw-btn" disabled={!rows || rows.length < 30 || page >= 10000} onClick={() => setPage(value => value + 1)}>다음</button></nav>
  </div></main>;
}
