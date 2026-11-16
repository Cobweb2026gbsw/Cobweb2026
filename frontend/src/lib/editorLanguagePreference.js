import {FILE_NAMES} from './problemModel.js';

export const LAST_LANGUAGE_KEY='cobweb.editor.language.v1';
const known=new Set(Object.keys(FILE_NAMES));

// 브라우저에는 언어 ID만 남깁니다. 코드·문제·계정 정보는 저장하지 않습니다.
export function preferredLanguage(allowed=[],current=''){
  if(allowed.includes(current))return current;
  try{const saved=window.localStorage.getItem(LAST_LANGUAGE_KEY);if(known.has(saved)&&allowed.includes(saved))return saved;}catch{/* 저장소가 막혀 있으면 문제의 첫 허용 언어를 사용합니다. */}
  return allowed[0]||'';
}
export function rememberLanguage(language){
  if(!known.has(language))return;
  try{window.localStorage.setItem(LAST_LANGUAGE_KEY,language);}catch{/* 언어 선택 자체는 계속 동작합니다. */}
}
