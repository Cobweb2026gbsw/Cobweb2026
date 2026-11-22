import {validProblemNumber} from './problemNavigation.js';

const positiveId=value=>Number.isSafeInteger(Number(value))&&Number(value)>0;
export function submissionListUrl(value){
  return typeof value==='string'&&value.length<=4096&&/^\/submissions(?:\?[^#]*)?$/.test(value)&&!/[\\\u0000-\u001f\u007f]/.test(value)?value:'/submissions';
}
export function submissionResultUrl(id,from){
  return `/submissions/${id}?`+new URLSearchParams({from:submissionListUrl(from)});
}
export function submissionProblemLink(submission,fallback,origin){
  if(!submission)return null;
  if(submission.contest_id!=null){
    return positiveId(submission.contest_id)&&positiveId(submission.contestGroupId)
      ? {to:`/groups/${submission.contestGroupId}/contests/${submission.contest_id}`,label:'코딩테스트로 돌아가기'}:null;
  }
  const number=submission.problemNumber??fallback;
  if(!validProblemNumber(number))return null;
  const path = positiveId(submission.groupId)
    ? `/groups/${submission.groupId}/problems/${Number(number)}`
    : `/problems/${Number(number)}`;
  const label = '문제로 돌아가기';
  if (origin?.pathname === path && typeof origin.search === 'string' &&
      (origin.search === '' || origin.search.startsWith('?'))) {
    return {to: path + origin.search, state: origin.state, label};
  }
  return {to: path, label};
}
