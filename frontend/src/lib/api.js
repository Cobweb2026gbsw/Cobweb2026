/** 서버 공통 응답과 메모리 전용 토큰을 처리합니다. 계정 전환 후 이전 응답은 폐기합니다. */
import {ApiError,NetworkError,ProtocolError,RequestError,StaleSessionError,SessionChangedError,safeServerMessage,statusErrorCode} from './errors.js';
let accessToken = null;
let sessionEpoch = 0;
let refreshFlight = null;
let logoutFlight = null;
let cookieFlight = null;
let sessionNotice = "";
const listeners = new Set();
export const getSessionEpoch = () => sessionEpoch;
export const getAccessToken = () => accessToken;
export const getSessionNotice = () => sessionNotice;
export const isStale = (error) => Boolean(error?.stale || error?.name === "AbortError");
// 로그아웃의 Set-Cookie 응답이 다음 로그인 쿠키를 뒤늦게 지우지 않도록 순서를 보장합니다.
export async function waitForLogout() { if (logoutFlight) await logoutFlight.catch(() => {}); }
// fetch가 resolve되기 전에 브라우저가 Set-Cookie를 적용하므로, 세션 쿠키를 쓰는
// 요청은 응답 순서까지 직렬화합니다. epoch 검사만으로는 늦은 쿠키를 막을 수 없습니다.
function mutateSessionCookie(request) {
  const previous = cookieFlight;
  const flight = (async () => {
    if (previous) await previous.catch(() => {});
    return request();
  })();
  cookieFlight = flight;
  flight.finally(() => { if (cookieFlight === flight) cookieFlight = null; }).catch(() => {});
  return flight;
}
export async function waitForSessionCookies() {
  // 대기 중 새 로그아웃/쿠키 요청이 추가된 경우에도 마지막 응답까지 기다립니다.
  while (logoutFlight || cookieFlight) {
    if (logoutFlight) await waitForLogout();
    if (cookieFlight) await cookieFlight.catch(() => {});
  }
}
export function staleError() { return new StaleSessionError(); }
export function subscribeSession(listener) { listeners.add(listener); return () => listeners.delete(listener); }
export function beginSession(token = null, notice = "") {
  accessToken = token; sessionEpoch += 1; refreshFlight = null;
  sessionNotice = notice;
  for (const listener of listeners) listener(sessionEpoch);
  return sessionEpoch;
}
function assertCurrent(epoch) { if (epoch !== sessionEpoch) throw staleError(); }
// 이 디코딩은 서명·만료·권한의 검증이 아닙니다(인증은 서버만 수행합니다).
// 다른 탭이 바꾼 refresh 쿠키로 이전 화면의 요청을 다른 계정에 재전송하지 않는 경계입니다.
function tokenSubject(token) {
  try {
    if (typeof token !== "string" || token.length > 16384) return null;
    const parts = token.split(".");
    if (parts.length !== 3 || parts.some(part => !/^[A-Za-z0-9_-]+$/.test(part))) return null;
    const encoded = parts[1].replaceAll("-", "+").replaceAll("_", "/");
    const payload = JSON.parse(atob(encoded.padEnd(Math.ceil(encoded.length / 4) * 4, "=")));
    return typeof payload.sub === "string" && /^[1-9][0-9]*$/.test(payload.sub) ? payload.sub : null;
  } catch { return null; }
}
function requireSameAccount(previous, next) {
  const nextSubject = tokenSubject(next);
  if (!nextSubject || previous && tokenSubject(previous) !== nextSubject) {
    const message = "로그인 계정이 변경됐거나 인증 정보를 확인할 수 없습니다. 다시 로그인해 주세요.";
    beginSession(null, message);
    throw new SessionChangedError(message);
  }
}
async function transport(path,options) {
  try{return await fetch(path,options);}
  catch(error){
    // 취소/계정 무효화는 오류 화면이나 자동 재전송으로 바꾸지 않습니다.
    if(isStale(error)||error instanceof ApiError)throw error;
    throw new NetworkError({requestMayHaveCompleted:!['GET','HEAD'].includes(options.method)});
  }
}
async function responseData(response,{requestMayHaveCompleted=false}={}) {
  if(!response||typeof response.ok!=='boolean'||!Number.isInteger(response.status)||typeof response.json!=='function')throw new ProtocolError();
  let envelope;
  try{envelope=await response.json();}
  catch(error){
    if(isStale(error))throw error;
    // 프록시의 HTML 오류·손상된 JSON은 절대 화면에 그대로 복사하지 않습니다.
    if(!response.ok)throw new ApiError(safeServerMessage(null,response.status),{status:response.status,errorCode:statusErrorCode(response.status)});
    throw new ProtocolError(undefined,{status:response.status,requestMayHaveCompleted});
  }
  if (!response.ok || envelope?.success === false) {
    throw new ApiError(safeServerMessage(envelope?.message,response.status),{status:response.status,errorCode:envelope?.errorCode||statusErrorCode(response.status)});
  }
  if (!envelope || Array.isArray(envelope) || envelope.success!==true || !Object.hasOwn(envelope, "data")) throw new ProtocolError(undefined,{status:response.status,requestMayHaveCompleted});
  return envelope.data;
}
/** 초기 쿠키 복구와 동시 401은 같은 재발급 요청을 공유합니다. */
export async function restoreSession() {
  if (logoutFlight) await waitForLogout();
  const epoch = sessionEpoch, previous = accessToken;
  if (!refreshFlight) {
    const flight = (async () => {
      const response = await mutateSessionCookie(() => {
        assertCurrent(epoch);
        return transport("/api/auth/reissue", { method: "POST", credentials: "same-origin", cache: "no-store", headers: { "Content-Type": "application/json" } });
      });
      assertCurrent(epoch);
      const data = await responseData(response);
      assertCurrent(epoch);
      if (typeof data?.accessToken !== "string" || !data.accessToken) throw new ProtocolError("인증 토큰이 없습니다.");
      requireSameAccount(previous, data.accessToken);
      accessToken = data.accessToken;
      return data;
    })();
    refreshFlight = flight;
    flight.finally(() => { if (refreshFlight === flight) refreshFlight = null; }).catch(() => {});
  }
  const result = await refreshFlight;
  assertCurrent(epoch);
  return result;
}
export async function api(path, method = "GET", body = null, options = {}) {
  let target;
  try{if(typeof path==='string')target=new URL(path,'http://cobweb.invalid');}catch{/* 아래 공통 요청 오류로 처리합니다. */}
  if (typeof path !== "string" || !path.startsWith("/api/") || /[\\#\u0000-\u001f\u007f]/.test(path) || target?.origin!=='http://cobweb.invalid' || !target.pathname.startsWith('/api/')) throw new RequestError("허용되지 않은 API 주소입니다.");
  if(typeof method!=='string'||!['GET','HEAD','POST','PUT','PATCH','DELETE'].includes(method))throw new RequestError('지원하지 않는 요청 방식입니다.');
  const epoch = sessionEpoch, token = accessToken;
  let encodedBody;
  try{encodedBody=body==null?undefined:JSON.stringify(body);}catch{throw new RequestError('요청 본문을 JSON으로 변환할 수 없습니다.');}
  const headers = { "Content-Type": "application/json" };
  if (token) headers.Authorization = `Bearer ${token}`;
  let response;
  try {
    const request = () => {
      assertCurrent(epoch);
      return transport(path, { method, headers, credentials: "same-origin", cache: "no-store", signal: options.signal, body: encodedBody });
    };
    response = path === "/api/auth/login" && method === "POST" ? await mutateSessionCookie(request) : await request();
  }
  catch (error) { assertCurrent(epoch); throw error; }
  assertCurrent(epoch);
  // 익명 공개 조회 실패는 불필요한 재발급이나 로그아웃을 유발하지 않습니다.
  if (response.status === 401 && token && options.retry !== false && !path.startsWith("/api/auth/")) {
    try { await restoreSession(); }
    catch (error) { if (error.sessionChanged) throw error; assertCurrent(epoch); if (!isStale(error)) beginSession(null); throw error; }
    assertCurrent(epoch);
    return api(path, method, body, { ...options, retry: false });
  }
  try { const data = await responseData(response,{requestMayHaveCompleted:!['GET','HEAD'].includes(method)}); assertCurrent(epoch); return data; }
  catch (error) { assertCurrent(epoch); throw error; }
}
/** 현재 계정을 즉시 비운 뒤 캡처한 이전 토큰으로 서버 refresh 쿠키도 폐기합니다. */
export async function logoutSession() {
  if (logoutFlight) return logoutFlight;
  const token = accessToken, pendingRefresh = refreshFlight, epoch = beginSession(null);
  const work = (async () => {
    if (pendingRefresh) await pendingRefresh.catch(() => {});
    assertCurrent(epoch);
    if (!token && !pendingRefresh) return;
    const send = value => mutateSessionCookie(() => {
      assertCurrent(epoch);
      return transport("/api/auth/logout", { method: "POST", credentials: "same-origin", cache: "no-store", headers: { "Content-Type": "application/json", Authorization: `Bearer ${value}` } });
    });
    let response = await send(token);
    assertCurrent(epoch);
    // 만료된 access token도 refresh 쿠키를 폐기할 수 있도록 먼저 새 토큰을 받아 즉시 로그아웃합니다.
    if (response.status === 401) {
      const refreshed = await mutateSessionCookie(() => {
        assertCurrent(epoch);
        return transport("/api/auth/reissue", { method: "POST", credentials: "same-origin", cache: "no-store", headers: { "Content-Type": "application/json" } });
      });
      assertCurrent(epoch);
      if (refreshed.status === 401) return; // 서버에서도 이미 만료된 익명 세션입니다.
      const fresh = await responseData(refreshed);
      assertCurrent(epoch);
      if (typeof fresh?.accessToken !== "string" || !fresh.accessToken) throw new ProtocolError("인증 토큰이 없습니다.");
      // 공유 쿠키가 B 계정으로 바뀌었어도 A의 로그아웃이 B 세션을 폐기하면 안 됩니다.
      requireSameAccount(token, fresh.accessToken);
      response = await send(fresh.accessToken);
      assertCurrent(epoch);
    }
    await responseData(response);
  })();
  logoutFlight = work;
  try { return await work; }
  finally { if (logoutFlight === work) logoutFlight = null; }
}
