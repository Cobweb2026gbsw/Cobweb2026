import {problemUrl} from './problemNavigation.js';

export const CATEGORIES = {
  HARDWARE: { label: '하드웨어', tone: 'blue' }, SOFTWARE: { label: '소프트웨어', tone: 'mint' },
  NEWS: { label: '최신 정보', tone: 'yellow' }, PROBLEM_QNA: { label: '문제 질문', tone: 'blue' },
  MAJOR_QNA: { label: '전공 질문', tone: 'green' }, GENERAL: { label: '자유', tone: 'gray' }, NOTICE: { label: '공지', tone: 'mint' },
};
export const displayDate = value => value ? new Date(value).toLocaleString('ko-KR') : '—';

export function forumListDestination(value, admin = false) {
  const fallback = admin ? '/admin/community' : '/forum';
  if (typeof value !== 'string' || value.length > 4096 || /[\\#\u0000-\u001f]/.test(value)) return fallback;
  const path = value.split('?')[0];
  const allowed = admin ? ['/admin/community'] : ['/forum', '/community', '/notices'];
  return allowed.includes(path) ? value : fallback;
}

export function forumProblemUrl(number, list) {
  const params = new URL(forumListDestination(list), 'http://localhost').searchParams;
  return problemUrl(number, params.get('problemNumber') === String(number) ? params.get('list') : null);
}
