import { useEffect, useState } from 'react';
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { api } from '../lib/api';
import { useResource, useScope } from '../lib/useResource';
import { validProblemNumber, problemUrl } from '../lib/problemNavigation';
import { CATEGORIES, forumListDestination } from '../lib/community';
import './common.css';
import './Forum.css';

export default function ForumWrite({ dark }) {
  const { id } = useParams(); const [params] = useSearchParams(); const admin = params.get('admin') === '1';
  const { permissions } = useAuth(); const navigate = useNavigate(); const scope = useScope();
  const { data, error: loadError, loading } = useResource(id ? `${admin ? '/api/admin/community' : '/api/community'}/posts/${id}` : null);
  const location = useLocation();
  const linkedNumber = params.get('problemNumber');
  const question = !id && validProblemNumber(linkedNumber) && Number(linkedNumber) >= 1000;
  const list = forumListDestination(params.get('from'), admin);
  const postUrl = postId => (admin ? '/admin/community/' : '/forum/') + postId + '?' + new URLSearchParams({from:list});
  const backToProblem = question && !params.has('from');
  const cancel = id ? postUrl(id) : backToProblem ? problemUrl(linkedNumber, params.get('list')) : list;
  const [form, setForm] = useState({
    title: '', body: '', category: question ? 'PROBLEM_QNA' : params.get('category') === 'NOTICE' && permissions.canManage ? 'NOTICE' : 'GENERAL',
    problemNumber: question ? linkedNumber : '',
  });
  const [preserve, setPreserve] = useState(true), [error, setError] = useState(''), [busy, setBusy] = useState(false);
  useEffect(() => { if (data) setForm({ title: data.title, body: data.body, category: data.category, problemNumber: data.problemLinkHidden ? '' : data.problemNumber ?? '' }); }, [data]);
  const set = key => event => setForm(old => ({ ...old, [key]: event.target.value }));
  const submit = async event => {
    event.preventDefault(); const current = scope(); setError(''); setBusy(true);
    try {
      const body = { ...form, title: form.title.trim(), problemNumber: form.problemNumber === '' ? null : Number(form.problemNumber) };
      if (id) { body.version = data.version; body.preserveProblemLink = Boolean(data.problemLinkHidden && preserve && form.problemNumber === ''); }
      const result = await api('/api/community/posts' + (id ? '/' + id : ''), id ? 'PUT' : 'POST', body);
      if (current()) navigate(postUrl(id || result), { replace: true });
    } catch (failure) { if (current() && !failure.stale) setError(failure.message); }
    finally { if (current()) setBusy(false); }
  };
  return <main className={`cw-page fm-page ${dark ? 'dark' : 'light'}`}><div className="cw-wrap fm-narrow">
    <header className="cw-head"><div><p className="cw-eyebrow">COMMUNITY / {id ? 'EDIT' : 'NEW'}</p><h1 className="cw-title">{id ? '게시글 수정' : '글쓰기'}</h1></div></header>
    {loadError && <p className="cw-error" role="alert">{loadError}</p>}
    {id && loading ? <p>불러오는 중…</p> : id && !data?.canEdit ? <p>이 게시글을 수정할 권한이 없습니다.</p> : <form className="cw-card fm-write" onSubmit={submit}>
      <div className="fm-write-top"><label className="cw-label">분류<select className="cw-select" value={form.category} onChange={set('category')}>
        {Object.entries(CATEGORIES).filter(([key]) => key !== 'NOTICE' || permissions.canManage).map(([key, item]) => <option key={key} value={key}>{item.label}</option>)}</select></label>
        <label className="cw-label">공개 일반문제 번호 (선택)<input className="cw-input" type="number" min="1000" value={form.problemNumber} onChange={set('problemNumber')} /></label></div>
      {data?.problemLinkHidden && <label><input type="checkbox" checked={preserve} onChange={event => setPreserve(event.target.checked)} /> 비공개로 전환된 기존 문제 연결 유지 (새 번호 입력 시 교체)</label>}
      <label className="cw-label fm-field">제목<input className="cw-input" value={form.title} maxLength={200} required onChange={set('title')} /></label>
      <label className="cw-label fm-field">본문<textarea className="cw-textarea fm-body-input" value={form.body} maxLength={20000} required onChange={set('body')} /></label>
      <p className="cw-hint">{form.body.length} / 20,000자</p>
      {error && <p className="cw-error" role="alert">{error}</p>}
      <div className="fm-write-actions"><Link className="cw-btn" to={cancel} state={question ? location.state : undefined}>{backToProblem ? '문제로 돌아가기' : '취소'}</Link>
        <button className="cw-btn primary" disabled={busy || !form.title.trim() || !form.body.trim()}>{busy ? '저장 중…' : '저장'}</button></div>
    </form>}
  </div></main>;
}
