import {ProtocolError} from './errors.js';

const LOOPBACK_HOSTS = new Set(['localhost', '127.0.0.1', '[::1]']);

/** 서버가 결정한 제공자 URL 또는 canonical 시작 URL에 최상위 이동만 합니다.
 * 다른 출처에 fetch로 쿠키를 만들거나 state 검증을 우회하지 않습니다.
 */
export function oauthNavigationUrl(value) {
  try {
    if (typeof value !== 'string' || !/^https?:\/\//i.test(value) || /[\\\u0000-\u0020\u007f]/.test(value)) throw new Error();
    const target = new URL(value);
    if (target.username || target.password || !target.hostname ||
        target.protocol !== 'https:' && !(target.protocol === 'http:' && LOOPBACK_HOSTS.has(target.hostname))) throw new Error();
    return target.href;
  } catch {
    throw new ProtocolError('인증 주소가 올바르지 않습니다.');
  }
}

export function navigateOAuth(value, navigate = url => window.location.assign(url)) {
  const target = oauthNavigationUrl(value);
  navigate(target);
  return target;
}
