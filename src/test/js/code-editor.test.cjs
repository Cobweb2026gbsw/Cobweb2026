const assert=require('node:assert/strict');
const editor=require('../../main/resources/static/code-editor.js');

function change(value,start,end,key,options={}){
  return editor.edit(value,start,end,key,options);
}
let result=change('ab',1,1,'Tab');
assert.equal(result.value,'a    b');
assert.equal(result.selectionStart,5);
assert.equal(result.selectionEnd,5);
assert.equal(result.value.includes('\t'),false,'Tab must insert four literal spaces');

result=change('a\nb\nc',0,4,'Tab',{direction:'backward'});
assert.equal(result.value,'    a\n    b\nc','selection ending at next line start must exclude that line');
assert.equal(result.selectionStart,4);
assert.equal(result.selectionEnd,12);
assert.equal(result.direction,'backward');
result=change('a\nb',0,3,'Tab');
assert.equal(result.value,'    a\n    b');
assert.equal(result.selectionEnd,11);
result=change('ab',0,2,'Tab');
assert.equal(result.value,'    ab','selected text must not be deleted by indentation');
assert.equal(result.selectionStart,4);
assert.equal(result.selectionEnd,6);

result=change('    first\n  second\n\tthird\nlast',2,25,'Tab',{shiftKey:true});
assert.equal(result.value,'first\nsecond\nthird\nlast');
assert.equal(result.selectionStart,0,'selection inside removed indentation must clamp to line start');
assert.equal(result.selectionEnd,18);
result=change('    text',6,6,'Tab',{shiftKey:true});
assert.equal(result.value,'text');
assert.equal(result.selectionStart,2);
result=change('  text',1,1,'Tab',{shiftKey:true});
assert.equal(result.value,'text');
assert.equal(result.selectionStart,0);
result=change('text',2,2,'Tab',{shiftKey:true});
assert.equal(result.value,'text');
assert.equal(result.selectionStart,2);
result=change('a\n',2,2,'Tab');
assert.equal(result.value,'a\n    ');
result=change('\nnext',0,0,'Tab',{shiftKey:true});
assert.equal(result.value,'\nnext','outdent at the first empty line must not affect the next line');
result=change('\nnext',0,1,'Tab');
assert.equal(result.value,'    \nnext','selecting the first empty line must not indent the next line');
result=change('\nnext',0,0,'Enter');
assert.equal(result.value,'\n\nnext');
result=change('    a\n    b',0,10,'Tab',{shiftKey:true});
assert.equal(result.value,'a\nb');

result=change('    return value',16,16,'Enter',{language:'PYTHON'});
assert.equal(result.value,'    return value\n    ');
assert.equal(result.selectionStart,result.value.length);
result=change('if ready: # comment',19,19,'Enter',{language:'PYTHON'});
assert.equal(result.value,'if ready: # comment\n    ');
result=change('    # if ready:',15,15,'Enter',{language:'PYTHON'});
assert.equal(result.value,'    # if ready:\n    ','a comment colon must not open a block');
result=change('    x = "a#b:"',14,14,'Enter',{language:'PYTHON'});
assert.equal(result.value,'    x = "a#b:"\n    ','colon in a string must not open a block');
result=change('if (ready) { // comment',23,23,'Enter',{language:'CPP'});
assert.equal(result.value,'if (ready) { // comment\n    ');
result=change('    // {',8,8,'Enter',{language:'JAVA'});
assert.equal(result.value,'    // {\n    ');
result=change('    label:',10,10,'Enter',{language:'CPP'});
assert.equal(result.value,'    label:\n    ','C++ label colon is not a Python block');
result=change('SELECT 1 -- {',13,13,'Enter',{language:'SQL'});
assert.equal(result.value,'SELECT 1 -- {\n');
result=change('\tif ready:',10,10,'Enter',{language:'PYTHON'});
assert.equal(result.value,'\tif ready:\n        ','automatic indent copies existing tabs as four spaces');

for(const [open,close] of [['{','}'],['[',']'],['(',')']]){
  result=change('    '+open+close,5,5,'Enter',{language:'JAVASCRIPT'});
  assert.equal(result.value,'    '+open+'\n        \n    '+close);
  assert.equal(result.selectionStart,14);
  assert.equal(result.selectionEnd,14);
}
result=change('{   }',1,1,'Enter');
assert.equal(result.value,'{\n    \n}','paired expansion must not retain extra spaces before closer');
result=change('if ready:old text',9,17,'Enter',{language:'PYTHON'});
assert.equal(result.value,'if ready:\n    ','Enter must replace the current selection');
assert.equal(change('x',1,1,'Enter',{shiftKey:true}),null);
assert.equal(change('x',1,1,'a'),null);

class Area{
  constructor(value='',language='PYTHON'){
    this.value=value;this.selectionStart=value.length;this.selectionEnd=value.length;this.selectionDirection='none';
    this.dataset={};this.maxLength=-1;this.events=[];this.marked=true;this.scrollTop=73;this.scrollLeft=15;
    this.form={querySelector:()=>({value:language})};
    this.ownerDocument={defaultView:{Event}};
  }
  matches(selector){assert.equal(selector,'textarea[data-code-editor]');return this.marked;}
  closest(selector){return selector==='form'?this.form:null;}
  setRangeText(text,start,end){this.value=this.value.slice(0,start)+text+this.value.slice(end);this.scrollTop=0;this.scrollLeft=0;}
  setSelectionRange(start,end,direction){this.selectionStart=start;this.selectionEnd=end;this.selectionDirection=direction;}
  dispatchEvent(event){this.events.push(event);}
}
function press(area,key,extra={}){
  const event={target:area,key,preventDefault(){this.prevented=true;},...extra};
  editor.handleKeyDown(event);return event;
}
const area=new Area('if ready:');
assert.equal(press(area,'Enter').prevented,true);
assert.equal(area.value,'if ready:\n    ');
assert.equal(area.events.length,1);
assert.equal(area.events[0].type,'input');
assert.equal(area.events[0].bubbles,true);
assert.equal(area.scrollTop,73);
assert.equal(area.scrollLeft,15);
assert.equal(press(area,'Tab').prevented,true);
assert.equal(area.value,'if ready:\n        ');

const reference=new Area('if (ok) {','PYTHON');
reference.closest=selector=>selector==='.repeat-card'?{querySelector:()=>({value:'CPP'})}:null;
press(reference,'Enter');
assert.equal(reference.value,'if (ok) {\n    ','reference code must use its own row language');
const rowPython=new Area('case value:', 'CPP');
rowPython.closest=selector=>selector==='.repeat-card'?{querySelector:()=>({value:'PYTHON'})}:rowPython.form;
press(rowPython,'Enter');
assert.equal(rowPython.value,'case value:\n    ');

for(const extra of [{isComposing:true},{keyCode:229},{ctrlKey:true},{metaKey:true},{altKey:true},{defaultPrevented:true}]){
  const untouched=new Area('x');assert.equal(press(untouched,'Tab',extra).prevented,undefined);assert.equal(untouched.value,'x');assert.equal(untouched.events.length,0);
}
for(const property of ['readOnly','disabled']){
  const untouched=new Area('x');untouched[property]=true;
  assert.equal(press(untouched,'Tab').prevented,undefined);assert.equal(untouched.value,'x');
}
const ordinary=new Area('일반 본문');ordinary.marked=false;
assert.equal(press(ordinary,'Tab').prevented,undefined);
assert.equal(ordinary.value,'일반 본문');
const bounded=new Area('x');bounded.maxLength=4;
assert.equal(press(bounded,'Tab').prevented,true);assert.equal(bounded.value,'x');assert.equal(bounded.events.length,0);
const emptyOutdent=new Area('x');
assert.equal(press(emptyOutdent,'Tab',{shiftKey:true}).prevented,true);assert.equal(emptyOutdent.events.length,0);
press(area,'Escape');
assert.equal(press(area,'Tab').prevented,undefined,'Esc then Tab must let keyboard focus leave the editor');
assert.equal(press(area,'Tab').prevented,true,'the following Tab resumes four-space editing');

const listeners=[];
const doc={addEventListener:(type,listener)=>listeners.push({type,listener})};
assert.equal(editor.install(doc),true);
assert.equal(editor.install(doc),false);
assert.equal(editor.install(null),false);
assert.equal(editor.install({}),false);
assert.equal(listeners.length,1);
assert.equal(listeners[0].type,'keydown');
assert.equal(listeners[0].listener,editor.handleKeyDown);
console.log('code editor: four spaces, selection/outdent, automatic indent, caret, IME, events and keyboard escape passed');
