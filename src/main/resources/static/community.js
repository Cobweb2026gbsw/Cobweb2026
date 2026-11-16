// 커뮤니티·공지 화면. 본문과 이력은 HTML로 해석하지 않고 일반 텍스트로만 출력합니다.
const CobwebCommunity=(()=>{
  const CATEGORIES=[['HARDWARE','하드웨어'],['SOFTWARE','소프트웨어'],['NEWS','뉴스'],['PROBLEM_QNA','문제 질문'],['MAJOR_QNA','전공 질문'],['GENERAL','자유'],['NOTICE','공지']];
  let ctx,view='community',page=0,search='',category='',adminMode=false,current=null,viewEpoch=0,listRequest=0,postRequest=0,historyRequest=0;
  const $=selector=>document.querySelector(selector);
  const node=(tag,text='',className='')=>{const result=document.createElement(tag);result.textContent=String(text??'');if(className)result.className=className;return result;};
  const label=value=>CATEGORIES.find(([key])=>key===value)?.[1]||value;
  const list=data=>Array.isArray(data)?data:(data?.items||[]);
  const root=()=>$(view==='notices'?'#notices-app':'#community-app');
  const apiPath=id=>'/api/community/posts'+(id?'/'+id:'');
  const adminPath=id=>'/api/admin/community/posts'+(id?'/'+id:'');
  const commentPagePath=post=>(adminMode&&ctx.isOperator()?adminPath(post.id):apiPath(post.id))+'/comments';
  const viewScope=()=>({epoch:viewEpoch,view,postRequest});
  const viewScopeCurrent=scope=>scope.epoch===viewEpoch&&scope.view===view&&scope.postRequest===postRequest&&$('#'+scope.view)?.hidden!==true;
  function init(context){ctx=context;}
  function button(text,action,secondary=false){const result=node('button',text,secondary?'secondary':'');result.type='button';let pending=false;result.onclick=()=>{if(pending)return;pending=true;result.disabled=true;return Promise.resolve().then(action).catch(error=>fail(error)).finally(()=>{pending=false;result.disabled=false;});};return result;}
  function fail(error){ctx.notice(error.status===409?'다른 변경과 충돌했습니다. 최신 글을 다시 확인해 주세요. '+error.message:error.message);}
  function textField(text,name,value='',max=200,type='text'){const labelElement=node('label',text),input=document.createElement('input');input.name=name;input.type=type;input.value=value??'';input.maxLength=max;labelElement.append(input);return labelElement;}
  function bodyField(text,name,value='',max=20000){const labelElement=node('label',text),textarea=document.createElement('textarea');textarea.name=name;textarea.value=value??'';textarea.maxLength=max;textarea.rows=8;textarea.required=true;labelElement.append(textarea);return labelElement;}
  function categoryField(selected,noticeOnly,filter=false){const wrap=node('label','카테고리'),select=document.createElement('select');select.name='category';for(const [key,text] of CATEGORIES){if((noticeOnly&&key!=='NOTICE')||(!filter&&!ctx.isOperator()&&key==='NOTICE'))continue;const option=node('option',text);option.value=key;select.append(option);}select.value=selected||select.options[0]?.value;wrap.append(select);return wrap;}
  function submitForm(form,action){form.onsubmit=async event=>{event.preventDefault();const button=form.querySelector('[type=submit]');if(button)button.disabled=true;try{await action(Object.fromEntries(new FormData(form)));}catch(error){fail(error);}finally{if(button)button.disabled=false;}};}
  async function open(nextView){
    viewEpoch++;listRequest++;postRequest++;historyRequest++;view=nextView;page=0;search='';category='';adminMode=false;current=null;renderShell();await loadList();
  }
  function clear(){viewEpoch++;listRequest++;postRequest++;historyRequest++;current=null;adminMode=false;
    $('#community-app')?.replaceChildren();$('#notices-app')?.replaceChildren();}
  function renderShell(){
    const box=root();box.replaceChildren();const heading=node('div','','section-heading'),copy=node('div');copy.append(node('p',view==='notices'?'운영 소식':'함께 나누는 이야기','eyebrow'),node('h1',view==='notices'?'공지':'커뮤니티'));
    copy.append(node('p',view==='notices'?'운영자가 올린 알림을 확인하세요.':'기기, 소프트웨어, 문제 풀이와 전공 이야기를 나눕니다.'));heading.append(copy);box.append(heading);
    const layout=node('div','','community-layout'),listPanel=node('section','','panel'),detailPanel=node('section','','panel community-detail');
    detailPanel.append(node('p','게시글을 선택하세요.'));layout.append(listPanel,detailPanel);box.append(layout);
    const searchForm=document.createElement('form');searchForm.className='community-search';
    if(view!=='notices'){
      searchForm.append(textField('검색','search',search,200,'search'),categoryField(category,false,true));
      const select=searchForm.querySelector('[name=category]'),all=node('option','전체');all.value='';select.prepend(all);select.value=category;
      const submit=node('button','검색');submit.type='submit';searchForm.append(submit);
      submitForm(searchForm,async values=>{search=values.search||'';category=values.category||'';page=0;await loadList();});listPanel.append(searchForm);
    }
    if(ctx.isOperator()&&view!=='notices'){
      const toggle=button('운영자 보기 · 삭제된 글 포함',async()=>{adminMode=!adminMode;page=0;toggle.textContent=adminMode?'일반 목록 보기':'운영자 보기 · 삭제된 글 포함';await loadList();},true);
      listPanel.append(toggle);
    }
    listPanel.append(node('div','','community-list'),node('div','','community-pager'));
    if(ctx.isAuthenticated()&&(view!=='notices'||ctx.isOperator())){const create=node('details','','community-create');create.append(node('summary',view==='notices'?'새 공지 작성':'새 글 작성'),postForm());listPanel.append(create);}
    listPanel.append(node('p','삭제된 글과 댓글은 복구·감사 이력을 위해 보관되며 일반 목록에서는 숨겨집니다.','field-hint'));
  }
  async function loadList(){
    const epoch=viewEpoch,request=++listRequest,shell=root(),box=shell?.querySelector('.community-list'),pager=shell?.querySelector('.community-pager'),wasAdmin=adminMode;if(!box||!pager)return;
    const query=new URLSearchParams({page:String(page)});let endpoint;
    if(view==='notices')endpoint='/api/community/notices?'+query;
    else{query.set('search',search);if(category)query.set('category',category);if(adminMode)query.set('includeDeleted','true');endpoint=(adminMode?adminPath():apiPath())+'?'+query;}
    const result=await ctx.api(endpoint);
    if(epoch!==viewEpoch||request!==listRequest||shell!==root()||shell.querySelector('.community-list')!==box||(wasAdmin&&!ctx.isOperator()))return;
    const items=list(result);box.replaceChildren();if(!items.length)box.append(node('p','게시글이 없습니다.'));
    for(const item of items){const card=node('article','','community-item'),summary=node('div');summary.append(node('strong',item.title),node('p',label(item.category)+' · '+item.authorUsername+' · '+formatTime(item.createdAt)+(item.deleted?' · 삭제됨':'')));
      if(item.commentCount!=null)summary.append(node('small','댓글 '+item.commentCount));card.append(summary,button('읽기',()=>openPost(item.id),true));box.append(card);}
    pager.replaceChildren();const previous=button('이전',async()=>{page=Math.max(0,page-1);await loadList();},true),next=button('다음',async()=>{page++;await loadList();},true);previous.disabled=page===0;next.disabled=(page+1)*20>=Number(result.total??0);
    pager.append(previous,node('span',(page+1)+' / '+Math.max(1,Math.ceil(Number(result.total??0)/20))),next);
  }
  function formatTime(value){const date=new Date(value);return Number.isNaN(date.getTime())?'':date.toLocaleString();}
  function postForm(post=null){
    const form=document.createElement('form');form.className='community-post-form';
    form.append(textField('제목','title',post?.title||''),categoryField(post?.category||((view==='notices')?'NOTICE':'GENERAL'),view==='notices'),textField('관련 공개 문제 번호 (선택)','problemNumber',post?.problemNumber||'',9,'number'),bodyField('본문','body',post?.body||''));
    const title=form.querySelector('[name=title]');title.required=true;form.querySelector('[name=problemNumber]').min='1';
    if(post?.problemLinkHidden)form.append(node('p','연결된 문제는 현재 비공개입니다. 번호를 새로 입력하지 않으면 기존 연결을 유지합니다.','field-hint'));
    const submit=node('button',post?'수정 저장':'게시');submit.type='submit';form.append(submit);
    submitForm(form,async values=>{const scope=viewScope(),body=postPayload(values,post?.version,post?.problemLinkHidden,post?.problemNumber);if(post){await ctx.api(apiPath(post.id),'PUT',body);if(!viewScopeCurrent(scope))return;ctx.notice('글을 수정했습니다.');await openPost(post.id);}else{const id=await ctx.api(apiPath(),'POST',body);if(!viewScopeCurrent(scope))return;ctx.notice('글을 작성했습니다.');await loadList();if(viewScopeCurrent(scope))await openPost(id);}});return form;
  }
  function postPayload(values,version=null,preserveHiddenLink=false,currentProblemNumber=null){
    const title=String(values.title||'').trim(),body=String(values.body||''),category=values.category;
    if(!title||title.length>200||!body.trim()||body.length>20000||!CATEGORIES.some(([key])=>key===category))throw Error('제목·본문·카테고리와 길이를 확인해 주세요.');
    if(category==='NOTICE'&&!ctx.isOperator())throw Error('공지는 운영자만 작성할 수 있습니다.');
    const problemNumber=values.problemNumber?Number(values.problemNumber):null;
    if(problemNumber!==null&&(!Number.isSafeInteger(problemNumber)||problemNumber<1))throw Error('관련 문제 번호를 확인해 주세요.');
    const keepLink=version!==null&&preserveHiddenLink&&(problemNumber===null||problemNumber===currentProblemNumber);
    return {...(version===null?{}:{version}),title,body,category,problemNumber:keepLink?null:problemNumber,
      ...(keepLink?{preserveProblemLink:true}:{})};
  }
  async function openPost(id){
    const epoch=viewEpoch,request=++postRequest,wasAdmin=adminMode&&ctx.isOperator();historyRequest++;
    const post=await ctx.api(wasAdmin?adminPath(id):apiPath(id));
    if(epoch!==viewEpoch||request!==postRequest||(wasAdmin&&!ctx.isOperator())||!root()?.querySelector('.community-detail'))return;
    current=post;renderPost(post);
  }
  function renderPost(post){
    const panel=root().querySelector('.community-detail');panel.replaceChildren();panel.append(node('p',label(post.category)+' · '+post.authorUsername+' · '+formatTime(post.createdAt)+(post.edited?' · 수정됨':''),'eyebrow'),node('h2',post.title));
    if(post.deleted)panel.append(node('p','삭제된 글 · 운영자만 볼 수 있습니다.','community-deleted'));
    panel.append(node('pre',post.body||'','community-body'));
    if(post.problemNumber)panel.append(button('관련 문제 #'+post.problemNumber,()=>ctx.openProblem(post.problemNumber),true));
    const actions=node('div','','community-actions');
    if(post.canEdit){const edit=button('수정',()=>{const form=postForm(post);panel.querySelector('.community-edit-slot').replaceChildren(form);},true);actions.append(edit);}
    if(post.canDelete)actions.append(button('삭제',async()=>{if(!confirm('글을 삭제하시겠습니까? 이력은 보관됩니다.'))return;const scope=viewScope();await ctx.api(apiPath(post.id)+'?version='+post.version,'DELETE');if(!viewScopeCurrent(scope))return;ctx.notice('글을 삭제했습니다.');await loadList();if(viewScopeCurrent(scope))panel.replaceChildren(node('p','삭제된 글입니다.'));},true));
    if(post.deleted&&ctx.isOperator())actions.append(button('복구',async()=>{const scope=viewScope();await ctx.api(adminPath(post.id)+'/restore','POST',{version:post.version});if(!viewScopeCurrent(scope))return;ctx.notice('글을 복구했습니다.');await loadList();if(viewScopeCurrent(scope))await openPost(post.id);},true));
    if(ctx.isOperator())actions.append(button('수정 이력',()=>showHistory('post',post.id),true));
    panel.append(actions,node('div','','community-edit-slot'),node('div','','community-history-slot'));
    const comments=node('section','','community-comments');comments.append(node('h3','댓글'));
    if(ctx.isAuthenticated()&&!post.deleted)comments.append(commentForm(post.id,null));
    renderComments(comments,post);panel.append(comments);
  }
  function commentForm(postId,parentId,initial=''){
    const form=document.createElement('form');form.className='community-comment-form';form.append(bodyField(parentId?'답글':'댓글','body',initial,5000));form.querySelector('textarea').rows=3;
    const submit=node('button',parentId?'답글 달기':'댓글 달기');submit.type='submit';form.append(submit);
    submitForm(form,async values=>{const scope=viewScope();await ctx.api(apiPath(postId)+'/comments','POST',{parentId:parentId||null,body:values.body});if(!viewScopeCurrent(scope))return;ctx.notice('댓글을 작성했습니다.');await openPost(postId);});return form;
  }
  function renderComments(container,post){
    const roots=node('div','','comment-roots');container.append(roots);
    if(!post.comments?.length)roots.append(node('p','첫 댓글을 남겨보세요.'));
    renderThreadPage(roots,post,null,post.comments||[],Number(post.commentsTotal??post.comments?.length??0),Number(post.commentsPage??0));
  }
  function renderThreadPage(container,post,parentId,initialItems,total,initialPage){
    let loaded=0,pageIndex=initialPage;
    const itemsBox=node('div','','comment-page-items'),pager=node('div','','comment-more');container.append(itemsBox,pager);
    const appendItems=items=>{for(const comment of items)itemsBox.append(renderComment(post,comment));loaded+=items.length;pager.replaceChildren();
      if(loaded<total)pager.append(button(parentId?'답글 더 보기':'댓글 더 보기',async()=>{const scope=viewScope(),mode=adminMode;
        const nextPage=pageIndex+1,query=new URLSearchParams({page:String(nextPage)});if(parentId)query.set('parentId',parentId);
        const result=await ctx.api(commentPagePath(post)+'?'+query);if(!viewScopeCurrent(scope)||mode!==adminMode)return;pageIndex=nextPage;total=Number(result.total??total);appendItems(list(result));
      },true));};
    appendItems(initialItems);
  }
  function canReply(post,comment,authenticated){return Boolean(authenticated&&!post.deleted&&!comment.deleted&&Number(comment.depth)<2);}
  function renderComment(post,comment){
    const item=node('article','','community-comment depth-'+comment.depth),body=node('div','','comment-body');
    body.append(node('strong',comment.authorUsername||'작성자'),node('small',formatTime(comment.createdAt)+(comment.edited?' · 수정됨':'')),node('pre',comment.deleted?'삭제된 댓글입니다.':comment.body||'','community-comment-text'));
    if(comment.deleted)body.classList.add('deleted');item.append(body);const actions=node('div','','community-actions');
    if(canReply(post,comment,ctx.isAuthenticated()))actions.append(button('답글',()=>{const slot=item.querySelector('.comment-form-slot');slot.replaceChildren(commentForm(post.id,comment.id));},true));
    if(comment.canEdit)actions.append(button('수정',()=>{const slot=item.querySelector('.comment-form-slot');slot.replaceChildren(editCommentForm(post.id,comment));},true));
    if(comment.canDelete)actions.append(button('삭제',async()=>{if(!confirm('댓글을 삭제하시겠습니까? 하위 답글은 남습니다.'))return;const scope=viewScope();await ctx.api('/api/community/comments/'+comment.id+'?version='+comment.version,'DELETE');if(viewScopeCurrent(scope))await openPost(post.id);},true));
    if(comment.deleted&&ctx.isOperator())actions.append(button('복구',async()=>{const scope=viewScope();await ctx.api('/api/admin/community/comments/'+comment.id+'/restore','POST',{version:comment.version});if(viewScopeCurrent(scope))await openPost(post.id);},true));
    if(ctx.isOperator())actions.append(button('이력',()=>showHistory('comment',comment.id),true));
    item.append(actions,node('div','','comment-form-slot'));
    const children=node('div','','comment-children'),replyCount=Number(comment.replyCount??0);
    if(replyCount>0){const more=button('답글 '+replyCount+'개 보기',async()=>{const scope=viewScope(),mode=adminMode;
      const query=new URLSearchParams({page:'0',parentId:String(comment.id)}),result=await ctx.api(commentPagePath(post)+'?'+query);if(!viewScopeCurrent(scope)||mode!==adminMode)return;more.remove();
      renderThreadPage(children,post,comment.id,list(result),Number(result.total??replyCount),0);
    },true);children.append(more);}
    item.append(children);return item;
  }
  function editCommentForm(postId,comment){
    const form=document.createElement('form');form.className='community-comment-form';form.append(bodyField('댓글 수정','body',comment.body,5000));form.querySelector('textarea').rows=3;
    const submit=node('button','수정 저장');submit.type='submit';form.append(submit);
    submitForm(form,async values=>{const scope=viewScope();await ctx.api('/api/community/comments/'+comment.id,'PUT',{body:values.body,version:comment.version});if(!viewScopeCurrent(scope))return;ctx.notice('댓글을 수정했습니다.');await openPost(postId);});return form;
  }
  async function showHistory(kind,id,historyPage=0){
    if(!ctx.isOperator())return;
    const epoch=viewEpoch,request=++historyRequest,postRequestAtStart=postRequest,postId=current?.id;
    const endpoint=kind==='post'?adminPath(id)+'/history':'/api/admin/community/comments/'+id+'/history';
    const entries=list(await ctx.api(endpoint+'?page='+historyPage));
    if(epoch!==viewEpoch||request!==historyRequest||postRequestAtStart!==postRequest||postId!==current?.id||!ctx.isOperator())return;
    const slot=root()?.querySelector('.community-history-slot');if(!slot)return;
    slot.replaceChildren(node('h3',kind==='post'?'게시글 수정 이력':'댓글 수정 이력'));
    if(!entries.length)slot.append(node('p','이력이 없습니다.'));
    for(const entry of entries){const item=node('details','','history-item');item.append(node('summary',entry.action+' · '+(entry.editorUsername||'')+' · '+formatTime(entry.editedAt)),node('pre','이전 v'+(entry.oldVersion??'-')+'\n'+(entry.oldTitle||'')+'\n'+(entry.oldBody||'')+'\n\n변경 v'+(entry.newVersion??'-')+'\n'+(entry.newTitle||'')+'\n'+(entry.newBody||''),'community-body'));slot.append(item);}
    const pager=node('div','','pager');if(historyPage>0)pager.append(button('이전 이력',()=>showHistory(kind,id,historyPage-1),true));if(entries.length===20)pager.append(button('다음 이력',()=>showHistory(kind,id,historyPage+1),true));slot.append(pager);
  }
  return {init,open,clear,postPayload,canReply,CATEGORIES,_test:{renderComment,renderThreadPage,commentForm,loadList,openPost,showHistory}};
})();
if(typeof module==='object'&&module.exports)module.exports=CobwebCommunity;
