// 토큰은 메모리에만 보관합니다. 서버 응답을 innerHTML로 넣지 않아 문제 본문의 스크립트 실행을 막습니다.
let token=null, page=0, currentProblem=null, resetToken=null, polling=null, languages=[], executionMode='STDIN';
let permissions={canCreate:false,canManage:false}, minePage=0, adminPage=0, adminProblem=null, editorLoaded=false;
let editorCanSave=true, editorCanReview=false;
let sessionEpoch=0, viewEpoch=0, loggingOut=false;
let permissionsRequest=0, problemListRequest=0, problemRequest=0, mineRequest=0, adminListRequest=0;
let editorRequest=0, adminProblemRequest=0, groupDestinationsRequest=0, pollRequest=0;
const STALE_REQUEST_MESSAGE='__COBWEB_STALE_REQUEST__';
const $=s=>document.querySelector(s);
function notice(text){if(String(text).includes(STALE_REQUEST_MESSAGE))return;$('#notice').textContent=text;}
function staleRequest(){const error=Error(STALE_REQUEST_MESSAGE);error.stale=true;return error;}
function currentSession(epoch){return epoch===sessionEpoch;}
function currentView(id,view,session){return currentSession(session)&&view===viewEpoch&&!$('#'+id).hidden;}
async function api(path,method='GET',body=null,retry=true){
  const session=sessionEpoch;
  const headers={'Content-Type':'application/json'};
  if(token)headers.Authorization='Bearer '+token;
  let res;
  try{res=await fetch(path,{method,headers,credentials:'same-origin',body:body===null?undefined:JSON.stringify(body)});}
  catch(error){if(!currentSession(session))throw staleRequest();throw error;}
  if(!currentSession(session))throw staleRequest();
  if(res.status===401&&retry&&(!path.startsWith('/api/auth/')||path==='/api/auth/logout')){
    try{const fresh=await api('/api/auth/reissue','POST',null,false);if(!currentSession(session))throw staleRequest();
      token=fresh.accessToken;return api(path,method,body,false);}catch(error){if(error.stale)throw error;}
  }
  const data=await res.json().catch(()=>({}));
  if(!currentSession(session))throw staleRequest();
  if(!res.ok){const error=Error(data.message||((res.status===401||res.status===403)?'로그인이 필요하거나 접근 권한이 없습니다.':'요청 실패 ('+res.status+')'));error.status=res.status;throw error;}
  return data.data;
}
function setPermissions(value){
  permissions={canCreate:Boolean(value?.canCreate),canManage:Boolean(value?.canManage)};
  $('#author-nav').hidden=!(permissions.canCreate||permissions.canManage);
  $('#admin-nav').hidden=!permissions.canManage;
}
async function refreshPermissions(){
  const session=sessionEpoch,request=++permissionsRequest;
  if(!token){setPermissions(null);return;}
  try{const value=await api('/api/problems/permissions');if(currentSession(session)&&request===permissionsRequest)setPermissions(value);}
  catch{if(currentSession(session)&&request===permissionsRequest)setPermissions(null);}
}
async function show(id){
  if(id==='author'&&!permissions.canCreate&&!permissions.canManage){await show(token?'problems':'auth');notice(token?'문제 작성 권한이 없습니다.':'문제를 만들려면 로그인해 주세요.');return;}
  if(id==='admin'&&!permissions.canManage){await show(token?'problems':'auth');notice(token?'문제 관리 권한이 없습니다.':'문제를 관리하려면 로그인해 주세요.');return;}
  if(id!=='contest')CobwebGroups.leaveContest();
  const view=++viewEpoch,session=sessionEpoch;
  clearTimeout(polling);pollRequest++;document.querySelectorAll('.view').forEach(v=>v.hidden=v.id!==id);notice('');
  try{
    if(id==='problems')await list();
    if(id==='submissions'){const data=await api('/api/submissions');if(!currentView(id,view,session))return;
      rows('#submission-list',data,s=>[s.problem_number+' · '+s.problem_title,languageLabel(s.language)+' · '+s.status]);}
    if(id==='ranking'){const data=await api('/api/ranking');if(!currentView(id,view,session))return;
      rows('#rank-list',data,(p,i)=>[(i+1)+'. '+p.username,p.rank_type+' '+p.rank_int+' · '+p.solved_count+'문제']);}
    if(id==='profile'){const p=await api('/api/me');if(!currentView(id,view,session))return;
      $('#profile-info').textContent=p.username+' · '+p.rank_type+' '+p.rank_int+'\n해결 '+p.solved_count+' / 제출 '+p.submit_count;
      $('#profile-form [name=bio]').value=p.bio;$('#profile-form [name=githubUrl]').value=p.github_url;}
    if(id==='author'){if(!editorLoaded)resetEditor();await refreshGroupDestinations().catch(()=>{});
      if(!currentView(id,view,session))return;await listMine();}
    if(id==='admin')await listAdmin();
    if(id==='groups')await CobwebGroups.open();
    if(id==='community'||id==='notices')await CobwebCommunity.open(id);
  }catch(e){if(currentView(id,view,session)&&!e.stale)notice(e.message);}
  return view;
}
function rows(selector,data,render){
  const box=$(selector);box.replaceChildren();if(!data.length){box.textContent='아직 기록이 없습니다.';return;}
  data.forEach((item,i)=>{const row=document.createElement('div');row.className='row';
    render(item,i).forEach(text=>{const span=document.createElement('span');span.textContent=text;row.append(span);});box.append(row);});
}
async function list(){
  const session=sessionEpoch,view=viewEpoch,request=++problemListRequest;
  const data=await api('/api/problems?page='+page+'&search='+encodeURIComponent($('#search [name=q]').value));
  if(!currentView('problems',view,session)||request!==problemListRequest)return;
  rows('#problem-list',data,p=>[p.problem_number+' · '+p.problem_title,p.rank_type+' '+p.rank_int]);
  [...$('#problem-list').children].forEach((row,i)=>{if(!data[i])return;const b=document.createElement('button');b.textContent='풀기';b.onclick=()=>openProblem(data[i].problem_number);row.append(b);});
  $('#previous').disabled=page===0;$('#next').disabled=data.length<30;
}
async function openProblem(number){
  const session=sessionEpoch,originView=viewEpoch,request=++problemRequest;
  try{const p=await api('/api/problems/'+number);
    if(!currentSession(session)||originView!==viewEpoch||request!==problemRequest)return;
    const shownView=await show('problem');if(!currentView('problem',shownView,session))return;
    currentProblem=number;
    $('#problem-title').textContent=number+'. '+p.problem_title;$('#problem-meta').textContent=p.rank_type+' '+p.rank_int+' · '+p.time_limit_ms+' ms · '+p.memory_limit_mb+' MB';
    for(const [id,key] of [['description','description'],['input-description','input_description'],['output-description','output_description'],['constraints','constraints_text']])renderMarkdown($('#'+id),p[key]||'');
    $('#examples').replaceChildren();p.examples.forEach((e,i)=>renderExample($('#examples'),e,i));
    executionMode=p.execution_mode;
    const select=$('#submission-language'), previous=select.value;
    select.replaceChildren();
    languages.filter(l=>p.languages.includes(l.id)).forEach(l=>{const option=document.createElement('option');option.value=l.id;option.textContent=l.label;select.append(option);});
    if(p.languages.includes(previous))select.value=previous;
    updateLanguageHelp();$('#judge-result').textContent='';
  }catch(e){if(currentSession(session)&&originView===viewEpoch&&!e.stale)notice(e.message);}
}
function renderMarkdown(box,content){
  if(typeof CobwebMarkdown==='object')return CobwebMarkdown.render(box,content);
  box.textContent=content??'';return box;
}
function renderExample(box,item,index){
  if(typeof CobwebMarkdown==='object')return CobwebMarkdown.example(box,item,index);
  const pre=document.createElement('pre');pre.textContent='예제 '+(index+1)+'\n'+(item.input_text??item.input??'')+'\n→ '+(item.output_text??item.output??'')+'\n'+(item.explanation||'');box.append(pre);return pre;
}
function form(id,action){$('#'+id).onsubmit=async e=>{e.preventDefault();const b=e.target.querySelector('button');b.disabled=true;
  try{await action(Object.fromEntries(new FormData(e.target)));}catch(e){notice(e.message);}finally{b.disabled=false;}};}
document.querySelectorAll('[data-view]').forEach(b=>b.onclick=()=>show(b.dataset.view));
form('search',async()=>{page=0;await list();});
$('#previous').onclick=()=>{page=Math.max(0,page-1);list().catch(e=>notice(e.message));};
$('#next').onclick=()=>{page++;list().catch(e=>notice(e.message));};
form('login',async d=>{if(loggingOut)throw Error('로그아웃 처리 중입니다. 잠시 후 다시 로그인해 주세요.');
  const r=await api('/api/auth/login','POST',d);sessionEpoch++;clearPrivateViews();token=r.accessToken;
  $('#account').textContent=d.username;const session=sessionEpoch;await refreshPermissions();if(!currentSession(session))return;
  await show({'/create':'author','/admin':'admin','/groups':'groups','/community':'community','/notices':'notices'}[location.pathname]||'problems');});
form('email-send',async d=>{await api('/api/email-verifications','POST',{...d,purpose:'JOIN'});notice('인증 코드를 발송했습니다.');});
form('email-verify',async d=>{await api('/api/email-verifications/verify','POST',{...d,purpose:'JOIN'});notice('이메일 인증을 완료했습니다.');});
form('signup',async d=>{await api('/api/auth/signup','POST',d);notice('가입되었습니다. 로그인해 주세요.');});
form('reset-request',async d=>{await api('/api/auth/password-reset/request','POST',d);notice('등록된 이메일이라면 재설정 코드를 발송했습니다.');});
form('reset-verify',async d=>{const r=await api('/api/auth/password-reset/verify-code','POST',d);resetToken=r.resetToken;notice('새 비밀번호를 입력하세요.');});
form('reset-confirm',async d=>{if(!resetToken)throw Error('먼저 코드를 확인해 주세요.');await api('/api/auth/password-reset/confirm','POST',{...d,resetToken});resetToken=null;notice('비밀번호를 변경했습니다.');});
form('profile-form',async d=>{await api('/api/me','PATCH',d);notice('프로필을 저장했습니다.');});
$('#logout').onclick=async()=>{
  if(loggingOut)return;loggingOut=true;const session=++sessionEpoch;
  let failure=null;
  try{await api('/api/auth/logout','POST');}catch(e){failure=e;}
  if(!currentSession(session)){loggingOut=false;return;}
  token=null;$('#account').textContent='로그인';clearPrivateViews();await refreshPermissions();await show('auth');
  loggingOut=false;if(failure&&!failure.stale)notice('서버 로그아웃에 실패했습니다. 다시 시도해 주세요: '+failure.message);
};
function languageLabel(id){return languages.find(l=>l.id===id)?.label||id;}
const functionSignatures={
  PYTHON:'def sol(matrix, target) -> bool',
  C:'bool sol(const int matrix[15][15], int target_row, int target_col)',
  CPP:'bool sol(const std::vector<std::vector<int>>& matrix, std::pair<int,int> target)',
  JAVA:'public static boolean sol(int[][] matrix, int[] target) — Main 클래스',
  JAVASCRIPT:'function sol(matrix, target) — 함수 선언 또는 CommonJS export',
  TYPESCRIPT:'export function sol(matrix: number[][], target: [number,number]): boolean',
  CSHARP:'public static bool Sol(int[][] matrix, int[] target) — Solution 클래스',
  GO:'func sol(matrix [][]int, target [2]int) bool — package main',
  RUST:'fn sol(matrix: Vec<Vec<i32>>, target: (usize,usize)) -> bool',
  KOTLIN:'fun sol(matrix: Array<IntArray>, target: Pair<Int,Int>): Boolean',
  RUBY:'def sol(matrix, target)',
  LLVM_IR:'define i1 @sol(ptr %matrix, i32 %target_row, i32 %target_col) — matrix는 행 우선 i32[225]',
  PASCAL:'function sol(const matrix: TMatrix; const target: TTarget): Boolean; — TMatrix=array[0..14,0..14] of LongInt, TTarget=array[0..1] of LongInt는 제공됨',
  PHP:'<?php function sol(array $matrix, array $target): bool',
  DART:'bool sol(List<List<int>> matrix, List<int> target)'
};
function updateLanguageHelp(){
  const id=$('#submission-language').value;
  const names={JAVA:'Main.java / public class Main',KOTLIN:'Main.kt / fun main()',CSHARP:'Main.cs / Main 또는 최상위 문장',LLVM_IR:'LLVM 18 텍스트 IR / main 진입점',
    PASCAL:'main.pas / Free Pascal 프로그램',PHP:'main.php / <?php로 시작하는 CLI 프로그램',DART:'main.dart / void main()',UMJUNSIK:'main.umm / 엄랭 v2 표준 입출력 프로그램'};
  $('#language-help').textContent=executionMode==='FUNCTION'?(functionSignatures[id]||'지원하지 않는 함수형 언어')+' · true/false를 반환하세요. main은 작성하지 않습니다.':
    executionMode==='SQL'?'SQLite 읽기 쿼리 한 개를 제출하세요. 각 케이스는 별도의 DB이며 결과는 헤더 없는 TSV입니다.':
    (names[id]||languageLabel(id))+' · 표준 입력을 읽고 표준 출력에 답을 쓰세요. 외부 패키지 설치는 지원하지 않습니다.';
  $('#source').placeholder=executionMode==='FUNCTION'?(id==='PYTHON'?'def sol(matrix, target):\n    return False':functionSignatures[id]||'sol 함수를 작성하세요.'):
    executionMode==='SQL'?'SELECT ... FROM ... ORDER BY ...;':'선택한 언어의 풀이 코드를 입력하세요.';
}
$('#submission-language').onchange=updateLanguageHelp;
form('submit',async d=>{const session=sessionEpoch,view=viewEpoch,request=++pollRequest;
  const id=await api('/api/submissions','POST',{problemNumber:currentProblem,sourceCode:d.source,language:d.language});
  if(currentView('problem',view,session)&&request===pollRequest)await poll(id,request,session,view);});
async function poll(id,request=pollRequest,session=sessionEpoch,view=viewEpoch){
  try{const r=await api('/api/submissions/'+id);if(!currentView('problem',view,session)||request!==pollRequest)return;
    $('#judge-result').textContent='제출 #'+id+' · '+languageLabel(r.language)+' · '+r.status+(r.score===null?'':' · '+r.score+'점');
    if(['QUEUED','RUNNING'].includes(r.status))polling=setTimeout(()=>poll(id,request,session,view),1500);
  }catch(e){if(currentView('problem',view,session)&&request===pollRequest&&!e.stale)notice(e.message);}}
const MAX_EXAMPLES=20, MAX_TESTS=50, MAX_CODE_BYTES=64*1024;
const defaultFileNames={PYTHON:'main.py',C:'main.c',CPP:'main.cpp',JAVA:'Main.java',JAVASCRIPT:'main.js',TYPESCRIPT:'main.ts',CSHARP:'Main.cs',GO:'main.go',RUST:'main.rs',KOTLIN:'Main.kt',SQL:'query.sql',RUBY:'main.rb',LLVM_IR:'main.ll',PASCAL:'main.pas',PHP:'main.php',DART:'main.dart',UMJUNSIK:'main.umm'};
const MAX_SOLUTIONS=Object.keys(defaultFileNames).length;
function supportedLanguages(mode){return languages.filter(l=>Array.isArray(l.modes)?l.modes.includes(mode):(mode==='SQL'?l.id==='SQL':l.id!=='SQL'&&(mode!=='FUNCTION'||l.id!=='UMJUNSIK')));}
function buildProblemPayload(fields,examples,tests,allowedLanguages,solutions,catalog=languages){
  const title=String(fields.title||'').trim(), description=String(fields.description||'');
  if(!title||!description.trim())throw Error('제목과 문제 본문을 입력해 주세요.');
  if(examples.length>MAX_EXAMPLES)throw Error('예제는 최대 20개까지 추가할 수 있습니다.');
  if(tests.length<1||tests.length>MAX_TESTS)throw Error('비공개 테스트는 1~50개가 필요합니다.');
  const mode=fields.executionMode;
  const compatible=new Set(catalog.filter(l=>Array.isArray(l.modes)?l.modes.includes(mode):(mode==='SQL'?l.id==='SQL':l.id!=='SQL'&&(mode!=='FUNCTION'||l.id!=='UMJUNSIK'))).map(l=>l.id));
  if(!allowedLanguages.length||new Set(allowedLanguages).size!==allowedLanguages.length||allowedLanguages.some(id=>!compatible.has(id)))
    throw Error('실행 방식에 맞는 허용 언어를 하나 이상 선택해 주세요.');
  if(solutions.length>MAX_SOLUTIONS)throw Error('정답 코드는 최대 '+MAX_SOLUTIONS+'개까지 추가할 수 있습니다.');
  const used=new Set();
  for(const solution of solutions){
    if(!allowedLanguages.includes(solution.language)||used.has(solution.language))throw Error('정답 코드는 허용 언어별로 하나만 등록할 수 있습니다.');
    used.add(solution.language);
    if(!solution.sourceCode?.trim())throw Error('정답 코드가 비어 있습니다. 비어 있는 항목은 삭제해 주세요.');
    if(new TextEncoder().encode(solution.sourceCode).byteLength>MAX_CODE_BYTES)throw Error('정답 코드는 각각 64 KiB 이하여야 합니다.');
    if(!solution.fileName||solution.fileName.length>120||/[\\/\u0000-\u001f]/.test(solution.fileName))throw Error('파일명은 경로 없이 120자 이하로 입력해 주세요.');
  }
  const tags=[...new Set(String(fields.tags||'').split(',').map(tag=>tag.trim()).filter(Boolean))];
  if(tags.length>10||tags.some(tag=>tag.length>50))throw Error('태그는 최대 10개, 각각 50자 이하로 입력해 주세요.');
  const groupId=fields.scope==='GROUP'?Number(fields.groupDestination||fields.groupId):null;
  if(fields.scope==='GROUP'&&(!Number.isSafeInteger(groupId)||groupId<1))throw Error('문제를 만들 그룹을 선택해 주세요.');
  return {title,description,inputDescription:String(fields.inputDescription||''),outputDescription:String(fields.outputDescription||''),
    constraints:String(fields.constraints||''),rankType:fields.rankType,rankInt:Number(fields.rankInt),
    timeLimitMs:Number(fields.timeLimitMs),memoryLimitMb:Number(fields.memoryLimitMb),executionMode:mode,
    examples,tests,languages:allowedLanguages,tags,sourceName:String(fields.sourceName||'').trim()||null,
    sourceUrl:String(fields.sourceUrl||'').trim()||null,referenceSolutions:solutions,groupId,
    visibility:groupId?'GROUP':fields.visibility||'PUBLIC'};
}
async function readSolutionFile(file){
  if(!file||file.size>MAX_CODE_BYTES)throw Error('파일은 64 KiB 이하의 UTF-8 텍스트여야 합니다.');
  if(!file.name||file.name.length>120||/[\\/\u0000-\u001f]/.test(file.name))throw Error('파일명은 경로 없이 120자 이하로 입력해 주세요.');
  const sourceCode=new TextDecoder('utf-8',{fatal:true}).decode(await file.arrayBuffer());
  if(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/.test(sourceCode))throw Error('바이너리 파일은 정답 코드로 사용할 수 없습니다.');
  return {fileName:file.name,sourceCode};
}
function editorMode(){return $('#author-form [name=executionMode]').value;}
function renderEditorLanguages(mode,selected){
  const box=$('#author-languages');box.replaceChildren();
  for(const language of supportedLanguages(mode)){
    const label=document.createElement('label');label.className='choice';
    const input=document.createElement('input');input.type='checkbox';input.value=language.id;input.checked=selected.includes(language.id);
    label.append(input,document.createTextNode(language.label));box.append(label);
  }
  for(const row of $('#solution-rows').children){
    const select=row.querySelector('[data-field=language]');
    for(const option of select.options)option.disabled=!supportedLanguages(mode).some(l=>l.id===option.value);
  }
  updateRepeatCounts();
}
function textField(labelText,field,value,rows=3){
  const label=document.createElement('label');label.textContent=labelText;
  const control=document.createElement('textarea');control.dataset.field=field;control.rows=rows;control.value=value??'';
  label.append(control);return label;
}
function addTextRow(kind,data={}){
  const box=$('#'+kind+'-rows'), max=kind==='example'?MAX_EXAMPLES:MAX_TESTS;
  if(box.children.length>=max)throw Error((kind==='example'?'예제':'테스트')+' 추가 한도에 도달했습니다.');
  const row=document.createElement('div');row.className='repeat-card';
  const heading=document.createElement('div');heading.className='repeat-heading';
  const title=document.createElement('strong');title.className='repeat-title';
  const remove=document.createElement('button');remove.type='button';remove.textContent='삭제';remove.className='secondary';
  remove.onclick=()=>{row.remove();updateRepeatCounts();};heading.append(title,remove);row.append(heading);
  const grid=document.createElement('div');grid.className='form-grid';
  grid.append(textField('입력','input',data.input),textField(kind==='example'?'출력':'기대 출력',kind==='example'?'output':'expected',kind==='example'?data.output:data.expected));
  row.append(grid);
  if(kind==='example')row.append(textField('설명','explanation',data.explanation,2));
  box.append(row);updateRepeatCounts();return row;
}
function addSolutionRow(data={}){
  const box=$('#solution-rows');if(box.children.length>=MAX_SOLUTIONS)throw Error('정답 코드는 최대 '+MAX_SOLUTIONS+'개입니다.');
  const used=new Set([...box.children].map(row=>row.querySelector('[data-field=language]').value));
  const languageId=data.language||supportedLanguages(editorMode()).find(l=>!used.has(l.id))?.id;
  if(!languageId)throw Error('추가할 수 있는 언어가 없습니다.');
  const row=document.createElement('div');row.className='repeat-card';
  const heading=document.createElement('div');heading.className='repeat-heading';
  const title=document.createElement('strong');title.className='repeat-title';
  const remove=document.createElement('button');remove.type='button';remove.textContent='삭제';remove.className='secondary';
  remove.onclick=()=>{row.remove();updateRepeatCounts();};heading.append(title,remove);row.append(heading);
  const grid=document.createElement('div');grid.className='form-grid';
  const languageLabel=document.createElement('label');languageLabel.textContent='언어';
  const select=document.createElement('select');select.dataset.field='language';
  for(const language of languages){const option=document.createElement('option');option.value=language.id;option.textContent=language.label;
    option.disabled=!supportedLanguages(editorMode()).some(l=>l.id===language.id);select.append(option);}
  select.value=languageId;languageLabel.append(select);
  const fileLabel=document.createElement('label');fileLabel.textContent='파일명';
  const fileName=document.createElement('input');fileName.type='text';fileName.dataset.field='fileName';fileName.maxLength=120;
  fileName.value=data.fileName||defaultFileNames[languageId]||'solution.txt';fileLabel.append(fileName);grid.append(languageLabel,fileLabel);row.append(grid);
  const codeLabel=textField('정답 코드','sourceCode',data.sourceCode,10);codeLabel.className='code-field';codeLabel.querySelector('textarea').dataset.codeEditor='';codeLabel.querySelector('textarea').spellcheck=false;row.append(codeLabel);
  const fileActions=document.createElement('div');fileActions.className='file-actions';
  const uploadLabel=document.createElement('label');uploadLabel.textContent='코드 파일 첨부';
  const file=document.createElement('input');file.type='file';file.accept='text/*,.py,.c,.cpp,.java,.js,.ts,.cs,.go,.rs,.kt,.rb,.sql,.ll,.pas,.pp,.php,.dart,.umm';
  file.onchange=async()=>{if(!file.files?.[0])return;try{const loaded=await readSolutionFile(file.files[0]);fileName.value=loaded.fileName;codeLabel.querySelector('textarea').value=loaded.sourceCode;notice('파일 내용을 정답 코드에 불러왔습니다. 저장 버튼으로 서버에 반영하세요.');}catch(e){notice(e.message);}finally{file.value='';}};
  uploadLabel.append(file);
  const download=document.createElement('button');download.type='button';download.textContent='코드 다운로드';download.className='secondary';
  download.onclick=()=>{try{const name=fileName.value.trim();if(!name||name.length>120||/[\\/\u0000-\u001f]/.test(name))throw Error('경로 없는 120자 이하 파일명을 입력해 주세요.');
    const url=URL.createObjectURL(new Blob([codeLabel.querySelector('textarea').value],{type:'text/plain;charset=utf-8'}));
    const link=document.createElement('a');link.href=url;link.download=name;link.click();setTimeout(()=>URL.revokeObjectURL(url),0);
  }catch(e){notice(e.message);}};
  let previousLanguage=languageId;
  select.onchange=()=>{if(fileName.value===defaultFileNames[previousLanguage])fileName.value=defaultFileNames[select.value]||'solution.txt';previousLanguage=select.value;updateRepeatCounts();};
  fileActions.append(uploadLabel,download);row.append(fileActions);box.append(row);updateRepeatCounts();return row;
}
function updateRepeatCounts(){
  for(const [kind,label,max] of [['example','예제',MAX_EXAMPLES],['test','테스트',MAX_TESTS],['solution','정답 코드',MAX_SOLUTIONS]]){
    const box=$('#'+kind+'-rows'),count=box.children.length;$('#'+kind+'-count').textContent='('+count+'/'+max+')';
    [...box.children].forEach((row,index)=>{row.querySelector('.repeat-title').textContent=label+' '+(index+1);
      const remove=row.querySelector('.repeat-heading button');remove.setAttribute('aria-label',label+' '+(index+1)+' 삭제');remove.disabled=kind==='test'&&count===1;});
    $('#add-'+kind).disabled=count>=max;
  }
  const used=new Set([...$('#solution-rows').children].map(row=>row.querySelector('[data-field=language]').value));
  if(supportedLanguages(editorMode()).every(l=>used.has(l.id)))$('#add-solution').disabled=true;
}
function editorRows(kind){return [...$('#'+kind+'-rows').children].map(row=>Object.fromEntries(
  [...row.querySelectorAll('[data-field]')].map(control=>[control.dataset.field,control.value])));}
function collectEditorPayload(){
  const fields=Object.fromEntries(new FormData($('#author-form')));
  fields.scope=$('#author-form [name=scope]').value;
  fields.groupDestination=$('#author-form [name=groupDestination]').value;
  const allowed=[...$('#author-languages').querySelectorAll('input:checked')].map(input=>input.value);
  return buildProblemPayload(fields,editorRows('example'),editorRows('test'),allowed,editorRows('solution'));
}
function resetEditor(){
  editorRequest++;groupDestinationsRequest++;
  $('#author-form').reset();$('#author-form [name=number]').value='';
  $('#author-form [name=groupId]').value='';$('#author-form [name=scope]').disabled=false;
  $('#author-form [name=groupDestination]').disabled=false;syncEditorScope();
  $('#example-rows').replaceChildren();$('#test-rows').replaceChildren();$('#solution-rows').replaceChildren();
  addTextRow('example');addTextRow('test');renderEditorLanguages(editorMode(),supportedLanguages(editorMode()).map(l=>l.id));
  $('#editor-heading').textContent='새 비공개 초안';$('#editor-state').textContent='저장하면 새 문제 번호가 발급됩니다.';
  editorCanSave=true;editorCanReview=false;$('#save-problem').disabled=false;$('#request-review').disabled=true;editorLoaded=true;
}
function loadEditorData(problem){
  groupDestinationsRequest++;
  $('#author-form').reset();
  const number=problem.number??problem.problem_number;
  $('#author-form [name=number]').value=number;
  const groupId=problem.groupId??null;
  $('#author-form [name=groupId]').value=groupId??'';
  $('#author-form [name=scope]').value=groupId?'GROUP':'GLOBAL';
  $('#author-form [name=scope]').disabled=true;
  $('#author-form [name=groupDestination]').disabled=Boolean(groupId);
  if(groupId){setGroupDestinationOption(groupId,problem.groupName||'그룹 #'+groupId);$('#author-form [name=groupDestination]').value=String(groupId);}
  syncEditorScope();
  for(const field of ['title','description','inputDescription','outputDescription','constraints','rankType','rankInt','timeLimitMs','memoryLimitMb','executionMode','sourceName','sourceUrl']){
    const input=$('#author-form [name='+field+']');if(input)input.value=problem[field]??'';
  }
  $('#author-form [name=tags]').value=(problem.tags||[]).join(', ');
  $('#example-rows').replaceChildren();$('#test-rows').replaceChildren();$('#solution-rows').replaceChildren();
  for(const item of problem.examples||[])addTextRow('example',item);
  for(const item of problem.tests||[])addTextRow('test',item);
  if(!$('#test-rows').children.length)addTextRow('test');
  renderEditorLanguages(editorMode(),problem.languages||[]);
  for(const item of problem.referenceSolutions||[])addSolutionRow(item);
  const status=problem.status||'DRAFT',visibility=problem.currentVisibility||'PRIVATE';
  if(!groupId)$('#author-form [name=visibility]').value=problem.visibility||'PUBLIC';
  $('#editor-heading').textContent='문제 #'+number+' 수정';
  $('#editor-state').textContent=status+' · '+visibility+(status==='PUBLISHED'&&visibility==='PUBLIC'?' · 공개 문제는 먼저 비공개로 전환해야 수정할 수 있습니다.':'');
  editorCanSave=typeof problem.canEdit==='boolean'?problem.canEdit:
    ['DRAFT','REJECTED'].includes(status)||(permissions.canManage&&status==='PUBLISHED'&&visibility==='PRIVATE');
  editorCanReview=editorCanSave&&['DRAFT','REJECTED'].includes(status);
  $('#save-problem').disabled=!editorCanSave;$('#request-review').disabled=!editorCanReview;editorLoaded=true;
}
function clearPrivateViews(){
  viewEpoch++;permissionsRequest++;problemListRequest++;problemRequest++;mineRequest++;adminListRequest++;
  editorRequest++;adminProblemRequest++;groupDestinationsRequest++;pollRequest++;clearTimeout(polling);polling=null;
  resetToken=null;currentProblem=null;executionMode='STDIN';
  editorLoaded=false;editorCanSave=true;editorCanReview=false;adminProblem=null;minePage=0;adminPage=0;
  setPermissions(null);
  CobwebGroups.clear();CobwebCommunity.clear();
  for(const id of ['mine-list','admin-list','admin-preview','example-rows','test-rows','solution-rows','author-languages','examples','problem-list','submission-list'])$('#'+id).replaceChildren();
  for(const id of ['problem-title','problem-meta','description','input-description','output-description','constraints','judge-result','profile-info','admin-detail-title','admin-detail-meta','editor-heading','editor-state'])$('#'+id).textContent='';
  for(const id of ['author-form','profile-form','login','email-send','email-verify','signup','reset-request','reset-verify','reset-confirm'])$('#'+id).reset();
  $('#author-form [name=number]').value='';$('#author-form [name=groupDestination]').replaceChildren();
  $('#source').value='';$('#admin-detail').hidden=true;
}
function setGroupDestinationOption(id,name){
  const select=$('#author-form [name=groupDestination]');
  if([...select.options].some(option=>option.value===String(id)))return;
  const option=document.createElement('option');option.value=String(id);option.textContent=name;select.append(option);
}
async function refreshGroupDestinations(){
  const session=sessionEpoch,view=viewEpoch,request=++groupDestinationsRequest;
  const select=$('#author-form [name=groupDestination]'),selected=select.value,loaded=$('#author-form [name=groupId]').value;
  const mine=await api('/api/groups/mine');if(!currentView('author',view,session)||request!==groupDestinationsRequest)return;
  select.replaceChildren();
  for(const group of mine.filter(group=>group.permissions?.includes('CREATE_PROBLEMS')))setGroupDestinationOption(group.id,group.groupName);
  if(loaded)setGroupDestinationOption(loaded,'그룹 #'+loaded);
  if([...select.options].some(option=>option.value===selected))select.value=selected;
  syncEditorScope();
}
function syncEditorScope(){
  const group=$('#author-form [name=scope]').value==='GROUP';
  $('#group-destination-label').hidden=!group;
  $('#author-form [name=visibility]').closest('label').hidden=group;
}
async function openEditor(number,asAdmin=false){
  const session=sessionEpoch,originView=viewEpoch,request=++editorRequest;
  const detail=await api(asAdmin?'/api/admin/problems/'+number:'/api/problems/'+number+'/edit');
  if(!currentSession(session)||originView!==viewEpoch||request!==editorRequest||!(permissions.canCreate||permissions.canManage))return;
  loadEditorData(detail);const shownView=await show('author');
  if(!currentView('author',shownView,session)||request!==editorRequest)return;
  $('#author-form [name=title]').focus();
}
async function startGroupProblem(gid,name){
  const session=sessionEpoch,shownView=await show('author');if(!currentView('author',shownView,session))return;
  resetEditor();setGroupDestinationOption(gid,name);
  $('#author-form [name=scope]').value='GROUP';$('#author-form [name=groupDestination]').value=String(gid);syncEditorScope();
  $('#author-form [name=title]').focus();
}
function problemNumber(item){return item.problem_number??item.number;}
function problemTitle(item){return item.problem_title??item.title??'(제목 없음)';}
function listItems(data){return Array.isArray(data)?data:(data?.items||data?.content||[]);}
async function listMine(){
  const session=sessionEpoch,view=viewEpoch,request=++mineRequest;
  const query=new URLSearchParams({page:String(minePage),search:$('#mine-search [name=search]').value});
  const items=listItems(await api('/api/problems/mine?'+query));if(!currentView('author',view,session)||request!==mineRequest)return;
  const box=$('#mine-list');box.replaceChildren();
  if(!items.length)box.textContent='작성한 문제가 없습니다.';
  for(const item of items){const row=document.createElement('article');row.className='row summary-row';
    const summary=document.createElement('div');const heading=document.createElement('h3');heading.textContent='#'+problemNumber(item)+' · '+problemTitle(item);
    const meta=document.createElement('p');meta.textContent=(item.status||'DRAFT')+' · '+(item.visibility||'PRIVATE');summary.append(heading,meta);
    const button=document.createElement('button');button.type='button';button.textContent='불러오기';button.onclick=()=>openEditor(problemNumber(item)).catch(e=>notice(e.message));
    row.append(summary,button);box.append(row);}
  $('#mine-previous').disabled=minePage===0;$('#mine-next').disabled=items.length<30;
}
async function listAdmin(){
  const session=sessionEpoch,view=viewEpoch,request=++adminListRequest;
  const search=Object.fromEntries(new FormData($('#admin-search'))),query=new URLSearchParams({page:String(adminPage),search:search.search||''});
  if(search.status)query.set('status',search.status);if(search.visibility)query.set('visibility',search.visibility);
  const items=listItems(await api('/api/admin/problems?'+query));if(!currentView('admin',view,session)||request!==adminListRequest||!permissions.canManage)return;
  const box=$('#admin-list');box.replaceChildren();
  if(!items.length)box.textContent='조건에 맞는 문제가 없습니다.';
  for(const item of items){const row=document.createElement('article');row.className='row summary-row';
    const summary=document.createElement('div');const heading=document.createElement('h3');heading.textContent='#'+problemNumber(item)+' · '+problemTitle(item);
    const meta=document.createElement('p');meta.textContent=(item.author_username||item.authorUsername||'작성자 미상')+' · '+(item.status||'')+' · '+(item.visibility||'')+' · '+(item.updated_at||item.updatedAt||'');
    summary.append(heading,meta);const button=document.createElement('button');button.type='button';button.textContent='상세 검토';button.onclick=()=>openAdminProblem(problemNumber(item)).catch(e=>notice(e.message));
    row.append(summary,button);box.append(row);}
  $('#admin-previous').disabled=adminPage===0;$('#admin-next').disabled=items.length<30;
}
function addReviewSection(box,title,content,markdown=false){
  const section=document.createElement('section');const heading=document.createElement('h3');heading.textContent=title;
  const pre=document.createElement(markdown?'div':'pre');pre.className='prose';if(markdown)renderMarkdown(pre,content);else pre.textContent=content??'';section.append(heading,pre);box.append(section);
}
function renderAdminProblem(detail){
  const number=detail.number??detail.problem_number;adminProblem={...detail,number};
  $('#admin-detail').hidden=false;$('#admin-detail-title').textContent='#'+number+' · '+problemTitle(detail);
  const actual=detail.currentVisibility||detail.visibility||'PRIVATE',group=Boolean(detail.groupId);
  $('#admin-detail-meta').textContent=(detail.status||'')+' · '+actual+(group?' (그룹 #'+detail.groupId+')':'')+' · '+(detail.authorUsername||detail.author_username||'')+
    (detail.usedInContest?' · 시험에 사용된 문제는 복제하여 수정해 주세요.':'');
  $('#admin-edit').disabled=Boolean(detail.usedInContest);
  const box=$('#admin-preview');box.replaceChildren();
  addReviewSection(box,'문제 본문',detail.description,true);addReviewSection(box,'입력 설명',detail.inputDescription,true);
  addReviewSection(box,'출력 설명',detail.outputDescription,true);addReviewSection(box,'제한 사항',detail.constraints,true);
  addReviewSection(box,'설정',[(detail.rankType||'')+' '+(detail.rankInt||''),(detail.timeLimitMs||'')+' ms / '+(detail.memoryLimitMb||'')+' MB',detail.executionMode,(detail.languages||[]).join(', '),(detail.tags||[]).join(', '),detail.sourceName||'',detail.sourceUrl||''].join('\n'));
  (detail.examples||[]).forEach((item,index)=>renderExample(box,item,index));
  (detail.tests||[]).forEach((item,index)=>addReviewSection(box,'비공개 테스트 '+(index+1),'입력\n'+item.input+'\n\n기대 출력\n'+item.expected));
  (detail.referenceSolutions||[]).forEach((item,index)=>addReviewSection(box,'정답 코드 '+(index+1)+' · '+item.language+' · '+item.fileName,item.sourceCode));
  $('#visibility-form [name=visibility]').value=actual==='PUBLIC'||(group&&detail.status==='PUBLISHED')?'PUBLIC':'PRIVATE';
  $('#visibility-form [value=PUBLIC]').textContent=group?'그룹 공개':'전체 공개';
  $('#visibility-form [value=PRIVATE]').textContent=group?'그룹 숨김':'비공개';
  $('#review [value=PUBLISHED]').textContent=group?'그룹 공개 승인':'공개 승인';
  $('#visibility-form [value=PUBLIC]').disabled=group?!['PUBLISHED','ARCHIVED'].includes(detail.status):detail.status!=='PUBLISHED';
  for(const option of $('#review [name=status]').options)option.disabled=detail.status!=='PENDING_REVIEW'&&option.value!=='ARCHIVED';
  $('#review [name=status]').value=detail.status==='PENDING_REVIEW'?'PUBLISHED':'ARCHIVED';
}
async function openAdminProblem(number){
  const session=sessionEpoch,view=viewEpoch,request=++adminProblemRequest;
  const detail=await api('/api/admin/problems/'+number);
  if(!currentView('admin',view,session)||request!==adminProblemRequest||!permissions.canManage)return;
  renderAdminProblem(detail);
}
$('#new-problem').onclick=resetEditor;
$('#author-form [name=scope]').onchange=syncEditorScope;
$('#add-example').onclick=()=>{try{addTextRow('example');}catch(e){notice(e.message);}};
$('#add-test').onclick=()=>{try{addTextRow('test');}catch(e){notice(e.message);}};
$('#add-solution').onclick=()=>{try{addSolutionRow();}catch(e){notice(e.message);}};
$('#author-form [name=executionMode]').onchange=()=>{renderEditorLanguages(editorMode(),supportedLanguages(editorMode()).map(l=>l.id));};
$('#author-form').onsubmit=async event=>{
  event.preventDefault();const button=$('#save-problem'),session=sessionEpoch,view=viewEpoch,request=++editorRequest;button.disabled=true;
  try{const body=collectEditorPayload(),number=$('#author-form [name=number]').value;
    const result=await api('/api/problems'+(number?'/'+number:''),number?'PUT':'POST',body);
    if(!currentView('author',view,session)||request!==editorRequest)return;
    const savedNumber=number||result,detail=await api('/api/problems/'+savedNumber+'/edit');
    if(!currentView('author',view,session)||request!==editorRequest)return;
    loadEditorData(detail);
    await listMine();if(currentView('author',view,session)&&request===editorRequest)notice('문제 #'+savedNumber+' 초안을 저장했습니다.');
  }catch(e){if(currentView('author',view,session)&&request===editorRequest&&!e.stale)
      notice(e.status===409?'저장할 수 없습니다. 중복 제목 또는 현재 상태를 확인해 주세요. '+e.message:e.message);}
  finally{button.disabled=!editorCanSave;}
};
$('#request-review').onclick=async()=>{
  const number=$('#author-form [name=number]').value;if(!number){notice('먼저 초안을 저장해 주세요.');return;}
  const button=$('#request-review'),session=sessionEpoch,view=viewEpoch,request=++editorRequest;button.disabled=true;
  try{const saved=await api('/api/problems/'+number+'/edit');
    if(!currentView('author',view,session)||request!==editorRequest)return;
    if(!saved.referenceSolutions?.length)throw Error('검수 요청 전 정답 코드를 하나 이상 저장해 주세요.');
    await api('/api/problems/'+number+'/review-request','POST');if(!currentView('author',view,session)||request!==editorRequest)return;
    const detail=await api('/api/problems/'+number+'/edit');if(!currentView('author',view,session)||request!==editorRequest)return;
    loadEditorData(detail);
    await listMine();if(currentView('author',view,session)&&request===editorRequest)notice('문제 #'+number+'의 검수를 요청했습니다.');
  }catch(e){if(currentView('author',view,session)&&request===editorRequest&&!e.stale)notice(e.message);}
  finally{button.disabled=!editorCanReview;}
};
form('mine-search',async()=>{minePage=0;await listMine();});
$('#mine-previous').onclick=()=>{minePage=Math.max(0,minePage-1);listMine().catch(e=>notice(e.message));};
$('#mine-next').onclick=()=>{minePage++;listMine().catch(e=>notice(e.message));};
form('admin-search',async()=>{adminPage=0;await listAdmin();});
$('#admin-previous').onclick=()=>{adminPage=Math.max(0,adminPage-1);listAdmin().catch(e=>notice(e.message));};
$('#admin-next').onclick=()=>{adminPage++;listAdmin().catch(e=>notice(e.message));};
$('#admin-close').onclick=()=>{adminProblemRequest++;$('#admin-detail').hidden=true;adminProblem=null;};
$('#admin-edit').onclick=async()=>{
  if(!adminProblem)return;
  if(adminProblem.usedInContest){notice('시험에 사용된 문제는 복제하여 수정해 주세요.');return;}
  const session=sessionEpoch,view=viewEpoch;
  try{const number=adminProblem.number;
    if(adminProblem.status==='PUBLISHED'&&(adminProblem.currentVisibility==='PUBLIC'||adminProblem.groupId)){
      if(!confirm(adminProblem.groupId?'그룹 문제를 먼저 숨긴 뒤 편집하시겠습니까?':'공개 문제를 먼저 비공개로 전환한 뒤 편집하시겠습니까?'))return;
      await api('/api/admin/problems/'+number+'/visibility','PATCH',{visibility:'PRIVATE',cause:'관리자 편집'});
    }
    if(!currentView('admin',view,session))return;
    await openEditor(number,true);
  }catch(e){if(currentView('admin',view,session)&&!e.stale)notice(e.message);}
};
form('review',async data=>{
  if(!adminProblem)throw Error('검토할 문제를 먼저 선택해 주세요.');
  const session=sessionEpoch,view=viewEpoch,number=adminProblem.number;
  await api('/api/problems/'+number+'/review','POST',{status:data.status,cause:data.cause});
  if(!currentView('admin',view,session))return;
  await openAdminProblem(number);await listAdmin();if(currentView('admin',view,session))notice('검수 처리를 완료했습니다.');
});
form('visibility-form',async data=>{
  if(!adminProblem)throw Error('공개 범위를 바꿀 문제를 먼저 선택해 주세요.');
  if(data.visibility==='PUBLIC'&&!(adminProblem.groupId?['PUBLISHED','ARCHIVED'].includes(adminProblem.status):adminProblem.status==='PUBLISHED'))throw Error('승인되거나 보관된 그룹 문제만 공개할 수 있습니다.');
  const session=sessionEpoch,view=viewEpoch,number=adminProblem.number;
  await api('/api/admin/problems/'+number+'/visibility','PATCH',{visibility:data.visibility,cause:data.cause});
  if(!currentView('admin',view,session))return;
  await openAdminProblem(number);await listAdmin();if(currentView('admin',view,session))notice('공개 범위를 변경했습니다.');
});
document.querySelectorAll('[data-oauth]').forEach(b=>b.onclick=async()=>{try{const r=await api('/api/auth/oauth/'+b.dataset.oauth+'/authorize-url');location.href=r.authorizeUrl;}catch(e){notice(e.message);}});
if(typeof CobwebCodeEditor==='object')CobwebCodeEditor.install(document);
CobwebGroups.init({api,notice,show,isAuthenticated:()=>Boolean(token),languageLabel,languages:()=>languages,openProblem,openEditor,startGroupProblem,renderMarkdown,renderExample});
CobwebCommunity.init({api,notice,show,isAuthenticated:()=>Boolean(token),isOperator:()=>permissions.canManage,openProblem});
(async()=>{const fragment=new URLSearchParams(location.hash.slice(1));token=fragment.get('accessToken');if(token){sessionEpoch++;history.replaceState({},'',location.pathname);}
  const session=sessionEpoch;languages=await api('/api/languages');if(!currentSession(session))return;
  if(!token)try{const fresh=await api('/api/auth/reissue','POST',null,false);if(!currentSession(session))return;token=fresh.accessToken;}catch{}
  if(!currentSession(session))return;
  if(token)$('#account').textContent='내 계정';await refreshPermissions();if(!currentSession(session))return;
  const initialView={'/create':'author','/admin':'admin','/groups':'groups','/community':'community','/notices':'notices'}[location.pathname]||'problems';
  await show(initialView);})().catch(e=>{if(!e.stale)notice(e.message);});
