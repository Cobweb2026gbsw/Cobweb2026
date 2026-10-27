export const CATEGORIES = {
  HARDWARE: { label: '하드웨어', tone: 'blue' }, SOFTWARE: { label: '소프트웨어', tone: 'mint' },
  NEWS: { label: '최신 정보', tone: 'yellow' }, PROBLEM_QNA: { label: '문제 질문', tone: 'blue' },
  MAJOR_QNA: { label: '전공 질문', tone: 'green' }, GENERAL: { label: '자유', tone: 'gray' }, NOTICE: { label: '공지', tone: 'mint' },
};
export const displayDate = value => value ? new Date(value).toLocaleString('ko-KR') : '—';
