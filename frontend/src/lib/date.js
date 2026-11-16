// 제출 API의 JDBC DATETIME은 UTC지만 오프셋 없이 직렬화됩니다.
// 명시적 Z/오프셋이 있는 그룹·커뮤니티 시각은 그대로 보존합니다.
export function serverDate(value) {
  if (!value) return null;
  let text = typeof value === 'string' ? value.replace(' ', 'T') : value;
  if (typeof text === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?$/.test(text)) text += 'Z';
  const result = new Date(text);
  return Number.isNaN(result.getTime()) ? null : result;
}
export const formatServerDate = (value, options) => serverDate(value)?.toLocaleString('ko-KR', options) || '—';
