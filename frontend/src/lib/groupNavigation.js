export function groupListState(search = '') {
  const params = new URLSearchParams(search);
  const rawPage = params.get('page') || '0';
  return {
    tab: params.get('tab') === 'mine' ? 'mine' : 'explore',
    search: (params.get('q') || '').slice(0, 100),
    page: /^\d+$/.test(rawPage) && Number(rawPage) <= 10000 ? Number(rawPage) : 0,
  };
}
export function groupListUrl(value) {
  if (typeof value !== 'string' || value.length > 4096 || !/^\/groups(?:\?[^#]*)?$/.test(value) || /[\\\u0000-\u001f\u007f]/.test(value)) return '/groups';
  const {tab, search, page} = groupListState(value.split('?')[1]);
  const params = new URLSearchParams();
  if (tab === 'mine') params.set('tab', tab);
  if (search) params.set('q', search);
  if (page) params.set('page', String(page));
  return '/groups' + (params.size ? '?' + params : '');
}
export const groupDetailUrl = (id, from) => `/groups/${id}?` + new URLSearchParams({from: groupListUrl(from)});
export const groupReturnUrl = search => groupListUrl(new URLSearchParams(search).get('from'));
const positionKey = owner => 'cobweb.group-positions.v1:' + owner;
export function rememberGroupPosition(owner, from, y) {
  try {
    const key = positionKey(owner), old = JSON.parse(sessionStorage.getItem(key) || '[]');
    const url = groupListUrl(from);
    const entries = Array.isArray(old) ? old.filter(item => Array.isArray(item) && item[0] !== url).slice(-19) : [];
    entries.push([url, Math.max(0, Number(y) || 0)]);
    sessionStorage.setItem(key, JSON.stringify(entries));
  } catch { /* Navigation still works when storage is unavailable. */ }
}
export function groupPosition(owner, from) {
  try {
    const entries = JSON.parse(sessionStorage.getItem(positionKey(owner)) || '[]');
    const value = Array.isArray(entries) ? entries.find(item => Array.isArray(item) && item[0] === groupListUrl(from))?.[1] : 0;
    return Number.isFinite(value) && value >= 0 ? value : 0;
  } catch {return 0;}
}
