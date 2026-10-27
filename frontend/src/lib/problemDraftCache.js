import {FILE_NAMES,sourceBytes} from './problemModel.js';

export const DRAFT_CACHE_KEY='cobweb.problem-drafts.v1';
export const DRAFT_TTL_MS=30*24*60*60*1000;
export const MAX_DRAFTS=50;
export const MAX_CACHE_BYTES=2*1024*1024;
const known=new Set(Object.keys(FILE_NAMES));
const ownerValid=owner=>owner==='guest'||/^user:[1-9][0-9]{0,18}$/.test(owner);
const validScope=scope=>scope&&ownerValid(scope.owner)&&Number.isSafeInteger(scope.number)&&scope.number>=1000&&typeof scope.context==='string'&&scope.context.length<=4096;
const same=(entry,scope)=>entry.owner===scope.owner&&entry.number===scope.number&&entry.context===scope.context;

// 계정명은 재사용될 수 있으므로 서버가 확인한 계정 ID만 사용합니다.
// 인증 로딩 중이거나 식별자가 불명확한 계정은 익명 저장소로 대체하지 않습니다.
export function draftOwner(user){
  if(user===null)return 'guest';
  if(typeof user?.id==='number'&&!Number.isSafeInteger(user.id))return '';
  const id=String(user?.id??'');
  return /^[1-9][0-9]{0,18}$/.test(id)?`user:${id}`:'';
}
export function draftContext(problem){
  if(!problem)return '';
  return JSON.stringify([problem.problem_title,problem.execution_mode,problem.functionAbi??null,problem.functionSignature??null]);
}
function read(now){
  const storage=window.localStorage,raw=storage.getItem(DRAFT_CACHE_KEY);
  if(!raw)return {storage,entries:[]};
  if(raw.length>MAX_CACHE_BYTES||sourceBytes(raw)>MAX_CACHE_BYTES)return {storage,entries:[]};
  let value;
  try{value=JSON.parse(raw);}catch{return {storage,entries:[]};}
  const entries=Array.isArray(value?.entries)&&value.version===1?value.entries:[];
  return {storage,entries:entries.slice(-MAX_DRAFTS).filter(entry=>validScope(entry)&&known.has(entry.language)&&typeof entry.code==='string'&&sourceBytes(entry.code)<=65536&&Number.isSafeInteger(entry.savedAt)&&entry.savedAt<=now&&now-entry.savedAt<DRAFT_TTL_MS)};
}
function write(storage,entries){
  const retained=entries.slice(-MAX_DRAFTS);
  let encoded=JSON.stringify({version:1,entries:retained});
  while(retained.length&&sourceBytes(encoded)>MAX_CACHE_BYTES){retained.shift();encoded=JSON.stringify({version:1,entries:retained});}
  if(retained.length)storage.setItem(DRAFT_CACHE_KEY,encoded);
  else storage.removeItem(DRAFT_CACHE_KEY);
}
export function loadProblemDrafts(scope,languages,now=Date.now()){
  if(!validScope(scope))return {codes:{},available:false};
  try{
    const {storage,entries}=read(now),codes={};
    for(const entry of entries)if(same(entry,scope)&&languages.includes(entry.language))codes[entry.language]=entry.code;
    // 손상·만료된 항목은 이 앱의 키에서만 정리합니다. 다른 앱 데이터는 건드리지 않습니다.
    write(storage,entries);
    return {codes,available:true};
  }catch{return {codes:{},available:false};}
}
export function saveProblemDraft(scope,language,code,now=Date.now()){
  if(!validScope(scope)||!known.has(language)||typeof code!=='string')return {ok:false,reason:'invalid'};
  if(sourceBytes(code)>65536)return {ok:false,reason:'size'};
  try{
    const {storage,entries}=read(now);
    const next=entries.filter(entry=>!(entry.owner===scope.owner&&entry.number===scope.number&&entry.language===language));
    next.push({...scope,language,code,savedAt:now});write(storage,next);
    return {ok:true};
  }catch{return {ok:false,reason:'storage'};}
}
export function removeProblemDraft(scope,language,now=Date.now()){
  if(!validScope(scope))return false;
  try{
    const {storage,entries}=read(now);
    write(storage,entries.filter(entry=>!(entry.owner===scope.owner&&entry.number===scope.number&&entry.language===language)));
    return true;
  }catch{return false;}
}
