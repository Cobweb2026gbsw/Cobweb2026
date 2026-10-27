// Markdown은 고정 버전 lexer로 분석하고, 허용한 DOM만 직접 만듭니다.
// 사용자 HTML/이벤트 속성을 해석하거나 innerHTML에 넣지 않습니다.
const CobwebMarkdown=(()=>{
  const parser=typeof module==='object'&&module.exports?require('./vendor/marked.umd.js'):marked;
  const element=(doc,tag,text)=>{const node=doc.createElement(tag);if(text!==undefined)node.textContent=String(text??'');return node;};
  function decode(value,doc){
    // 고정 span의 속성에 문자참조 한 개만 넣어 inert 문서에서 해석합니다.
    // 결과는 text node/검증된 URL에만 사용하며 code/codespan은 원문을 보존합니다.
    return String(value??'').replace(/&(?:#x[0-9a-f]+|#[0-9]+|[a-z][a-z0-9]*);/gi,entity=>{
      if(entity.length>64)return entity;
      const Decoder=doc.defaultView?.DOMParser||(typeof DOMParser==='function'?DOMParser:null);
      if(!Decoder)return entity;
      // 속성 문맥은 &notit; 같은 미등록 이름의 부분 디코딩도 막습니다.
      return new Decoder().parseFromString('<span title="'+entity+'"></span>','text/html').body.firstElementChild.getAttribute('title');
    });
  }
  function text(doc,value){return doc.createTextNode(String(value??''));}
  function href(value,doc){
    try{const decoded=decode(value,doc);if(/[\u0000-\u001f\u007f-\u009f]/.test(decoded))return null;
      const url=new URL(decoded,doc.baseURI||'http://localhost/');
      return ['http:','https:','mailto:'].includes(url.protocol)?url.href:null;
    }catch{return null;}
  }
  function inline(parent,tokens,doc,depth=0){
    for(const token of tokens||[]){
      if(depth>32){parent.append(text(doc,token.raw??token.text));continue;}
      let child;
      if(['strong','em','del'].includes(token.type)){child=element(doc,token.type);inline(child,token.tokens,doc,depth+1);}
      else if(token.type==='codespan')child=element(doc,'code',token.text);
      else if(token.type==='br')child=element(doc,'br');
      else if(token.type==='link'||token.type==='image'){
        const target=href(token.href,doc);child=element(doc,target?'a':'span');
        if(target){child.setAttribute('href',target);child.setAttribute('rel','noopener noreferrer');}
        if(token.title)child.setAttribute('title',decode(token.title,doc));
        // 원격 이미지를 자동 요청하지 않아 외부 추적/개인정보 유출을 막습니다.
        if(token.type==='image')child.append(text(doc,decode(token.text||'이미지',doc)));
        else inline(child,token.tokens||[{type:'text',text:token.text}],doc,depth+1);
      }
      else if(token.type==='checkbox'){child=element(doc,'input');child.type='checkbox';child.disabled=true;child.checked=Boolean(token.checked);child.setAttribute('aria-label',token.checked?'완료':'미완료');}
      else if(token.tokens){child=element(doc,'span');inline(child,token.tokens,doc,depth+1);}
      else child=text(doc,token.type==='html'?(token.text??token.raw):decode(token.text??token.raw,doc));
      parent.append(child);
    }
  }
  function blocks(parent,tokens,doc,depth=0){
    for(const token of tokens||[]){
      if(token.type==='space'||token.type==='def')continue;
      if(depth>32){parent.append(element(doc,'p',token.raw??token.text));continue;}
      let child;
      switch(token.type){
        case 'heading':child=element(doc,'h'+Math.max(1,Math.min(6,token.depth)));inline(child,token.tokens,doc);break;
        case 'paragraph':child=element(doc,'p');inline(child,token.tokens,doc);break;
        case 'text':child=element(doc,'span');inline(child,token.tokens||[token],doc);break;
        case 'checkbox':child=element(doc,'span');inline(child,[token],doc);break;
        case 'hr':child=element(doc,'hr');break;
        case 'code':{
          child=element(doc,'pre');const code=element(doc,'code',token.text),lang=String(token.lang||'').split(/\s+/)[0];
          if(/^[\w+-]{1,32}$/.test(lang))code.className='language-'+lang;child.append(code);break;
        }
        case 'blockquote':child=element(doc,'blockquote');blocks(child,token.tokens,doc,depth+1);break;
        case 'list':{
          child=element(doc,token.ordered?'ol':'ul');if(token.ordered&&Number.isSafeInteger(token.start))child.setAttribute('start',String(token.start));
          for(const item of token.items||[]){const li=element(doc,'li');blocks(li,item.tokens,doc,depth+1);child.append(li);}break;
        }
        case 'table':{
          const wrapper=element(doc,'div');wrapper.className='markdown-table';child=element(doc,'table');
          const head=element(doc,'thead'),body=element(doc,'tbody');
          for(const [index,cells] of [token.header,...token.rows].entries()){
            const row=element(doc,'tr');for(const [column,cell] of cells.entries()){
              const td=element(doc,index===0?'th':'td'),align=token.align?.[column];
              if(['left','center','right'].includes(align))td.className='align-'+align;
              inline(td,cell.tokens,doc);row.append(td);
            }(index===0?head:body).append(row);
          }child.append(head,body);wrapper.append(child);child=wrapper;break;
        }
        // raw HTML도 텍스트로만 표시합니다. 스크립트·SVG·iframe은 생성하지 않습니다.
        default:child=element(doc,'span',token.text??token.raw);break;
      }
      parent.append(child);
    }
  }
  function render(container,value){
    const content=String(value??''),doc=container.ownerDocument||document;container.replaceChildren();
    container.className=(container.className+' markdown-body').trim().split(/\s+/).filter((v,i,a)=>a.indexOf(v)===i).join(' ');
    try{blocks(container,parser.lexer(content,{gfm:true,breaks:false}),doc);}
    catch{container.replaceChildren(element(doc,'pre',content));}
    return container;
  }
  function sample(container,value){
    const content=String(value??''),doc=container.ownerDocument||document;
    if(/(?:^|\n) {0,3}(?:`{3,}|~{3,})/.test(content))return render(container,content);
    const pre=element(doc,'pre');pre.append(element(doc,'code',content));container.append(pre);return container;
  }
  function example(container,value,index){
    const doc=container.ownerDocument||document,article=element(doc,'article');article.className='problem-example markdown-body';
    article.append(element(doc,'h3','예제 '+(index+1)));
    for(const [title,content] of [['입력',value.input_text??value.input??''],['출력',value.output_text??value.output??'']]){
      const section=element(doc,'section');section.append(element(doc,'h4',title));const box=element(doc,'div');sample(box,content);section.append(box);article.append(section);
    }
    if(value.explanation){const detail=element(doc,'div');render(detail,value.explanation);article.append(detail);}
    container.append(article);return article;
  }
  return {render,example,safeHref:href};
})();
if(typeof module==='object'&&module.exports)module.exports=CobwebMarkdown;
