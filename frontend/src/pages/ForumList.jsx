import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useResource } from '../lib/useResource';
import { CATEGORIES, displayDate } from '../lib/community';
import Icon from '../components/Icon';
import './common.css';
import './Forum.css';

export default function ForumList({ dark, notices = false, admin = false }) {
  const [category, setCategory] = useState(notices ? 'NOTICE' : ''), [draft, setDraft] = useState(''), [query, setQuery] = useState('');
  const [page, setPage] = useState(0), [includeDeleted, setIncludeDeleted] = useState(admin);
  const endpoint = admin ? '/api/admin/community/posts' : '/api/community/posts';
  const { data, error, loading } = useResource(endpoint + '?' + new URLSearchParams({
    page, search: query, category: notices ? 'NOTICE' : category, ...(admin ? { includeDeleted } : {}),
  }));
  const items = data?.items || [], title = admin ? '커뮤니티 관리' : notices ? '공지사항' : '커뮤니티';
  return <main className={`cw-page fm-page ${dark ? 'dark' : 'light'}`}><div className="cw-wrap">
    <header className="cw-head"><div><p className="cw-eyebrow">COMMUNITY{admin ? ' / ADMIN' : ''}</p>
      <h1 className="cw-title">{title}</h1><p className="cw-desc">하드웨어·소프트웨어부터 전공과 문제 질문까지. 수정·삭제 이력은 서버에 보존됩니다.</p></div>
      <Link to={'/forum/new' + (notices ? '?category=NOTICE' : '')} className="cw-btn primary"><Icon name="plus" />글쓰기</Link></header>
    <form className="cw-card fm-filter" onSubmit={event => { event.preventDefault(); setQuery(draft); setPage(0); }}>
      {!notices && <div className="fm-cats" aria-label="분류"><button type="button" className={!category ? 'on' : ''} onClick={() => { setCategory(''); setPage(0); }}>전체</button>
        {Object.entries(CATEGORIES).map(([key, item]) => <button type="button" key={key} className={category === key ? 'on' : ''} onClick={() => { setCategory(key); setPage(0); }}>{item.label}</button>)}</div>}
      <div className="fm-filter-row"><input className="cw-input" value={draft} maxLength={200} onChange={event => setDraft(event.target.value)} placeholder="제목·본문 검색" aria-label="커뮤니티 검색" />
        {admin ? <label><input type="checkbox" checked={includeDeleted} onChange={event => { setIncludeDeleted(event.target.checked); setPage(0); }} /> 삭제된 글 포함</label> : <span />}
        <button className="cw-btn primary">검색</button></div>
    </form>
    {error && <p className="cw-error" role="alert">{error}</p>}
    <section className="cw-card fm-table" aria-label="게시글 목록" aria-busy={loading}>
      <div className="fm-row fm-row-head"><span>분류</span><span>제목</span><span>작성자</span><span>작성일</span><span>상태</span><span>댓글</span></div>
      {loading ? <p className="fm-empty">불러오는 중…</p> : !items.length && <p className="fm-empty">조건에 맞는 게시글이 없습니다.</p>}
      {items.map(post => <Link to={`${admin ? '/admin/community' : '/forum'}/${post.id}`} className={`fm-row fm-row-item ${post.category === 'NOTICE' ? 'pinned' : ''}`} key={post.id}>
        <span><span className={`cw-badge ${CATEGORIES[post.category]?.tone || 'gray'}`}>{CATEGORIES[post.category]?.label || post.category}</span></span>
        <span className="fm-title">{post.title}{post.problemNumber && <em>#{post.problemNumber}</em>}</span>
        <span className="muted">{post.authorUsername}</span><span className="muted">{displayDate(post.createdAt)}</span>
        <span>{post.deleted ? '삭제됨' : post.version > 1 ? '수정됨' : '게시됨'}</span><span>{post.commentCount}</span>
      </Link>)}
    </section>
    <nav className="cw-pager fm-pager" aria-label="페이지 이동"><button className="cw-btn" disabled={!page || loading} onClick={() => setPage(page - 1)}>이전</button>
      <span>{page + 1} / {Math.max(1, Math.ceil((data?.total || 0) / 20))}</span><button className="cw-btn" disabled={loading || (page + 1) * 20 >= (data?.total || 0)} onClick={() => setPage(page + 1)}>다음</button></nav>
  </div></main>;
}
