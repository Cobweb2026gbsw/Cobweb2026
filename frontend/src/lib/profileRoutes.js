// 이름은 경로 조각으로 쓰지 않습니다. 기존 '/', '..', 유니코드 이름도 그대로 보존합니다.
const query = username => new URLSearchParams({username: String(username ?? '')}).toString();
export const profileHref = username => '/users?' + query(username);
export const publicProfileApi = username => '/api/profiles?' + query(username);

export function rankingReturnPath(value) {
  if (typeof value !== 'string' || value.length > 4096) return null;
  return /^\/ranking(?:\?[^#]*)?$/.test(value) && !/[\\\u0000-\u001f\u007f]/.test(value) ? value : null;
}
