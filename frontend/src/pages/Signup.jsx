import { useEffect, useRef, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import TermsConsent, { TERMS_VERSION } from '../components/TermsConsent';
import ThemeToggle from "../components/ThemeToggle";
import { api, isStale } from "../lib/api";
import { advanceOnEnter, useProgressiveFields } from '../lib/useProgressiveFields.js';
import "./Signup.css";
import '../components/AuthProgressive.css';

const FIELDS = ['username', 'email', 'code', 'password', 'confirm'];
const STEP_HELP = ['아이디를 입력해 주세요.', '인증번호를 받을 이메일을 입력해 주세요.', '메일로 받은 인증번호를 입력해 주세요.', '사용할 비밀번호를 입력해 주세요.', '비밀번호를 한 번 더 입력해 주세요.'];

export default function Signup({ dark, setDark }) {
  const navigate = useNavigate();
  const [accepted, setAccepted] = useState(false);
  const [form, setForm] = useState({ username: "", email: "", code: "", password: "", confirm: "" });
  const [verified, setVerified] = useState(false), [busy, setBusy] = useState("");
  const [sentTo,setSentTo]=useState('');
  const [error, setError] = useState(""), [notice, setNotice] = useState("");
  const { step, inputs, reveal, truncate } = useProgressiveFields(FIELDS);
  const formRef = useRef(null);
  const sequence = useRef(0);
  useEffect(() => () => { sequence.current++; }, []);
  const set = (key, value) => {
    sequence.current++; setBusy(''); setError(''); setNotice('');
    if (key === 'email') { setSentTo(''); setVerified(false); truncate(1); }
    if (key === 'code') { setVerified(false); truncate(2); }
    setForm(previous => ({ ...previous, [key]: value, ...(key === 'email' ? { code: '' } : {}) }));
  };
  const valid = (key) => {
    const input = inputs.current[key];
    if (key === 'username' && !form.username.trim()) {
      setError('아이디를 입력해 주세요.'); input?.focus(); return false;
    }
    if (!input?.reportValidity()) return false;
    if (key === 'password' && form.password.length < 8) {
      setError('비밀번호는 8자 이상 입력해 주세요.'); input.focus(); return false;
    }
    return true;
  };
  const action = async (kind) => {
    if (busy || !valid('username') || !valid('email')) return;
    if (kind === 'verify') {
      if (sentTo !== form.email.trim()) { setError('이메일로 인증번호를 먼저 받아 주세요.'); inputs.current.email?.focus(); return; }
      if (!valid('code')) return;
    }
    const request = ++sequence.current; setBusy(kind); setError(""); setNotice("");
    try {
      if (kind === "send") {
        await api("/api/email-verifications", "POST", { email: form.email.trim(), purpose: "JOIN" }, { retry: false });
        if (request === sequence.current) {
          setSentTo(form.email.trim()); setVerified(false); setForm(previous => ({ ...previous, code: '' }));
          setNotice('8자리 인증번호를 발송했습니다. 메일함을 확인해 주세요.');
          truncate(2); reveal('code');
        }
      } else {
        await api("/api/email-verifications/verify", "POST", { email: form.email.trim(), code: form.code, purpose: "JOIN" }, { retry: false });
        if (request === sequence.current) { setVerified(true); setNotice("이메일 인증을 확인했습니다."); reveal('password'); }
      }
    } catch (failure) { if (request === sequence.current && !isStale(failure)) setError(failure.message); }
    finally { if (request === sequence.current) setBusy(""); }
  };
  const submit = async () => {
    if (busy || !valid('username') || !valid('password')) return;
    if (!verified) { setError("이메일 인증을 먼저 확인해 주세요."); return; }
    if (form.password !== form.confirm) { setError("비밀번호 확인이 일치하지 않습니다."); inputs.current.confirm?.focus(); return; }
    if (!accepted) { setError("회원가입하려면 이용약관에 동의해 주세요."); formRef.current.querySelector('[type="checkbox"]')?.focus(); return; }
    if (!formRef.current.reportValidity()) return;
    const request = ++sequence.current; setBusy("signup"); setError("");
    try {
      await api("/api/auth/signup", "POST", { username: form.username.trim(), email: form.email.trim(), verificationCode: form.code, password: form.password, acceptedTermsVersion: TERMS_VERSION }, { retry: false });
      if (request === sequence.current) { setForm({ username: "", email: "", code: "", password: "", confirm: "" }); navigate("/login", { replace: true }); }
    } catch (failure) { if (request === sequence.current && !isStale(failure)) setError(failure.message); }
    finally { if (request === sequence.current) setBusy(""); }
  };
  const advance = (key = FIELDS[step]) => {
    if (busy) return;
    if (key === 'confirm') { submit(); return; }
    if (!valid(key)) return;
    setError('');
    if (key === 'username') reveal('email');
    if (key === 'email') {
      if (sentTo === form.email.trim()) reveal('code');
      else action('send');
    }
    if (key === 'code') {
      if (verified) reveal('password');
      else action('verify');
    }
    if (key === 'password') reveal('confirm');
  };
  const fieldProps = key => ({
    ref: node => { inputs.current[key] = node; },
    value: form[key],
    readOnly: busy === 'signup',
    enterKeyHint: key === 'confirm' ? 'done' : 'next',
    onChange: e => set(key, e.target.value),
    onKeyDown: e => advanceOnEnter(e, () => advance(key)),
  });
  return <div className={"SignupPage " + (dark ? "dark" : "light")}><ThemeToggle dark={dark} setDark={setDark} />
    <form ref={formRef} className="SignupBox" noValidate onSubmit={event => { event.preventDefault(); advance(); }}>
      <h1>회원가입</h1>
      <p className="auth-progress" role="status">{step + 1} / 5 · {STEP_HELP[step]}<br />Enter 또는 아래 버튼으로 계속할 수 있어요.</p>
      <div className="auth-field">
        <label htmlFor="signup-username">아이디</label><input {...fieldProps('username')} id="signup-username" autoFocus maxLength={16} required autoComplete="username" aria-describedby="signup-username-help" /><small id="signup-username-help" className="signup-help">최대 16자까지 입력할 수 있어요.</small>
      </div>
      {step >= 1 && <div className="auth-field auth-field--revealed">
        <label htmlFor="signup-email">이메일</label><div className="signup-row"><input {...fieldProps('email')} id="signup-email" type="email" required maxLength={255} autoComplete="email" />{sentTo && <button type="button" disabled={!!busy} onClick={() => action('send')}>{busy === 'send' ? '발송 중…' : '인증번호 재발송'}</button>}</div>
      </div>}
      {step >= 2 && <div className="auth-field auth-field--revealed">
        <label htmlFor="signup-code">인증번호{verified ? ' · 인증 완료' : ''}</label><input {...fieldProps('code')} id="signup-code" required inputMode="numeric" pattern="[0-9]{8}" maxLength={8} autoComplete="one-time-code" aria-describedby="signup-code-help" />
        <small id="signup-code-help" className="signup-help">메일로 받은 숫자 8자리를 입력해 주세요.</small>
      </div>}
      {step >= 3 && <div className="auth-field auth-field--revealed">
        <label htmlFor="signup-password">비밀번호 (8자 이상)</label><input {...fieldProps('password')} id="signup-password" required type="password" minLength={8} autoComplete="new-password" />
      </div>}
      {step >= 4 && <div className="auth-field auth-field--revealed">
        <label htmlFor="signup-confirm">비밀번호 확인</label><input {...fieldProps('confirm')} id="signup-confirm" required type="password" minLength={8} autoComplete="new-password" aria-invalid={Boolean(form.confirm&&form.password!==form.confirm)} aria-describedby="signup-submit-help" />
      </div>}
      {step >= 4 && <TermsConsent checked={accepted} onChange={setAccepted} disabled={!!busy} />}
      {error && <p className="auth-error" role="alert">{error}</p>}{notice && <p className="auth-notice" role="status">{notice}</p>}
      {step >= 4 && <p id="signup-submit-help" className="signup-help" role="status">{form.confirm&&form.password!==form.confirm?'비밀번호와 비밀번호 확인을 같게 입력해 주세요.':'약관에 동의하고 회원가입을 눌러 주세요.'}</p>}
      <button type="submit" aria-describedby={step >= 4 ? 'signup-submit-help' : undefined} disabled={!!busy}>{busy === 'send' ? '발송 중…' : busy === 'verify' ? '확인 중…' : busy === 'signup' ? '가입 중…' : ['다음', '인증번호 발송', '인증번호 확인', '다음', '회원가입'][step]}</button>
      <Link to="/login"><p>계정이 있으신가요? <span>로그인</span></p></Link><p className="signup-logo"><Link to="/">CobWeb</Link></p>
    </form>
  </div>;
}
