import { useEffect, useState } from 'react';
import { Link, useLocation, useSearchParams } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { useResource } from '../lib/useResource';
import { CATEGORIES, displayDate } from '../lib/community';
import {validProblemNumber,problemUrl} from '../lib/problemNavigation';
import Icon from '../components/Icon';
import './common.css';
import './Forum.css';

export default function ForumList({ dark, notices = false, admin = false }) {
  const { permissions, user } = useAuth();
  const location = useLocation();
  const [params, setParams] = useSearchParams();
  const query = (params.get('q') || '').slice(0, 200);
  const scoped = !admin && !notices && validProblemNumber(params.get('problemNumber'));
  const number = scoped ? params.get('problemNumber') : null;
  const category = scoped ? 'PROBLEM_QNA' : notices ? 'NOTICE' : Object.hasOwn(CATEGORIES, params.get('category')) ? params.get('category') : '';
  const requested = Number(params.get('page'));
  const page = Number.isInteger(requested) ? Math.min(10000, Math.max(0, requested)) : 0;
  const includeDeleted = admin && params.get('deleted') !== '0';
  const [draft, setDraft] = useState(query);
  useEffect(() => setDraft(query), [query]);
  const from = location.pathname + location.search;
  const origin = new URLSearchParams({from});
  const writeParams = new URLSearchParams(origin);
  if (scoped) writeParams.set('problemNumber',number);
  if (notices) writeParams.set('category', 'NOTICE');
  if (admin) writeParams.set('admin', '1');
  const update = (key, value) => {
    const next = new URLSearchParams(params);
    if (key !== 'page') next.delete('page');
    if (value) next.set(key, String(value)); else next.delete(key);
    setParams(next);
  };
  const endpoint = admin ? '/api/admin/community/posts' : '/api/community/posts';
  const { data, error, loading, reload } = useResource(endpoint + '?' + new URLSearchParams({
    page, search: query, category: notices ? 'NOTICE' : category, ...(admin ? { includeDeleted } : {}), ...(scoped ? {problemNumber:number} : {}),
  }));
  const items = data?.items || [], title = scoped ? '#'+number+' 질문' : admin ? '커뮤니티 관리' : notices ? '공지사항' : '커뮤니티';
  const pages = Math.ceil((data?.total || 0) / 20);
  useEffect(() => {
    if (!data || page <= Math.max(0, pages - 1)) return;
    const next = new URLSearchParams(params);
    if (pages > 1) next.set('page', String(pages - 1)); else next.delete('page');
    setParams(next, {replace: true});
  }, [data, page, pages, params, setParams]);
  const filtered = Boolean(query || (!notices && !scoped && category));
  function clearSearch() {
    setDraft('');
    const next = new URLSearchParams(params);
    for (const key of ['q', 'page', 'category']) next.delete(key);
    setParams(next);
  }
  return <main className={`cw-page fm-page ${dark ? 'dark' : 'light'}`}><div className="cw-wrap">
    <header className="cw-head"><div><p className="cw-eyebrow">COMMUNITY{admin ? ' / ADMIN' : ''}</p>
      <h1 className="cw-title">{title}</h1><p className="cw-desc">{notices ? '서비스 소식과 운영 안내를 확인하세요.' : '문제에 관해 질문하고 풀이 경험을 나눠 보세요.'}</p></div>
      <div className="cw-head-actions">{scoped && <Link className="cw-btn" to={problemUrl(number,params.get('list'))} state={location.state}>문제로 돌아가기</Link>}
      {(!notices || permissions.canManage) && <Link to={'/forum/new?' + writeParams} className="cw-btn primary"><Icon name="plus" />{notices ? '공지 작성' : scoped ? (user ? '질문 작성' : '로그인 후 질문 작성') : user ? '글쓰기' : '로그인 후 글쓰기'}</Link>}</div></header>
    <form className="cw-card fm-filter" onSubmit={event => { event.preventDefault(); update('q',draft.trim()); }}>
      {!notices && !scoped && <div className="fm-cats" aria-label="분류"><button type="button" className={!category ? 'on' : ''} aria-pressed={!category} onClick={() => update('category','')}>전체</button>
        {Object.entries(CATEGORIES).map(([key, item]) => <button type="button" key={key} className={category === key ? 'on' : ''} aria-pressed={category === key} onClick={() => update('category',key)}>{item.label}</button>)}</div>}
      <div className="fm-filter-row"><input className="cw-input" value={draft} maxLength={200} onChange={event => setDraft(event.target.value)} placeholder={scoped?"질문 제목·본문 검색":"제목·본문·문제 번호 검색"} aria-label="커뮤니티 검색" />
        {admin ? <label><input type="checkbox" checked={includeDeleted} onChange={event => { update('deleted',event.target.checked ? '' : '0'); }} /> 삭제된 글 포함</label> : null}
        <button className="cw-btn primary">검색</button></div>
    </form>
    <section className="cw-card fm-table" aria-label="게시글 목록" aria-busy={loading}>
      <div className="fm-row fm-row-head"><span>분류</span><span>제목</span><span>작성자</span><span>작성일</span><span>상태</span><span>댓글</span></div>
      {loading ? <p className="fm-empty" role="status">불러오는 중…</p> : error ? <div className="fm-empty">
        <p role="alert">{error}</p><button className="cw-btn" onClick={reload}>다시 시도</button>
      </div> : !items.length && <div className="fm-empty">
        <p>{filtered ? '조건에 맞는 게시글이 없습니다.' : scoped ? '이 문제에 등록된 질문이 없습니다.' : notices ? '아직 등록된 공지가 없습니다.' : '아직 게시글이 없습니다.'}</p>
        {filtered && <button className="cw-btn" onClick={clearSearch}>검색 조건 지우기</button>}
      </div>}
      {items.map(post => <Link to={`${admin ? '/admin/community' : '/forum'}/${post.id}?${origin}`} className={`fm-row fm-row-item ${post.category === 'NOTICE' ? 'pinned' : ''}`} key={post.id}>
        <span><span className={`cw-badge ${CATEGORIES[post.category]?.tone || 'gray'}`}>{CATEGORIES[post.category]?.label || post.category}</span></span>
        <span className="fm-title">{post.title}{post.problemNumber && <em>#{post.problemNumber}</em>}</span>
        <span className="muted">{post.authorUsername}</span><span className="muted">{displayDate(post.createdAt)}</span>
        <span>{post.deleted ? '삭제됨' : post.version > 1 ? '수정됨' : '게시됨'}</span><span>{post.commentCount}</span>
      </Link>)}
    </section>
    {!error && !loading && pages > 1 && <nav className="cw-pager fm-pager" aria-label="페이지 이동"><button className="cw-btn" disabled={!page || loading} onClick={() => update('page',page - 1)}>이전</button>
      <span>{page + 1} / {pages}</span><button className="cw-btn" disabled={loading || (page + 1) * 20 >= (data?.total || 0)} onClick={() => update('page',page + 1)}>다음</button></nav>}
  </div></main>;
}
