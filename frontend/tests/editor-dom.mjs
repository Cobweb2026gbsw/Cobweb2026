// jsdom은 레이아웃을 계산하지 않습니다. 편집 내용/트랜잭션 검증용 최소 DOM 표면만 보완합니다.
// 실제 커서 위치·스크롤·팝업은 브라우저 검증으로 별도 확인합니다.
import {EditorView} from '@codemirror/view';

export function installEditorDom(window) {
  const rect = () => ({x:0,y:0,left:0,right:0,top:0,bottom:0,width:0,height:0,toJSON(){return {};}});
  if (!window.Range.prototype.getBoundingClientRect) window.Range.prototype.getBoundingClientRect=rect;
  if (!window.Range.prototype.getClientRects) window.Range.prototype.getClientRects=()=>[];
  if (!window.requestAnimationFrame) window.requestAnimationFrame=callback=>window.setTimeout(()=>callback(Date.now()),16);
  if (!window.cancelAnimationFrame) window.cancelAnimationFrame=id=>window.clearTimeout(id);
  if (!window.ResizeObserver) window.ResizeObserver=class {observe(){} unobserve(){} disconnect(){}};
  for (const key of ['Node','Text','Range','Window','Document','Selection','DOMRect','ResizeObserver']) {
    if(window[key])globalThis[key]=window[key];
  }
  globalThis.getComputedStyle=window.getComputedStyle.bind(window);
  globalThis.requestAnimationFrame=window.requestAnimationFrame.bind(window);
  globalThis.cancelAnimationFrame=window.cancelAnimationFrame.bind(window);
}

export function editorView(node) {
  const root=node?.matches?.('.cm-editor')?node:node?.closest?.('.cm-editor');
  const view=root&&EditorView.findFromDOM(root);
  if(!view)throw new Error('실제 CodeMirror 편집기를 찾을 수 없습니다.');
  return view;
}
export const editorValue=node=>editorView(node).state.doc.toString();
export function setEditorValue(node,value) {
  const view=editorView(node);
  view.dispatch({changes:{from:0,to:view.state.doc.length,insert:value},selection:{anchor:value.length}});
}
export function selectEditor(node,start,end=start) {
  editorView(node).dispatch({selection:{anchor:start,head:end}});
}
export function inputEditorText(node,text) {
  const view=editorView(node),{from,to}=view.state.selection.main;
  const insert=()=>view.state.update({changes:{from,to,insert:text},selection:{anchor:from+text.length},userEvent:'input.type'});
  for(const handler of view.state.facet(EditorView.inputHandler))if(handler(view,from,to,text,insert))return;
  view.dispatch(insert());
}
