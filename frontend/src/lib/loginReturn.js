// The return address is an application route. Never send it to the OAuth provider.
const storageKey = 'cobweb.oauth.return';
const maxAge = 30 * 60 * 1000;

export function loginReturnPath(value) {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//') ||
      value.length > 8192 || /[\\\x00-\x1f\x7f]/.test(value)) return null;
  const url = new URL(value, 'https://cobweb.invalid');
  if (url.origin !== 'https://cobweb.invalid' || url.pathname.startsWith('//') ||
      /^\/(?:api|login|signup|password-reset|oauth)(?:\/|$)/.test(url.pathname)) return null;
  return url.pathname + url.search + url.hash;
}

export function rememberOAuthReturn(path) {
  try {
    const from = loginReturnPath(path);
    if (from) window.sessionStorage.setItem(storageKey, JSON.stringify({from, time: Date.now()}));
    else window.sessionStorage.removeItem(storageKey);
  } catch { /* Sign-in still works without browser storage. */ }
}

export function oauthReturnPath() {
  try {
    const saved = JSON.parse(window.sessionStorage.getItem(storageKey));
    const age = Date.now() - saved?.time;
    return age >= 0 && age < maxAge ? loginReturnPath(saved?.from) : null;
  } catch { return null; }
}
