import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { api, isStale } from "../lib/api";
import { formatServerDate } from "../lib/date";
import Icon from "../components/Icon";
import "./common.css";
import "./Dashboard.css";

export default function Dashboard({ dark }) {
  const { user, epoch } = useAuth();
  const [data, setData] = useState(null), [error, setError] = useState("");
  useEffect(() => {
    const control = new AbortController(); setData(null); setError("");
    Promise.all([api("/api/me", "GET", null, { signal: control.signal }), api("/api/submissions", "GET", null, { signal: control.signal }), api("/api/groups/mine", "GET", null, { signal: control.signal })])
      .then(([profile, submissions, groups]) => { if (!control.signal.aborted) setData({ profile, submissions, groups }); })
      .catch(failure => { if (!control.signal.aborted && !isStale(failure)) setError(failure.message); });
    return () => control.abort();
  }, [user?.username, epoch]);
  const profile = data?.profile;
  // 기본 그룹은 표시하되 일반 그룹 3개 제한에는 포함하지 않습니다. 누락된 flag는 일반 그룹입니다.
  const systemGroupCount = data?.groups.filter(group => group.systemGroup === true).length || 0;
  const ordinaryGroupCount = data ? data.groups.length - systemGroupCount : 0;
  const groupQuota = `일반 ${ordinaryGroupCount} / 3개 · 기본 ${systemGroupCount}개`;
  const stats = profile ? [["checkcircle", "해결한 문제", profile.solved_count, "문제"], ["file", "일반 제출", profile.submit_count, "회"], ["gauge", "해결 / 제출", profile.submit_count ? (100 * profile.solved_count / profile.submit_count).toFixed(1) : "0.0", "%"], ["users", "가입 그룹", ordinaryGroupCount, "/ 3개"]] : [];
  return <main className={"cw-page db-page " + (dark ? "dark" : "light")}><div className="cw-wrap">
    <header className="cw-head"><div><p className="cw-eyebrow">DASHBOARD</p><h1 className="cw-title">{user?.username}님의 대시보드</h1><p className="cw-desc">실제 일반 제출·해결 기록과 가입 그룹을 확인하세요. 그룹 시험 기록은 별도로 관리됩니다.</p></div><Link to="/problems" className="cw-btn primary">문제 풀기</Link></header>
    {error && <p role="alert">{error}</p>}{!data && !error && <p role="status">내 기록을 불러오고 있습니다…</p>}
    {data && <>
      <section className="db-stats" aria-label="요약">{stats.map(([icon, label, value, unit]) => <div className="cw-card db-stat" key={label}><span className="db-stat-icon"><Icon name={icon} /></span><div><p>{label}</p><strong className={icon === "users" ? "db-group-quota" : undefined} aria-label={icon === "users" ? groupQuota : undefined}>{icon === "users" && <small>일반</small>}{value}<small>{unit}</small>{icon === "users" && <small>· 기본 {systemGroupCount}개</small>}</strong></div></div>)}</section>
      <div className="db-two"><section className="cw-card"><h2 className="cw-card-title">가입한 그룹</h2><ul className="db-contests db-group-list">{data.groups.map(group => <li key={group.id}><Link to={"/groups/" + group.id}><strong>{group.groupName}</strong><small>{group.roleName || ""}{group.systemGroup === true && <span className="db-system-group-label">기본 그룹 · 가입 제한 제외</span>}</small></Link></li>)}</ul>{!data.groups.length && <p className="db-empty">가입한 그룹이 없습니다. <Link to="/groups">그룹 찾아보기</Link></p>}</section>
        <section className="cw-card"><h2 className="cw-card-title">공개 해결 문제</h2><ul className="db-contests">{(profile.solvedProblems || []).slice(0, 5).map(problem => <li key={problem.problem_number}><Link to={"/problems/" + problem.problem_number}><strong>{problem.problem_number} · {problem.problem_title}</strong></Link></li>)}</ul>{!profile.solvedProblems?.length && <p className="db-empty">공개 해결 기록이 없습니다.</p>}<Link className="cw-text-link db-card-link" to="/me">프로필에서 더 보기<span aria-hidden="true">→</span></Link></section></div>
      <section className="cw-card db-recent"><div className="db-sec-head"><h2 className="cw-card-title">최근 일반 제출 <small>최대 50개 중 최근 5개</small></h2><Link to="/submissions">전체 보기</Link></div><ul>{data.submissions.slice(0, 5).map(submission => <li key={submission.id}><Link to={"/submissions/" + submission.id}><span className="cw-mono muted">#{submission.id}</span><span>{submission.problem_number} · {submission.problem_title}</span><span className={"cw-badge " + (submission.status === "ACCEPTED" ? "green" : "gray")}>{submission.status}</span><span className="cw-mono muted">{formatServerDate(submission.submitted_at)}</span></Link></li>)}</ul>{!data.submissions.length && <p className="db-empty">아직 제출한 기록이 없습니다.</p>}</section>
    </>}
  </div></main>;
}
