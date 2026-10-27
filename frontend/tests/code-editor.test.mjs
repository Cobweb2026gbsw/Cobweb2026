import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {readFileSync} from 'node:fs';
import {build} from 'esbuild';
import {JSDOM} from 'jsdom';
import {installEditorDom,editorView,editorValue,setEditorValue,selectEditor,inputEditorText} from './editor-dom.mjs';
import {EDITOR_CATALOG} from '../src/lib/editorCatalog.js';

const require=createRequire(import.meta.url),root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const dom=new JSDOM('<!doctype html><html><body></body></html>',{url:'http://localhost:8080/'});
Object.defineProperty(globalThis,'navigator',{value:dom.window.navigator,configurable:true});
for(const key of ['window','document','HTMLElement','Event','InputEvent','KeyboardEvent','MutationObserver'])globalThis[key]=dom.window[key];
installEditorDom(dom.window);globalThis.IS_REACT_ACT_ENVIRONMENT=true;
const React=require('react'),{render,fireEvent,cleanup,act}=require('@testing-library/react');
const {undo}=require('@codemirror/commands'),{startCompletion,currentCompletions,insertBracket}=require('@codemirror/autocomplete');
const result=await build({entryPoints:[path.join(root,'src/components/CodeEditor.jsx')],bundle:true,write:false,platform:'node',format:'cjs',packages:'external',jsx:'automatic',loader:{'.css':'empty'}});
const module={exports:{}};new Function('require','module','exports',result.outputFiles[0].text)(require,module,module.exports);
const Editor=module.exports.default,preferenceKey=module.exports.EDITOR_PREFERENCES_KEY;
function Harness({initial='',...props}){const [value,setValue]=React.useState(initial);return React.createElement(Editor,{...props,value,onChange:setValue});}
function start(initial='',language='PYTHON',props={}){cleanup();window.localStorage.clear();return render(React.createElement(Harness,{initial,language,...props}));}
function press(area,key,extra={}){act(()=>fireEvent.keyDown(area,{key,...extra}));}
const tick=()=>new Promise(resolve=>setTimeout(resolve,100));

test('clipboard capability and permission failures produce safe feedback without losing source',async()=>{
  const descriptor=Object.getOwnPropertyDescriptor(navigator,'clipboard');
  try{
    Object.defineProperty(navigator,'clipboard',{value:undefined,configurable:true});
    let view=start('private-copy-source');
    fireEvent.click(view.getByRole('button',{name:'코드 복사'}));
    assert.match(view.getByRole('status').textContent,/지원하지 않습니다/);assert.equal(editorValue(view.getByRole('textbox')),'private-copy-source');
    Object.defineProperty(navigator,'clipboard',{value:{writeText:async()=>{throw new Error('private-permission-detail');}},configurable:true});
    await act(async()=>fireEvent.click(view.getByRole('button',{name:'코드 복사'})));
    assert.match(view.getByRole('status').textContent,/권한을 확인/);assert.doesNotMatch(view.getByRole('status').textContent,/private/);
    let copied;Object.defineProperty(navigator,'clipboard',{value:{writeText:async value=>{copied=value;}},configurable:true});
    await act(async()=>fireEvent.click(view.getByRole('button',{name:'코드 복사'})));
    assert.equal(copied,'private-copy-source');assert.equal(view.getByRole('status').textContent,'코드를 복사했습니다.');
  }finally{if(descriptor)Object.defineProperty(navigator,'clipboard',descriptor);else delete navigator.clipboard;cleanup();}
});

test('late clipboard results cannot update a replaced language or source',async()=>{
  const descriptor=Object.getOwnPropertyDescriptor(navigator,'clipboard');let complete;
  try{
    Object.defineProperty(navigator,'clipboard',{value:{writeText:()=>new Promise(resolve=>{complete=resolve;})},configurable:true});
    const view=start('old-private-source');fireEvent.click(view.getByRole('button',{name:'코드 복사'}));
    view.rerender(React.createElement(Editor,{value:'new-language-source',language:'DART'}));
    await act(async()=>complete());
    assert.equal(view.queryByRole('status')===null,true);assert.equal(editorValue(view.getByRole('textbox')),'new-language-source');
  }finally{if(descriptor)Object.defineProperty(navigator,'clipboard',descriptor);else delete navigator.clipboard;cleanup();}
});

test('real editor indents conditionally, inserts exactly four spaces, and escapes Tab',()=>{
  const view=start('def sol():');const area=view.getByRole('textbox');act(()=>selectEditor(area,editorValue(area).length));
  press(area,'Enter');assert.equal(editorValue(area),'def sol():\n    ');
  press(area,'Tab');assert.equal(editorValue(area),'def sol():\n        ');
  press(area,'Tab',{shiftKey:true});assert.equal(editorValue(area),'def sol():\n    ');
  press(area,'Escape');let allowed;act(()=>{allowed=fireEvent.keyDown(area,{key:'Tab'});});assert.equal(allowed,true);assert.equal(editorValue(area),'def sol():\n    ');
  const unchanged=editorValue(area);let nativeAllowed;act(()=>{nativeAllowed=fireEvent.keyDown(area,{key:'Enter',isComposing:true,keyCode:229});});assert.equal(editorValue(area),unchanged);assert.equal(nativeAllowed,true);
  cleanup();
});
test('Enter does not treat Python comment or string colons as blocks',()=>{
  const view=start('# if fake:');const area=view.getByRole('textbox');act(()=>selectEditor(area,editorValue(area).length));press(area,'Enter');assert.equal(editorValue(area),'# if fake:\n');
  act(()=>{setEditorValue(area,'value = "not a block:"');selectEditor(area,editorValue(area).length);});press(area,'Enter');assert.equal(editorValue(area),'value = "not a block:"\n');cleanup();
});
test('preferences persist display settings only, synchronize editors, and disable indentation/highlighting',()=>{
  cleanup();window.localStorage.clear();const view=render(React.createElement('div',null,React.createElement(Harness,{initial:'def sol():',language:'PYTHON','aria-label':'one'}),React.createElement(Harness,{initial:'return 1',language:'PYTHON','aria-label':'two'})));
  assert.ok(view.container.querySelectorAll('.cw-syntax-keyword').length>0);
  fireEvent.click(view.getAllByText('편집기 설정')[0]);fireEvent.click(view.getAllByRole('checkbox',{name:'문법 강조'})[0]);
  assert.equal(view.container.querySelectorAll('.cw-syntax-keyword').length,0);assert.equal(view.getAllByRole('checkbox',{name:'문법 강조'})[1].checked,false);
  fireEvent.click(view.getAllByRole('checkbox',{name:'자동 들여쓰기'})[0]);const area=view.getByRole('textbox',{name:'one'});act(()=>selectEditor(area,editorValue(area).length));press(area,'Enter');assert.equal(editorValue(area),'def sol():\n');
  const saved=JSON.parse(window.localStorage.getItem(preferenceKey));assert.deepEqual(Object.keys(saved).sort(),['brackets','fontSize','highlight','indentation','suggestions']);assert.equal(['brackets','highlight','indentation','suggestions'].every(key=>typeof saved[key]==='boolean'),true);assert.equal(saved.fontSize,13);assert.doesNotMatch(window.localStorage.getItem(preferenceKey),/def|return/);cleanup();
});
test('font size changes synchronize editors, persist, and preserve source, selection and undo',()=>{
  cleanup();window.localStorage.clear();const view=render(React.createElement('div',null,React.createElement(Harness,{initial:'private source',language:'PYTHON','aria-label':'one'}),React.createElement(Harness,{initial:'second source',language:'PYTHON','aria-label':'two'})));
  const [first,second]=view.getAllByRole('combobox',{name:'글자 크기'}),area=view.getByRole('textbox',{name:'one'}),originalView=editorView(area);
  assert.equal(first.value,'13');assert.equal(first.options[0].value,'10');assert.equal(first.options[first.options.length-1].value,'32');
  act(()=>{setEditorValue(area,'edited private source');selectEditor(area,3,8);});
  fireEvent.change(first,{target:{value:'20'}});
  assert.equal(editorView(area),originalView);assert.equal(editorValue(area),'edited private source');assert.equal(originalView.state.selection.main.from,3);assert.equal(originalView.state.selection.main.to,8);
  assert.equal(second.value,'20');for(const editor of view.container.querySelectorAll('.cw-rich-editor'))assert.equal(editor.style.getPropertyValue('--cw-editor-font-size'),'20px');
  assert.equal(editorValue(view.getByRole('textbox',{name:'two'})),'second source');
  act(()=>undo(originalView));assert.equal(editorValue(area),'private source');
  const saved=window.localStorage.getItem(preferenceKey);assert.equal(JSON.parse(saved).fontSize,20);assert.doesNotMatch(saved,/private|source/);
  cleanup();const reopened=render(React.createElement(Editor,{value:'fresh document',language:'PYTHON'}));assert.equal(reopened.getByRole('combobox',{name:'글자 크기'}).value,'20');cleanup();
});
test('old or malformed font preferences fall back safely and integer sizes are bounded',()=>{
  for(const [saved,expected] of [['invalid json',13],[JSON.stringify({highlight:false}),13],[JSON.stringify({fontSize:'24px'}),13],[JSON.stringify({fontSize:17.5}),13],[JSON.stringify({fontSize:0}),10],[JSON.stringify({fontSize:900}),32],[JSON.stringify({fontSize:18}),18]]){
    cleanup();window.localStorage.setItem(preferenceKey,saved);const view=render(React.createElement(Editor,{value:'',language:'PYTHON'}));assert.equal(view.getByRole('combobox',{name:'글자 크기'}).value,String(expected),saved);cleanup();
  }
  window.localStorage.clear();
});
test('font size follows cross-tab settings updates and local preference clearing',()=>{
  const view=start('preserved');
  act(()=>{window.localStorage.setItem(preferenceKey,JSON.stringify({fontSize:24}));window.dispatchEvent(new dom.window.StorageEvent('storage',{key:preferenceKey}));});
  assert.equal(view.getByRole('combobox',{name:'글자 크기'}).value,'24');assert.equal(editorValue(view.getByRole('textbox')),'preserved');
  act(()=>{window.localStorage.clear();window.dispatchEvent(new dom.window.StorageEvent('storage',{key:null}));});assert.equal(view.getByRole('combobox',{name:'글자 크기'}).value,'13');cleanup();
});
test('font adjustment still works without localStorage and is disabled while the editor is disabled',()=>{
  const view=start('not persisted');const originalSet=dom.window.Storage.prototype.setItem;
  try{dom.window.Storage.prototype.setItem=()=>{throw new Error('blocked storage');};fireEvent.change(view.getByRole('combobox',{name:'글자 크기'}),{target:{value:'18'}});assert.equal(view.getByRole('combobox',{name:'글자 크기'}).value,'18');assert.equal(view.container.querySelector('.cw-rich-editor').style.getPropertyValue('--cw-editor-font-size'),'18px');assert.equal(editorValue(view.getByRole('textbox')),'not persisted');}
  finally{dom.window.Storage.prototype.setItem=originalSet;}
  view.rerender(React.createElement(Editor,{value:'read only source',disabled:true}));assert.equal(view.getByRole('combobox',{name:'글자 크기'}).disabled,true);cleanup();
});
test('font size toolbar can wrap without retaining the former fixed 40px height',()=>{
  cleanup();window.localStorage.clear();const css=['../src/pages/ProblemDetail.css','../src/components/CodeEditor.css'].map(file=>readFileSync(new URL(file,import.meta.url),'utf8')).join('\n');
  const view=render(React.createElement('div',null,React.createElement('style',null,css),React.createElement(Harness,{initial:'',language:'PYTHON'}))),bar=view.container.querySelector('.pd-editor-bar'),style=window.getComputedStyle(bar);
  assert.equal(style.height,'auto');assert.equal(style.minHeight,'40px');assert.equal(style.flexWrap,'wrap');assert.equal(window.getComputedStyle(view.container.querySelector('.cw-editor-actions')).flexWrap,'wrap');cleanup();
});
test('completion works for rare languages and disabled completion stays off for Ctrl Space',async()=>{
  const view=start('get','LLVM_IR');const area=view.getByRole('textbox');act(()=>selectEditor(area,3));act(()=>startCompletion(editorView(area)));await act(tick);
  assert.ok(currentCompletions(editorView(area).state).some(item=>item.label==='getelementptr'));
  fireEvent.click(view.getByText('편집기 설정'));fireEvent.click(view.getByRole('checkbox',{name:'문법 추천'}));
  assert.equal(currentCompletions(editorView(area).state).length,0);press(area,' ',{ctrlKey:true,code:'Space'});await act(tick);assert.equal(currentCompletions(editorView(area).state).length,0);cleanup();
});
test('completion excludes the currently typed partial word and prioritizes the real return keyword',async()=>{
  const view=start('def sol():\n    ret','PYTHON'),area=view.getByRole('textbox');
  act(()=>selectEditor(area,editorValue(area).length));act(()=>startCompletion(editorView(area)));await act(tick);
  const options=currentCompletions(editorView(area).state);assert.equal(options.some(item=>item.label==='ret'),false);assert.equal(options.some(item=>item.label==='return'),true);assert.equal(options[0].label,'return');cleanup();
});
test('bracket extension can be toggled and disabled/required/copy contracts remain explicit',()=>{
  const view=start('','JAVASCRIPT');const area=view.getByRole('textbox');act(()=>inputEditorText(area,'('));assert.equal(editorValue(area),'()');
  act(()=>inputEditorText(area,')'));assert.equal(editorValue(area),'()');
  act(()=>{setEditorValue(area,'');inputEditorText(area,'[');});press(area,'Backspace');assert.equal(editorValue(area),'');
  fireEvent.click(view.getByText('편집기 설정'));fireEvent.click(view.getByRole('checkbox',{name:'괄호 자동 닫기'}));
  assert.equal(view.getByRole('checkbox',{name:'괄호 자동 닫기'}).checked,false);act(()=>inputEditorText(area,'('));assert.equal(editorValue(area),'(');
  view.rerender(React.createElement(Editor,{value:'private',onChange:()=>{},language:'JAVASCRIPT',disabled:true,required:true,allowCopy:false}));
  const next=view.getByRole('textbox');assert.equal(next.getAttribute('aria-disabled'),'true');assert.equal(next.getAttribute('aria-required'),'true');assert.equal(next.getAttribute('contenteditable'),'false');assert.equal(view.queryByRole('button',{name:'코드 복사'})===null,true);cleanup();
});
test('all 17 languages offer actual keyword/snippet completion and syntax token highlighting',async()=>{
  const samples={PYTHON:'return True',C:'int main() { return 0; }',CPP:'class Test {};',JAVA:'class Test {}',JAVASCRIPT:'const value = true;',TYPESCRIPT:'const value: number = 1;',CSHARP:'class Test {}',GO:'package main',RUST:'fn main() {}',KOTLIN:'fun main() {}',SQL:'SELECT 1;',RUBY:'def sol\nend',LLVM_IR:'define i32 @sol() { ret i32 0 }',PASCAL:'begin\nend.',PHP:'function sol() { return 0; }',DART:'class Test {}',UMJUNSIK:'어떻게\n엄.\n식어!\n이 사람이름이냐ㅋㅋ'};
  for(const [language,sample] of Object.entries(samples)){
    const view=start(sample,language),area=view.getByRole('textbox');assert.ok(view.container.querySelector('[class*="cw-syntax-"]'),language+' highlight');
    act(()=>setEditorValue(area,''));act(()=>startCompletion(editorView(area)));await act(tick);
    const options=currentCompletions(editorView(area).state);assert.ok(options.some(item=>item.label===EDITOR_CATALOG[language].keywords[0]),language+' keyword');assert.ok(options.some(item=>item.type==='snippet'),language+' syntax snippets');cleanup();
  }
});
test('newlines follow parser blocks rather than string/comment braces across languages',()=>{
  for(const [language,text,expected] of [['JAVASCRIPT','if (ready) {}','if (ready) {\n    \n}'],['JAVASCRIPT','// {','// {\n'],['JAVASCRIPT','const value = "{";','const value = "{";\n'],['PASCAL','begin','begin\n    '],['UMJUNSIK','엄.','엄.\n']]){
    const view=start(text,language),area=view.getByRole('textbox');act(()=>selectEditor(area,language==='JAVASCRIPT'&&text.endsWith('{}')?text.length-1:text.length));press(area,'Enter');assert.equal(editorValue(area),expected,language+' '+text);cleanup();
  }
});
test('controlled replacement does not emit edits and language switch drops old undo history',()=>{
  cleanup();window.localStorage.clear();const changes=[];const view=render(React.createElement(Editor,{value:'old',onChange:value=>changes.push(value),language:'PYTHON'}));let area=view.getByRole('textbox');act(()=>setEditorValue(area,'secret-python'));assert.equal(changes.length,1);
  view.rerender(React.createElement(Editor,{value:'function sol() {}',onChange:value=>changes.push(value),language:'JAVASCRIPT'}));area=view.getByRole('textbox');act(()=>undo(editorView(area)));assert.equal(editorValue(area),'function sol() {}');assert.equal(changes.length,1);cleanup();
});
