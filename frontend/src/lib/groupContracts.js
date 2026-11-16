import {RequestError} from './errors.js';
/** 서버가 반환한 시험 상태가 권위입니다. 브라우저 시계는 표시·보조 차단에만 씁니다. */
export const GROUP_PERMISSIONS = [
  ['VIEW_PROBLEMS', '문제 보기'], ['CREATE_PROBLEMS', '문제 만들기'],
  ['EDIT_PROBLEMS', '문제 편집'], ['REVIEW_PROBLEMS', '문제 검수'],
  ['MANAGE_MEMBERS', '회원 관리'], ['MANAGE_ROLES', '역할 관리'],
  ['MANAGE_GROUP', '그룹 설정'], ['CREATE_CONTESTS', '코테 생성'],
  ['MANAGE_CONTESTS', '코테 관리'], ['VIEW_CONTEST_RESULTS', '코테 결과 보기'],
  ['TAKE_CONTESTS', '코테 참여'],
];
export const CONTEST_STATUS = {
  UPCOMING: {label: '시작 전', tone: 'yellow'}, ACTIVE: {label: '진행 중', tone: 'green'},
  ENDED: {label: '종료', tone: 'gray'},
};
export const formatDate = value => value ? new Date(value).toLocaleString('ko-KR') : '-';
// 보호 옵션 새로고침은 현재 작성 코드를 지우지 않고, 시간창/재보기 권한 변경은 즉시 분리합니다.
export const questionAccessKey = contest => contest.status === 'ACTIVE' ? 'ACTIVE' : `${contest.status}:${Boolean(contest.replayAllowed)}`;
export function localDatetime(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '' : new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 19);
}
export function contestPayload(fields, current = null) {
  const options = Object.fromEntries(['replayAllowed', 'captureProtection', 'windowSwitchProtection'].map(key => [key, Boolean(fields[key])]));
  // 시작 후에는 ISO 문자열과 문제 순서를 그대로 보내 마이크로초·짧은 시험을 보존합니다.
  if (current && current.status !== 'UPCOMING') return {
    title: current.title, startsAt: current.startsAt, endsAt: current.endsAt,
    problemNumbers: (current.problems || []).map(problem => problem.problemNumber), ...options,
  };
  const problemNumbers = String(fields.problemNumbers || '').split(',').map(value => value.trim()).filter(Boolean).map(Number);
  if (!problemNumbers.length || problemNumbers.length > 50 || problemNumbers.some(number => !Number.isSafeInteger(number) || number < 1000) || new Set(problemNumbers).size !== problemNumbers.length)
    throw new RequestError('1000번 이상의 중복 없는 문제 번호 1~50개를 입력해 주세요.');
  const start = new Date(fields.startsAt), end = new Date(fields.endsAt);
  if (!Number.isFinite(start.getTime()) || !Number.isFinite(end.getTime()) || start >= end)
    throw new RequestError('시작·종료 시각을 확인해 주세요.');
  const title = String(fields.title || '').trim();
  if (!title || title.length > 200) throw new RequestError('제목은 1~200자로 입력해 주세요.');
  return {title, startsAt: start.toISOString(), endsAt: end.toISOString(), problemNumbers, ...options};
}
export function shouldRecordEvent(contest, type, now = Date.now()) {
  if (!contest || contest.status !== 'ACTIVE' || !Number.isFinite(new Date(contest.endsAt).getTime()) || now >= new Date(contest.endsAt).getTime()) return false;
  if (['WINDOW_BLUR', 'VISIBILITY_HIDDEN', 'FULLSCREEN_EXIT'].includes(type)) return Boolean(contest.windowSwitchProtection);
  return ['COPY_BLOCKED', 'PRINT_BLOCKED', 'CAPTURE_KEY'].includes(type) && Boolean(contest.captureProtection);
}
export function countdown(contest, now = Date.now()) {
  const end = new Date(contest.status === 'UPCOMING' ? contest.startsAt : contest.endsAt).getTime();
  const total = Math.max(0, Math.ceil((end - now) / 1000));
  return `${Math.floor(total / 3600)}:${String(Math.floor(total % 3600 / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`;
}
