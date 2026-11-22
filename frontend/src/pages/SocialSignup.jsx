import {oauthReturnPath} from '../lib/loginReturn.js';
import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import ThemeToggle from '../components/ThemeToggle';
import TermsConsent, { TERMS_VERSION } from '../components/TermsConsent';
import { api, isStale } from '../lib/api';
import { navigateOAuth } from '../lib/oauthTransport';
import './Signup.css';

export default function SocialSignup({ dark, setDark }) {
  const [params] = useSearchParams();
  const [from] = useState(oauthReturnPath);
  const provider = params.get('provider');
  const name = provider === 'google' ? 'Google' : provider === 'github' ? 'GitHub' : null;
  const [accepted, setAccepted] = useState(false), [busy, setBusy] = useState(false), [error, setError] = useState('');
  const request = useRef(null);
  useEffect(() => () => request.current?.abort(), []);
  async function start(event) {
    event.preventDefault();
    if (!accepted || !name || busy) return;
    const controller = new AbortController();
    request.current = controller;
    setBusy(true); setError('');
    try {
      const result = await api('/api/auth/oauth/' + provider + '/signup-start', 'POST',
        { acceptedTermsVersion: TERMS_VERSION }, { signal: controller.signal, retry: false });
      if (!controller.signal.aborted) navigateOAuth(result.authorizeUrl);
    } catch (failure) {
      if (!controller.signal.aborted && !isStale(failure)) { setError(failure.message); setBusy(false); }
    }
  }
  return <main className={'SignupPage ' + (dark ? 'dark' : 'light')}>
    <ThemeToggle dark={dark} setDark={setDark} />
    <form className="SignupBox" onSubmit={start}>
      <h1>소셜 회원가입</h1>
      {name ? <>
        <p>이용약관에 동의한 뒤 {name} 계정을 다시 확인하면 회원가입이 완료됩니다.</p>
        <TermsConsent checked={accepted} onChange={setAccepted} disabled={busy} />
        {error && <p className="auth-error" role="alert">{error}</p>}
        <button type="submit" disabled={!accepted || busy}>{name} 계정으로 계속</button>
      </> : <p role="alert">지원하지 않는 소셜 가입 경로입니다.</p>}
      <Link to="/login" state={from ? {from} : null}><p>로그인으로 돌아가기</p></Link>
    </form>
  </main>;
}
