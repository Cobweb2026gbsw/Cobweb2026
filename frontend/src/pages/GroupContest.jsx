import {useCallback, useEffect, useRef, useState} from 'react';
import {Link, useParams} from 'react-router-dom';
import {useAuth} from '../context/AuthContext';
import {api, getSessionEpoch} from '../lib/api';
import {CONTEST_STATUS, countdown, formatDate, questionAccessKey, shouldRecordEvent} from '../lib/groupContracts';
import {FILE_NAMES, languageHelp, sourceBytes, starterCode} from '../lib/problemModel';
import {preferredLanguage,rememberLanguage} from '../lib/editorLanguagePreference';
import {ContestForm} from './GroupDetail';
import CodeEditor from '../components/CodeEditor';
import ProblemStatement from '../components/ProblemStatement';
import ProblemAttribution from '../components/ProblemAttribution';
import './common.css';
import './Groups.css';
import './Contests.css';
import './GroupContest.css';
import './ProblemDetail.css';

const pending = value => ['QUEUED', 'RUNNING'].includes(value);
export default function GroupContest({dark}) {
  const {gid, cid} = useParams(), {epoch} = useAuth();
  return <ContestView key={`${epoch}:${gid}:${cid}`} gid={gid} cid={cid} dark={dark} />;
}
function ContestView({gid, cid, dark}) {
  const base = `/api/groups/${gid}/contests/${cid}`;
  const [contest, setContest] = useState(null), [groupPermissions, setGroupPermissions] = useState([]);
  const [now, setNow] = useState(Date.now()), [error, setError] = useState(''), [selected, setSelected] = useState(null), [tab, setTab] = useState('problems'), [mineReload, setMineReload] = useState(0);
  const [warning, setWarning] = useState(''), [eventError, setEventError] = useState('');
  const alive = useRef(true), request = useRef(0), refreshController = useRef(null), lastBoundary = useRef(0), ownFullscreen = useRef(false);
  useEffect(() => {alive.current = true; return () => {alive.current = false; request.current++;};}, []);
  const refresh = useCallback(async () => {
    const session = getSessionEpoch(), sequence = ++request.current, controller = new AbortController();
    refreshController.current?.abort(); refreshController.current = controller;
    // Refresh the role and the contest together. A newly granted TAKE_CONTESTS
    // must enable submission, and a revoked role must unmount old private data.
    try {const [value, membership] = await Promise.all([api(base, 'GET', null, {signal: controller.signal}), api('/api/groups/' + gid, 'GET', null, {signal: controller.signal})]); if (!alive.current || controller.signal.aborted || session !== getSessionEpoch() || sequence !== request.current) return; setContest(value); setGroupPermissions(membership.permissions || []); setError(''); setSelected(previous => value.problems?.some(problem => problem.problemNumber === previous) ? previous : null);}
    catch (failure) {if (alive.current && !controller.signal.aborted && !failure.stale && session === getSessionEpoch() && sequence === request.current) {setError(failure.message); if ([401, 403, 404].includes(failure.status)) {setContest(null); setSelected(null); setGroupPermissions([]);}}}
    finally {if (refreshController.current === controller) refreshController.current = null;}
  }, [base, gid]);
  useEffect(() => {
    refresh();
    const clock = setInterval(() => setNow(Date.now()), 1000), poll = setInterval(refresh, 15000);
    return () => {refreshController.current?.abort(); clearInterval(clock); clearInterval(poll); request.current++;};
  }, [gid, refresh]);
  useEffect(() => {
    if (!contest) return;
    const boundary = new Date(contest.status === 'UPCOMING' ? contest.startsAt : contest.endsAt).getTime();
    if (contest.status !== 'ENDED' && now >= boundary && now - lastBoundary.current > 15000) {lastBoundary.current = now; refresh();}
  }, [now, contest, refresh]);
  const canTake = groupPermissions.includes('TAKE_CONTESTS');
  useEffect(() => {
    if (!contest || contest.status !== 'ACTIVE' || !canTake) {setWarning(''); return;}
    const removers = [], session = getSessionEpoch(), recent = new Map();
    const add = (target, type, listener, options) => {target.addEventListener(type, listener, options); removers.push(() => target.removeEventListener(type, listener, options));};
    function record(type) {
      if (!shouldRecordEvent(contest, type) || getSessionEpoch() !== session) return;
      const at = Date.now(); if (at - (recent.get(type) || 0) < 2000) return; recent.set(type, at);
      const failed=failure=>{if(alive.current&&!failure?.stale&&getSessionEpoch()===session)setEventError('감시 신호를 기록하지 못했습니다. 연결 및 브라우저 보안 설정을 확인해주세요.');};
      try{
        // HTTP/구형 브라우저에서 randomUUID가 없어도 동기 예외를 이벤트 루프에 흘리지 않습니다.
        if(typeof globalThis.crypto?.randomUUID!=='function'){failed();return;}
        api(base + '/events', 'POST', {type, eventId: globalThis.crypto.randomUUID()}).catch(failed);
      }catch(failure){failed(failure);}
    }
    const warn = text => {if (alive.current && getSessionEpoch() === session) setWarning(text);};
    if (contest.captureProtection) {
      for (const type of ['copy', 'cut', 'contextmenu']) add(document, type, event => {event.preventDefault(); record('COPY_BLOCKED');});
      add(window, 'beforeprint', () => record('PRINT_BLOCKED'));
      add(document, 'keydown', event => {if (event.key === 'PrintScreen') {event.preventDefault(); record('CAPTURE_KEY');} if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'p') {event.preventDefault(); record('PRINT_BLOCKED');}});
    }
    if (contest.windowSwitchProtection) {
      add(window, 'blur', () => {record('WINDOW_BLUR'); warn('시험 창을 벗어났습니다. 전체 화면으로 돌아와 주세요.');});
      add(document, 'visibilitychange', () => {if (document.hidden) {record('VISIBILITY_HIDDEN'); warn('시험 탭이 숨겨졌습니다. 전체 화면으로 돌아와 주세요.');}});
      add(document, 'fullscreenchange', () => {if (!document.fullscreenElement) {record('FULLSCREEN_EXIT'); warn('전체 화면이 종료되었습니다. 다시 시작해 주세요.');} else warn('');});
      if (!document.fullscreenElement) warn('창 전환 감시가 켜져 있습니다. 시험을 시작하려면 전체 화면으로 전환해 주세요.');
    }
    return () => {removers.forEach(remove => remove());};
  }, [base, contest?.status, contest?.endsAt, contest?.captureProtection, contest?.windowSwitchProtection, canTake]);
  useEffect(() => () => {if (ownFullscreen.current && document.fullscreenElement) document.exitFullscreen?.().catch(() => {});}, []);
  useEffect(() => {if ((!contest || contest.status === 'ENDED' || !canTake) && ownFullscreen.current && document.fullscreenElement) {ownFullscreen.current = false; document.exitFullscreen?.().catch(() => {});}}, [contest?.status, canTake]);
  const tone = CONTEST_STATUS[contest?.status];
  return <main className={`cw-page gc-page gp-page ${dark ? 'dark' : 'light'} ${contest?.captureProtection && contest.status === 'ACTIVE' ? 'gc-capture-protected' : ''}`}><div className="cw-wrap">
    <header className="cw-head"><div><p className="cw-eyebrow">GROUP #{gid} / CONTEST #{cid}</p><h1 className="cw-title">{contest?.title || '코딩 테스트'}</h1><p className="cw-desc">{contest ? `${formatDate(contest.startsAt)} ~ ${formatDate(contest.endsAt)}` : '시험을 불러오는 중입니다.'}</p></div><div className="gp-actions"><Link to={'/groups/' + gid} className="cw-btn">← 그룹으로</Link><button className="cw-btn" onClick={refresh}>새로고침</button></div></header>
    {error && <p className="gp-error" role="alert">{error}</p>}{eventError && <p className="gp-error" role="alert">{eventError}</p>}
    {contest && <><div className="ct-top"><section className="cw-card ct-clock"><span>{tone?.label || contest.status}</span><strong className="cw-mono">{contest.status === 'ENDED' ? '시험 종료' : countdown(contest, now)}</strong><small className="gp-muted">최종 시작·종료 판정은 서버 시각 기준입니다.</small></section><section className="cw-card ct-rules"><h2 className="cw-card-title">시험 안내</h2><p>시험 제출·통계는 일반 풀이와 분리됩니다. 종료 후에는 관리자가 허용했고 실제 응시했던 경우에만 문제와 내 답안을 다시 볼 수 있습니다.</p><p className="ct-meta">{contest.problemCount}문제 · 다시 보기 {contest.replayAllowed ? '허용' : '비허용'} · 복사/인쇄 억제 {contest.captureProtection ? '켜짐' : '꺼짐'} · 창 전환 신호 {contest.windowSwitchProtection ? '켜짐' : '꺼짐'}</p></section></div>
      {(contest.captureProtection || contest.windowSwitchProtection) && <p className="gc-caution">브라우저 내 복사·인쇄 억제와 창 전환 신호만 제공합니다. 운영체제 캡처·다른 기기 사용은 완전히 막을 수 없으며, 신호만으로 부정행위나 실격을 확정하지 않습니다.</p>}
      <div className="gp-tabbar"><button className={'cw-btn ' + (tab === 'problems' ? 'active' : '')} onClick={() => setTab('problems')}>문제와 내 제출</button>{contest.canManage || contest.canViewResults ? <button className={'cw-btn ' + (tab === 'staff' ? 'active' : '')} onClick={() => setTab('staff')}>시험 운영</button> : null}</div>
      {tab === 'problems' && <><div className="gc-layout"><section className="cw-card"><h2 className="cw-card-title">시험 문제</h2>{!contest.problems?.length ? <p className="gp-muted">현재 시간·권한에서 볼 수 있는 문제가 없습니다.</p> : <div className="gc-question-list">{contest.problems.map((problem, index) => <button className={'cw-btn ' + (selected === problem.problemNumber ? 'primary' : '')} key={problem.problemNumber} onClick={() => setSelected(problem.problemNumber)}>{index + 1}. {problem.problemTitle}</button>)}</div>}</section><section className="cw-card gc-question">{selected ? <ContestQuestion key={`${selected}:${questionAccessKey(contest)}:${canTake}`} base={base} number={selected} contest={contest} canTake={canTake} onSubmitted={() => setMineReload(value => value + 1)} /> : <p className="gp-muted">목록에서 문제를 선택하세요. 비공개 문제는 서버가 숨깁니다.</p>}</section></div>{canTake ? <MineSubmissions key={`${contest.status}:${contest.replayAllowed}`} base={base} contest={contest} reload={mineReload} /> : <section className="cw-card gp-section"><h2 className="cw-card-title">내 시험 제출</h2><p className="gp-muted">시험 참여 권한이 없어 개인 답안을 표시하지 않습니다.</p></section>}</>}
      {tab === 'staff' && (contest.canManage || contest.canViewResults) && <section className="cw-card"><h2 className="cw-card-title">시험 운영</h2>{contest.canManage && <details><summary>시험 설정 편집</summary><ContestForm gid={gid} current={contest} onSaved={refresh} /></details>}{contest.canViewResults && <ContestResults base={base} status={contest.status} />}</section>}
    </>}
    {warning && contest?.status === 'ACTIVE' && <div className="gc-warning" role="dialog" aria-modal="true" aria-label="전체 화면 안내"><div className="cw-card"><h2>시험 화면으로 돌아와 주세요</h2><p>{warning}</p><p className="gp-muted">감시 신호는 참고 자료이며 자동 실격 처리가 아닙니다.</p><div className="gp-actions"><button className="cw-btn primary" onClick={async () => {try {await document.documentElement.requestFullscreen(); ownFullscreen.current = true; setWarning('');} catch {setWarning('전체 화면 전환이 허용되지 않았습니다. 브라우저 설정을 확인하거나 그룹 화면으로 나가 주세요.');}}}>전체 화면으로 돌아가기</button><Link to={'/groups/' + gid} className="cw-btn">시험 화면 나가기</Link></div></div></div>}
  </div></main>;
}
function ContestQuestion({base, number, contest, canTake, onSubmitted}) {
  const [problem, setProblem] = useState(null), [languages, setLanguages] = useState([]), [language, setLanguage] = useState(''), [codes, setCodes] = useState({}), [error, setError] = useState(''), [busy, setBusy] = useState(false), [result, setResult] = useState(null);
  const alive = useRef(true), pollTimer = useRef(null), pollGeneration = useRef(0), submitFlight = useRef(false), accessWindow = useRef(null);
  useEffect(() => {alive.current = true; return () => {alive.current = false; clearTimeout(pollTimer.current); pollGeneration.current++;};}, []);
  useEffect(() => {
    const controller = new AbortController(); setProblem(null); setCodes({}); setLanguage(''); setResult(null); setError(''); clearTimeout(pollTimer.current); pollGeneration.current++;
    Promise.all([api(base + '/problems/' + number, 'GET', null, {signal: controller.signal}), api('/api/languages', 'GET', null, {signal: controller.signal})]).then(([value, catalog]) => {if (controller.signal.aborted) return; setProblem(value); setLanguages(catalog); setLanguage(preferredLanguage(value.languages||[]));}).catch(failure => {if (!controller.signal.aborted && !failure.stale) setError(failure.message);});
    return () => controller.abort();
  }, [base, number, questionAccessKey(contest)]);
  async function poll(id, generation, session) {
    if (!alive.current || generation !== pollGeneration.current || session !== getSessionEpoch()) return;
    try {const value = await api('/api/submissions/' + id); if (!alive.current || generation !== pollGeneration.current || session !== getSessionEpoch()) return; setResult(value); if (pending(value.status)) pollTimer.current = setTimeout(() => poll(id, generation, session), 1500); else {setBusy(false); onSubmitted();}}
    catch (failure) {if (alive.current && generation === pollGeneration.current && !failure.stale && session === getSessionEpoch()) {setError(failure.message); setBusy(false);}}
  }
  const allowed = languages.filter(item => problem?.languages?.includes(item.id));
  // 시험 답안도 언어별로 분리하고, 계정/문제/열람 권한 변경 시 컴포넌트와 함께 폐기합니다.
  const code = Object.prototype.hasOwnProperty.call(codes,language) ? codes[language] : starterCode(problem,language);
  const setCode = value => setCodes(previous => ({...previous,[language]:value}));
  const open = contest.status === 'ACTIVE' && Date.now() < new Date(contest.endsAt).getTime() && canTake;
  accessWindow.current = {status:contest.status,endsAt:contest.endsAt,canTake};
  // 진행 중에 종료 시각이 바뀌어도 오래된 제출 콜백이 새 권한 창에 오류를 덮어쓰지 않습니다.
  const submissionWindowOpen = () => {
    const current = accessWindow.current;
    return current?.canTake && current.status === 'ACTIVE' && Date.now() < new Date(current.endsAt).getTime();
  };
  return <>{error && <p className="gp-error" role="alert">{error}</p>}{problem ? <><ProblemAttribution problem={problem}/><ProblemStatement problem={problem} />
    <form className="gp-form" onSubmit={async event => {
      event.preventDefault(); if (!open || !submissionWindowOpen() || busy || submitFlight.current) return;
      if (sourceBytes(code) > 65536) {setError('코드는 UTF-8 64 KiB 이하여야 합니다.'); return;}
      rememberLanguage(language);
      const session = getSessionEpoch(), generation = ++pollGeneration.current;
      submitFlight.current = true;
      clearTimeout(pollTimer.current); setBusy(true); setError('');
      try {
        const id = await api(base + '/submissions', 'POST', {problemNumber: number, sourceCode: code, language});
        if (!alive.current || generation !== pollGeneration.current || session !== getSessionEpoch()) return;
        setResult({id, status: 'QUEUED'}); onSubmitted(); poll(id, generation, session);
      } catch (failure) {
        if (alive.current && generation === pollGeneration.current && session === getSessionEpoch()) {
          if (!failure.stale && submissionWindowOpen()) setError(failure.message);
          setBusy(false);
        }
      } finally {if (generation === pollGeneration.current) submitFlight.current = false;}
    }}>
      <label className="cw-label">제출 언어<select className="cw-select" value={language} onChange={event => {setLanguage(event.target.value);rememberLanguage(event.target.value);}} disabled={!open || busy}>{allowed.map(item => <option value={item.id} key={item.id}>{item.label}</option>)}</select></label>
      <CodeEditor value={code} onChange={setCode} language={language} fileName={FILE_NAMES[language]} allowCopy={!contest.captureProtection || contest.status !== 'ACTIVE'} required disabled={!open || busy} />
      <p className="gp-muted">{languageHelp(language, problem.execution_mode, problem)}</p><button className="cw-btn primary" disabled={!open || busy || !code.trim()}>시험 답안 제출</button>
      <p className="gp-muted">종료된 시험은 다시 보기가 허용되어도 제출할 수 없습니다.</p>
      {result && <p role="status" className="gc-result">제출 #{result.id} · {result.status}{result.score != null ? ` · ${result.score}점` : ''}</p>}
    </form></> : !error && <p role="status">시험 문제를 불러오는 중입니다.</p>}</>;
}
function SubmissionRows({items}) {
  return items.length ? <div>{items.map(item => <details key={item.id} className="gc-submission"><summary>#{item.id} · 문제 #{item.problemNumber} · {item.language} · {item.status} · {item.score ?? '-'}점</summary><p className="gp-muted">{formatDate(item.submittedAt)}{item.judgedAt ? ' · 채점 ' + formatDate(item.judgedAt) : ''}</p><Link className="cw-btn sm" to={'/submissions/' + item.id}>상세 판정 보기</Link>{item.sourceCode != null && <pre className="gp-code">{item.sourceCode}</pre>}</details>)}</div> : <p className="gp-muted">제출 내역이 없습니다.</p>;
}
function MineSubmissions({base, contest, reload}) {
  const [items, setItems] = useState([]), [error, setError] = useState(''), [loading, setLoading] = useState(true);
  useEffect(() => {const controller = new AbortController(); let sequence = 0; async function load() {const request = ++sequence; try {const value = await api(base + '/me', 'GET', null, {signal: controller.signal}); if (!controller.signal.aborted && request === sequence) {setItems(value.submissions || []); setError('');}} catch (failure) {if (!controller.signal.aborted && !failure.stale && request === sequence) {setItems([]); setError(failure.message);}} finally {if (!controller.signal.aborted && request === sequence) setLoading(false);}} load(); const timer = contest.status === 'ACTIVE' ? setInterval(load, 5000) : null; return () => {controller.abort(); clearInterval(timer); sequence++;};}, [base, contest.status, contest.replayAllowed, reload]);
  return <section className="cw-card gp-section"><h2 className="cw-card-title">내 시험 제출</h2><p className="gp-muted">응시 중이거나, 종료 후 다시 보기가 허용된 참여자만 원문을 볼 수 있습니다.</p>{loading ? <p role="status">제출 내역을 불러오는 중입니다.</p> : error ? <p className="gp-muted">{error}</p> : <SubmissionRows items={items} />}</section>;
}
function ContestResults({base, status}) {
  const [items, setItems] = useState([]), [events, setEvents] = useState([]), [error, setError] = useState(''), [target, setTarget] = useState(null), [refresh, setRefresh] = useState(0);
  useEffect(() => {const controller = new AbortController(); let sequence = 0; async function load() {const request = ++sequence; try {const [rows, signals] = await Promise.all([api(base + '/results', 'GET', null, {signal: controller.signal}), api(base + '/events', 'GET', null, {signal: controller.signal})]); if (!controller.signal.aborted && request === sequence) {setItems(rows || []); setEvents(signals || []); setError('');}} catch (failure) {if (!controller.signal.aborted && !failure.stale && request === sequence) {setItems([]); setEvents([]); setTarget(null); setError(failure.message);}}} load(); const timer = setInterval(load, 10000); return () => {controller.abort(); clearInterval(timer); sequence++;};}, [base, status, refresh]);
  return <div className="gp-section"><div className="gp-actions"><h3>실시간 결과</h3><button className="cw-btn sm" onClick={() => setRefresh(value => value + 1)}>결과 새로고침</button></div><p className="gp-muted">10초마다 갱신합니다. 해결 수 내림차순, 각 문제 첫 정답까지 걸린 시간 합 오름차순입니다.</p>{error && <p className="gp-error" role="alert">{error}</p>}<div className="gc-results"><table><thead><tr><th>순위</th><th>사용자</th><th>해결</th><th>시간 합</th><th>답안</th></tr></thead><tbody>{items.map((item, index) => <tr key={item.userId}><td>{index + 1}</td><td>{item.username}</td><td>{item.solvedCount}</td><td>{item.timeSumSeconds}초</td><td><button className="cw-btn sm" onClick={() => setTarget(item)}>제출 보기</button></td></tr>)}</tbody></table></div>{!items.length && !error && <p className="gp-muted">아직 참여 기록이 없습니다.</p>}{target && <StaffSubmissions key={target.userId} base={base} target={target} onClose={() => setTarget(null)} />}<h3>창 전환·복사/인쇄 신호</h3><p className="gp-muted">최근 최대 500개입니다. 이 신호만으로 부정행위를 단정하지 마세요.</p>{!events.length && <p className="gp-muted">기록된 신호가 없습니다.</p>}<div className="gc-events">{events.map(event => <p key={event.id}><strong>{event.username}</strong> · {event.type}<span>{formatDate(event.occurredAt)}</span></p>)}</div></div>;
}
function StaffSubmissions({base, target, onClose}) {
  const [items, setItems] = useState([]), [error, setError] = useState(''), [events, setEvents] = useState([]), [loading, setLoading] = useState(true);
  useEffect(() => {const controller = new AbortController(); Promise.all([api(base + '/submissions?userId=' + target.userId, 'GET', null, {signal: controller.signal}), api(base + '/events?userId=' + target.userId, 'GET', null, {signal: controller.signal})]).then(([value, signals]) => {if (!controller.signal.aborted) {setItems(value.submissions || []); setEvents(signals || []);}}).catch(failure => {if (!controller.signal.aborted && !failure.stale) setError(failure.message);}).finally(() => {if (!controller.signal.aborted) setLoading(false);}); return () => controller.abort();}, [base, target.userId]);
  return <section className="gc-staff-submissions"><div className="gp-actions"><h3>{target.username}의 시험 제출</h3><button className="cw-btn sm" onClick={onClose}>닫기</button></div>{loading ? <p role="status">답안을 불러오는 중입니다.</p> : error ? <p role="alert" className="gp-error">{error}</p> : <><SubmissionRows items={items} /><h4>이 사용자의 감시 신호 {events.length}개</h4><div className="gc-events">{events.map(event => <p key={event.id}>{event.type}<span>{formatDate(event.occurredAt)}</span></p>)}</div></>}</section>;
}
