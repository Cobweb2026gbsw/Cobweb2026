// 토큰은 메모리에만 보관합니다. 서버 응답을 innerHTML로 넣지 않아 문제 본문의 스크립트 실행을 막습니다.
let token=null, page=0, currentProblem=null, resetToken=null, polling=null, languages=[], executionMode='STDIN';
const $=s=>document.querySelector(s);
function notice(text){$('#notice').textContent=text;}
async function api(path,method='GET',body=null,retry=true){
  const headers={'Content-Type':'application/json'};
  if(token)headers.Authorization='Bearer '+token;
  const res=await fetch(path,{method,headers,credentials:'same-origin',body:body===null?undefined:JSON.stringify(body)});
  if(res.status===401&&retry&&(!path.startsWith('/api/auth/')||path==='/api/auth/logout')){
    try{const fresh=await api('/api/auth/reissue','POST',null,false);token=fresh.accessToken;return api(path,method,body,false);}catch{}
  }
  const data=await res.json().catch(()=>({}));
  if(!res.ok)throw Error(data.message||((res.status===401||res.status===403)?'로그인이 필요하거나 접근 권한이 없습니다.':'요청 실패 ('+res.status+')'));
  return data.data;
}
async function show(id){
  clearTimeout(polling);document.querySelectorAll('.view').forEach(v=>v.hidden=v.id!==id);notice('');
  try{
    if(id==='problems')await list();
    if(id==='submissions')rows('#submission-list',await api('/api/submissions'),s=>[s.problem_number+' · '+s.problem_title,languageLabel(s.language)+' · '+s.status]);
    if(id==='ranking')rows('#rank-list',await api('/api/ranking'),(p,i)=>[(i+1)+'. '+p.username,p.rank_type+' '+p.rank_int+' · '+p.solved_count+'문제']);
    if(id==='profile'){const p=await api('/api/me');$('#profile-info').textContent=p.username+' · '+p.rank_type+' '+p.rank_int+'\n해결 '+p.solved_count+' / 제출 '+p.submit_count;
      $('#profile-form [name=bio]').value=p.bio;$('#profile-form [name=githubUrl]').value=p.github_url;}
  }catch(e){notice(e.message);}
}
function rows(selector,data,render){
  const box=$(selector);box.replaceChildren();if(!data.length){box.textContent='아직 기록이 없습니다.';return;}
  data.forEach((item,i)=>{const row=document.createElement('div');row.className='row';
    render(item,i).forEach(text=>{const span=document.createElement('span');span.textContent=text;row.append(span);});box.append(row);});
}
async function list(){
  const data=await api('/api/problems?page='+page+'&search='+encodeURIComponent($('#search [name=q]').value));
  rows('#problem-list',data,p=>[p.problem_number+' · '+p.problem_title,p.rank_type+' '+p.rank_int]);
  [...$('#problem-list').children].forEach((row,i)=>{if(!data[i])return;const b=document.createElement('button');b.textContent='풀기';b.onclick=()=>openProblem(data[i].problem_number);row.append(b);});
  $('#previous').disabled=page===0;$('#next').disabled=data.length<30;
}
async function openProblem(number){
  try{const p=await api('/api/problems/'+number);currentProblem=number;await show('problem');
    $('#problem-title').textContent=number+'. '+p.problem_title;$('#problem-meta').textContent=p.rank_type+' '+p.rank_int+' · '+p.time_limit_ms+' ms · '+p.memory_limit_mb+' MB';
    for(const [id,key] of [['description','description'],['input-description','input_description'],['output-description','output_description'],['constraints','constraints_text']])$('#'+id).textContent=p[key]||'';
    $('#examples').replaceChildren();p.examples.forEach((e,i)=>{const pre=document.createElement('pre');pre.textContent='예제 '+(i+1)+'\n'+e.input_text+'\n→ '+e.output_text+'\n'+(e.explanation||'');$('#examples').append(pre);});
    executionMode=p.execution_mode;
    const select=$('#submission-language'), previous=select.value;
    select.replaceChildren();
    languages.filter(l=>p.languages.includes(l.id)).forEach(l=>{const option=document.createElement('option');option.value=l.id;option.textContent=l.label;select.append(option);});
    if(p.languages.includes(previous))select.value=previous;
    updateLanguageHelp();$('#judge-result').textContent='';
  }catch(e){notice(e.message);}
}
function form(id,action){$('#'+id).onsubmit=async e=>{e.preventDefault();const b=e.target.querySelector('button');b.disabled=true;
  try{await action(Object.fromEntries(new FormData(e.target)));}catch(e){notice(e.message);}finally{b.disabled=false;}};}
document.querySelectorAll('[data-view]').forEach(b=>b.onclick=()=>show(b.dataset.view));
form('search',async()=>{page=0;await list();});
$('#previous').onclick=()=>{page=Math.max(0,page-1);list().catch(e=>notice(e.message));};
$('#next').onclick=()=>{page++;list().catch(e=>notice(e.message));};
form('login',async d=>{const r=await api('/api/auth/login','POST',d);token=r.accessToken;$('#account').textContent=d.username;await show('problems');});
form('email-send',async d=>{await api('/api/email-verifications','POST',{...d,purpose:'JOIN'});notice('인증 코드를 발송했습니다.');});
form('email-verify',async d=>{await api('/api/email-verifications/verify','POST',{...d,purpose:'JOIN'});notice('이메일 인증을 완료했습니다.');});
form('signup',async d=>{await api('/api/auth/signup','POST',d);notice('가입되었습니다. 로그인해 주세요.');});
form('reset-request',async d=>{await api('/api/auth/password-reset/request','POST',d);notice('등록된 이메일이라면 재설정 코드를 발송했습니다.');});
form('reset-verify',async d=>{const r=await api('/api/auth/password-reset/verify-code','POST',d);resetToken=r.resetToken;notice('새 비밀번호를 입력하세요.');});
form('reset-confirm',async d=>{if(!resetToken)throw Error('먼저 코드를 확인해 주세요.');await api('/api/auth/password-reset/confirm','POST',{...d,resetToken});resetToken=null;notice('비밀번호를 변경했습니다.');});
form('profile-form',async d=>{await api('/api/me','PATCH',d);notice('프로필을 저장했습니다.');});
$('#logout').onclick=async()=>{
  let failure=null;
  try{await api('/api/auth/logout','POST');}catch(e){failure=e;}
  token=null;$('#account').textContent='로그인';await show('auth');
  if(failure)notice('서버 로그아웃에 실패했습니다. 다시 시도해 주세요: '+failure.message);
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
  LLVM_IR:'define i1 @sol(ptr %matrix, i32 %target_row, i32 %target_col) — matrix는 행 우선 i32[225]'
};
function updateLanguageHelp(){
  const id=$('#submission-language').value;
  const names={JAVA:'Main.java / public class Main',KOTLIN:'Main.kt / fun main()',CSHARP:'Main.cs / Main 또는 최상위 문장',LLVM_IR:'LLVM 18 텍스트 IR / main 진입점'};
  $('#language-help').textContent=executionMode==='FUNCTION'?(functionSignatures[id]||'지원하지 않는 함수형 언어')+' · true/false를 반환하세요. main은 작성하지 않습니다.':
    executionMode==='SQL'?'SQLite 읽기 쿼리 한 개를 제출하세요. 각 케이스는 별도의 DB이며 결과는 헤더 없는 TSV입니다.':
    (names[id]||languageLabel(id))+' · 표준 입력을 읽고 표준 출력에 답을 쓰세요. 외부 패키지 설치는 지원하지 않습니다.';
  $('#source').placeholder=executionMode==='FUNCTION'?(id==='PYTHON'?'def sol(matrix, target):\n    return False':functionSignatures[id]||'sol 함수를 작성하세요.'):
    executionMode==='SQL'?'SELECT ... FROM ... ORDER BY ...;':'선택한 언어의 풀이 코드를 입력하세요.';
}
$('#submission-language').onchange=updateLanguageHelp;
form('submit',async d=>{const id=await api('/api/submissions','POST',{problemNumber:currentProblem,sourceCode:d.source,language:d.language});await poll(id);});
async function poll(id){try{const r=await api('/api/submissions/'+id);$('#judge-result').textContent='제출 #'+id+' · '+languageLabel(r.language)+' · '+r.status+(r.score===null?'':' · '+r.score+'점');
  if(['QUEUED','RUNNING'].includes(r.status))polling=setTimeout(()=>poll(id),1500);
}catch(e){notice(e.message);}}
form('author-form',async d=>{
  const body={...d,rankInt:+d.rankInt,timeLimitMs:+d.timeLimitMs,memoryLimitMb:+d.memoryLimitMb,
    examples:[{input:d.exampleInput,output:d.exampleOutput,explanation:''}],tests:[{input:d.testInput,expected:d.testOutput}]};
  const result=await api('/api/problems'+(d.number?'/'+d.number:''),d.number?'PUT':'POST',body);
  if(!d.number)$('#author-form [name=number]').value=result;
  notice('초안을 저장했습니다. 문제 번호: '+(d.number||result));
});
form('review-request',async d=>{await api('/api/problems/'+d.number+'/review-request','POST');notice('검수를 요청했습니다.');});
form('review',async d=>{await api('/api/problems/'+d.number+'/review','POST',{status:d.status,cause:d.cause});notice('검수 처리를 완료했습니다.');});
document.querySelectorAll('[data-oauth]').forEach(b=>b.onclick=async()=>{try{const r=await api('/api/auth/oauth/'+b.dataset.oauth+'/authorize-url');location.href=r.authorizeUrl;}catch(e){notice(e.message);}});
(async()=>{const fragment=new URLSearchParams(location.hash.slice(1));token=fragment.get('accessToken');if(token)history.replaceState({},'',location.pathname);
  languages=await api('/api/languages');
  if(!token)try{token=(await api('/api/auth/reissue','POST',null,false)).accessToken;}catch{}
  if(token)$('#account').textContent='내 계정';await show('problems');})().catch(e=>notice(e.message));
