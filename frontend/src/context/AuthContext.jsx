import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import { api, beginSession, getSessionEpoch, getSessionNotice, isStale, logoutSession, restoreSession, subscribeSession, waitForSessionCookies } from "../lib/api";
import {ProtocolError} from '../lib/errors.js';

const AuthContext = createContext(null);
const NONE = { canCreate: false, canManage: false };

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [permissions, setPermissions] = useState(NONE);
  const [epoch, setEpoch] = useState(getSessionEpoch);
  const [sessionNotice, setSessionNotice] = useState(getSessionNotice);
  const [loading, setLoading] = useState(true);
  const mounted = useRef(false);
  const initial = useRef(null);

  const loadIdentity = useCallback(async (session) => {
    const [profile, rights] = await Promise.all([api("/api/me"), api("/api/problems/permissions")]);
    if (!mounted.current || session !== getSessionEpoch()) return null;
    const normalized = { ...profile, nickname: profile.username };
    setUser(normalized);
    setPermissions({ canCreate: Boolean(rights?.canCreate), canManage: Boolean(rights?.canManage) });
    return normalized;
  }, []);

  useEffect(() => {
    mounted.current = true;
    const unsubscribe = subscribeSession((next) => {
      if (!mounted.current) return;
      setEpoch(next); setSessionNotice(getSessionNotice()); setUser(null); setPermissions(NONE); setLoading(false);
    });
    // StrictMode의 재실행에서도 OAuth fragment/refresh 쿠키는 한 번만 소비합니다.
    if (!initial.current) {
      const fragment = new URLSearchParams(window.location.hash.slice(1));
      const supplied = fragment.get("accessToken");
      if (supplied) window.history.replaceState({}, "", window.location.pathname + window.location.search);
      initial.current = (async () => {
        let session = getSessionEpoch();
        try {
          if (supplied) session = beginSession(supplied);
          else {
            const restored = await restoreSession();
            session = beginSession(restored.accessToken);
          }
          // 익명으로 시작한 조회도 새 인증 세대에서 다시 요청하도록 갱신합니다.
          setLoading(true);
          await loadIdentity(session);
        } catch (error) {
          if (!isStale(error) && session === getSessionEpoch() && mounted.current) {
            setUser(null); setPermissions(NONE);
          }
        } finally { if (mounted.current && session === getSessionEpoch()) setLoading(false); }
      })();
    }
    return () => { mounted.current = false; unsubscribe(); };
  }, [loadIdentity]);

  const refreshUser = useCallback(() => loadIdentity(getSessionEpoch()), [loadIdentity]);
  const acceptToken = useCallback(async (token) => {
    if (typeof token !== "string" || !token) throw new ProtocolError("인증 토큰이 없습니다.");
    const session = beginSession(token);
    setLoading(true);
    try { return await loadIdentity(session); }
    finally { if (mounted.current && session === getSessionEpoch()) setLoading(false); }
  }, [loadIdentity]);
  const login = useCallback(async (credentials) => {
    // 초기 복구의 profile 응답까지 끝나야 기존 계정이 새 로그인 세대를 덮지 않습니다.
    if (initial.current) await initial.current.catch(() => {});
    await waitForSessionCookies();
    const session = beginSession(null);
    setLoading(true);
    try {
      const result = await api("/api/auth/login", "POST", credentials, { retry: false });
      if (session !== getSessionEpoch()) return null;
      return await acceptToken(result.accessToken);
    } finally {
      if (mounted.current && session === getSessionEpoch()) setLoading(false);
    }
  }, [acceptToken]);
  const logout = useCallback(async () => {
    setLoading(false);
    const request = logoutSession(), session = getSessionEpoch();
    try { await request; }
    finally { if (mounted.current && session === getSessionEpoch()) { setUser(null); setPermissions(NONE); setLoading(false); } }
  }, []);
  const value = useMemo(() => ({ user, loading, login, logout, permissions, epoch, refreshUser, acceptToken, sessionNotice }),
    [user, loading, login, logout, permissions, epoch, refreshUser, acceptToken, sessionNotice]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error("useAuth는 AuthProvider 안에서 사용해야 합니다.");
  return context;
}
