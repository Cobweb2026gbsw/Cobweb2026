import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { api, isStale } from "../lib/api";
import Icon from "../components/Icon";
import "./common.css";
import "./Steps.css";

const RANKS = ["INITIATE", "SKILLED", "ELITE", "EXPERT", "LEGEND", "MYTHIC", "ABSOLUTE"];
export default function Steps({ dark }) {
  const { user, epoch } = useAuth();
  const [rows, setRows] = useState(null), [solved, setSolved] = useState([]), [page, setPage] = useState(0), [open, setOpen] = useState(null), [error, setError] = useState("");
  useEffect(() => {
    const control = new AbortController(); setRows(null); setSolved([]); setError("");
    Promise.all([api("/api/problems?page=" + page, "GET", null, { signal: control.signal }), user ? api("/api/me", "GET", null, { signal: control.signal }) : Promise.resolve(null)])
      .then(([problems, profile]) => { if (!control.signal.aborted) { setRows(problems); setSolved((profile?.solvedProblems || []).map(problem => problem.problem_number)); } })
      .catch(failure => { if (!control.signal.aborted && !isStale(failure)) setError(failure.message); });
    return () => control.abort();
  }, [page, user?.username, epoch]);
  const sections = useMemo(() => RANKS.map(rank => ({ rank, problems: (rows || []).filter(problem => problem.rank_type === rank) })).filter(section => section.problems.length), [rows]);
  return <main className={"cw-page st-page " + (dark ? "dark" : "light")}><div className="cw-wrap st-wrap">
    <header className="cw-head"><div><p className="cw-eyebrow">STEP BY STEP</p><h1 className="cw-title">난이도별로 문제 풀기</h1><p className="cw-desc">현재 공개 문제 페이지를 서버 난이도별로 묶었습니다. 별도의 교육 과정·단계 API는 없으므로 가상의 진도나 문제를 표시하지 않습니다. 해결 표시는 최근 공개 해결 기록 최대 100개 기준입니다.</p></div></header>
    {error && <p role="alert">{error}</p>}{rows === null && !error && <p role="status">공개 문제를 불러오고 있습니다…</p>}
    <ol className="st-list">{sections.map((section, index) => <li key={section.rank} className="cw-card st-step"><button type="button" className="st-head" aria-expanded={open === section.rank} aria-controls={"step-" + section.rank} onClick={() => setOpen(open === section.rank ? null : section.rank)}><span className="st-no">{index + 1}</span><span className="st-info"><strong>{section.rank}</strong><small>{section.problems.length}개 공개 문제</small></span><span className="st-progress">등록 난이도 기준</span><Icon name="chevronR" /></button>
      {open === section.rank && <ul id={"step-" + section.rank} className="st-problems">{section.problems.map(problem => <li key={problem.problem_number}><Link to={"/problems/" + problem.problem_number}><span className="cw-mono">{problem.problem_number}</span><span>{problem.problem_title}</span><span className={"cw-badge " + (solved.includes(problem.problem_number) ? "green" : "gray")}>{solved.includes(problem.problem_number) ? "해결" : problem.rank_type + " " + problem.rank_int}</span></Link></li>)}</ul>}
    </li>)}</ol>{rows && !rows.length && <p>공개된 문제가 없습니다.</p>}
    <nav className="cw-pager" aria-label="문제 페이지"><button className="cw-btn" disabled={page === 0 || rows === null} onClick={() => setPage(value => value - 1)}>이전</button><span>{page + 1} 페이지</span><button className="cw-btn" disabled={!rows || rows.length < 30 || page >= 10000} onClick={() => setPage(value => value + 1)}>다음</button></nav>
  </div></main>;
}
