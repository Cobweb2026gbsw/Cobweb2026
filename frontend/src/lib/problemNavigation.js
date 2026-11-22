import {RANKS} from './problemModel.js';

export const RANK_NAMES = {INITIATE:'입문',SKILLED:'기초',ELITE:'중급',EXPERT:'고급',LEGEND:'심화',MYTHIC:'최상급',ABSOLUTE:'최고 난도'};
export const LEVELS = [5,4,3,2,1];
export const validProblemNumber = value => /^\d+$/.test(String(value)) && Number.isSafeInteger(Number(value)) && Number(value)>0 && Number(value)<=2147483647;

export function problemSearchTarget(value){
  const query=value.trim();
  return validProblemNumber(query)?`/problems/${Number(query)}`:query?'/problems?q='+encodeURIComponent(query):'/problems';
}

export function rankProblemsUrl(rank){
  const params=new URLSearchParams();
  if(RANKS.includes(rank))for(const level of LEVELS)params.append('difficulty',`${rank}:${level}`);
  return '/problems'+(params.size?'?'+params:'');
}

// Only list URLs can become a return destination; never accept an arbitrary redirect.
export function problemListUrl(value){
  return typeof value==='string' && value.length<=4096 && /^\/problems(?:\?[^#]*)?$/.test(value) && !/[\\\u0000-\u001f\u007f]/.test(value)?value:'/problems';
}

const chapterList = /^\/steps\?chapter=(?:[45]-[1-5]|6-[1-4])$/;

function returnOrigin(value) {
  if (value == null) return null;
  return typeof value === 'string' && chapterList.test(value) ? value : problemListUrl(value);
}

export function problemUrl(number, from) {
  const path = `/problems/${Number(number)}`;
  const origin = returnOrigin(from);
  return origin ? path + '?' + new URLSearchParams({from: origin}) : path;
}

export function problemQuestionsUrl(number, from) {
  const params = new URLSearchParams({problemNumber: number});
  const origin = returnOrigin(from);
  if (origin) params.set('list', origin);
  return '/forum?' + params;
}

export function problemTrail(location, number) {
  // Older history entries kept the chapter in router state only.
  const savedChapter = '/steps?chapter=' + location.state?.chapterReturn;
  const origin = returnOrigin(new URLSearchParams(location.search).get('from') ??
    (chapterList.test(savedChapter) ? savedChapter : null));
  const from = origin ?? '/problems';
  const fromChapter = chapterList.test(from);
  const trail = location.state?.problemTrail;
  const items = origin && !fromChapter && trail?.from === from && Array.isArray(trail.items)
    ? trail.items.filter(validProblemNumber).slice(0, 30).map(Number) : [];
  const index = items.indexOf(Number(number));
  return {
    from, origin, fromChapter, byChapter: !origin || fromChapter,
    next: index >= 0 ? items[index + 1] : undefined,
    state: {problemTrail: {from, items}},
  };
}

export function neighborRequest(number,from,chapter){
  const filters=new URL(from,'http://localhost').searchParams;
  const params=new URLSearchParams({number:String(number),chapter:String(chapter)});
  if(!chapter){
    params.set('search',filters.get('q')||'');
    params.set('solved',filters.get('solved')||'all');
    params.set('sort',filters.get('sort')==='submits'?'submits':'number');
    for(const value of filters.getAll('difficulty'))params.append('difficulty',value);
  }
  return '/api/problems/next?'+params;
}

export function neighborDestination(neighbor,from,chapter){
  if(!neighbor||!validProblemNumber(neighbor.number)||!Number.isSafeInteger(neighbor.page)||neighbor.page<0)return null;
  if(chapter)return problemUrl(neighbor.number, chapterList.test(from) ? from : null);
  const list=new URL(from,'http://localhost');
  if(neighbor.page)list.searchParams.set('page',String(neighbor.page));else list.searchParams.delete('page');
  return problemUrl(neighbor.number,list.pathname+list.search);
}

const recentKey=owner=>'cobweb.recent-problem.v1:'+owner;
export function rememberProblem(owner,number,from){
  if(!owner||!validProblemNumber(number))return;
  try{window.localStorage.setItem(recentKey(owner),JSON.stringify({number:Number(number),from:returnOrigin(from)}));}catch{}
}
export function recentProblem(owner){
  if(!owner)return null;
  try{const value=JSON.parse(window.localStorage.getItem(recentKey(owner)));return validProblemNumber(value?.number)?{number:value.number,url:problemUrl(value.number,value.from)}:null;}catch{return null;}
}
