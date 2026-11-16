import {useEffect, useRef, useState} from 'react';
import {Link, useNavigate} from 'react-router-dom';
import {useAuth} from '../context/AuthContext';
import {api, getSessionEpoch} from '../lib/api';
import './common.css';
import './Groups.css';

export default function Groups({dark}) {
  const {epoch} = useAuth();
  return <GroupBrowser key={epoch} dark={dark} />;
}
function GroupBrowser({dark}) {
  const {user} = useAuth(), navigate = useNavigate();
  const [items, setItems] = useState([]), [mine, setMine] = useState([]), [page, setPage] = useState(0);
  const [query, setQuery] = useState(''), [search, setSearch] = useState(''), [reload, setReload] = useState(0);
  const [loading, setLoading] = useState(true), [error, setError] = useState(''), [busy, setBusy] = useState(false);
  const [mineLoading, setMineLoading] = useState(Boolean(user)), [mineReady, setMineReady] = useState(false);
  const [form, setForm] = useState({name: '', description: ''});
  const alive = useRef(true);
  useEffect(() => {alive.current = true; return () => {alive.current = false;};}, []);
  useEffect(() => {
    const controller = new AbortController(); setLoading(true); setItems([]); setError('');
    api('/api/groups?' + new URLSearchParams({page, search}), 'GET', null, {signal: controller.signal})
      .then(value => {if (!controller.signal.aborted) setItems(value || []);})
      .catch(failure => {if (!controller.signal.aborted && !failure.stale) setError(failure.message);})
      .finally(() => {if (!controller.signal.aborted) setLoading(false);});
    return () => controller.abort();
  }, [page, search, reload]);
  useEffect(() => {
    if (!user) return;
    const controller = new AbortController(); setMineLoading(true); setMineReady(false);
    api('/api/groups/mine', 'GET', null, {signal: controller.signal}).then(value => {if (!controller.signal.aborted) {setMine(value || []); setMineReady(true);}})
      .catch(failure => {if (!controller.signal.aborted && !failure.stale) setError(failure.message);})
      .finally(() => {if (!controller.signal.aborted) setMineLoading(false);});
    return () => controller.abort();
  }, [user, reload]);
  async function mutate(path, body) {
    const session = getSessionEpoch(); setBusy(true); setError('');
    try {
      const id = await api(path, 'POST', body);
      if (!alive.current || session !== getSessionEpoch()) return;
      if (path === '/api/groups') navigate('/groups/' + id);
      else {setReload(value => value + 1); navigate(path.replace('/api', '').replace('/join', ''));}
    } catch (failure) {if (alive.current && !failure.stale && session === getSessionEpoch()) setError(failure.message);}
    finally {if (alive.current && session === getSessionEpoch()) setBusy(false);}
  }
  const mineIds = new Set(mine.map(group => group.id));
  // 기본 그룹은 가입 목록에 남지만 일반 그룹 3개 제한에는 포함하지 않습니다.
  const ordinaryCount = mine.filter(group => group.systemGroup !== true).length;
  const hasSystemGroup = mine.some(group => group.systemGroup === true);
  return <main className={`cw-page gp-page ${dark ? 'dark' : 'light'}`}><div className="cw-wrap">
    <header className="cw-head"><div><p className="cw-eyebrow">STUDY GROUPS</p><h1 className="cw-title">함께 푸는 그룹</h1><p className="cw-desc">그룹 전용 문제와 코딩 테스트를 함께 풀고, 역할별 기능을 관리하세요.</p></div><Link className="cw-btn" to="/contests">내 코딩 테스트</Link></header>
    {error && <p className="gp-error" role="alert">{error}</p>}
    <div className="gp-two"><section className="cw-card"><h2 className="cw-card-title">공개 그룹 찾기</h2>
      <form className="gp-search" onSubmit={event => {event.preventDefault(); setPage(0); setSearch(query);}}><label className="cw-label" htmlFor="group-search">그룹 이름</label><input className="cw-input" id="group-search" value={query} onChange={event => setQuery(event.target.value)} maxLength={100} /><button className="cw-btn primary" disabled={loading}>검색</button></form>
      {loading ? <p role="status">그룹을 불러오는 중입니다.</p> : !items.length ? <p className="gp-muted">검색 결과가 없습니다.</p> : items.map(group => <article className="gp-row" key={group.id}><div><h3>{group.groupName}</h3>{group.systemGroup === true && <span className="gp-system-badge">기본 그룹 · 가입 제한 제외</span>}<p>{group.memberCount}명 참여</p></div>{mineIds.has(group.id) ? <Link to={'/groups/' + group.id} className="cw-btn sm">그룹 열기</Link> : user ? <button className="cw-btn sm" disabled={busy || !mineReady || (group.systemGroup !== true && ordinaryCount >= 3)} onClick={() => mutate('/api/groups/' + group.id + '/join', null)}>가입</button> : <Link to="/login" className="cw-btn sm">로그인 후 가입</Link>}</article>)}
      <div className="cw-pager"><button className="cw-btn" disabled={page === 0 || loading} onClick={() => setPage(value => value - 1)}>이전</button><span>{page + 1}</span><button className="cw-btn" disabled={items.length < 30 || loading} onClick={() => setPage(value => value + 1)}>다음</button></div>
    </section><section className="cw-card"><h2 className="cw-card-title">내 그룹 {user && <small className="gp-quota">{mineReady ? `일반 그룹 ${ordinaryCount}/3${hasSystemGroup ? ' + 기본 그룹' : ''}` : mineLoading ? '가입 현황 확인 중' : '가입 현황 확인 불가'}</small>}</h2>
      {!user ? <p><Link to="/login">로그인</Link>하면 가입한 그룹이 나타납니다.</p> : <>{mineReady && !mine.length && <p className="gp-muted">아직 가입한 그룹이 없습니다.</p>}{mine.map(group => <Link to={'/groups/' + group.id} className="gp-row gp-link" key={group.id}><div><h3>{group.groupName}</h3>{group.systemGroup === true && <span className="gp-system-badge">기본 그룹 · 가입 제한 제외</span>}<p>{group.roleName} · {group.memberCount}명</p></div><span>열기 →</span></Link>)}
      <form className="gp-form" onSubmit={event => {event.preventDefault(); mutate('/api/groups', form);}}><h3>그룹 만들기</h3><p className="gp-muted">해결한 문제 50개 이상이면 그룹을 만들 수 있습니다. 소유 그룹을 포함한 일반 그룹은 동시에 최대 3개까지 참여할 수 있고, 기본 그룹은 이 제한에서 제외됩니다. 최종 조건은 서버에서 확인합니다.</p><label className="cw-label" htmlFor="group-name">그룹 이름</label><input id="group-name" className="cw-input" required maxLength={16} value={form.name} onChange={event => setForm(value => ({...value, name: event.target.value}))} /><label className="cw-label" htmlFor="group-description">소개</label><textarea id="group-description" className="cw-textarea" maxLength={1000} value={form.description} onChange={event => setForm(value => ({...value, description: event.target.value}))} /><button className="cw-btn primary" disabled={busy || !mineReady || ordinaryCount >= 3 || Number(user.solved_count || 0) < 50}>그룹 만들기</button></form></>}
    </section></div>
  </div></main>;
}
