import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { api } from '../lib/api';
import { useResource, useScope } from '../lib/useResource';
import { CATEGORIES, displayDate } from '../lib/community';
import { profileHref } from '../lib/profileRoutes';
import CommentBody from '../components/CommentBody';
import './common.css';
import './Forum.css';

// 이력은 운영자 전용 API에서만 읽습니다. 원문은 React 텍스트로 표시합니다.
function History({kind,id,onClose}){
  const [page,setPage]=useState(0);
  const {data,loading,error}=useResource(`/api/admin/community/${kind}/${id}/history?page=${page}`);
  return <section className="cw-card fm-history" aria-label="수정 이력"><div className="cw-actions"><h2>수정·삭제 이력</h2><button className="cw-btn sm" onClick={onClose}>닫기</button></div>
    {error&&<p role="alert" className="cw-error">{error}</p>}{loading&&<p>불러오는 중…</p>}
    {(data||[]).map(entry=><details key={entry.id}><summary>{entry.action} · {entry.editorUsername} · {displayDate(entry.editedAt)}</summary>
      <p>사유: {entry.reason||'—'}</p>{entry.oldTitle!=null&&<p>이전 제목: {entry.oldTitle}</p>}{entry.newTitle!=null&&<p>이후 제목: {entry.newTitle}</p>}
      <h3>변경 전</h3><pre>{entry.oldBody??'—'}</pre><h3>변경 후</h3><pre>{entry.newBody??'—'}</pre>
      <p>버전 {entry.oldVersion??0} → {entry.newVersion} · 삭제 {String(entry.oldDeleted??false)} → {String(entry.newDeleted)}</p>
      {(entry.oldProblemNumber||entry.newProblemNumber)&&<p>관련 문제: {entry.oldProblemNumber??'없음'} → {entry.newProblemNumber??'없음'}</p>}
    </details>)}
    <div className="cw-pager"><button className="cw-btn" disabled={!page||loading} onClick={()=>setPage(page-1)}>이전</button><span>{page+1}</span><button className="cw-btn" disabled={loading||data?.length!==20} onClick={()=>setPage(page+1)}>다음</button></div>
  </section>;
}
function CommentList({postId,parentId=null,admin=false,disabled=false,compose=true,onChange}){
  const {user,permissions}=useAuth(); const scope=useScope();
  const [page,setPage]=useState(0),[items,setItems]=useState([]),[text,setText]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState('');
  const base=admin?'/api/admin/community':'/api/community';
  const path=`${base}/posts/${postId}/comments?`+new URLSearchParams({page,...(parentId==null?{}:{parentId})});
  const resource=useResource(path);
  useEffect(()=>{if(resource.data)setItems(old=>page===0?resource.data.items:[...new Map([...old,...resource.data.items].map(item=>[item.id,item])).values()]);},[resource.data,page]);
  const submit=async event=>{
    event.preventDefault();const current=scope();setBusy(true);setError('');
    try{await api(`/api/community/posts/${postId}/comments`,'POST',{body:text,parentId});if(current()){setText('');onChange();}}
    catch(failure){if(current()&&!failure.stale)setError(failure.message);}finally{if(current())setBusy(false);}
  };
  return <div className="fm-thread">
    {(resource.error||error)&&<p role="alert" className="cw-error">{resource.error||error}</p>}
    {items.map(item=><Comment key={item.id} item={item} postId={postId} admin={admin} disabled={disabled} onChange={onChange} canManage={permissions.canManage}/>)}
    {resource.loading&&<p role="status">댓글 불러오는 중…</p>}
    {!items.length&&!resource.loading&&<p className="cw-hint">아직 댓글이 없습니다.</p>}
    {items.length<(resource.data?.total||0)&&<button className="cw-btn sm" disabled={resource.loading} onClick={()=>setPage(page+1)}>댓글 더 보기</button>}
    {user&&!disabled&&compose&&<form className="fm-comment-form" onSubmit={submit}><label className="cw-label">{parentId==null?'댓글 작성':'답글 작성'}<textarea className="cw-textarea" maxLength={5000} value={text} onChange={event=>setText(event.target.value)} required/></label>
      <div className="fm-comment-foot"><span>{text.length} / 5,000자</span><button className="cw-btn primary sm" disabled={busy||!text.trim()}>등록</button></div></form>}
  </div>;
}
function Comment({item,postId,admin,disabled,onChange,canManage}){
  const scope=useScope(),{user}=useAuth();
  const [editing,setEditing]=useState(false),[body,setBody]=useState(item.body||''),[replies,setReplies]=useState(false),[history,setHistory]=useState(false),[busy,setBusy]=useState(false),[error,setError]=useState('');
  const mutate=async(action)=>{
    if(action==='delete'&&!window.confirm('댓글을 삭제할까요? 원문과 이력은 DB에 보존됩니다.'))return;
    const current=scope();setBusy(true);setError('');
    try{
      if(action==='edit')await api('/api/community/comments/'+item.id,'PUT',{body,version:item.version});
      else if(action==='delete')await api('/api/community/comments/'+item.id+'?version='+item.version,'DELETE');
      else await api('/api/admin/community/comments/'+item.id+'/restore','POST',{version:item.version,reason:'관리 화면에서 복원'});
      if(current())onChange();
    }catch(failure){if(current()&&!failure.stale)setError(failure.message);}finally{if(current())setBusy(false);}
  };
  return <article className="fm-thread-item" data-depth={item.depth}>
    <div className="fm-comment-head"><Link to={profileHref(item.authorUsername)}>{item.authorUsername}</Link><span>{displayDate(item.createdAt)}{item.edited?' · 수정됨':''}{item.deleted?' · 삭제됨':''}</span></div>
    {editing?<form onSubmit={event=>{event.preventDefault();mutate('edit');}}><textarea aria-label="댓글 수정" className="cw-textarea" maxLength={5000} required value={body} onChange={event=>setBody(event.target.value)}/><div className="cw-actions"><button className="cw-btn sm primary" disabled={busy}>저장</button><button type="button" className="cw-btn sm" onClick={()=>setEditing(false)}>취소</button></div></form>:<CommentBody key={item.body??item.deletedMessage} body={item.body??item.deletedMessage??'삭제된 댓글입니다.'}/>}
    <div className="cw-actions">
      {item.canEdit&&<button className="cw-btn sm" disabled={busy} onClick={()=>setEditing(!editing)}>댓글 수정</button>}
      {item.canDelete&&<button className="cw-btn sm danger" disabled={busy} onClick={()=>mutate('delete')}>댓글 삭제</button>}
      {admin&&item.deleted&&<button className="cw-btn sm" disabled={busy||disabled} onClick={()=>mutate('restore')}>댓글 복원</button>}
      {canManage&&<button className="cw-btn sm" onClick={()=>setHistory(!history)}>댓글 이력</button>}
      {item.depth<2&&(item.replyCount>0||user&&!disabled&&!item.deleted)&&<button className="cw-btn sm" aria-expanded={replies} onClick={()=>setReplies(!replies)}>답글 {item.replyCount||0}</button>}
    </div>
    {error&&<p className="cw-error" role="alert">{error}</p>}
    {history&&<History kind="comments" id={item.id} onClose={()=>setHistory(false)}/>}
    {replies&&<CommentList postId={postId} parentId={item.id} admin={admin} disabled={disabled} compose={!item.deleted} onChange={onChange}/>}
  </article>;
}
export default function ForumPost({dark,admin=false}){
  const {id}=useParams(),{user,permissions}=useAuth(),scope=useScope(),navigate=useNavigate();
  const resource=useResource(`${admin?'/api/admin/community':'/api/community'}/posts/${id}`);
  const post=resource.data;const[history,setHistory]=useState(false),[error,setError]=useState(''),[busy,setBusy]=useState(false);
  const mutate=async restore=>{
    if(!restore&&!window.confirm('게시글을 삭제할까요? 원문과 이력은 DB에 보존되며 운영자가 복원할 수 있습니다.'))return;
    const current=scope();setError('');setBusy(true);
    try{
      if(restore)await api('/api/admin/community/posts/'+id+'/restore','POST',{version:post.version,reason:'관리 화면에서 복원'});
      else await api('/api/community/posts/'+id+'?version='+post.version,'DELETE');
      if(current()){if(!restore&&!admin)navigate('/forum',{replace:true});else resource.reload();}
    }catch(failure){if(current()&&!failure.stale)setError(failure.message);}finally{if(current())setBusy(false);}
  };
  const category=CATEGORIES[post?.category];
  return <main className={`cw-page fm-page ${dark?'dark':'light'}`}><div className="cw-wrap fm-narrow">
    <Link className="fm-back" to={admin?'/admin/community':'/forum'}>← 목록으로</Link>
    {(resource.error||error)&&<p className="cw-error" role="alert">{resource.error||error}</p>}
    {resource.loading?<p role="status">게시글 불러오는 중…</p>:post&&<>
      <article className="cw-card fm-article"><div className="fm-article-top"><span className={`cw-badge ${category?.tone||'gray'}`}>{category?.label||post.category}</span>{post.deleted&&<span className="cw-badge red">삭제됨</span>}
        {post.problemNumber&&<Link className="cw-badge mint" to={'/problems/'+post.problemNumber}>문제 #{post.problemNumber}</Link>}</div>
        <h1>{post.title}</h1><p className="fm-meta"><Link to={profileHref(post.authorUsername)}>{post.authorUsername}</Link><span>{displayDate(post.createdAt)}</span>{post.edited&&<span>수정됨 · {displayDate(post.updatedAt)}</span>}</p>
        <div className="fm-body">{post.body}</div><div className="fm-article-actions cw-actions">
          {post.canEdit&&<Link className="cw-btn sm" to={'/forum/'+id+'/edit'+(admin?'?admin=1':'')}>게시글 수정</Link>}
          {post.canDelete&&<button className="cw-btn sm danger" disabled={busy} onClick={()=>mutate(false)}>게시글 삭제</button>}
          {admin&&post.deleted&&<button className="cw-btn sm primary" disabled={busy} onClick={()=>mutate(true)}>게시글 복원</button>}
          {permissions.canManage&&<button className="cw-btn sm" onClick={()=>setHistory(!history)}>게시글 이력</button>}
        </div></article>
      {history&&<History kind="posts" id={id} onClose={()=>setHistory(false)}/>}
      <section className="cw-card fm-comments"><h2 className="cw-card-title">댓글 <small>최대 대대댓글까지 · 수정 이력 보존</small></h2>
        <CommentList key={resource.revision+':'+post.version} postId={id} admin={admin} disabled={post.deleted} onChange={resource.reload}/>
        {!user&&<p className="fm-login-note"><Link to="/login" state={{from:'/forum/'+id}}>로그인</Link> 후 댓글을 작성할 수 있습니다.</p>}
      </section>
    </>}
  </div></main>;
}
