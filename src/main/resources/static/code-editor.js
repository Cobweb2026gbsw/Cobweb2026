// 코드 전용 textarea의 편집 보조. 일반 본문/댓글 입력과 IME 조합은 건드리지 않습니다.
const CobwebCodeEditor=(()=>{
  const INDENT='    ',installed=new WeakSet(),released=new WeakSet();
  const lineStart=(value,position)=>position<=0?0:value.lastIndexOf('\n',position-1)+1;
  function replacement(value,start,end,text,selectionStart=start+text.length,selectionEnd=selectionStart,direction='none'){
    return {value:value.slice(0,start)+text+value.slice(end),from:start,to:end,text,selectionStart,selectionEnd,direction};
  }
  function mapPosition(position,edits){
    let offset=0;
    for(const edit of edits){
      if(position<edit.at)break;
      if(position<=edit.at+edit.remove)return edit.at+offset+edit.insert.length;
      offset+=edit.insert.length-edit.remove;
    }
    return position+offset;
  }
  function indent(value,start,end,reverse=false,direction='none'){
    if(!reverse&&start===end)return replacement(value,start,end,INDENT);
    // 선택 끝이 다음 줄 맨 앞이면 그 줄은 선택된 것으로 보지 않습니다.
    const first=lineStart(value,start),last=lineStart(value,end>start?end-1:start);
    const newline=value.indexOf('\n',last),finish=newline<0?value.length:newline;
    const lines=value.slice(first,finish).split('\n'),edits=[];
    let position=first;
    const changed=lines.map(line=>{
      const remove=reverse?(line.startsWith('\t')?1:(line.match(/^ {1,4}/)?.[0].length||0)):0;
      const insert=reverse?'':INDENT;
      edits.push({at:position,remove,insert});position+=line.length+1;
      return insert+line.slice(remove);
    }).join('\n');
    return replacement(value,first,finish,changed,mapPosition(start,edits),mapPosition(end,edits),direction);
  }
  function withoutComment(line,language){
    let result='',quote='',escaped=false,block=false;
    for(let i=0;i<line.length;i++){
      const char=line[i],next=line[i+1];
      if(block){if(char==='*'&&next==='/'){block=false;i++;}continue;}
      if(quote){result+=char;if(escaped)escaped=false;else if(char==='\\')escaped=true;else if(char===quote)quote='';continue;}
      if(char==='"'||char==="'"||char==='`'){quote=char;result+=char;continue;}
      if(char==='/'&&next==='*'&&!['PYTHON','RUBY','SQL','LLVM_IR'].includes(language)){block=true;i++;continue;}
      if((char==='#'&&(!language||['PYTHON','RUBY'].includes(language)))||
        (char==='/'&&next==='/'&&!['PYTHON','RUBY','SQL','LLVM_IR'].includes(language))||
        (char==='-'&&next==='-'&&language==='SQL')||(char===';'&&language==='LLVM_IR'))break;
      result+=char;
    }
    return result.trimEnd();
  }
  function newline(value,start,end,language=''){
    const left=value.slice(lineStart(value,start),start),base=(left.match(/^[ \t]*/)?.[0]||'').replace(/\t/g,INDENT);
    const code=withoutComment(left,String(language).toUpperCase()),last=code.slice(-1),pairs={'{':'}','[':']','(':')'};
    const deeper=Boolean(pairs[last])||(last===':'&&(!language||String(language).toUpperCase()==='PYTHON'));
    const leading=value.slice(end).match(/^[ \t]*/)?.[0]||'';
    // {|} 안에서 Enter: 내부 한 줄과 원래 깊이의 닫는 괄호 줄을 동시에 만듭니다.
    if(pairs[last]&&value[end+leading.length]===pairs[last]){
      const inner='\n'+base+INDENT;
      return replacement(value,start,end+leading.length,inner+'\n'+base,start+inner.length);
    }
    return replacement(value,start,end,'\n'+base+(deeper?INDENT:''));
  }
  function edit(value,start,end,key,{shiftKey=false,language='',direction='none'}={}){
    value=String(value);start=Math.max(0,Math.min(value.length,start));end=Math.max(start,Math.min(value.length,end));
    if(key==='Tab')return indent(value,start,end,shiftKey,direction);
    if(key==='Enter'&&!shiftKey)return newline(value,start,end,language);
    return null;
  }
  function languageOf(area){
    return area.dataset.language||area.closest?.('.repeat-card')?.querySelector('[data-field=language]')?.value||
      area.closest?.('form')?.querySelector('select[name=language]')?.value||'';
  }
  function handleKeyDown(event){
    const area=event.target;
    if(event.defaultPrevented||event.isComposing||event.keyCode===229||event.ctrlKey||event.metaKey||event.altKey||
      !area?.matches?.('textarea[data-code-editor]')||area.readOnly||area.disabled)return;
    // Esc 다음 Tab은 기본 포커스 이동을 허용해 키보드만으로 편집기를 빠져나갈 수 있습니다.
    if(event.key==='Escape'){released.add(area);return;}
    if(released.has(area)){released.delete(area);if(event.key==='Tab')return;}
    const change=edit(area.value,area.selectionStart,area.selectionEnd,event.key,
      {shiftKey:event.shiftKey,language:languageOf(area),direction:area.selectionDirection||'none'});
    if(!change)return;
    event.preventDefault();
    if((area.maxLength>=0&&change.value.length>area.maxLength&&change.value.length>area.value.length)||change.value===area.value)return;
    const scrollTop=area.scrollTop,scrollLeft=area.scrollLeft;
    area.setRangeText(change.text,change.from,change.to,'preserve');
    area.setSelectionRange(change.selectionStart,change.selectionEnd,change.direction);
    area.scrollTop=scrollTop;area.scrollLeft=scrollLeft;
    const view=area.ownerDocument?.defaultView;
    let input;
    try{input=new (view?.InputEvent||InputEvent)('input',{bubbles:true,inputType:event.key==='Enter'?'insertLineBreak':'insertText',data:change.text});}
    catch{input=new (view?.Event||Event)('input',{bubbles:true});}
    area.dispatchEvent(input);
  }
  function install(document){
    if(!document?.addEventListener||installed.has(document))return false;
    installed.add(document);document.addEventListener('keydown',handleKeyDown);return true;
  }
  return {install,edit,indent,newline,handleKeyDown,INDENT};
})();
if(typeof module==='object'&&module.exports)module.exports=CobwebCodeEditor;
