import { useEffect, useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { api, isStale } from "../lib/api";
import { publicProfileApi } from "../lib/profileRoutes";
import Icon from "../components/Icon";
import "./common.css";
import "./Profile.css";

function safeGithub(value) { try { const url = new URL(value); return url.protocol === "https:" && url.hostname === "github.com" ? url.href : null; } catch { return null; } }
export default function Profile({ dark, isMe = false }) {
  const { nickname } = useParams(), [params] = useSearchParams(), { user, epoch } = useAuth();
  const username = nickname ?? params.get("username") ?? "";
  const [profile, setProfile] = useState(null), [error, setError] = useState(""), [shown, setShown] = useState(20);
  useEffect(() => {
    const control = new AbortController(); setProfile(null); setError(""); setShown(20);
    api(isMe ? "/api/me" : publicProfileApi(username), "GET", null, { signal: control.signal })
      .then(data => { if (!control.signal.aborted) setProfile(data); })
      .catch(failure => { if (!control.signal.aborted && !isStale(failure)) setError(failure.message); });
    return () => control.abort();
  }, [isMe, username, user?.username, epoch]);
  const rank = profile ? profile.rank_type + " " + profile.rank_int : "";
  const github = safeGithub(profile?.github_url);
  const solved = profile?.solvedProblems || [];
  return <main className={"cw-page pf-page " + (dark ? "dark" : "light")}><div className="cw-wrap">
    {error && <p className="pr-banner" role="alert">{error}</p>}
    {!profile && !error && <p role="status">프로필을 불러오고 있습니다…</p>}
    {profile && <>
      <header className="cw-card pf-hero"><span className="pf-avatar" aria-hidden="true">{profile.username.slice(0, 1).toUpperCase()}</span>
        <div className="pf-info"><p className="cw-eyebrow">{isMe ? "MY PROFILE" : "PROFILE"}</p><h1 className="cw-title">{profile.username}</h1>
          <p className="pf-bio">{profile.bio || "작성된 소개가 없습니다."}</p><div className="pf-links"><span className="cw-badge blue">{rank}</span>
            {github && <a href={github} target="_blank" rel="noopener noreferrer"><Icon name="code" /> GitHub</a>}</div></div>
        {isMe && <Link to="/me/edit" className="cw-btn"><Icon name="save" /> 프로필 수정</Link>}
      </header>
      <section className="pf-stats" aria-label="활동 요약">{[
        ["랭크", rank, "서버 기준"], ["해결 수", profile.solved_count, "문제"], ["제출 수", profile.submit_count, "회"],
        ["해결 / 제출", profile.submit_count ? (100 * profile.solved_count / profile.submit_count).toFixed(1) + "%" : "0%", "일반 문제 통계"],
      ].map(([label, value, unit]) => <div key={label} className="cw-card pf-stat"><span>{label}</span><strong>{value}</strong><small>{unit}</small></div>)}</section>
      <section className="cw-card pf-solved"><div className="pf-solved-head"><h2 className="cw-card-title">공개 해결 문제 <small>최근 최대 100개</small></h2></div>
        <div className="pf-table"><div className="pf-row pf-row-head"><span>번호</span><span>문제</span><span></span><span></span></div>
          {!solved.length && <p className="pf-empty">공개된 해결 문제가 없습니다.</p>}
          {solved.slice(0, shown).map(problem => <Link className="pf-row pf-row-item" to={"/problems/" + problem.problem_number} key={problem.problem_number}><span className="cw-mono">{problem.problem_number}</span><span className="pf-title">{problem.problem_title}</span><span className="cw-badge green">해결</span><span></span></Link>)}
        </div>{solved.length > shown && <div className="pf-more"><button className="cw-btn" onClick={() => setShown(value => value + 20)}>더 보기</button></div>}
      </section>
    </>}
  </div></main>;
}
