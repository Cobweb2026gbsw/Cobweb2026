// 이름은 경로 조각으로 쓰지 않습니다. 기존 '/', '..', 유니코드 이름도 그대로 보존합니다.
const query = username => new URLSearchParams({username: String(username ?? '')}).toString();
export const profileHref = username => '/users?' + query(username);
export const publicProfileApi = username => '/api/profiles?' + query(username);
