// 토큰은 메모리에만 보관합니다. 서버 응답을 innerHTML로 넣지 않아 문제 본문의 스크립트 실행을 막습니다.
let token=null, page=0, currentProblem=null, resetToken=null, polling=null;
const $=s=>document.querySelector(s);
function notice(text){$('#notice').textContent=text;}
async function api(path,method='GET',body=null,retry=true){
  const headers={'Content-Type':'application/json'};
  if(token)headers.Authorization='Bearer '+token;
  const res=await fetch(path,{method,headers,credentials:'same-origin',body:body===null?undefined:JSON.stringify(body)});
  if((res.status===401||res.status===403)&&retry&&!path.startsWith('/api/auth/')){
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
    if(id==='submissions')rows('#submission-list',await api('/api/submissions'),s=>[s.problem_number+' · '+s.problem_title,s.status]);
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
    $('#judge-result').textContent='';
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
form('reset-request',async d=>{await api('/api/auth/password-reset/request','POST',d);notice('재설정 코드를 발송했습니다.');});
form('reset-verify',async d=>{const r=await api('/api/auth/password-reset/verify-code','POST',d);resetToken=r.resetToken;notice('새 비밀번호를 입력하세요.');});
form('reset-confirm',async d=>{if(!resetToken)throw Error('먼저 코드를 확인해 주세요.');await api('/api/auth/password-reset/confirm','POST',{...d,resetToken});resetToken=null;notice('비밀번호를 변경했습니다.');});
form('profile-form',async d=>{await api('/api/me','PATCH',d);notice('프로필을 저장했습니다.');});
$('#logout').onclick=async()=>{try{await api('/api/auth/logout','POST');token=null;$('#account').textContent='로그인';show('auth');}catch(e){notice(e.message);}};
form('submit',async d=>{const id=await api('/api/submissions','POST',{problemNumber:currentProblem,sourceCode:d.source});await poll(id);});
async function poll(id){try{const r=await api('/api/submissions/'+id);$('#judge-result').textContent='제출 #'+id+' · '+r.status+(r.score===null?'':' · '+r.score+'점');
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
  if(!token)try{token=(await api('/api/auth/reissue','POST',null,false)).accessToken;}catch{}
  if(token)$('#account').textContent='내 계정';await show('problems');})();
