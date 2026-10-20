// 그룹·역할·코딩 테스트 화면. 모든 사용자 문자열은 textContent/value로만 표시합니다.
const CobwebGroups=(()=>{
  const FLAGS=[['VIEW_PROBLEMS','문제 보기'],['CREATE_PROBLEMS','문제 만들기'],['EDIT_PROBLEMS','문제 편집'],['REVIEW_PROBLEMS','문제 검수'],['MANAGE_MEMBERS','회원 관리'],['MANAGE_ROLES','역할 관리'],['MANAGE_GROUP','그룹 설정'],['CREATE_CONTESTS','코테 생성'],['MANAGE_CONTESTS','코테 관리'],['VIEW_CONTEST_RESULTS','코테 결과 보기'],['TAKE_CONTESTS','코테 참여']];
  let ctx,groupPage=0,groupQuery='',groupDetail=null,contest=null,contestGroupId=null,contestProblem=null,contestDraft=null;
  let ticker=null,refreshTimer=null,activeListeners=[],eventLocked=false,lastBoundaryRefresh=0,contestEpoch=0,questionRequest=0,groupEpoch=0,groupDetailRequest=0;
  let publicRequest=0,mineRequest=0,groupProblemRequest=0,contestRefreshRequest=0,contestRenderRequest=0,contestMineRequest=0,resultsRequest=0,staffSubmissionRequest=0;
  // 패널별 마지막 검색/페이지를 보존해 검수 후에도 현재 보고 있던 목록만 갱신합니다.
  const groupProblemViews=new WeakMap();
  const $=selector=>document.querySelector(selector);
  const node=(tag,text='',className='')=>{const result=document.createElement(tag);result.textContent=String(text??'');if(className)result.className=className;return result;};
  const button=(text,action,secondary=false)=>{const result=node('button',text,secondary?'secondary':'');result.type='button';let pending=false;result.onclick=()=>{if(pending)return;pending=true;result.disabled=true;return Promise.resolve().then(action).catch(error=>ctx.notice(error.message)).finally(()=>{pending=false;result.disabled=false;});};return result;};
  const list=value=>Array.isArray(value)?value:(value?.items||value?.content||[]);
  const problemNumber=value=>value.problemNumber??value.problem_number??value.number;
  const problemTitle=value=>value.problemTitle??value.problem_title??value.title??'(제목 없음)';
  const base=gid=>'/api/groups/'+gid;
  const contestBase=(gid,cid)=>base(gid)+'/contests/'+cid;
  const can=permission=>groupDetail?.permissions?.includes(permission);
  const loggedIn=()=>ctx.isAuthenticated();
  const groupScope=()=>({epoch:groupEpoch,detailRequest:groupDetailRequest});
  const groupScopeCurrent=scope=>scope.epoch===groupEpoch&&scope.detailRequest===groupDetailRequest&&$('#groups')?.hidden!==true;
  const contestScope=()=>({epoch:contestEpoch});
  const contestScopeCurrent=scope=>scope.epoch===contestEpoch&&$('#contest')?.hidden!==true;
  function init(context){ctx=context;}
  function field(labelText,name,value='',type='text',options=null){
    const label=node('label',labelText),control=document.createElement(options?'select':'input');control.name=name;
    if(options){for(const [key,text] of options){const option=node('option',text);option.value=key;control.append(option);}}else control.type=type;
    control.value=value??'';label.append(control);return label;
  }
  function multiline(labelText,name,value='',rows=4){const label=node('label',labelText),control=document.createElement('textarea');control.name=name;control.value=value??'';control.rows=rows;label.append(control);return label;}
  function checkbox(text,name,checked=false){const label=node('label',text,'check-field'),control=document.createElement('input');control.type='checkbox';control.name=name;control.checked=Boolean(checked);label.prepend(control);return label;}
  function formAction(form,action){form.onsubmit=async event=>{event.preventDefault();const submit=form.querySelector('[type=submit]');if(submit)submit.disabled=true;try{await action(Object.fromEntries(new FormData(form)));}catch(error){ctx.notice(error.status===409?'상태가 변경되었거나 중복된 값입니다. 다시 확인해 주세요. '+error.message:error.message);}finally{if(submit)submit.disabled=false;}};}
  async function open(){
    const epoch=++groupEpoch;groupDetailRequest++;groupDetail=null;
    const root=$('#groups-app');root.replaceChildren();
    const head=node('div','','section-heading'),copy=node('div');copy.append(node('p','함께 푸는 문제','eyebrow'),node('h1','그룹'),node('p','그룹별 문제, 역할, 시간제 코딩 테스트를 관리합니다.'));head.append(copy);root.append(head);
    const grid=node('div','','group-browser');
    const publicPanel=node('section','','panel'),minePanel=node('section','','panel');
    publicPanel.append(node('h2','공개 그룹 찾기'));
    const search=document.createElement('form');search.className='inline-form';search.append(field('그룹 이름','search',groupQuery,'search'));const submit=node('button','검색');submit.type='submit';search.append(submit);
    formAction(search,async values=>{groupQuery=values.search||'';groupPage=0;await renderPublic(publicPanel);});publicPanel.append(search,node('div','','group-public-list'));
    const pager=node('div','','pager');pager.append(button('이전',async()=>{groupPage=Math.max(0,groupPage-1);await renderPublic(publicPanel);},true),button('다음',async()=>{groupPage++;await renderPublic(publicPanel);},true));publicPanel.append(pager);
    minePanel.append(node('h2','내 그룹'));
    if(loggedIn()){minePanel.append(node('p','그룹 가입·생성은 합쳐서 최대 3개입니다. 그룹 생성은 해결한 문제 50개 이상이 필요합니다.','field-hint'),node('div','','group-mine-list'));renderCreateGroup(minePanel);}else minePanel.append(node('p','로그인하면 가입한 그룹과 만들기 기능이 나타납니다.'));
    grid.append(publicPanel,minePanel);root.append(grid,node('div','','group-detail-slot'));
    await renderPublic(publicPanel);if(epoch!==groupEpoch)return;
    if(loggedIn())await renderGroupMine(minePanel);
  }
  async function renderPublic(panel){
    const epoch=groupEpoch,request=++publicRequest,items=list(await ctx.api('/api/groups?'+new URLSearchParams({page:String(groupPage),search:groupQuery})));
    if(epoch!==groupEpoch||request!==publicRequest)return;
    const box=panel.querySelector('.group-public-list');box.replaceChildren();
    if(!items.length)box.append(node('p','검색 결과가 없습니다.'));
    for(const item of items){const card=node('article','','group-card');const name=node('h3',item.groupName),meta=node('p',(item.memberCount??0)+'명 참여');card.append(name,meta);
      if(loggedIn())card.append(button('가입',async()=>{const scope=groupScope();await ctx.api(base(item.id)+'/join','POST');if(!groupScopeCurrent(scope))return;ctx.notice('그룹에 가입했습니다.');await openGroup(item.id);if(scope.epoch!==groupEpoch||$('#groups')?.hidden===true)return;await renderGroupMine($('#groups-app .group-browser .panel:last-child'));}));
      else card.append(node('p','로그인하면 가입할 수 있습니다.','field-hint'));box.append(card);}
    const [previous,next]=panel.querySelectorAll('.pager button');previous.disabled=groupPage===0;next.disabled=items.length<30;
  }
  async function renderGroupMine(panel){
    const epoch=groupEpoch,request=++mineRequest,items=list(await ctx.api('/api/groups/mine'));
    if(epoch!==groupEpoch||request!==mineRequest)return;
    const box=panel.querySelector('.group-mine-list');box.replaceChildren();
    if(!items.length)box.append(node('p','참여 중인 그룹이 없습니다.'));
    for(const item of items){const card=node('article','','group-card');card.append(node('h3',item.groupName),node('p',(item.roleName||'회원')+' · '+(item.memberCount??0)+'명'),button('그룹 열기',()=>openGroup(item.id)));box.append(card);}
  }
  function renderCreateGroup(panel){
    const form=document.createElement('form');form.className='group-create-form';form.append(node('h3','그룹 만들기'),field('그룹 이름','name'),multiline('소개','description','',3));
    form.querySelector('[name=name]').maxLength=16;form.querySelector('[name=name]').required=true;form.querySelector('[name=description]').maxLength=1000;
    const submit=node('button','만들기');submit.type='submit';form.append(submit);formAction(form,async values=>{const scope=groupScope(),gid=await ctx.api('/api/groups','POST',values);if(!groupScopeCurrent(scope))return;ctx.notice('그룹을 만들었습니다.');await renderGroupMine(panel);if(!groupScopeCurrent(scope))return;await openGroup(gid);});panel.append(form);
  }
  async function openGroup(gid){
    const epoch=groupEpoch,request=++groupDetailRequest,detail=await ctx.api(base(gid));
    if(epoch!==groupEpoch||request!==groupDetailRequest)return;
    const me=await ctx.api('/api/me').catch(()=>null);
    if(epoch!==groupEpoch||request!==groupDetailRequest)return;
    groupDetail=detail;
    const root=$('#groups-app .group-detail-slot');if(!root)return;root.replaceChildren();
    const group=detail.group,head=node('div','','section-heading'),copy=node('div');copy.append(node('p','그룹 #'+gid+' · '+(group.memberCount??0)+'명','eyebrow'),node('h2',group.groupName),node('p',group.description||''));head.append(copy);
    if(!groupDetail.members.some(member=>member.owner&&member.username===me?.username))head.append(button('그룹 탈퇴',async()=>{if(!confirm('이 그룹에서 탈퇴하시겠습니까?'))return;const scope=groupScope();await ctx.api(base(gid)+'/leave','POST');if(!groupScopeCurrent(scope))return;groupDetail=null;await open();},true));
    root.append(head);if(can('CREATE_PROBLEMS'))root.append(button('이 그룹 문제 만들기',()=>ctx.startGroupProblem(gid,group.groupName)));
    const sections=node('div','','group-detail-grid'),problems=node('section','','panel'),contests=node('section','','panel');problems.append(node('h3','그룹 공개 문제'),node('div','','group-problem-list'));contests.append(node('h3','코딩 테스트'),node('div','','group-contest-list'));sections.append(problems,contests);root.append(sections);
    if(can('REVIEW_PROBLEMS')||can('EDIT_PROBLEMS'))renderReviewQueue(root,gid,()=>renderGroupProblems(problems,gid));
    if(can('MANAGE_GROUP'))renderGroupSettings(root,gid,group);
    renderMembers(root,gid);renderRoles(root,gid);
    await Promise.allSettled([renderGroupProblems(problems,gid),renderContests(contests,gid)]);
    if(epoch!==groupEpoch||request!==groupDetailRequest)return;
    root.scrollIntoView({behavior:'smooth',block:'start'});
  }
  function renderGroupSettings(root,gid,group){
    const panel=node('section','','panel'),form=document.createElement('form');panel.append(node('h3','그룹 설정'));
    form.append(field('그룹 이름','name',group.groupName),multiline('소개','description',group.description,3));form.querySelector('[name=name]').maxLength=16;form.querySelector('[name=description]').maxLength=1000;
    const save=node('button','설정 저장');save.type='submit';form.append(save);formAction(form,async values=>{const scope=groupScope();await ctx.api(base(gid),'PATCH',values);if(!groupScopeCurrent(scope))return;ctx.notice('그룹 설정을 저장했습니다.');await openGroup(gid);});panel.append(form);root.append(panel);
  }
  function renderReviewQueue(root,gid,refreshProblems){
    const panel=node('section','','panel'),search=document.createElement('form'),itemsBox=node('div','','group-review-items'),detailBox=node('div','','group-review-detail');
    panel.append(node('h3','그룹 문제 관리'),node('p','검수·편집 권한자가 비공개 테스트와 정답 코드를 확인합니다. 시험에 사용된 문제는 복제 후 편집하세요.','field-hint'));
    search.className='inline-form';search.append(field('제목 검색','search','','search'));const submit=node('button','검색');submit.type='submit';search.append(submit);
    const epoch=groupEpoch,detailRequest=groupDetailRequest;let page=0,query='',loadRequest=0,reviewRequest=0;async function load(){const request=++loadRequest,params=new URLSearchParams({page:String(page),search:query}),items=list(await ctx.api(base(gid)+'/problems/review-queue?'+params));if(epoch!==groupEpoch||detailRequest!==groupDetailRequest||request!==loadRequest)return;itemsBox.replaceChildren();
      if(!items.length)itemsBox.append(node('p','그룹 문제가 없습니다.'));
      for(const item of items){const row=node('div','','group-problem-row');row.append(node('span','#'+problemNumber(item)+' · '+problemTitle(item)+' · '+(item.status||'')+' · '+(item.authorUsername||'')),button('상세',()=>openReviewDetail(problemNumber(item)),true));itemsBox.append(row);}
      pager.replaceChildren();const previous=button('이전',async()=>{page--;await load();},true),next=button('다음',async()=>{page++;await load();},true);previous.disabled=page===0;next.disabled=items.length<30;pager.append(previous,next);}
    async function openReviewDetail(number){const request=++reviewRequest,problem=await ctx.api('/api/problems/'+number+'/edit');if(epoch!==groupEpoch||detailRequest!==groupDetailRequest||request!==reviewRequest)return;detailBox.replaceChildren();detailBox.append(node('h4','#'+number+' · '+problem.title+' · '+problem.status),node('pre',problem.description||'','prose'));
      for(const [index,test] of (problem.tests||[]).entries())detailBox.append(node('pre','테스트 '+(index+1)+'\n입력\n'+test.input+'\n기대 출력\n'+test.expected,'code-preview'));
      for(const solution of problem.referenceSolutions||[])detailBox.append(node('pre',solution.language+' · '+solution.fileName+'\n'+solution.sourceCode,'code-preview'));
      if(problem.canEdit)detailBox.append(button('편집기로 불러오기',()=>ctx.openEditor(number),true));
      if(can('REVIEW_PROBLEMS')&&problem.status==='PENDING_REVIEW'){
        const form=document.createElement('form');form.append(field('처리','status','PUBLISHED','text',[['PUBLISHED','그룹 공개 승인'],['REJECTED','반려'],['ARCHIVED','보관']]),field('사유','cause',''));
        const save=node('button','검수 처리');save.type='submit';form.append(save);formAction(form,async values=>{const scope=groupScope();await ctx.api('/api/problems/'+number+'/review','POST',{status:values.status,cause:values.cause});if(!groupScopeCurrent(scope))return;ctx.notice('검수 처리를 완료했습니다.');if(request===reviewRequest)detailBox.replaceChildren();await Promise.all([load(),refreshProblems()]);});detailBox.append(form);}}
    formAction(search,async values=>{query=values.search||'';page=0;await load();});panel.append(search,itemsBox);const pager=node('div','','pager');panel.append(pager,detailBox);root.append(panel);load().catch(error=>{if(epoch===groupEpoch&&detailRequest===groupDetailRequest)itemsBox.textContent=error.message;});
  }
  function renderMembers(root,gid){
    const panel=node('section','','panel');panel.append(node('h3','회원 · '+groupDetail.members.length));
    for(const member of groupDetail.members){const row=node('div','','group-member');row.append(node('strong',member.username),node('span',member.roleName+(member.owner?' · 소유자':'')));
      if(can('MANAGE_MEMBERS')&&!member.owner){const select=document.createElement('select');select.setAttribute('aria-label',member.username+' 역할');for(const role of groupDetail.roles){if(role.builtinKey==='OWNER')continue;const option=node('option',role.name);option.value=role.id;select.append(option);}select.value=member.roleId;
        row.append(select,button('역할 변경',async()=>{const scope=groupScope();await ctx.api(base(gid)+'/members/'+member.userId,'PATCH',{roleId:Number(select.value)});if(groupScopeCurrent(scope))await openGroup(gid);},true),button('내보내기',async()=>{if(!confirm(member.username+' 회원을 내보내시겠습니까?'))return;const scope=groupScope();await ctx.api(base(gid)+'/members/'+member.userId,'DELETE');if(groupScopeCurrent(scope))await openGroup(gid);},true));}
      panel.append(row);}
    root.append(panel);
  }
  function roleForm(gid,role=null){
    const form=document.createElement('form');form.className='role-form';form.append(field('역할 이름','name',role?.name||''));form.querySelector('[name=name]').maxLength=32;form.querySelector('[name=name]').required=true;
    const grid=node('fieldset','','role-flags');grid.append(node('legend','허용 기능'));
    for(const [key,label] of FLAGS)grid.append(checkbox(label,key,role?.permissions?.includes(key)));form.append(grid);
    const submit=node('button',role?'역할 저장':'역할 추가');submit.type='submit';form.append(submit);
    formAction(form,async values=>{const scope=groupScope(),body={name:values.name,permissions:FLAGS.filter(([key])=>form.elements[key].checked).map(([key])=>key)};
      await ctx.api(base(gid)+'/roles'+(role?'/'+role.id:''),role?'PUT':'POST',body);if(!groupScopeCurrent(scope))return;ctx.notice('역할을 저장했습니다.');await openGroup(gid);});return form;
  }
  function renderRoles(root,gid){
    const panel=node('section','','panel');panel.append(node('h3','역할 · '+groupDetail.roles.length+'/8'));
    for(const role of groupDetail.roles){const details=node('details','','group-role'),summary=node('summary',role.name+(role.builtinKey?' · 기본 역할':''));details.append(summary,node('p',role.permissions.map(key=>FLAGS.find(([value])=>value===key)?.[1]||key).join(' · '),'field-hint'));
      if(can('MANAGE_ROLES')&&role.builtinKey!=='OWNER'){details.append(roleForm(gid,role));if(!role.builtinKey)details.append(button('역할 삭제',async()=>{if(!confirm(role.name+' 역할을 삭제하시겠습니까? 배정된 회원이 있으면 삭제할 수 없습니다.'))return;const scope=groupScope();await ctx.api(base(gid)+'/roles/'+role.id,'DELETE');if(groupScopeCurrent(scope))await openGroup(gid);},true));}panel.append(details);}
    if(can('MANAGE_ROLES')&&groupDetail.roles.length<8)panel.append(node('h4','새 역할'),roleForm(gid));root.append(panel);
  }
  async function renderGroupProblems(panel,gid,pageIndex,search){
    const view=groupProblemViews.get(panel);pageIndex??=view?.pageIndex??0;search??=view?.search??'';groupProblemViews.set(panel,{pageIndex,search});
    let form=panel.querySelector('.group-problem-search');if(!form){form=document.createElement('form');form.className='inline-form group-problem-search';form.append(field('제목 검색','search','','search'));const submit=node('button','검색');submit.type='submit';form.append(submit);
      formAction(form,async values=>renderGroupProblems(panel,gid,0,values.search||''));panel.insertBefore(form,panel.querySelector('.group-problem-list'));}
    const box=panel.querySelector('.group-problem-list'),epoch=groupEpoch,detailRequest=groupDetailRequest,request=++groupProblemRequest;try{const query=new URLSearchParams({page:String(pageIndex),search}),items=list(await ctx.api(base(gid)+'/problems?'+query));if(epoch!==groupEpoch||detailRequest!==groupDetailRequest||request!==groupProblemRequest)return;box.replaceChildren();if(!items.length)box.append(node('p','공개된 그룹 문제가 없습니다.'));
      for(const item of items){const row=node('div','','group-problem-row');row.append(node('span','#'+problemNumber(item)+' · '+problemTitle(item)));
        if(item.usedInContest)row.append(node('span','코테 전용 · 코테 목록에서 응시/다시 보기','field-hint'));
        else row.append(button('풀기',()=>ctx.openProblem(problemNumber(item)),true));box.append(row);}
      let pager=panel.querySelector('.group-problem-pager');if(!pager){pager=node('div','','pager group-problem-pager');panel.append(pager);}pager.replaceChildren();
      const previous=button('이전',()=>renderGroupProblems(panel,gid,Math.max(0,pageIndex-1),search),true),next=button('다음',()=>renderGroupProblems(panel,gid,pageIndex+1,search),true);
      previous.disabled=pageIndex===0;next.disabled=items.length<30;pager.append(previous,next);}
    catch(error){if(epoch===groupEpoch&&detailRequest===groupDetailRequest&&request===groupProblemRequest)box.textContent=error.message;}
  }
  function contestForm(gid,current=null){
    const form=document.createElement('form');form.className='contest-form';form.append(node('h4',current?'코테 수정':'새 코테 만들기'));
    form.append(field('제목','title',current?.title||''),field('시작 (내 시간대)','startsAt',current?.startsAt?localDatetime(current.startsAt):'','datetime-local'),field('종료 (내 시간대)','endsAt',current?.endsAt?localDatetime(current.endsAt):'','datetime-local'));
    form.querySelector('[name=title]').required=true;form.querySelector('[name=title]').maxLength=200;form.querySelector('[name=startsAt]').required=true;form.querySelector('[name=endsAt]').required=true;
    form.append(field('문제 번호 (쉼표로 구분, 최대 50개)','problemNumbers',(current?.problems||[]).map(problemNumber).join(', ')));
    if(current&&current.status!=='UPCOMING'){
      for(const name of ['title','startsAt','endsAt','problemNumbers'])form.querySelector('[name='+name+']').readOnly=true;
      form.append(node('p','시작된 시험은 문제와 시각을 변경할 수 없습니다. 아래 보호·다시 보기 설정만 수정할 수 있습니다.','field-hint'));
    }
    form.append(checkbox('종료 후 참여자 다시 보기 허용','replayAllowed',current?.replayAllowed),checkbox('복사·인쇄 억제 및 캡처 키 신호','captureProtection',current?.captureProtection),checkbox('전체 화면 권고 및 창 전환 신호','windowSwitchProtection',current?.windowSwitchProtection));
    form.append(node('p','운영체제 스크린샷의 완전 차단은 불가능합니다. 신호는 참고 자료이며 자동 실격에 사용하지 않습니다.','field-hint'));
    const submit=node('button',current?'변경 저장':'코테 생성');submit.type='submit';form.append(submit);
    formAction(form,async values=>{const inContest=Boolean(current&&contest?.id===current.id&&contestGroupId===gid&&$('#contest')?.hidden!==true),scope=inContest?contestScope():groupScope(),body=contestPayload(values,current);
      await ctx.api(base(gid)+'/contests'+(current?'/'+current.id:''),current?'PUT':'POST',body);if(!(inContest?contestScopeCurrent(scope):groupScopeCurrent(scope)))return;ctx.notice('코딩 테스트를 저장했습니다.');if(inContest)await refreshContest(true);else await openGroup(gid);});return form;
  }
  function localDatetime(value){const date=new Date(value);return Number.isNaN(date.getTime())?'':new Date(date.getTime()-date.getTimezoneOffset()*60000).toISOString().slice(0,16);}
  function contestPayload(values,current=null){
    const options={replayAllowed:values.replayAllowed==='on',captureProtection:values.captureProtection==='on',windowSwitchProtection:values.windowSwitchProtection==='on'};
    // 시작 후에는 옵션만 편집합니다. 표시용 분 단위 입력/Date 변환을 거치면
    // 같은 분 안의 시험이 잘못 거절되거나 서버의 마이크로초 시각이 손실됩니다.
    if(current&&current.status!=='UPCOMING')return {title:current.title,startsAt:current.startsAt,endsAt:current.endsAt,problemNumbers:(current.problems||[]).map(problemNumber),...options};
    const problemNumbers=String(values.problemNumbers||'').split(',').map(value=>value.trim()).filter(Boolean).map(Number);
    if(!problemNumbers.length||problemNumbers.length>50||problemNumbers.some(number=>!Number.isSafeInteger(number)||number<1)||new Set(problemNumbers).size!==problemNumbers.length)throw Error('중복 없는 문제 번호 1~50개를 입력해 주세요.');
    const startsAt=new Date(values.startsAt),endsAt=new Date(values.endsAt);if(!Number.isFinite(startsAt.getTime())||!Number.isFinite(endsAt.getTime())||startsAt>=endsAt)throw Error('시작·종료 시각을 확인해 주세요.');
    return {title:String(values.title||'').trim(),startsAt:startsAt.toISOString(),endsAt:endsAt.toISOString(),problemNumbers,...options};
  }
  async function renderContests(panel,gid){
    const box=panel.querySelector('.group-contest-list'),epoch=groupEpoch,detailRequest=groupDetailRequest;try{const items=list(await ctx.api(base(gid)+'/contests'));if(epoch!==groupEpoch||detailRequest!==groupDetailRequest)return;box.replaceChildren();if(!items.length)box.append(node('p','코딩 테스트가 없습니다.'));
      for(const item of items){const card=node('article','','group-card');card.append(node('h4',item.title),node('p',statusLabel(item.status)+' · '+(item.problemCount??0)+'문제'),node('p',formatRange(item.startsAt,item.endsAt)));card.append(button('코테 열기',()=>openContest(gid,item.id)));box.append(card);}}
    catch(error){if(epoch===groupEpoch&&detailRequest===groupDetailRequest)box.textContent=error.message;}
    if(epoch!==groupEpoch||detailRequest!==groupDetailRequest)return;
    if(can('CREATE_CONTESTS'))panel.append(contestForm(gid));
  }
  function statusLabel(value){return {UPCOMING:'시작 전',ACTIVE:'진행 중',ENDED:'종료'}[value]||value||'';}
  function formatRange(start,end){return new Date(start).toLocaleString()+' ~ '+new Date(end).toLocaleString();}
  async function openContest(gid,cid){
    leaveContest();const epoch=contestEpoch;lastBoundaryRefresh=0;
    const detail=await ctx.api(contestBase(gid,cid));if(epoch!==contestEpoch)return;
    contestGroupId=gid;contest=detail;
    await ctx.show('contest');if(epoch!==contestEpoch)return;await renderContest();if(epoch!==contestEpoch)return;
    ticker=setInterval(updateClock,1000);refreshTimer=setInterval(()=>refreshContest().catch(error=>ctx.notice(error.message)),15000);
  }
  function leaveContest(){
    contestEpoch++;contestRefreshRequest++;contestRenderRequest++;contestMineRequest++;resultsRequest++;staffSubmissionRequest++;
    if(ticker)clearInterval(ticker);if(refreshTimer)clearInterval(refreshTimer);ticker=null;refreshTimer=null;
    clearProtectionListeners();contestDraft=null;contestProblem=null;contest=null;contestGroupId=null;eventLocked=false;
  }
  function clear(){leaveContest();groupEpoch++;groupDetailRequest++;groupDetail=null;groupPage=0;groupQuery='';
    $('#groups-app')?.replaceChildren();$('#contest-app')?.replaceChildren();}
  function clearProtectionListeners(){
    for(const [target,type,handler,options] of activeListeners)target.removeEventListener(type,handler,options);
    activeListeners=[];
  }
  function addListener(target,type,handler,options){target.addEventListener(type,handler,options);activeListeners.push([target,type,handler,options]);}
  function timeLeft(){if(!contest)return 0;const end=contest.status==='UPCOMING'?contest.startsAt:contest.endsAt;return Math.max(0,new Date(end).getTime()-Date.now());}
  function updateClock(){
    const label=$('#contest-clock');if(!label||!contest)return;
    const remaining=timeLeft(),seconds=Math.ceil(remaining/1000),hours=Math.floor(seconds/3600),minutes=Math.floor(seconds%3600/60);
    label.textContent=statusLabel(contest.status)+' · '+hours+'시간 '+minutes+'분 '+(seconds%60)+'초 남음';
    if(remaining===0){const submit=$('#contest-submit button[type=submit]');if(submit)submit.disabled=true;
      if(Date.now()-lastBoundaryRefresh>=15000){lastBoundaryRefresh=Date.now();refreshContest().catch(()=>{});}}
  }
  async function refreshContest(forceRender=false){
    if(!contest)return;const epoch=contestEpoch,request=++contestRefreshRequest,gid=contestGroupId,cid=contest.id;
    const latest=await ctx.api(contestBase(gid,cid));if(epoch!==contestEpoch||request!==contestRefreshRequest||!contest)return;
    const redraw=forceRender||['status','title','startsAt','endsAt','captureProtection','windowSwitchProtection','replayAllowed','canManage','canViewResults'].some(key=>latest[key]!==contest[key]);contest=latest;
    if(redraw)await renderContest();
    else updateClock();
    if(epoch!==contestEpoch||request!==contestRefreshRequest||!contest)return;
    if(contest.canViewResults)await renderResults().catch(()=>{});
  }
  function rememberContestDraft(epoch,renderRequest,gid,cid){
    const form=$('#contest-submit'),number=problemNumber(contestProblem||{});
    if(contest.status==='ACTIVE'&&form&&Number.isSafeInteger(Number(number))&&form.querySelector('[name=sourceCode]')&&form.querySelector('[name=language]'))
      contestDraft={epoch,renderRequest,gid,cid,number:Number(number),sourceCode:form.querySelector('[name=sourceCode]').value,language:form.querySelector('[name=language]').value};
    else if(contest.status==='ACTIVE'&&contestDraft?.epoch===epoch&&contestDraft.gid===gid&&contestDraft.cid===cid)contestDraft.renderRequest=renderRequest;
    else contestDraft=null;
  }
  async function renderContest(){
    if(!contest)return;const epoch=contestEpoch,renderRequest=++contestRenderRequest,gid=contestGroupId,cid=contest.id,root=$('#contest-app');
    rememberContestDraft(epoch,renderRequest,gid,cid);
    contestProblem=null;const restoreQuestionRequest=++questionRequest;clearProtectionListeners();root.replaceChildren();
    const back=button('← 그룹으로',async()=>{leaveContest();await ctx.show('groups');await openGroup(gid);},true),head=node('div','','section-heading'),copy=node('div');
    copy.append(node('p','그룹 코딩 테스트 · #'+cid,'eyebrow'),node('h1',contest.title),node('p',formatRange(contest.startsAt,contest.endsAt)));
    const clock=node('p');clock.id='contest-clock';head.append(copy,back);root.append(head,clock);
    root.append(node('p','시험 문제와 제출은 서버 시각·권한으로 통제됩니다. 자동 실격은 없으며 감시 신호만으로 부정행위를 단정하지 않습니다.','field-hint'));
    if(contest.captureProtection||contest.windowSwitchProtection)root.append(node('p','복사·인쇄 억제와 전체 화면 감시는 브라우저 범위에서만 가능합니다. 운영체제 스크린샷은 완전히 막을 수 없습니다.','contest-caution'));
    root.append(node('div','','contest-warning'));
    updateClock();
    if(contest.status==='UPCOMING')root.append(node('section','시작 전에는 문제 본문을 볼 수 없습니다.','panel contest-wait'));
    else {
      const layout=node('div','','contest-layout'),listPanel=node('section','','panel'),question=node('section','','panel');listPanel.append(node('h2','시험 문제'),node('div','','contest-problem-list'));
      question.id='contest-question';question.append(node('p','왼쪽에서 문제를 선택하세요.'));layout.append(listPanel,question);root.append(layout);
      try{const problems=list(await ctx.api(contestBase(gid,cid)+'/problems'));if(epoch!==contestEpoch||renderRequest!==contestRenderRequest)return;const listBox=listPanel.querySelector('.contest-problem-list');
        if(!problems.length)listBox.append(node('p','열람 가능한 문제가 없습니다.'));
        for(const item of problems){const number=problemNumber(item);listBox.append(button('#'+number+' · '+problemTitle(item),()=>openContestProblem(number),true));}}
      catch(error){if(epoch!==contestEpoch||renderRequest!==contestRenderRequest||!contest)return;listPanel.querySelector('.contest-problem-list').textContent=contest.status==='ENDED'?'종료 후 다시 보기는 참여 이력과 설정에 따라 허용됩니다. '+error.message:error.message;}
      if(epoch!==contestEpoch||renderRequest!==contestRenderRequest)return;
      if(contest.status==='ACTIVE'&&groupDetail?.permissions?.includes('TAKE_CONTESTS'))enableProtections();
      const canTake=await renderContestMine(root).catch(()=>false);if(epoch!==contestEpoch||renderRequest!==contestRenderRequest)return;
      if(contest.status==='ACTIVE'&&canTake&&contestDraft?.epoch===epoch&&contestDraft.renderRequest===renderRequest&&
          contestDraft.gid===gid&&contestDraft.cid===cid&&restoreQuestionRequest===questionRequest){
        const draftNumber=contestDraft.number;
        try{await openContestProblem(draftNumber,true);}catch{contestDraft=null;}
        if(epoch!==contestEpoch||renderRequest!==contestRenderRequest)return;
      }else contestDraft=null;
    }
    if(contest.canManage||contest.canViewResults)renderContestStaff(root);
  }
  async function openContestProblem(number,restoreDraft=false){
    if(!restoreDraft)contestDraft=null;
    const epoch=contestEpoch,request=++questionRequest,gid=contestGroupId,cid=contest.id,problem=await ctx.api(contestBase(gid,cid)+'/problems/'+number);
    if(epoch!==contestEpoch||request!==questionRequest)return;contestProblem=problem;
    const box=$('#contest-question');box.replaceChildren();box.append(node('h2','#'+number+' · '+problem.problem_title));
    box.append(node('p',(problem.rank_type||'')+' '+(problem.rank_int||'')+' · '+(problem.time_limit_ms||'')+'ms · '+(problem.memory_limit_mb||'')+'MB','field-hint'));
    for(const [label,key] of [['문제 설명','description'],['입력','input_description'],['출력','output_description'],['제한 사항','constraints_text']]){
      const section=node('section','','contest-statement');section.append(node('h3',label),node('pre',problem[key]||'','prose'));box.append(section);}
    const examples=node('section','','contest-statement');examples.append(node('h3','예제'));
    for(const [index,example] of (problem.examples||[]).entries())examples.append(node('pre','예제 '+(index+1)+'\n입력\n'+(example.input_text||'')+'\n출력\n'+(example.output_text||'')+'\n'+(example.explanation||''),'prose'));box.append(examples);
    const form=document.createElement('form');form.id='contest-submit';form.append(node('h3','시험 제출'));
    const select=field('언어','language','', 'text',(problem.languages||[]).map(id=>[id,ctx.languageLabel(id)]));form.append(select,multiline('풀이 코드','sourceCode','',16));
    const draft=restoreDraft&&contestDraft?.epoch===epoch&&contestDraft.gid===gid&&contestDraft.cid===cid&&contestDraft.number===number&&contest.status==='ACTIVE'?contestDraft:null;
    if(draft){form.querySelector('[name=sourceCode]').value=draft.sourceCode;if((problem.languages||[]).includes(draft.language))form.querySelector('[name=language]').value=draft.language;contestDraft=null;}
    form.querySelector('textarea').className='contest-source';form.querySelector('textarea').required=true;
    const submit=node('button','제출하고 채점하기');submit.type='submit';submit.disabled=contest.status!=='ACTIVE'||Date.now()>=new Date(contest.endsAt).getTime();form.append(submit,node('p',contest.status==='ACTIVE'?'시험 중에만 제출할 수 있습니다.':'종료된 시험은 다시 볼 수 있어도 제출할 수 없습니다.','field-hint'));
    formAction(form,async values=>{const scope=contestScope(),activeQuestion=questionRequest;if(contest.status!=='ACTIVE'||Date.now()>=new Date(contest.endsAt).getTime())throw Error('제출 가능 시간이 아닙니다.');
      const id=await ctx.api(contestBase(gid,cid)+'/submissions','POST',{problemNumber:number,sourceCode:values.sourceCode,language:values.language});
      if(!contestScopeCurrent(scope)||activeQuestion!==questionRequest)return;ctx.notice('시험 제출 #'+id+' 접수');await pollSubmission(id,scope.epoch,activeQuestion);if(contestScopeCurrent(scope)&&activeQuestion===questionRequest)await renderContestMine($('#contest-app')).catch(()=>{});});box.append(form,node('pre','','contest-result'));
  }
  async function pollSubmission(id,epoch=contestEpoch,activeQuestion=questionRequest){
    if(!contest||epoch!==contestEpoch||activeQuestion!==questionRequest)return;try{const result=await ctx.api('/api/submissions/'+id);if(epoch!==contestEpoch||activeQuestion!==questionRequest)return;
      const output=$('#contest-question .contest-result');if(output)output.textContent='#'+id+' · '+result.status+(result.score===null?'':' · '+result.score+'점');
      if(['QUEUED','RUNNING'].includes(result.status))setTimeout(()=>{if(epoch===contestEpoch&&activeQuestion===questionRequest)pollSubmission(id,epoch,activeQuestion);},1500);}
    catch(error){if(epoch===contestEpoch&&activeQuestion===questionRequest)ctx.notice(error.message);}
  }
  async function renderContestMine(root){
    if(!contest||!root)return;const epoch=contestEpoch,request=++contestMineRequest,gid=contestGroupId,cid=contest.id;
    const previous=root.querySelector('.contest-mine');if(previous)previous.remove();
    const panel=node('section','','panel contest-mine');panel.append(node('h2','내 시험 제출'));
    let canTake=false;try{const data=await ctx.api(contestBase(gid,cid)+'/me');if(epoch!==contestEpoch||request!==contestMineRequest||root!==$('#contest-app'))return false;renderSubmissions(panel,data.submissions||[]);canTake=true;}
    catch(error){if(epoch!==contestEpoch||request!==contestMineRequest||root!==$('#contest-app'))return;panel.append(node('p','제출 기록은 응시 중 또는 허용된 다시 보기 기간에 확인할 수 있습니다. '+error.message));}
    if(epoch!==contestEpoch||request!==contestMineRequest||root!==$('#contest-app'))return;
    root.append(panel);return canTake;
  }
  function renderSubmissions(panel,items){
    if(!items.length){panel.append(node('p','제출 내역이 없습니다.'));return;}
    for(const item of items){const details=node('details','','submission-item');details.append(node('summary','#'+item.id+' · 문제 #'+item.problemNumber+' · '+ctx.languageLabel(item.language)+' · '+item.status+' · '+(item.score??'-')+'점'),node('p',String(item.submittedAt||'')));
      if(item.sourceCode!=null)details.append(node('pre',item.sourceCode,'code-preview'));panel.append(details);}
  }
  function renderContestStaff(root){
    const section=node('section','','panel contest-staff');section.append(node('h2','시험 운영'));
    if(contest.canManage){const editor=node('details');editor.append(node('summary','시험 설정 편집'),contestForm(contestGroupId,contest));section.append(editor);}
    if(contest.canViewResults)section.append(node('p','순위는 맞힌 문제 수 내림차순, 첫 정답까지 걸린 시간 합 오름차순입니다. 신호는 참고 자료이며 부정행위 확정 표시가 아닙니다.','field-hint'),node('div','','contest-rankings'),node('div','','contest-events'));
    root.append(section);if(contest.canViewResults)renderResults().catch(error=>ctx.notice(error.message));
  }
  async function renderResults(){
    const ranks=$('#contest-app .contest-rankings'),eventsBox=$('#contest-app .contest-events');if(!ranks||!eventsBox||!contest?.canViewResults)return;
    const epoch=contestEpoch,request=++resultsRequest,gid=contestGroupId,cid=contest.id;
    const items=list(await ctx.api(contestBase(gid,cid)+'/results'));if(epoch!==contestEpoch||request!==resultsRequest||ranks!==$('#contest-app .contest-rankings'))return;ranks.replaceChildren(node('h3','실시간 순위'));
    if(!items.length)ranks.append(node('p','참여 기록이 없습니다.'));
    for(const [index,item] of items.entries()){const row=node('div','','contest-rank-row');row.append(node('span',(index+1)+'위 · '+item.username+' · '+item.solvedCount+'문제 · '+item.timeSumSeconds+'초'),button('제출 보기',()=>showStaffSubmissions(item.userId),true));ranks.append(row);}
    const events=list(await ctx.api(contestBase(gid,cid)+'/events'));if(epoch!==contestEpoch||request!==resultsRequest||eventsBox!==$('#contest-app .contest-events'))return;eventsBox.replaceChildren(node('h3','창 전환·복사/인쇄 신호'));
    if(!events.length)eventsBox.append(node('p','기록된 신호가 없습니다.'));
    for(const item of events.slice(0,30))eventsBox.append(node('p',item.username+' · '+item.type+' · '+new Date(item.occurredAt).toLocaleString(),'contest-event'));
  }
  async function showStaffSubmissions(userId){
    const box=$('#contest-app .contest-rankings');if(!box)return;
    const epoch=contestEpoch,request=++staffSubmissionRequest,gid=contestGroupId,cid=contest.id;
    const data=await ctx.api(contestBase(gid,cid)+'/submissions?userId='+encodeURIComponent(userId));if(epoch!==contestEpoch||request!==staffSubmissionRequest||box!==$('#contest-app .contest-rankings'))return;
    let panel=box.querySelector('.staff-submissions');if(panel)panel.remove();panel=node('div','','staff-submissions');panel.append(node('h4','회원 #'+userId+' 제출'));renderSubmissions(panel,data.submissions||[]);box.append(panel);
  }
  function shouldRecordEvent(item,type,now=Date.now()){
    const end=new Date(item?.endsAt).getTime();
    if(!item||item.status!=='ACTIVE'||!Number.isFinite(end)||now>=end)return false;
    const windows=['WINDOW_BLUR','VISIBILITY_HIDDEN','FULLSCREEN_EXIT'],capture=['COPY_BLOCKED','PRINT_BLOCKED','CAPTURE_KEY'];
    return windows.includes(type)?Boolean(item.windowSwitchProtection):capture.includes(type)?Boolean(item.captureProtection):false;
  }
  function signal(type){
    if(!shouldRecordEvent(contest,type))return;
    const eventId=crypto.randomUUID();ctx.api(contestBase(contestGroupId,contest.id)+'/events','POST',{eventId,type}).catch(error=>ctx.notice('시험 신호를 기록하지 못했습니다: '+error.message));
  }
  function warn(text){
    if(!contest?.windowSwitchProtection)return;eventLocked=true;const box=$('#contest-app .contest-warning');if(!box)return;
    box.replaceChildren(node('p',text));box.classList.add('active');
    box.append(button('전체 화면으로 돌아가기',async()=>{try{await document.documentElement.requestFullscreen();eventLocked=false;box.classList.remove('active');}catch{ctx.notice('전체 화면이 허용되지 않았습니다. 브라우저 설정을 확인해 주세요.');}},false));
    box.append(button('시험 화면 나가기',()=>ctx.show('groups'),true));
  }
  function enableProtections(){
    if(contest.captureProtection){
      for(const type of ['copy','cut','contextmenu'])addListener(document,type,event=>{if(!$('#contest')?.hidden){event.preventDefault();signal(type==='contextmenu'?'COPY_BLOCKED':'COPY_BLOCKED');}});
      addListener(window,'beforeprint',event=>{event.preventDefault();signal('PRINT_BLOCKED');});
      addListener(document,'keydown',event=>{if(event.key==='PrintScreen'){event.preventDefault();signal('CAPTURE_KEY');}
        if((event.ctrlKey||event.metaKey)&&event.key.toLowerCase()==='p'){event.preventDefault();signal('PRINT_BLOCKED');}});
    }
    if(contest.windowSwitchProtection){
      addListener(window,'blur',()=>{signal('WINDOW_BLUR');warn('시험 창을 벗어났습니다. 전체 화면으로 돌아와 주세요.');});
      addListener(document,'visibilitychange',()=>{if(document.hidden){signal('VISIBILITY_HIDDEN');warn('시험 탭이 숨겨졌습니다. 돌아와 주세요.');}});
      addListener(document,'fullscreenchange',()=>{if(!document.fullscreenElement){signal('FULLSCREEN_EXIT');warn('전체 화면이 종료되었습니다. 다시 시작해 주세요.');}});
      warn('창 전환 방지 옵션이 켜져 있습니다. 시작하려면 전체 화면을 허용해 주세요.');
    }
  }
  return {init,open,openGroup,openContest,leaveContest,clear,contestPayload,localDatetime,shouldRecordEvent,FLAGS,
    _test:{renderPublic,renderGroupProblems,renderGroupSettings,contestForm,refreshContest,pollSubmission,showStaffSubmissions,
      setContestState:(value,gid)=>{leaveContest();contest=value;contestGroupId=gid;},getContestState:()=>contest,
      setContestProblem:value=>{contestProblem=value;},getContestDraft:()=>contestDraft,advanceQuestion:()=>{questionRequest++;}}};
})();
if(typeof module==='object'&&module.exports)module.exports=CobwebGroups;
