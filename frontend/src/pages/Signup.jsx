import { useEffect, useRef, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import ThemeToggle from "../components/ThemeToggle";
import { api, isStale } from "../lib/api";
import "./Signup.css";

export default function Signup({ dark, setDark }) {
  const navigate = useNavigate();
  const [form, setForm] = useState({ username: "", email: "", code: "", password: "", confirm: "" });
  const [verified, setVerified] = useState(false), [busy, setBusy] = useState("");
  const [error, setError] = useState(""), [notice, setNotice] = useState("");
  const sequence = useRef(0);
  useEffect(() => () => { sequence.current++; }, []);
  const set = (key, value) => { sequence.current++; setBusy(""); setError(""); setNotice(""); if (key === "email" || key === "code") setVerified(false); setForm(previous => ({ ...previous, [key]: value })); };
  const action = async (kind) => {
    const request = ++sequence.current; setBusy(kind); setError(""); setNotice("");
    try {
      if (kind === "send") {
        await api("/api/email-verifications", "POST", { email: form.email.trim(), purpose: "JOIN" }, { retry: false });
        if (request === sequence.current) { setVerified(false); setNotice("가입 인증 코드를 발송했습니다. 메일함을 확인해 주세요."); }
      } else {
        await api("/api/email-verifications/verify", "POST", { email: form.email.trim(), code: form.code, purpose: "JOIN" }, { retry: false });
        if (request === sequence.current) { setVerified(true); setNotice("이메일 인증을 확인했습니다."); }
      }
    } catch (failure) { if (request === sequence.current && !isStale(failure)) setError(failure.message); }
    finally { if (request === sequence.current) setBusy(""); }
  };
  const submit = async (event) => {
    event.preventDefault();
    if (!verified) { setError("이메일 인증을 먼저 확인해 주세요."); return; }
    if (form.password !== form.confirm) { setError("비밀번호 확인이 일치하지 않습니다."); return; }
    const request = ++sequence.current; setBusy("signup"); setError("");
    try {
      await api("/api/auth/signup", "POST", { username: form.username.trim(), email: form.email.trim(), verificationCode: form.code, password: form.password }, { retry: false });
      if (request === sequence.current) { setForm({ username: "", email: "", code: "", password: "", confirm: "" }); navigate("/login", { replace: true }); }
    } catch (failure) { if (request === sequence.current && !isStale(failure)) setError(failure.message); }
    finally { if (request === sequence.current) setBusy(""); }
  };
  return <div className={"SignupPage " + (dark ? "dark" : "light")}><ThemeToggle dark={dark} setDark={setDark} />
    <form className="SignupBox" onSubmit={submit}>
      <h1>Sign Up</h1>
      <label htmlFor="signup-username">아이디</label><input id="signup-username" maxLength={16} required autoComplete="username" value={form.username} onChange={e => set("username", e.target.value)} />
      <label htmlFor="signup-email">이메일</label><div className="signup-row"><input id="signup-email" type="email" required maxLength={255} autoComplete="email" value={form.email} onChange={e => set("email", e.target.value)} /><button type="button" disabled={!!busy || !form.email.trim()} onClick={() => action("send")}>인증번호 발송</button></div>
      <label htmlFor="signup-code">인증번호</label><div className="signup-row"><input id="signup-code" required inputMode="numeric" pattern="[0-9]{8}" maxLength={8} autoComplete="one-time-code" value={form.code} onChange={e => set("code", e.target.value)} /><button type="button" disabled={!!busy || !/^[0-9]{8}$/.test(form.code)} onClick={() => action("verify")}>{verified ? "인증 완료" : "인증번호 확인"}</button></div>
      <label htmlFor="signup-password">비밀번호 (8자 이상)</label><input id="signup-password" required type="password" minLength={8} autoComplete="new-password" value={form.password} onChange={e => set("password", e.target.value)} />
      <label htmlFor="signup-confirm">비밀번호 확인</label><input id="signup-confirm" required type="password" minLength={8} autoComplete="new-password" value={form.confirm} onChange={e => set("confirm", e.target.value)} />
      {error && <p className="auth-error" role="alert">{error}</p>}{notice && <p className="auth-notice" role="status">{notice}</p>}
      <button type="submit" disabled={!!busy || !verified}>{busy === "signup" ? "가입 중…" : "회원가입"}</button>
      <Link to="/login"><p>계정이 있으신가요? <span>로그인</span></p></Link><p className="signup-logo">CobWeb</p>
    </form>
  </div>;
}
