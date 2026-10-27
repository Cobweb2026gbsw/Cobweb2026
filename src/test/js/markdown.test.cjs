const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const markdown=require('../../main/resources/static/markdown.js');

// DOM을 HTML 문자열로 모사하지 않습니다. 허용된 노드를 실제로 만드는지 검사합니다.
class TextNode{
  constructor(value,doc){this.nodeType=3;this.ownerDocument=doc;this.textContent=String(value);}
}
class Element{
  constructor(tag,doc){this.nodeType=1;this.tagName=tag.toUpperCase();this.ownerDocument=doc;this.children=[];this.attributes={};this.className='';}
  set textContent(value){this.children=[];if(String(value))this.children.push(new TextNode(value,this.ownerDocument));}
  get textContent(){return this.children.map(child=>child.textContent).join('');}
  append(...children){for(const child of children){child.parentNode=this;this.children.push(child);}}
  replaceChildren(...children){this.children=[];this.append(...children);}
  setAttribute(name,value){this.attributes[name]=String(value);}
  getAttribute(name){return this.attributes[name]??null;}
  set innerHTML(_){throw Error('tests disallow HTML insertion');}
}
const parsedEntities=[];
class EntityParser{
  parseFromString(source,type){
    assert.equal(type,'text/html');
    const match=source.match(/^<span title="(&(?:#[xX][\da-fA-F]+|#\d+|[a-zA-Z][\da-zA-Z]+);)"><\/span>$/);
    assert.ok(match,'DOMParser may only receive the fixed inert wrapper containing one character reference');
    source=match[1];parsedEntities.push(source);
    const names={'&amp;':'&','&lt;':'<','&gt;':'>','&quot;':'"','&apos;':"'",'&copy;':'©','&nbsp;':'\u00a0','&AMP;':'&','&LT;':'<','&GT;':'>'};
    let content=names[source]??source;
    if(source.startsWith('&#')){
      const hex=/^&#[xX]/.test(source),value=parseInt(source.slice(hex?3:2,-1),hex?16:10);
      content=value===0||value>0x10ffff||(value>=0xd800&&value<=0xdfff)?'\ufffd':String.fromCodePoint(value);
    }
    return {body:{firstElementChild:{getAttribute(name){assert.equal(name,'title');return content;}}}};
  }
}
class Document{
  constructor(){this.baseURI='https://local.example/problems/1000';this.defaultView={DOMParser:EntityParser};}
  createElement(tag){return new Element(tag,this);}
  createTextNode(value){return new TextNode(value,this);}
}
const doc=new Document();
function render(source){const root=doc.createElement('div');markdown.render(root,source);return root;}
function elements(root,tag){const result=[];for(const child of root.children||[]){if(child.tagName&&(!tag||child.tagName===tag.toUpperCase()))result.push(child);result.push(...elements(child,tag));}return result;}
function one(root,tag){const found=elements(root,tag);assert.equal(found.length,1,'one '+tag+' expected');return found[0];}
const failures=[];let checks=0;
function test(name,run){checks++;try{run();}catch(error){failures.push({name,error});}}

test('headings, emphasis, inline code, strikeout and hard breaks',()=>{
  const root=render('# 제목\n\n본문 **강조**와 *기울임*, ~~취소~~, `matrix[7][2]`, 줄  \n바꿈');
  assert.equal(one(root,'h1').textContent,'제목');
  assert.equal(one(root,'strong').textContent,'강조');
  assert.equal(one(root,'em').textContent,'기울임');
  assert.equal(one(root,'del').textContent,'취소');
  assert.equal(one(root,'code').textContent,'matrix[7][2]');
  assert.equal(elements(root,'br').length,1);
  assert.match(root.className,/\bmarkdown-body\b/);
});
test('fenced Python code preserves indentation, quotes and comparison operators',()=>{
  const source='def sol(matrix, target):\n    if target[0] < 7 and matrix[7][2] > 0:\n        return "<script>&amp;"';
  const root=render('```python\n'+source+'\n```');
  const pre=one(root,'pre'),code=one(pre,'code');
  assert.equal(code.textContent,source);
  assert.equal(code.className,'language-python');
  assert.equal(elements(root,'script').length,0);
  assert.doesNotMatch(root.textContent,/```/);
});
test('tilde and indented code blocks',()=>{
  const root=render('~~~text\n(7, 7) → (7, 2)\n~~~\n\n    indented\n        nested');
  assert.equal(elements(root,'pre').length,2);
  assert.equal(elements(root,'code')[0].textContent,'(7, 7) → (7, 2)');
  assert.equal(elements(root,'code')[1].textContent,'indented\n    nested');
});
test('nested unordered and ordered lists',()=>{
  const root=render('- 첫 항목\n  - 하위 항목\n- 둘째 항목\n\n3. 세 번째\n4. 네 번째');
  assert.equal(elements(root,'ul').length,2);
  assert.equal(elements(root,'li').length,5);
  assert.equal(one(root,'ol').getAttribute('start'),'3');
  assert.match(root.textContent,/첫 항목하위 항목둘째 항목/);
});
test('GFM task checkboxes are disabled display-only controls',()=>{
  const root=render('- [x] 완료\n- [ ] 미완료');
  const inputs=elements(root,'input');assert.equal(inputs.length,2);
  assert.deepEqual(inputs.map(input=>input.type),['checkbox','checkbox']);
  assert.deepEqual(inputs.map(input=>input.checked),[true,false]);
  assert.equal(inputs.every(input=>input.disabled===true),true);
  assert.deepEqual(inputs.map(input=>input.getAttribute('aria-label')),['완료','미완료']);
  assert.doesNotMatch(root.textContent,/\[[ xX]\]/);
});
test('blockquote and horizontal rule',()=>{
  const root=render('> 인용 **강조**\n\n---');
  assert.equal(one(root,'blockquote').textContent,'인용 강조');
  assert.equal(one(root,'strong').textContent,'강조');
  assert.equal(elements(root,'hr').length,1);
});
test('GFM table header, rows, inline content and alignment',()=>{
  const root=render('| 이름 | 값 |\n| :--- | ---: |\n| **행** | `15` |\n| target | 7 |');
  const table=one(root,'table');
  assert.equal(elements(table,'thead').length,1);assert.equal(elements(table,'tbody').length,1);
  assert.equal(elements(table,'th').length,2);assert.equal(elements(table,'td').length,4);
  assert.equal(elements(table,'th')[0].className,'align-left');
  assert.equal(elements(table,'th')[1].className,'align-right');
  assert.equal(one(table,'strong').textContent,'행');assert.equal(one(table,'code').textContent,'15');
  assert.equal(table.parentNode.className,'markdown-table');
});
test('links resolve safely and preserve labels and titles',()=>{
  const root=render('[**문서**](/docs?q=1#part "도움말")와 [메일](mailto:help@example.invalid)');
  const links=elements(root,'a');assert.equal(links.length,2);
  assert.equal(links[0].getAttribute('href'),'https://local.example/docs?q=1#part');
  assert.equal(links[0].getAttribute('rel'),'noopener noreferrer');
  assert.equal(links[0].getAttribute('title'),'도움말');
  assert.equal(one(links[0],'strong').textContent,'문서');
  assert.equal(links[1].getAttribute('href'),'mailto:help@example.invalid');
});
test('reference links and safe autolinks',()=>{
  const root=render('[사이트][docs] 및 <https://example.invalid/path>\n\n[docs]: https://example.invalid/docs');
  assert.equal(elements(root,'a').length,2);
  assert.equal(elements(root,'a')[0].getAttribute('href'),'https://example.invalid/docs');
  assert.doesNotMatch(root.textContent,/\[docs\]:/);
});
test('images are links, never automatic network requests',()=>{
  const root=render('![설명](https://tracking.invalid/pixel.png "제목")');
  assert.equal(elements(root,'img').length,0);
  assert.equal(one(root,'a').textContent,'설명');
  assert.equal(one(root,'a').getAttribute('href'),'https://tracking.invalid/pixel.png');
});
test('raw script, SVG, iframe and event attributes stay inert text',()=>{
  const payload='<script>alert("x")</script>\n\n<svg onload="alert(1)"><a href="javascript:alert(1)">x</a></svg>\n\n<iframe srcdoc="<img src=x onerror=alert(1)>"></iframe>\n\n<input autofocus onfocus="alert(1)">';
  const root=render(payload);
  for(const forbidden of ['script','svg','iframe','img','input','style','object','embed','math','link','meta','base','form'])assert.equal(elements(root,forbidden).length,0,forbidden+' must not be created');
  assert.match(root.textContent,/<script>/);
  assert.match(root.textContent,/onload=/);
  for(const element of elements(root))for(const key of Object.keys(element.attributes))assert.doesNotMatch(key,/^(on|src|style)/i);
});
test('inline raw HTML also remains literal',()=>{
  const root=render('앞 <img src=x onerror="alert(1)"> 중간 <span onclick="alert(2)">뒤</span>');
  assert.equal(elements(root,'img').length,0);
  assert.match(root.textContent,/<img src=x onerror=/);
  assert.equal(elements(root).some(element=>'onclick' in element.attributes),false);
});
test('dangerous Markdown destinations have no clickable anchor',()=>{
  for(const url of ['javascript:alert%281%29','JaVaScRiPt:alert%281%29','data:text/html;base64,AAAA','vbscript:msgbox%281%29','file:///tmp/secret','ftp://example.invalid/file']){
    const root=render('[안전한 표시]('+url+')');
    assert.equal(elements(root,'a').length,0,url);
    assert.match(root.textContent,/안전한 표시/);
  }
});
test('entity-obfuscated javascript URLs are blocked',()=>{
  for(const url of ['jav&#97;script:alert%281%29','&#106;avascript:alert%281%29','javascript&#58;alert%281%29']){
    const root=render('[표시]('+url+')');assert.equal(elements(root,'a').length,0,url);
  }
});
test('safeHref rejects dangerous schemes and control characters',()=>{
  for(const url of ['javascript:alert(1)','JaVaScRiPt:alert(1)','data:text/html,hello','vbscript:msgbox(1)',
    'java\nscript:alert(1)','java\tscript:alert(1)','https://safe.invalid/a\nb','https://safe.invalid/\tfile',
    '\u0000https://safe.invalid/', 'https://safe.invalid/\u007ffile','https://safe.invalid/\u0085file'])assert.equal(markdown.safeHref(url,doc),null,JSON.stringify(url));
  assert.equal(markdown.safeHref('/relative',doc),'https://local.example/relative');
  assert.equal(markdown.safeHref('#section',doc),'https://local.example/problems/1000#section');
});
test('HTML named and numeric entities render as text characters',()=>{
  const root=render('&amp; &lt; &gt; &quot; &apos; &copy; &nbsp; &#65; &#x1f600;');
  assert.equal(root.textContent,'& < > " \' © \u00a0 A 😀');
  assert.equal(elements(root,'script').length,0);
});
test('code spans and fenced code keep entity spellings literal',()=>{
  const root=render('`&amp; &lt;`\n\n```html\n&lt;script&gt; &copy;\n```');
  assert.deepEqual(elements(root,'code').map(code=>code.textContent),['&amp; &lt;','&lt;script&gt; &copy;']);
});
test('entity text does not become active HTML after decoding',()=>{
  const root=render('&lt;script&gt;alert(1)&lt;/script&gt;');
  assert.equal(root.textContent,'<script>alert(1)</script>');
  assert.equal(elements(root,'script').length,0);
});
test('entity text in a link title is displayed correctly',()=>{
  const root=render('[문서](https://example.invalid/ "&quot;제목&quot; &amp; 도움말")');
  assert.equal(one(root,'a').getAttribute('title'),'"제목" & 도움말');
});
test('escaped ampersands are not decoded twice',()=>{
  const root=render('\\&amp; &amp;amp;');
  assert.equal(root.textContent,'&amp; &amp;');
});
test('semicolon-less and unknown entity-like text stays literal',()=>{
  const root=render('&copycat &notit; &amp &#65 &#x41');
  assert.equal(root.textContent,'&copycat &notit; &amp &#65 &#x41');
});
test('image alt and safe URL entities decode correctly without loading images',()=>{
  const root=render('![&copy; 그림](https://safe.invalid/?a=1&amp;b=2)');
  assert.equal(one(root,'a').textContent,'© 그림');
  assert.equal(one(root,'a').getAttribute('href'),'https://safe.invalid/?a=1&b=2');
  assert.equal(elements(root,'img').length,0);
});
test('character decoding uses the isolated single-entity parser',()=>{
  const count=parsedEntities.length;
  render('&amp; &copy; &lt;');
  assert.ok(parsedEntities.length>count);
});
test('plain example input/output are exact code text, not Markdown',()=>{
  const root=doc.createElement('div');
  const input='  # not a heading\n    1  2\n\n*not emphasis*\n';
  const output=' True  \n';
  const example=markdown.example(root,{input_text:input,output_text:output},0);
  assert.equal(example.className,'problem-example markdown-body');
  assert.equal(one(example,'h3').textContent,'예제 1');
  assert.deepEqual(elements(example,'h4').map(node=>node.textContent),['입력','출력']);
  assert.deepEqual(elements(example,'code').map(node=>node.textContent),[input,output]);
  assert.equal(elements(example,'h1').length,0);assert.equal(elements(example,'em').length,0);
});
test('existing Chain Laser fenced examples render instead of showing backticks',()=>{
  const root=doc.createElement('div');
  markdown.example(root,{
    input_text:'```python\nmatrix = [[0] * 15 for _ in range(15)]\nmatrix[7][2] = 1\nmatrix[2][2] = 1\n\ntarget = (2, 12)\n```',
    output_text:'```python\nTrue\n```',
    explanation:'중앙 `(7, 7)`에서 발사된다.\n\n```text\n(7, 7) → (7, 2) → (2, 2) → (2, 12)\n```'
  },0);
  const codes=elements(root,'code');
  assert.equal(codes[0].className,'language-python');
  assert.match(codes[0].textContent,/\n\ntarget = \(2, 12\)$/);
  assert.equal(codes[1].textContent,'True');
  assert.equal(codes[2].textContent,'(7, 7)');
  assert.equal(codes[3].className,'language-text');
  assert.equal(codes[3].textContent,'(7, 7) → (7, 2) → (2, 2) → (2, 12)');
  assert.doesNotMatch(root.textContent,/```/);
});
test('example supports current author API field aliases',()=>{
  const root=doc.createElement('div');
  markdown.example(root,{input:'1 2\n',output:'3\n',explanation:'**합**을 출력한다.'},1);
  assert.equal(one(root,'h3').textContent,'예제 2');
  assert.deepEqual(elements(root,'code').map(code=>code.textContent),['1 2\n','3\n']);
  assert.equal(one(root,'strong').textContent,'합');
});
test('render replaces old content and does not duplicate its CSS class',()=>{
  const root=render('**첫째**');
  markdown.render(root,'`둘째`');markdown.render(root,'`셋째`');
  assert.equal(root.textContent,'셋째');assert.equal(elements(root,'strong').length,0);
  assert.equal(root.className.split(/\s+/).filter(value=>value==='markdown-body').length,1);
});
test('empty and null contents are safe',()=>{
  assert.equal(render(null).textContent,'');assert.equal(render('').children.length,0);
  const root=doc.createElement('div');markdown.example(root,{},0);
  assert.deepEqual(elements(root,'code').map(code=>code.textContent),['','']);
});
test('fence language info cannot add event attributes',()=>{
  const root=render('```js onclick=alert(1)\nconsole.log(1)\n```');
  assert.equal(one(root,'code').className,'language-js');
  assert.deepEqual(one(root,'code').attributes,{});
});
test('browser scripts load Markdown and editor dependencies before consumers',()=>{
  const html=fs.readFileSync(path.resolve(__dirname,'../../main/resources/static/index.html'),'utf8');
  const scripts=[...html.matchAll(/<script\s+[^>]*src="([^"]+)"[^>]*>/g)];
  const urls=scripts.map(match=>match[1]);
  for(const asset of ['/vendor/marked.umd.js','/markdown.js','/code-editor.js','/groups.js','/app.js']){
    const index=urls.indexOf(asset);assert.ok(index>=0,asset+' must load');assert.match(scripts[index][0],/\bdefer\b/);
    assert.equal(fs.existsSync(path.resolve(__dirname,'../../main/resources/static','.'+asset)),true);
  }
  assert.ok(urls.indexOf('/vendor/marked.umd.js')<urls.indexOf('/markdown.js'));
  for(const asset of ['/markdown.js','/code-editor.js','/groups.js'])assert.ok(urls.indexOf(asset)<urls.indexOf('/app.js'));
  assert.match(html,/<textarea\b[^>]*id="source"[^>]*\bdata-code-editor\b/);
  for(const id of ['description','input-description','output-description','constraints']){
    assert.match(html,new RegExp('<div\\b[^>]*id="'+id+'"[^>]*>'));
    assert.doesNotMatch(html,new RegExp('<pre\\b[^>]*id="'+id+'"'));
  }
});
test('anonymous access permits only the new explicit static assets',()=>{
  const config=fs.readFileSync(path.resolve(__dirname,'../../main/java/com/drewdrew0414/global/config/SecurityConfig.java'),'utf8');
  const permitted=[...config.matchAll(/\.requestMatchers\(([\s\S]*?)\)\.permitAll\(\)/g)].map(match=>match[1]);
  for(const asset of ['/vendor/marked.umd.js','/markdown.js','/code-editor.js'])assert.ok(permitted.some(block=>block.includes('"'+asset+'"')),asset+' must be usable before login');
  assert.equal(permitted.some(block=>block.includes('"/**"')),false,'do not open all routes to make helpers load');
});

if(failures.length){
  for(const failure of failures)console.error('FAIL '+failure.name+'\n'+failure.error.stack);
  console.error('markdown: '+(checks-failures.length)+'/'+checks+' checks passed');process.exitCode=1;
}else console.log('markdown: '+checks+' checks passed (GFM, fenced examples, literal input, entities and inert HTML/URLs)');
