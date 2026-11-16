import {useLayoutEffect,useRef,useState} from 'react';
import {Compartment,EditorState,Prec,Transaction} from '@codemirror/state';
import {EditorView,keymap,lineNumbers,highlightActiveLine,highlightActiveLineGutter,drawSelection} from '@codemirror/view';
import {defaultKeymap,history,historyKeymap,indentLess,indentMore,insertNewline,insertNewlineAndIndent} from '@codemirror/commands';
import {indentUnit,indentOnInput,bracketMatching} from '@codemirror/language';
import {autocompletion,closeBrackets,closeBracketsKeymap,completionKeymap,acceptCompletion} from '@codemirror/autocomplete';
import {languageExtension,editorHighlight,completionSource} from '../lib/editorLanguages';
import Icon from './Icon';
import '../pages/ProblemDetail.css';
import './ProblemComponents.css';
import './CodeEditor.css';

export const EDITOR_PREFERENCES_KEY='cobweb.editor.preferences.v1';
const DEFAULTS=Object.freeze({suggestions:true,brackets:true,indentation:true,highlight:true});
const DEFAULT_FONT_SIZE=13,MIN_FONT_SIZE=10,MAX_FONT_SIZE=32;
const FONT_SIZES=Array.from({length:MAX_FONT_SIZE-MIN_FONT_SIZE+1},(_,index)=>MIN_FONT_SIZE+index);
function safeFontSize(value){return typeof value==='number'&&Number.isInteger(value)?Math.max(MIN_FONT_SIZE,Math.min(MAX_FONT_SIZE,value)):DEFAULT_FONT_SIZE;}
function loadPreferences(){
  try {const saved=JSON.parse(window.localStorage.getItem(EDITOR_PREFERENCES_KEY));return {...Object.fromEntries(Object.entries(DEFAULTS).map(([key,value])=>[key,typeof saved?.[key]==='boolean'?saved[key]:value])),fontSize:safeFontSize(saved?.fontSize)};}
  catch{return {...DEFAULTS,fontSize:DEFAULT_FONT_SIZE};}
}
const settingsLabels={suggestions:'문법 추천',brackets:'괄호 자동 닫기',indentation:'자동 들여쓰기',highlight:'문법 강조'};

// 소스는 controlled value와 현재 EditorView에만 두며, 저장소에는 표시 설정만 둡니다.
export default function CodeEditor({value='',onChange,language='',fileName='',rows=18,id,disabled=false,readOnly=false,required=false,allowCopy=true,'aria-label':ariaLabel='풀이 코드',...rest}){
  const host=useRef(null),viewRef=useRef(null),changeRef=useRef(onChange),syncing=useRef(false),releaseTab=useRef(false),imeKey=useRef(false);
  const preferencesRef=useRef(null),settingsCompartment=useRef(new Compartment()),editableCompartment=useRef(new Compartment());
  const [preferences,setPreferences]=useState(loadPreferences),[cursor,setCursor]=useState({ln:1,col:1}),[validation,setValidation]=useState('');
  const [copyNotice,setCopyNotice]=useState(''),copyAlive=useRef(false),copyRequest=useRef(0);
  useLayoutEffect(()=>{copyAlive.current=true;return()=>{copyAlive.current=false;copyRequest.current++;};},[]);
  useLayoutEffect(()=>{copyRequest.current++;setCopyNotice('');},[value,language,disabled]);
  changeRef.current=onChange;preferencesRef.current=preferences;
  const blocked=disabled||readOnly;
  function accessibility(){
    const attributes={'aria-label':ariaLabel,'aria-multiline':'true','aria-disabled':String(disabled),'aria-readonly':String(blocked),'aria-required':String(required),'data-language':language,spellcheck:'false',autocapitalize:'off',autocomplete:'off',autocorrect:'off'};
    if(id)attributes.id=id;
    for(const [key,setting] of Object.entries(rest))if((key.startsWith('aria-')||key.startsWith('data-'))&&setting!=null)attributes[key]=String(setting);
    return [EditorState.readOnly.of(blocked),EditorView.editable.of(!blocked),EditorView.contentAttributes.of(attributes)];
  }
  function settings(config){
    return [
      config.highlight?editorHighlight:[],
      config.brackets?[closeBrackets(),keymap.of(closeBracketsKeymap),bracketMatching()]:[],
      config.suggestions?autocompletion({override:[completionSource(language)],activateOnTyping:true,maxRenderedOptions:30,defaultKeymap:false,icons:true}):[],
      Prec.high(keymap.of([{key:'Enter',run:editor=>!imeKey.current&&((config.suggestions&&acceptCompletion(editor))||(config.indentation?insertNewlineAndIndent:insertNewline)(editor))}])),
      config.indentation?indentOnInput():[]
    ];
  }
  useLayoutEffect(()=>{
    let alive=true;
    const view=new EditorView({parent:host.current,state:EditorState.create({doc:value,extensions:[
      languageExtension(language),indentUnit.of('    '),EditorState.tabSize.of(4),
      lineNumbers(),highlightActiveLine(),highlightActiveLineGutter(),drawSelection(),history(),
      settingsCompartment.current.of(settings(preferencesRef.current)),editableCompartment.current.of(accessibility()),
      EditorView.domEventObservers({keydown(event){
        imeKey.current=event.isComposing||event.keyCode===229;
        if(imeKey.current)return false;
        if(event.key==='Escape'){releaseTab.current=true;return false;}
        if(event.key!=='Tab')releaseTab.current=false;
        return false;
      }}),
      keymap.of([
        {key:'Tab',run:editor=>{if(imeKey.current)return false;if(releaseTab.current){releaseTab.current=false;return false;}if(editor.state.readOnly)return false;if(!editor.state.selection.main.empty)return indentMore(editor);editor.dispatch(editor.state.replaceSelection('    '),{scrollIntoView:true,userEvent:'input'});return true;}},
        {key:'Shift-Tab',run:editor=>{if(imeKey.current)return false;if(releaseTab.current){releaseTab.current=false;return false;}return indentLess(editor);}},
        ...completionKeymap.filter(binding=>!['Enter','Tab','Shift-Tab'].includes(binding.key)),
        ...defaultKeymap.filter(binding=>!['Enter','Shift-Enter','Tab','Shift-Tab'].includes(binding.key)),
        ...historyKeymap
      ]),
      EditorView.updateListener.of(update=>{
        if(!alive)return;
        if(update.docChanged&&!syncing.current){setValidation('');changeRef.current?.(update.state.doc.toString());}
        if(update.docChanged||update.selectionSet){const head=update.state.selection.main.head,line=update.state.doc.lineAt(head);setCursor({ln:line.number,col:head-line.from+1});}
      })
    ]})});
    viewRef.current=view;releaseTab.current=false;setCursor({ln:1,col:1});
    return ()=>{alive=false;viewRef.current=null;view.destroy();};
    // 언어 변경은 새 문서 세션입니다. undo로 이전 언어의 답안을 되살리지 않습니다.
  },[language]);
  useLayoutEffect(()=>{
    const view=viewRef.current;if(!view)return;
    view.dispatch({effects:[settingsCompartment.current.reconfigure(settings(preferences)),editableCompartment.current.reconfigure(accessibility())]});
  },[preferences,disabled,readOnly,required,id,ariaLabel]);
  // 글자 크기만 바꿀 때 문서·커서·undo를 유지하고 줄 높이와 스크롤을 다시 계산합니다.
  useLayoutEffect(()=>{viewRef.current?.requestMeasure();},[preferences.fontSize,language]);
  useLayoutEffect(()=>{
    const view=viewRef.current;if(!view||view.state.doc.toString()===value)return;
    syncing.current=true;
    try{view.dispatch({changes:{from:0,to:view.state.doc.length,insert:value},selection:{anchor:0},annotations:Transaction.addToHistory.of(false)});}
    finally{syncing.current=false;}
  },[value,language]);
  useLayoutEffect(()=>{
    const update=()=>setPreferences(loadPreferences()),storage=event=>{if(event.key===EDITOR_PREFERENCES_KEY||event.key===null)update();};
    window.addEventListener('cobweb-editor-preferences',update);window.addEventListener('storage',storage);
    return ()=>{window.removeEventListener('cobweb-editor-preferences',update);window.removeEventListener('storage',storage);};
  },[]);
  useLayoutEffect(()=>{
    const form=host.current?.closest('form');if(!form)return;
    const validate=event=>{if(required&&!blocked&&!viewRef.current?.state.doc.toString().trim()){event.preventDefault();event.stopImmediatePropagation();setValidation('코드를 입력하세요.');viewRef.current?.focus();}};
    form.addEventListener('submit',validate,true);return ()=>form.removeEventListener('submit',validate,true);
  },[required,blocked]);
  function savePreferences(next){
    setPreferences(next);
    try{window.localStorage.setItem(EDITOR_PREFERENCES_KEY,JSON.stringify(next));window.dispatchEvent(new Event('cobweb-editor-preferences'));}catch{/* 저장이 막힌 환경에서도 현재 설정은 동작합니다. */}
  }
  function toggle(key){savePreferences({...preferences,[key]:!preferences[key]});}
  async function copy(){
    if(disabled)return;
    const request=++copyRequest.current;
    const notice=text=>{if(copyAlive.current&&request===copyRequest.current)setCopyNotice(text);};
    setCopyNotice('');
    if(typeof navigator.clipboard?.writeText!=='function'){notice('이 환경에서는 코드 복사를 지원하지 않습니다. 직접 선택해 복사해주세요.');return;}
    try{await navigator.clipboard.writeText(value);notice('코드를 복사했습니다.');}
    catch{notice('코드를 복사하지 못했습니다. 브라우저 권한을 확인하거나 직접 선택해 복사해주세요.');}
  }
  return <div className="pd-editor cw-rich-editor" style={{'--cw-editor-font-size':`${preferences.fontSize}px`}}>
    <div className="pd-editor-bar"><span className="cw-mono">{fileName||language}</span><div className="cw-editor-actions">
      <label className="cw-editor-font-size">글자 크기<select value={preferences.fontSize} disabled={disabled} onChange={event=>savePreferences({...preferences,fontSize:safeFontSize(Number(event.target.value))})}>{FONT_SIZES.map(size=><option key={size} value={size}>{size}px</option>)}</select></label>
      {allowCopy&&<button type="button" className="pd-icon-btn" aria-label="코드 복사" disabled={disabled} onClick={copy}><Icon name="copy"/></button>}
      <details className="cw-editor-settings"><summary>편집기 설정</summary><div className="cw-editor-options" role="group" aria-label="코드 편집기 설정">{Object.entries(settingsLabels).map(([key,label])=><label key={key}><input type="checkbox" checked={preferences[key]} disabled={disabled} onChange={()=>toggle(key)}/>{label}</label>)}<small>언어 키워드·문법 틀·현재 코드의 이름을 Ctrl+Space로 추천합니다.</small></div></details>
    </div></div>
    <div className="cw-editor-host" ref={host} style={{height:Math.min(520,Math.max(180,rows*22+32))}}/>
    {validation&&<p className="cw-editor-validation" role="alert">{validation}</p>}
    {copyNotice&&<p className="cw-editor-validation" role="status">{copyNotice}</p>}
    <div className="pd-editor-status cw-mono"><span>Ln {cursor.ln}, Col {cursor.col}</span></div>
  </div>;
}
