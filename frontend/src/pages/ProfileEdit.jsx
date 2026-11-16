import { useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { api, getSessionEpoch, isStale } from "../lib/api";
import Icon from "../components/Icon";
import "./common.css";
import "./ProfileEdit.css";

const BIO_MAX = 300;
const GITHUB_PATTERN = /^https:\/\/github\.com\/[A-Za-z0-9_-]+\/?$/;
export default function ProfileEdit({ dark }) {
  const { user, epoch, refreshUser } = useAuth(), navigate = useNavigate();
  const [initial, setInitial] = useState(null), [bio, setBio] = useState(""), [github, setGithub] = useState("");
  const [saving, setSaving] = useState(false), [error, setError] = useState("");
  useEffect(() => {
    const control = new AbortController(); setInitial(null); setBio(""); setGithub(""); setError("");
    api("/api/me", "GET", null, { signal: control.signal }).then(profile => {
      if (control.signal.aborted) return;
      setInitial(profile); setBio(profile.bio || ""); setGithub(profile.github_url || "");
    }).catch(failure => { if (!control.signal.aborted && !isStale(failure)) setError(failure.message); });
    return () => control.abort();
  }, [epoch, user?.username]);
  const githubTrim = github.trim();
  const githubError = githubTrim && !GITHUB_PATTERN.test(githubTrim) ? "https://github.com/아이디 형식으로 입력해 주세요." : "";
  const dirty = initial && (bio !== (initial.bio || "") || githubTrim !== (initial.github_url || ""));
  const submit = async event => {
    event.preventDefault(); if (!initial || !dirty || githubError || saving) return;
    const session = getSessionEpoch(); setSaving(true); setError("");
    try {
      await api("/api/me", "PATCH", { bio, githubUrl: githubTrim });
      await refreshUser();
      if (session === getSessionEpoch()) navigate("/me");
    } catch (failure) { if (session === getSessionEpoch() && !isStale(failure)) setError(failure.message); }
    finally { if (session === getSessionEpoch()) setSaving(false); }
  };
  return <main className={"cw-page pr-page " + (dark ? "dark" : "light")}><div className="cw-wrap">
    <header className="cw-head"><div><p className="cw-eyebrow">MY PROFILE / EDIT</p><h1 className="cw-title">프로필 수정</h1><p className="cw-desc">소개와 GitHub 주소는 다른 사용자에게 공개됩니다.</p></div></header>
    {!initial && !error && <p role="status">프로필을 불러오고 있습니다…</p>}
    {error && <p className="pr-banner" role="alert">{error}</p>}
    {initial && <form className="cw-card pr-form" onSubmit={submit}>
      <div className="pr-identity"><span className="pr-avatar">{initial.username.slice(0, 1).toUpperCase()}</span><div><strong>{initial.username}</strong><span className="cw-badge blue">{initial.rank_type} {initial.rank_int}</span></div><small>아이디와 랭크는 변경할 수 없습니다.</small></div>
      <div className="pr-field"><label className="cw-label" htmlFor="pr-bio">소개</label><textarea id="pr-bio" className="cw-textarea" maxLength={BIO_MAX} value={bio} onChange={event => setBio(event.target.value)} /><p className="cw-hint pr-count">{bio.length} / {BIO_MAX}</p></div>
      <div className="pr-field"><label className="cw-label" htmlFor="pr-github">GitHub URL</label><input id="pr-github" className="cw-input" maxLength={255} value={github} onChange={event => setGithub(event.target.value)} aria-invalid={!!githubError} /><p className="cw-hint pr-err">{githubError || "비워 두면 프로필에 표시하지 않습니다."}</p></div>
      <div className="pr-actions"><button type="button" className="cw-btn" onClick={() => { if (!dirty || window.confirm("저장하지 않은 변경을 버릴까요?")) navigate("/me"); }}>취소</button><button className="cw-btn primary" type="submit" disabled={!dirty || !!githubError || saving}><Icon name="save" /> {saving ? "저장 중…" : "저장"}</button></div>
    </form>}<p className="pr-back"><Link to="/me">내 프로필로 돌아가기</Link></p>
  </div></main>;
}
