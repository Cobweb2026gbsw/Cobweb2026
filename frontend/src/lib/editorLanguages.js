import {StreamLanguage,HighlightStyle,syntaxHighlighting,syntaxTree} from '@codemirror/language';
import {snippetCompletion} from '@codemirror/autocomplete';
import {tags} from '@lezer/highlight';
import {python} from '@codemirror/lang-python';
import {javascript} from '@codemirror/lang-javascript';
import {java} from '@codemirror/lang-java';
import {cpp} from '@codemirror/lang-cpp';
import {go} from '@codemirror/lang-go';
import {rust} from '@codemirror/lang-rust';
import {sql,SQLite} from '@codemirror/lang-sql';
import {php} from '@codemirror/lang-php';
import {csharp,kotlin,dart} from '@codemirror/legacy-modes/mode/clike';
import {pascal} from '@codemirror/legacy-modes/mode/pascal';
import {ruby} from '@codemirror/legacy-modes/mode/ruby';
import {EDITOR_CATALOG,LANGUAGE_IDS} from './editorCatalog';

export {EDITOR_CATALOG,LANGUAGE_IDS};

// LLVM IR과 엄랭에도 같은 편집기 기능을 연결하지만 범용 C 계열 문법으로 잘못
// 해석하지 않습니다. 엄랭의 구두점은 연산자이므로 문자열/주석 규칙을 붙이지 않습니다.
const llvmWords=new Set(EDITOR_CATALOG.LLVM_IR.keywords);
const llvmParser={
  name:'llvm-ir',startState:()=>({depth:0}),
  token(stream,state){
    if(stream.eatSpace())return null;
    if(stream.match(';')){stream.skipToEnd();return 'comment';}
    if(stream.match('"')){let escaped=false,c;while((c=stream.next())!=null){if(c==='"'&&!escaped)break;escaped=!escaped&&c==='\\';}return 'string';}
    if(stream.match(/[%@](?:[-\w.$]+|"(?:[^"\\]|\\.)*")/))return 'variableName';
    if(stream.match(/![-\w.]+/))return 'meta';
    if(stream.match(/i[0-9]+\b/))return 'typeName';
    if(stream.match(/[-+]?(?:0x[\da-f]+|\d+(?:\.\d*)?(?:e[-+]?\d+)?)/i))return 'number';
    if(stream.match(/[a-zA-Z_][-\w.]*/))return llvmWords.has(stream.current())?'keyword':'variableName';
    const c=stream.next();if(c==='{')state.depth++;if(c==='}')state.depth=Math.max(0,state.depth-1);
    return /[{}()[\],:]/.test(c)?'punctuation':'operator';
  },
  indent:(state,after,context)=>Math.max(0,state.depth-(/^\s*}/.test(after)?1:0))*context.unit,
  languageData:{commentTokens:{line:';'},closeBrackets:{brackets:['(','[','{','"']}}
};
const umjunsikParser={
  name:'umjunsik',startState:()=>({}),
  token(stream){
    if(stream.eatSpace())return null;
    if(stream.match(/어떻게|이 사람이름이냐ㅋㅋ|화이팅!|동탄|준/))return 'keyword';
    if(stream.match(/식[!?ㅋ]?|엄/))return 'builtin';
    if(stream.match(/어+/))return 'variableName';
    if(stream.match(/[.,]+/))return 'number';
    stream.next();return 'operator';
  },indent:()=>0,languageData:{closeBrackets:{brackets:[]}}
};
// legacy Pascal 모드는 강조만 제공하므로 begin/end 블록에 한해 들여쓰기를 보완합니다.
const pascalWithIndent={...pascal,startState:()=>({...pascal.startState(),depth:0}),
  token(stream,state){const style=pascal.token(stream,state),word=stream.current().toLowerCase();
    if(style==='keyword'&&/^(begin|record|repeat|try|case|asm)$/.test(word))state.depth++;
    if(style==='keyword'&&/^(end|until)$/.test(word))state.depth=Math.max(0,state.depth-1);
    return style;},
  indent:(state,after,context)=>Math.max(0,state.depth-(/^\s*(end|until)\b/i.test(after)?1:0))*context.unit
};

const supports={
  PYTHON:()=>python(),C:()=>cpp(),CPP:()=>cpp(),JAVA:()=>java(),
  JAVASCRIPT:()=>javascript(),TYPESCRIPT:()=>javascript({typescript:true}),
  CSHARP:()=>StreamLanguage.define(csharp),GO:()=>go(),RUST:()=>rust(),
  KOTLIN:()=>StreamLanguage.define(kotlin),SQL:()=>sql({dialect:SQLite}),
  RUBY:()=>StreamLanguage.define(ruby),LLVM_IR:()=>StreamLanguage.define(llvmParser),
  PASCAL:()=>StreamLanguage.define(pascalWithIndent),PHP:()=>php({plain:true}),
  DART:()=>StreamLanguage.define(dart),UMJUNSIK:()=>StreamLanguage.define(umjunsikParser)
};
const supportCache=new Map();
export function languageExtension(language){
  if(!supportCache.has(language))supportCache.set(language,supports[language]?.()||[]);
  return supportCache.get(language);
}

export const editorHighlight=syntaxHighlighting(HighlightStyle.define([
  {tag:[tags.keyword,tags.modifier,tags.controlKeyword],class:'cw-syntax-keyword'},
  {tag:[tags.string,tags.special(tags.string),tags.character],class:'cw-syntax-string'},
  {tag:[tags.comment],class:'cw-syntax-comment'},
  {tag:[tags.number,tags.bool,tags.null],class:'cw-syntax-number'},
  {tag:[tags.typeName,tags.className],class:'cw-syntax-type'},
  {tag:[tags.function(tags.variableName),tags.function(tags.propertyName)],class:'cw-syntax-function'},
  {tag:[tags.operator,tags.punctuation],class:'cw-syntax-operator'},
  {tag:[tags.meta,tags.processingInstruction],class:'cw-syntax-meta'}
]));

export function insideCommentOrString(state,pos){
  for(let node=syntaxTree(state).resolveInner(pos,-1);node;node=node.parent)
    if(/comment|string|charliteral/i.test(node.name))return true;
  return false;
}
const optionCache=new Map();
export function completionOptions(language){
  if(!optionCache.has(language)){
    const data=EDITOR_CATALOG[language];if(!data)return [];
    optionCache.set(language,[
      ...data.keywords.map(label=>({label,type:'keyword',detail:'언어 키워드'})),
      ...data.builtins.map(label=>({label,type:'function',detail:'주요 내장 이름'})),
      ...data.snippets.map(item=>snippetCompletion(item.template,{label:item.label,type:'snippet',detail:item.detail||'문법 틀',boost:2}))
    ]);
  }
  return optionCache.get(language);
}

// 서버·다른 편집기의 단어를 섞지 않습니다. 현재 문서의 식별자만 추가합니다.
export function completionSource(language){
  const fixed=completionOptions(language);
  return context=>{
    if(context.state.readOnly||insideCommentOrString(context.state,context.pos))return null;
    const word=context.matchBefore(/[\p{L}\p{N}_$@%.!?ㅋ]+/u);
    if(!context.explicit&&(!word||word.from===word.to))return null;
    const options=[...fixed],seen=new Set(fixed.map(option=>option.label));
    const text=context.state.doc.toString();
    for(const match of text.matchAll(/[\p{L}_$][\p{L}\p{N}_$]*/gu)){
      // 입력 중인 부분 단어 자체를 추천하면 ret가 return보다 앞에 놓여
      // Enter가 아무 일도 하지 않는 후보를 선택하게 됩니다. 고정 언어 항목은 유지합니다.
      const label=match[0];if(label.length>1&&label!==word?.text&&!seen.has(label)){seen.add(label);options.push({label,type:'variable',detail:'현재 코드'});}
      if(options.length>=fixed.length+200)break;
    }
    return {from:word?.from??context.pos,options,validFor:/^[\p{L}\p{N}_$@%.!?ㅋ]*$/u};
  };
}
