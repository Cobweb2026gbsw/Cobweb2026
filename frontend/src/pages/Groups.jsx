import {useEffect, useRef, useState} from 'react';
import {Link, useLocation, useNavigate, useSearchParams} from 'react-router-dom';
import {useAuth} from '../context/AuthContext';
import {api, getSessionEpoch} from '../lib/api';
import {groupListState, groupDetailUrl, groupPosition, rememberGroupPosition} from '../lib/groupNavigation';
import './common.css';
import './Groups.css';

export default function Groups({dark}) {
  const {epoch} = useAuth();
  return <GroupBrowser key={epoch} dark={dark} />;
}
function GroupBrowser({dark}) {
  const {user} = useAuth(), navigate = useNavigate(), location = useLocation();
  const [params, setParams] = useSearchParams();
  const {tab, page, search} = groupListState(params);
  const from = location.pathname + location.search, owner = String(user?.id || user?.username || 'guest');
  const [createOpen, setCreateOpen] = useState(false), [preview, setPreview] = useState(null);
  const [items, setItems] = useState([]), [mine, setMine] = useState([]);
  const [query, setQuery] = useState(location.state?.groupSearchDraft ?? search), [reload, setReload] = useState(0);
  const [loading, setLoading] = useState(true), [listError, setListError] = useState('');
  const [actionError, setActionError] = useState(''), [busy, setBusy] = useState(false), [joining, setJoining] = useState(null);
  const [mineLoading, setMineLoading] = useState(Boolean(user)), [mineReady, setMineReady] = useState(false), [mineError, setMineError] = useState('');
  const [form, setForm] = useState({name: '', description: ''});
  const alive = useRef(true), searchInput = useRef(null), tabButtons = useRef({});
  useEffect(() => {alive.current = true; return () => {alive.current = false;};}, []);
  useEffect(() => {
    const controller = new AbortController(); setLoading(true); setItems([]); setListError('');
    api('/api/groups?' + new URLSearchParams({page, search}), 'GET', null, {signal: controller.signal})
      .then(value => {if (!controller.signal.aborted) setItems(value || []);})
      .catch(failure => {if (!controller.signal.aborted && !failure.stale) setListError(failure.message);})
      .finally(() => {if (!controller.signal.aborted) setLoading(false);});
    return () => controller.abort();
  }, [page, search, reload]);
  useEffect(() => {
    if (!user) return;
    const controller = new AbortController(); setMineLoading(true); setMineReady(false); setMineError(''); setMine([]);
    api('/api/groups/mine', 'GET', null, {signal: controller.signal}).then(value => {if (!controller.signal.aborted) {setMine(value || []); setMineReady(true);}})
      .catch(failure => {if (!controller.signal.aborted && !failure.stale) setMineError(failure.message);})
      .finally(() => {if (!controller.signal.aborted) setMineLoading(false);});
    return () => controller.abort();
  }, [user, reload]);
  const restoredPosition = useRef('');
  useEffect(() => {
    if ((tab === 'mine' ? mineLoading : loading) || restoredPosition.current === from) return;
    const y = groupPosition(owner, from);
    const frame = window.requestAnimationFrame(() => {restoredPosition.current = from; if (y > 0) window.scrollTo({top: y, behavior: 'instant'});});
    return () => window.cancelAnimationFrame(frame);
  }, [from, owner, tab, loading, mineLoading]);
  function rememberPosition() {rememberGroupPosition(owner, from, window.scrollY);}
  useEffect(() => {
    const save = () => {if (restoredPosition.current === from) rememberGroupPosition(owner, from, window.scrollY);};
    window.addEventListener('scroll', save, {passive: true});
    return () => window.removeEventListener('scroll', save);
  }, [owner, from]);
  function updateList(changes) {
    rememberPosition();
    const next = new URLSearchParams(params);
    for (const [key, value] of Object.entries(changes)) {
      if (value === '' || value === 0 || key === 'tab' && value === 'explore') next.delete(key);
      else next.set(key, String(value));
    }
    setParams(next, {state: {groupSearchDraft: query, groupTabFocus: changes.tab}});
  }
  async function mutate(path, body) {
    if (busy) return;
    const session = getSessionEpoch(); setBusy(true); setJoining(path.endsWith('/join') ? path.split('/')[3] : null); setActionError('');
    try {
      const id = await api(path, 'POST', body);
      if (!alive.current || session !== getSessionEpoch()) return;
      rememberPosition();
      const targetId = path === '/api/groups' ? id : path.split('/')[3];
      navigate(groupDetailUrl(targetId, from));
    } catch (failure) {if (alive.current && !failure.stale && session === getSessionEpoch()) setActionError(failure.message);}
    finally {if (alive.current && session === getSessionEpoch()) {setBusy(false); setJoining(null);}}
  }
  function searchGroups(event) {
    event.preventDefault(); updateList({page: 0, q: query.trim()}); setReload(value => value + 1);
  }
  function changeTab(next, focus = false) {
    updateList({tab: next});
    if (focus) tabButtons.current[next]?.focus();
  }
  useEffect(() => {setQuery(location.state?.groupSearchDraft ?? search); if (location.state?.groupTabFocus) tabButtons.current[tab]?.focus();}, [location.key, search, tab]);
  function tabKey(event) {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault();
    changeTab(event.key === 'Home' ? 'explore' : event.key === 'End' ? 'mine' : tab === 'explore' ? 'mine' : 'explore', true);
  }
  const mineIds = new Set(mine.map(group => group.id));
  // 기본 그룹은 가입 목록에 남지만 일반 그룹 3개 제한에는 포함하지 않습니다.
  const ordinaryCount = mine.filter(group => group.systemGroup !== true).length;
  const hasSystemGroup = mine.some(group => group.systemGroup === true);
  const canCreate = Boolean(user && mineReady && ordinaryCount < 3 && Number(user.solved_count || 0) >= 50);
  const quota = mineReady ? `일반 그룹 ${ordinaryCount}/3${hasSystemGroup ? ' + 기본 그룹' : ''}` : mineLoading ? '가입 현황 확인 중' : '가입 현황 확인 불가';
  const retry = () => setReload(value => value + 1);
  const openPreview = group => {setActionError(''); setPreview(group);};
  function viewMine() {setPreview(null); setCreateOpen(false); changeTab('mine', true);}
  function groupAction(group, inPreview = false) {
    if (mineIds.has(group.id)) return <Link to={groupDetailUrl(group.id, from)} onClick={rememberPosition} className="cw-btn sm">그룹 열기</Link>;
    if (!user) return <Link to="/login" state={{from}} className="cw-btn sm">로그인 후 가입</Link>;
    const full = group.systemGroup !== true && ordinaryCount >= 3;
    const reason = !mineReady ? mineLoading ? '가입 현황을 확인하고 있어요.' : '가입 현황을 불러오지 못했어요.' : full ? '일반 그룹은 최대 3개까지 가입할 수 있어요.' : '';
    const reasonId = `gp-join-reason-${group.id}-${inPreview ? 'preview' : 'list'}`;
    return <div className="gp-join-action"><button className="cw-btn sm" disabled={busy || !mineReady || full} aria-describedby={reason ? reasonId : undefined} onClick={() => mutate('/api/groups/' + group.id + '/join', null)}>{joining === String(group.id) ? '가입 중…' : '가입'}</button>
      {reason && <p className="gp-action-reason" id={reasonId}>{reason}</p>}
      {!mineReady && !mineLoading ? <button type="button" className="gp-inline-action" onClick={retry}>가입 현황 다시 확인</button> : full && mineReady && <button type="button" className="gp-inline-action" onClick={viewMine}>내 그룹 확인하기</button>}
    </div>;
  }
  return <main className={`cw-page gp-page gp-browser ${dark ? 'dark' : 'light'}`}><div className="cw-wrap">
    <header className="cw-head gp-browser-head">
      <div><p className="cw-eyebrow">STUDY GROUPS</p><h1 className="cw-title">함께 푸는 그룹</h1><p className="cw-desc">함께 문제를 풀고 성장할 그룹을 찾아보세요.</p></div>
      <button className="cw-btn primary gp-create-trigger" type="button" aria-haspopup="dialog" onClick={() => {setActionError(''); setCreateOpen(true);}}>그룹 만들기</button>
    </header>
    <div className="gp-browser-tabs">
      <div role="tablist" aria-label="그룹 목록">
        {[['explore', '그룹 탐색'], ['mine', '내 그룹']].map(([key, label]) => <button key={key} type="button" role="tab" id={`gp-tab-${key}`} aria-controls={`gp-panel-${key}`} aria-selected={tab === key} tabIndex={tab === key ? 0 : -1} ref={node => {tabButtons.current[key] = node;}} onClick={() => changeTab(key)} onKeyDown={tabKey}>{label}</button>)}
      </div>
      {user && <small className="gp-quota">{quota}</small>}
    </div>
    {actionError && !createOpen && !preview && <p className="gp-error" role="alert">{actionError}</p>}
    {mineError && <div className="gp-error gp-browser-error" role="alert"><span>{mineError}</span><button className="cw-btn sm" onClick={retry}>가입 현황 다시 확인</button></div>}
    <section id="gp-panel-explore" role="tabpanel" aria-labelledby="gp-tab-explore" hidden={tab !== 'explore'} tabIndex={0}>
      {tab === 'explore' && <>
        <form className="gp-search gp-browser-search" role="search" aria-label="그룹 검색" onSubmit={searchGroups}>
          <input className="cw-input" id="group-search" ref={searchInput} aria-label="그룹 이름 검색" placeholder="그룹 이름으로 검색" value={query} onChange={event => setQuery(event.target.value)} maxLength={100} />
          <button className="cw-btn" disabled={loading}>검색</button>
        </form>
        {loading ? <div className="gp-empty" role="status"><p>그룹을 불러오는 중입니다.</p></div> : listError ? <div className="gp-empty"><p className="gp-error" role="alert">{listError}</p><button className="cw-btn" onClick={retry}>다시 불러오기</button></div> : !items.length ? <div className="gp-empty" role="status">
          <h2>{search ? '일치하는 그룹이 없어요' : '그룹 이름으로 검색해 보세요'}</h2>
          <p>{search ? '다른 이름으로 다시 검색해 보세요.' : '함께하고 싶은 그룹의 이름을 입력해 주세요.'}</p>
          <button className="cw-btn" onClick={() => {searchInput.current?.focus(); searchInput.current?.select();}}>{search ? '검색어 바꾸기' : '그룹 검색하기'}</button>
          {page > 0 && <button className="cw-text-link gp-reset-search" onClick={() => updateList({page: 0})}>첫 검색 결과 보기</button>}
        </div> : <div className="gp-group-list">{items.map(group => <GroupRow key={group.id} group={group} onPreview={() => openPreview(group)}>
          {groupAction(group)}
        </GroupRow>)}</div>}
        {!loading && !listError && items.length > 0 && (page > 0 || items.length === 30) && <nav className="cw-pager" aria-label="그룹 목록 페이지"><button className="cw-btn" disabled={page === 0} onClick={() => updateList({page: page - 1})}>이전</button><span aria-current="page">{page + 1}</span><button className="cw-btn" disabled={items.length < 30} onClick={() => updateList({page: page + 1})}>다음</button></nav>}
      </>}
    </section>
    <section id="gp-panel-mine" role="tabpanel" aria-labelledby="gp-tab-mine" hidden={tab !== 'mine'} tabIndex={0}>
      {tab === 'mine' && (!user ? <div className="gp-empty"><h2>내 그룹을 확인해 보세요</h2><p>로그인하면 가입한 그룹을 한곳에서 볼 수 있어요.</p><Link className="cw-btn" to="/login">로그인</Link></div> : mineLoading ? <div className="gp-empty" role="status"><p>내 그룹을 불러오는 중입니다.</p></div> : mineReady && !mine.length ? <div className="gp-empty" role="status"><h2>아직 가입한 그룹이 없어요</h2><p>관심 있는 그룹을 찾아 함께 시작해 보세요.</p><button className="cw-btn" onClick={() => changeTab('explore', true)}>그룹 탐색하기</button></div> : mineReady && <div className="gp-group-list">{mine.map(group => <GroupRow key={group.id} group={group} showRole activityUrl={groupDetailUrl(group.id, from)} onActivity={rememberPosition}><Link to={groupDetailUrl(group.id, from)} onClick={rememberPosition} className="cw-btn sm">그룹 열기</Link></GroupRow>)}</div>)}
    </section>
    {preview && <GroupDialog title={preview.groupName} titleId="gp-preview-title" closeLabel="그룹 미리보기 닫기" onClose={() => setPreview(null)}>
      <p className="gp-preview-meta">{preview.memberCount}명 참여</p>
      {preview.systemGroup === true && <span className="gp-system-badge">기본 그룹 · 가입 제한 제외</span>}
      <h3 className="gp-preview-label">그룹 소개</h3><p className="gp-preview-description">{preview.description || '아직 등록된 소개가 없어요.'}</p>
      {actionError && <p className="gp-error" role="alert">{actionError}</p>}
      <div className="gp-dialog-actions"><button className="cw-btn" onClick={() => setPreview(null)}>닫기</button>{groupAction(preview, true)}</div>
    </GroupDialog>}
    {createOpen && <CreateGroupDialog user={user} form={form} setForm={setForm} canCreate={canCreate} quota={quota} mineReady={mineReady} mineLoading={mineLoading} ordinaryCount={ordinaryCount} onRetry={retry} onViewMine={viewMine} busy={busy} error={actionError} onClose={() => setCreateOpen(false)} onSubmit={event => {event.preventDefault(); if (canCreate && form.name.trim()) mutate('/api/groups', {name: form.name.trim(), description: form.description.trim()});}} />}
  </div></main>;
}

const ROLE_LABELS = {OWNER: '그룹장', MANAGER: '관리자', MEMBER: '회원', AUTHOR: '출제자', CONTEST_MANAGER: '코딩테스트 관리자'};
function groupRoleLabel(group) {return ROLE_LABELS[group.roleKey] || (group.roleName === '소유자' ? '그룹장' : group.roleName) || '회원';}

function GroupRow({group, children, onPreview, showRole = false, activityUrl, onActivity}) {
  return <article className="gp-group-row">
    <div className="gp-group-copy"><div className="gp-group-title"><h3>{onPreview ? <button type="button" className="gp-preview-trigger" aria-haspopup="dialog" aria-label={`${group.groupName} 미리보기`} onClick={onPreview}>{group.groupName}<span aria-hidden="true">↗</span></button> : group.groupName}</h3>{showRole && <span className="gp-role-badge">{groupRoleLabel(group)}</span>}{group.systemGroup === true && <span className="gp-system-badge">기본 그룹 · 가입 제한 제외</span>}</div><p className="gp-group-description" title={group.description || undefined}>{group.description || '아직 등록된 소개가 없어요.'}</p>{showRole && group.activity && <GroupActivity activity={group.activity} url={activityUrl} onNavigate={onActivity}/>}</div>
    <span className="gp-group-members">{group.memberCount}명</span><div className="gp-group-action">{children}</div>
  </article>;
}

function GroupActivity({activity, url, onNavigate}) {
  const count = activity.recentProblemCount, contest = activity.nextContest;
  const date = contest?.startsAt ? new Date(contest.startsAt) : null;
  const validDate = date && Number.isFinite(date.getTime());
  return <div className="gp-group-activity" aria-label="그룹 새 소식">
    {Number.isFinite(count) && (count > 0 ? <Link className="gp-activity-link" to={url + '#group-problems'} onClick={onNavigate}>최근 {activity.windowDays || 7}일 새 문제 {count}개 →</Link> : <span>최근 {activity.windowDays || 7}일 새 문제 없음</span>)}
    {contest && validDate ? <Link className="gp-activity-link gp-next-contest" to={url + '#group-contests'} onClick={onNavigate}><span>예정 코딩테스트 · {contest.title}</span><time dateTime={contest.startsAt}>{date.toLocaleString('ko-KR', {month: 'numeric', day: 'numeric', weekday: 'short', hour: '2-digit', minute: '2-digit'})}</time></Link> : !contest && <span>예정된 코딩테스트 없음</span>}
  </div>;
}

function GroupDialog({title, titleId, closeLabel, onClose, children, focusInput = false}) {
  const dialog = useRef(null);
  useEffect(() => {
    const element = dialog.current, opener = document.activeElement, overflow = document.body.style.overflow;
    element.showModal(); if (focusInput) element.querySelector('input')?.focus(); document.body.style.overflow = 'hidden';
    return () => {element.close(); document.body.style.overflow = overflow; if (opener?.isConnected) opener.focus();};
  }, [focusInput]);
  return <dialog ref={dialog} className="gp-create-dialog" aria-labelledby={titleId} onCancel={event => {event.preventDefault(); onClose();}} onClick={event => {
    if (event.target !== event.currentTarget) return;
    const bounds = event.currentTarget.getBoundingClientRect();
    if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) onClose();
  }}>
    <div className="gp-dialog-head"><h2 id={titleId}>{title}</h2><button type="button" className="gp-dialog-close" aria-label={closeLabel} onClick={onClose}>×</button></div>
    {children}
  </dialog>;
}

function CreateGroupDialog({user, form, setForm, canCreate, quota, mineReady, mineLoading, ordinaryCount, onRetry, onViewMine, busy, error, onClose, onSubmit}) {
  const remaining = Math.max(0, 50 - Number(user?.solved_count || 0));
  return <GroupDialog title="그룹 만들기" titleId="gp-create-title" closeLabel="그룹 만들기 닫기" onClose={onClose} focusInput>
    <p className="gp-dialog-desc">함께 공부할 그룹의 이름과 소개를 적어 주세요.</p>
    <div className="gp-create-conditions"><h3>그룹 생성 조건</h3><p>해결한 문제 50개 이상 · 일반 그룹 참여 3개 미만</p><p>소유 그룹도 참여 수에 포함되며, 기본 그룹은 이 제한에서 제외됩니다.</p>{user && <p className="gp-create-progress">내 현황 · 해결 {Number(user.solved_count || 0)}개 · {quota}</p>}</div>
    {!user ? <div className="gp-dialog-guest"><p>로그인 후 그룹을 만들 수 있어요.</p><Link className="cw-btn primary" to="/login">로그인</Link></div> : <form className="gp-create-form" onSubmit={onSubmit}>
      <label className="cw-label" htmlFor="group-name">그룹 이름 <span className="gp-field-hint">필수 · 최대 16자</span></label><input id="group-name" className="cw-input" required maxLength={16} value={form.name} placeholder="그룹 이름을 입력해 주세요" onChange={event => setForm(value => ({...value, name: event.target.value}))} />
      <label className="cw-label" htmlFor="group-description">소개 <span className="gp-field-hint">선택</span></label><textarea id="group-description" className="cw-textarea" maxLength={1000} value={form.description} placeholder="어떤 문제를 함께 풀고 싶은가요?" onChange={event => setForm(value => ({...value, description: event.target.value}))} />
      {error && <p className="gp-error" role="alert">{error}</p>}
      {!canCreate && <div className="gp-create-help" id="gp-create-help">
        {!mineReady && <p>{mineLoading ? '가입 현황을 확인하고 있어요.' : '가입 현황을 불러오지 못해 생성 조건을 확인할 수 없어요.'}</p>}
        {!mineReady && !mineLoading && <button type="button" className="gp-inline-action" onClick={onRetry}>가입 현황 다시 확인</button>}
        {remaining > 0 && <p>문제 {remaining}개를 더 풀면 생성 조건을 충족해요. <Link to="/problems?solved=unsolved">문제 풀러 가기 →</Link></p>}
        {mineReady && ordinaryCount >= 3 && <p>참여 중인 일반 그룹이 3개예요. <button type="button" className="gp-inline-action" onClick={onViewMine}>내 그룹 확인하기 →</button></p>}
      </div>}
      <div className="gp-dialog-actions"><button type="button" className="cw-btn" onClick={onClose}>취소</button><button className="cw-btn primary" aria-describedby={!canCreate ? "gp-create-help" : undefined} disabled={busy || !canCreate || !form.name.trim()}>{busy ? '만드는 중…' : '그룹 만들기'}</button></div>
    </form>}
  </GroupDialog>;
}
