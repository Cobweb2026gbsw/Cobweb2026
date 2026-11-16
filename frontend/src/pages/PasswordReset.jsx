import { useEffect, useRef, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import ThemeToggle from "../components/ThemeToggle";
import { api, isStale } from "../lib/api";
import "./PasswordReset.css";

export default function PasswordReset({ dark, setDark }) {
  const navigate = useNavigate();
  const [email, setEmail] = useState(""), [code, setCode] = useState("");
  const [password, setPassword] = useState(""), [confirm, setConfirm] = useState("");
  const [resetToken, setResetToken] = useState(null), [busy, setBusy] = useState("");
  const [error, setError] = useState(""), [notice, setNotice] = useState("");
  const sequence = useRef(0);
  useEffect(() => () => { sequence.current++; }, []);
  const invalidate = () => { sequence.current++; setResetToken(null); setBusy(""); setError(""); setNotice(""); };
  const act = async (kind) => {
    const request = ++sequence.current; setBusy(kind); setError(""); setNotice("");
    try {
      if (kind === "send") {
        setResetToken(null);
        await api("/api/auth/password-reset/request", "POST", { email: email.trim() }, { retry: false });
        if (request === sequence.current) setNotice("입력한 이메일에 해당하는 계정이 있으면 안내 메일이 발송됩니다.");
      } else {
        const result = await api("/api/auth/password-reset/verify-code", "POST", { email: email.trim(), code }, { retry: false });
        if (request === sequence.current) { setResetToken(result.resetToken); setNotice("인증됐습니다. 새 비밀번호를 입력해 주세요."); }
      }
    } catch (failure) { if (request === sequence.current && !isStale(failure)) setError(failure.message); }
    finally { if (request === sequence.current) setBusy(""); }
  };
  const submit = async (event) => {
    event.preventDefault();
    if (!resetToken) { setError("인증번호를 먼저 확인해 주세요."); return; }
    if (password !== confirm) { setError("비밀번호 확인이 일치하지 않습니다."); return; }
    const request = ++sequence.current; setBusy("confirm"); setError("");
    try {
      await api("/api/auth/password-reset/confirm", "POST", { resetToken, newPassword: password }, { retry: false });
      if (request === sequence.current) { setResetToken(null); setPassword(""); setConfirm(""); navigate("/login", { replace: true }); }
    } catch (failure) { if (request === sequence.current && !isStale(failure)) setError(failure.message); }
    finally { if (request === sequence.current) setBusy(""); }
  };
  return <div className={"PasswordResetPage " + (dark ? "dark" : "light")}><ThemeToggle dark={dark} setDark={setDark} />
    <form className="PasswordResetBox" onSubmit={submit}><h1>Password Reset</h1>
      <label htmlFor="reset-email">이메일</label><div className="reset-row"><input id="reset-email" required type="email" autoComplete="email" maxLength={255} value={email} onChange={e => { invalidate(); setEmail(e.target.value); }} /><button type="button" disabled={!!busy || !email.trim()} onClick={() => act("send")}>인증번호 발송</button></div>
      <label htmlFor="reset-code">인증번호</label><div className="reset-row"><input id="reset-code" required inputMode="numeric" pattern="[0-9]{8}" maxLength={8} autoComplete="one-time-code" value={code} onChange={e => { invalidate(); setCode(e.target.value); }} /><button type="button" disabled={!!busy || !/^[0-9]{8}$/.test(code)} onClick={() => act("verify")}>인증번호 확인</button></div>
      <label htmlFor="reset-password">새 비밀번호 (8자 이상)</label><input id="reset-password" required type="password" minLength={8} autoComplete="new-password" value={password} onChange={e => setPassword(e.target.value)} />
      <label htmlFor="reset-confirm">새 비밀번호 확인</label><input id="reset-confirm" required type="password" minLength={8} autoComplete="new-password" value={confirm} onChange={e => setConfirm(e.target.value)} />
      {error && <p className="auth-error" role="alert">{error}</p>}{notice && <p className="auth-notice" role="status">{notice}</p>}
      <button type="submit" disabled={!!busy || !resetToken}>{busy === "confirm" ? "변경 중…" : "비밀번호 변경"}</button>
      <Link to="/login"><p>로그인으로 돌아가기</p></Link><p className="reset-logo">CobWeb</p>
    </form>
  </div>;
}
