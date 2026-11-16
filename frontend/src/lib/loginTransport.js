/** Secure 인증 쿠키는 HTTPS 또는 브라우저의 localhost 예외에서만 사용할 수 있습니다. */
export function requiresHttpsLogin(location = globalThis.location) {
  if (location?.protocol === 'https:') return false;
  return location?.protocol !== 'http:' || !['localhost', '127.0.0.1', '[::1]', '::1'].includes(location?.hostname);
}

export function httpsLoginUrl(location, httpsPort) {
  if (!requiresHttpsLogin(location) || !Number.isInteger(httpsPort) || httpsPort < 1 || httpsPort > 65535) return null;
  try {
    const url = new URL(location.href);
    if (url.protocol !== 'http:' || url.username || url.password) return null;
    url.protocol = 'https:';
    url.port = String(httpsPort);
    url.pathname = '/login';
    url.search = '';
    url.hash = '';
    return url.href;
  } catch { return null; }
}
