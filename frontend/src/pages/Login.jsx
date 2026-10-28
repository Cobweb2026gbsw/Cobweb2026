import { useEffect, useRef, useState } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { api, isStale, waitForSessionCookies } from "../lib/api";
import {ProtocolError} from '../lib/errors.js';
import {httpsLoginUrl, requiresHttpsLogin} from '../lib/loginTransport.js';
import ThemeToggle from "../components/ThemeToggle";
import "./Login.css";

export default function Login({ dark, setDark }) {
  const navigate = useNavigate(), location = useLocation();
  const { login, user, loading: authLoading, sessionNotice } = useAuth();
  const [username, setUsername] = useState(""), [password, setPassword] = useState("");
  const [error, setError] = useState(""), [busy, setBusy] = useState(false);
  const [rememberMe, setRememberMe] = useState(false), [secureLoginUrl, setSecureLoginUrl] = useState(null);
  const insecureLogin = requiresHttpsLogin(window.location);
  const active = useRef(true);
  useEffect(() => { active.current = true; return () => { active.current = false; }; }, []);
  useEffect(() => {
    if (!insecureLogin) return;
    const controller = new AbortController();
    api('/api/auth/login-options', 'GET', null, {signal: controller.signal})
      .then(options => { if (active.current) setSecureLoginUrl(httpsLoginUrl(window.location, options?.httpsPort)); })
      .catch(() => {}); // 설정 조회가 실패해도 평문 로그인은 허용하지 않습니다.
    return () => controller.abort();
  }, [insecureLogin]);
  const destination = typeof location.state?.from === "string" && location.state.from.startsWith("/") && !location.state.from.startsWith("//") && !/[\\\u0000-\u001f\u007f]/.test(location.state.from)
    ? location.state.from : "/dashboard";
  useEffect(() => { if (user && !authLoading) navigate(destination, { replace: true }); }, [user, authLoading, destination, navigate]);
  const submit = async (event) => {
    event.preventDefault();
    if (authLoading || busy) return;
    if (insecureLogin) { setError('IP 주소에서는 HTTPS로 접속해야 로그인 상태를 유지할 수 있습니다. HTTPS 주소로 이동해 주세요.'); return; }
    if (!username.trim() || !password) { setError("아이디와 비밀번호를 입력해 주세요."); return; }
    setBusy(true); setError("");
    try {
      const authenticated = await login({ username: username.trim(), password, rememberMe });
      if (authenticated && active.current) { setPassword(""); navigate(destination, { replace: true }); }
    } catch (failure) { if (active.current && !isStale(failure)) setError(failure.message); }
    finally { if (active.current) setBusy(false); }
  };
  const oauth = async (provider) => {
    if (authLoading || busy) return;
    if (insecureLogin) { setError('소셜 로그인도 HTTPS 주소에서 진행해 주세요.'); return; }
    setBusy(true); setError("");
    try {
      await waitForSessionCookies();
      const result = await api("/api/auth/oauth/" + provider + "/authorize-url");
      let target;
      try{target = new URL(result?.authorizeUrl);}catch{throw new ProtocolError('인증 주소가 올바르지 않습니다.');}
      if (!["https:", "http:"].includes(target.protocol)) throw new ProtocolError("인증 주소가 올바르지 않습니다.");
      if (active.current) window.location.assign(target.href);
    } catch (failure) { if (active.current && !isStale(failure)) { setError(failure.message); setBusy(false); } }
  };
  return <div className={"LoginPage " + (dark ? "dark" : "light")}>
    <ThemeToggle dark={dark} setDark={setDark} />
    <form className="LoginBox" onSubmit={submit}>
      <h1>LogIn</h1>
      <label htmlFor="login-username">아이디</label>
      <input id="login-username" autoComplete="username" maxLength={16} required value={username} onChange={e => setUsername(e.target.value)} />
      <label htmlFor="login-password">비밀번호</label>
      <input id="login-password" type="password" autoComplete="current-password" required value={password} onChange={e => setPassword(e.target.value)} />
      <label className="login-remember" htmlFor="login-remember"><input id="login-remember" type="checkbox" aria-describedby="login-remember-help" checked={rememberMe} onChange={e => setRememberMe(e.target.checked)} disabled={busy || authLoading} />로그인 유지</label>
      <small id="login-remember-help" className="login-remember-help">일반 로그인에 적용됩니다.</small>
      {insecureLogin && <p className="login-transport" role="status">IP 주소에서는 HTTPS 접속이 필요합니다. {secureLoginUrl ? <a href={secureLoginUrl}>HTTPS로 로그인하기</a> : '서버의 HTTPS 주소로 다시 접속해 주세요.'}</p>}
      <p className="login-links"><Link to="/signup">회원가입</Link><Link to="/password-reset">비밀번호 찾기</Link></p>
      {(error || sessionNotice) && <p className="login-error" role="alert">{error || sessionNotice}</p>}
      <button type="submit" disabled={busy || authLoading}>{busy ? "로그인 중…" : authLoading ? "로그인 상태 확인 중…" : "로그인"}</button>
      <div className="login-social" aria-label="소셜 로그인">{[["google", "Google"], ["github", "GitHub"], ["naver", "네이버"]].map(([id, label]) =>
        <button type="button" key={id} disabled={busy || authLoading} onClick={() => oauth(id)}>{label} 로그인</button>)}</div>
      <p className="login-help">네이버는 기존 연결 계정 로그인만 가능합니다. 외부 제공자 설정이 없는 로컬 환경에서는 소셜 로그인이 제한됩니다.</p>
      <p className="login-logo"><Link to="/">CobWeb</Link></p>
    </form>
  </div>;
}
