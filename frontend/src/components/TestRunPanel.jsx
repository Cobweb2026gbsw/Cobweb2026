import { useEffect, useId, useMemo, useRef, useState } from 'react';
import { useAuth } from '../context/AuthContext';
import { api, getSessionEpoch } from '../lib/api';
import { ProtocolError, RequestError } from '../lib/errors';
import { formatFunctionExampleInput } from '../lib/exampleInputFormat';
import { labelStatus, sourceBytes, STATUS } from '../lib/problemModel';
import { normalizePublicTestExample } from '../lib/publicTestExample';
import SubmissionDiagnostic from './SubmissionDiagnostic';
import './TestRunPanel.css';

const CASE_LIMIT = 2;
const BYTE_LIMIT = 65536;
const TEST_TABS = [['cases', '테스트 케이스'], ['output', '실행 출력']];
const SUBMISSION_TABS = [...TEST_TABS, ['submission', '채점 결과']];
const pending = status => status === 'QUEUED' || status === 'RUNNING';
const terminal = status => Object.hasOwn(STATUS, status) && !pending(status);
const sameProblem = (left, right) => left && right && left.number === right.number && left.epoch === right.epoch
  && left.owner === right.owner && left.problem === right.problem;
const sameScope = (left, right) => sameProblem(left, right) && left.language === right.language
  && left.code === right.code && left.mode === right.mode && left.loading === right.loading;

function publicExamples(problem) {
  const cases = [];
  for (const example of Array.isArray(problem?.examples) ? problem.examples : []) {
    const rawInput = example?.input_text ?? example?.input;
    const rawExpected = example?.output_text ?? example?.output;
    if (typeof rawInput !== 'string' || typeof rawExpected !== 'string') continue;
    const exampleCase = normalizePublicTestExample(problem, rawInput, rawExpected);
    if (!exampleCase) continue;
    const { input, expected } = exampleCase;
    // 빈 문자열은 테스트 값입니다. UTF-8 제한을 넘거나 값이 빠진 예제만 건너뜁니다.
    if ([input, expected].every(value => typeof value === 'string' && value.length <= BYTE_LIMIT
         && !/[\uD800-\uDFFF]/u.test(value) && sourceBytes(value) <= BYTE_LIMIT)) {
      cases.push(exampleCase);
      if (cases.length === CASE_LIMIT) break;
    }
  }
  return cases;
}

function resolveResults(data, id, snapshot) {
  const privateSample = snapshot.mode === 'private';
  const count = privateSample ? CASE_LIMIT : snapshot.cases.length;
  if (data?.id !== id || data.problemNumber !== Number(snapshot.number) || data.language !== snapshot.language ||
      data.privateSample !== privateSample || data.testCount !== count ||
      !['QUEUED', 'RUNNING', 'COMPLETED', 'SYSTEM_ERROR'].includes(data.status) ||
      !Array.isArray(data.results) || data.results.length > count ||
      (pending(data.status) && data.results.length) || (data.status === 'COMPLETED' && data.results.length !== count)) {
    throw new ProtocolError();
  }
  if (data.status === 'SYSTEM_ERROR' && privateSample) return [];
  return data.results.map((result, index) => {
    if (!terminal(result?.status) || !Number.isSafeInteger(result.timeMs) || result.timeMs < 0) throw new ProtocolError();
    const { status, timeMs } = result;
    // 비공개 응답에는 상태와 시간만 보관합니다.
    if (privateSample) return { status, timeMs };
    const { input, expected, displayInput } = snapshot.cases[index];
    if (result.input !== input || result.expected !== expected ||
        (result.actual != null && typeof result.actual !== 'string')) throw new ProtocolError();
    return { input, expected, displayInput, actual: result.actual ?? null, status, timeMs, diagnostic: result.diagnostic };
  });
}

function CaseValue({ label, value, empty = '빈 값' }) {
  return <div className="cw-test-value">
    <dt>{label}</dt>
    <dd>{value === '' || value == null
      ? <span className="cw-hint">{value === '' ? empty : '출력 확인 불가'}</span>
      : <pre tabIndex={0} aria-label={label}>{value}</pre>}</dd>
  </div>;
}

function CaseValues({ problem, test, showActual = false }) {
  const input = test.displayInput ?? formatFunctionExampleInput(problem, test.input) ?? test.input;
  return <dl className="cw-test-values">
    <CaseValue label="입력값" value={input} />
    <CaseValue label="예상 출력" value={test.expected} empty="빈 출력" />
    {showActual && <CaseValue label="실제 출력" value={test.actual} empty="빈 출력" />}
  </dl>;
}

export default function TestRunPanel({ problem, number, language, code, disabled = false, renderSubmitAction, submissionResult }) {
  const { user, epoch, loading } = useAuth();
  const publicCases = useMemo(() => publicExamples(problem), [problem]);
  const owner = user?.id ?? user?.username ?? null;
  const [choice, setChoice] = useState(null);
  const [run, setRun] = useState(null);
  const [activeTab, setActiveTab] = useState('cases');
  const tabId = useId(), tabs = useRef({}), viewport = useRef(null), validationAlert = useRef(null);
  const mounted = useRef(false), generation = useRef(0), flight = useRef(false);
  const controller = useRef(null), timer = useRef(null), scope = useRef(null);
  const context = { number: String(number), epoch, owner, problem };
  const mode = sameProblem(choice, context) ? choice.mode : 'public';
  scope.current = { ...context, language, code, mode, loading };
  const isPrivate = mode === 'private';
  const hasCases = isPrivate || publicCases.length > 0;
  const hasSubmissionTab = submissionResult != null;
  const tabItems = hasSubmissionTab ? SUBMISSION_TABS : TEST_TABS;

  function stop() {
    generation.current++;
    flight.current = false;
    controller.current?.abort();
    controller.current = null;
    clearTimeout(timer.current);
    timer.current = null;
  }

  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; stop(); };
  }, []);
  useEffect(() => {
    setChoice(null);
    setActiveTab('cases');
  }, [number, epoch, owner, problem]);
  useEffect(() => {
    stop();
    setRun(null);
    setActiveTab(tab => tab === 'submission' && hasSubmissionTab ? tab : 'cases');
  }, [number, epoch, owner, language, code, problem, mode, loading]);
  useEffect(() => {
    if (viewport.current) viewport.current.scrollTop = 0;
  }, [activeTab]);

  const visible = sameScope(run?.scope, scope.current) ? run : null;
  const busy = Boolean(visible && pending(visible.status));
  useEffect(() => {
    if (run?.errorKind === 'validation' && sameScope(run.scope, scope.current)) validationAlert.current?.focus();
  }, [run]);

  function selectMode(next) {
    if (mode === next) return;
    stop();
    setChoice({ ...context, mode: next });
    setActiveTab('cases');
  }

  function tabKey(event, tab) {
    const order = tabItems.map(([id]) => id);
    const index = order.indexOf(tab);
    const next = event.key === 'ArrowRight' ? order[(index + 1) % order.length]
      : event.key === 'ArrowLeft' ? order[(index + order.length - 1) % order.length]
        : event.key === 'Home' ? order[0] : event.key === 'End' ? order.at(-1) : null;
    if (next) {
      event.preventDefault();
      setActiveTab(next);
      tabs.current[next]?.focus();
    }
  }

  function showSubmissionResult() {
    if (!hasSubmissionTab) return;
    setActiveTab('submission');
    tabs.current.submission?.focus({ preventScroll: true });
  }

  async function execute() {
    if (disabled || flight.current || !hasCases) return;
    const snapshot = { ...scope.current, cases: isPrivate ? [] : publicCases };
    try {
      if (loading) throw new RequestError('로그인 정보를 확인 중입니다. 잠시 후 다시 시도해주세요.');
      if (!user) throw new RequestError('테스트를 실행하려면 로그인해주세요.');
      if (!Number.isSafeInteger(Number(number)) || Number(number) <= 0) throw new RequestError('문제 번호를 확인해주세요.');
      if (!language || !problem?.languages?.includes(language)) throw new RequestError('문제에서 허용한 언어를 선택하세요.');
      if (typeof code !== 'string' || !code.trim()) throw new RequestError('풀이 코드를 입력하세요.');
      if (sourceBytes(code) > BYTE_LIMIT) throw new RequestError('코드는 UTF-8 64 KiB 이하여야 합니다.');
    } catch (error) {
      setActiveTab('cases');
      setRun({ scope: snapshot, error: error.message, errorKind: 'validation' });
      return;
    }

    flight.current = true;
    const ticket = ++generation.current, session = getSessionEpoch(), abort = new AbortController();
    controller.current = abort;
    const current = () => mounted.current && !abort.signal.aborted && generation.current === ticket
      && session === getSessionEpoch() && sameScope(snapshot, scope.current);
    const update = data => { if (current()) setRun({ scope: snapshot, ...data }); };
    const fail = error => {
      if (current() && !error.stale && error.name !== 'AbortError') {
        flight.current = false;
        setActiveTab('output');
        update({ error: error.message });
      }
    };

    setActiveTab('output');
    update({ status: 'QUEUED', results: [] });
    try {
      const request = isPrivate ? { language, sourceCode: code, privateSample: true }
        : { language, sourceCode: code, privateSample: false, cases: snapshot.cases.map(({ input, expected }) => ({ input, expected })) };
      const id = await api(`/api/problems/${number}/test-runs`, 'POST', request, { signal: abort.signal });
      if (!current()) return;
      if (!Number.isSafeInteger(id) || id <= 0) throw new ProtocolError(undefined, { requestMayHaveCompleted: true });
      async function poll() {
        if (!current()) return;
        try {
          const data = await api(`/api/test-runs/${id}`, 'GET', null, { signal: abort.signal });
          if (!current()) return;
          const results = resolveResults(data, id, snapshot);
          update({ id, status: data.status, results });
          if (pending(data.status)) timer.current = setTimeout(poll, 1000);
          else flight.current = false;
        } catch (error) { fail(error); }
      }
      await poll();
    } catch (error) { fail(error); }
  }

  return <section className="cw-test-run-panel" aria-label="코드 테스트">
    <div className="cw-test-heading">
      <h2 className="cw-card-title">코드 테스트</h2>
      <div className="cw-test-modes" role="group" aria-label="테스트 방식">
        <button type="button" aria-pressed={!isPrivate} onClick={() => selectMode('public')}>공개 예제</button>
        <button type="button" aria-pressed={isPrivate} onClick={() => selectMode('private')}>비공개 채점</button>
      </div>
    </div>
    <p className="cw-hint">{isPrivate
      ? '실제 채점 케이스 2개의 통과 여부와 실행 시간을 보여줍니다.'
      : '문제에 공개된 예제로 입출력을 확인합니다.'}</p>
    <div className="cw-test-tabs" role="tablist" aria-label="코드 테스트 보기">
      {tabItems.map(([tab, label]) => <button key={tab} type="button" role="tab" id={`${tabId}-tab-${tab}`}
        aria-controls={`${tabId}-panel-${tab}`} aria-selected={activeTab === tab} tabIndex={activeTab === tab ? 0 : -1}
        ref={element => { tabs.current[tab] = element; }} onClick={() => setActiveTab(tab)}
        onKeyDown={event => tabKey(event, tab)}>{label}</button>)}
    </div>
    <div className="cw-test-scroll" ref={viewport}>
      <div className="cw-test-tabpanel" role="tabpanel" id={`${tabId}-panel-cases`}
        aria-labelledby={`${tabId}-tab-cases`} hidden={activeTab !== 'cases'} tabIndex={0}>
        {visible?.errorKind === 'validation' && <p ref={validationAlert} role="alert" tabIndex={-1}
          className="cw-status-message error">{visible.error}</p>}
        {isPrivate ? <div className="cw-test-private-cases">
          {Array.from({ length: CASE_LIMIT }, (_, i) => <article key={i} className="cw-test-private-case" aria-label={`비공개 테스트 ${i + 1}`}>
            <h3>테스트 {i + 1}</h3><span className="cw-badge">비공개 입력</span>
          </article>)}
        </div> : publicCases.length ? <div className="cw-test-public-cases">
          {publicCases.map((test, i) => <article key={i} className="cw-test-public-case" aria-label={`공개 예제 ${i + 1}`}>
            <h3>테스트 {i + 1}</h3><CaseValues problem={problem} test={test} />
          </article>)}
        </div> : <p className="cw-hint cw-test-empty">실행할 수 있는 공개 예제가 없습니다. 비공개 채점을 선택해 테스트할 수 있습니다.</p>}
      </div>
      <div className="cw-test-tabpanel" role="tabpanel" id={`${tabId}-panel-output`}
        aria-labelledby={`${tabId}-tab-output`} hidden={activeTab !== 'output'} tabIndex={0} aria-busy={busy}>
        {(!visible || visible.errorKind === 'validation') && <p className="cw-hint cw-test-empty">{isPrivate
          ? '실행 후 통과 여부와 실행 시간을 확인할 수 있습니다.'
          : '실행 후 입력값, 예상 출력, 실제 출력을 비교할 수 있습니다.'}</p>}
        {visible?.error && visible.errorKind !== 'validation' && <p role="alert" className="cw-status-message error">{visible.error}</p>}
        {busy && <p role="status" className="cw-hint">{visible.status === 'RUNNING' ? '코드를 실행하고 있습니다.' : '테스트 실행을 기다리고 있습니다.'}</p>}
        {visible?.status === 'SYSTEM_ERROR' && <p role="alert" className="cw-status-message error">테스트 실행을 완료하지 못했습니다. 잠시 후 다시 시도해주세요.</p>}
        {Boolean(visible?.results?.length) && <div className="cw-test-results" role="region" aria-label="테스트 실행 결과">
          {visible.results.map((result, i) => <article key={i}
            className={isPrivate ? 'cw-test-result' : 'cw-test-public-case'} aria-label={`테스트 ${i + 1} 결과`}>
            <div className="cw-test-result-head">
              <h3>테스트 {i + 1}</h3>
              <span className={`cw-badge ${result.status === 'ACCEPTED' ? 'green' : isPrivate ? 'red' : labelStatus(result.status).tone}`}>
                {result.status === 'ACCEPTED' ? '통과' : isPrivate ? '실패' : `실패 · ${labelStatus(result.status).label}`}
              </span>
              <span className="cw-hint">{result.timeMs} ms</span>
            </div>
            {!isPrivate && <><CaseValues problem={problem} test={result} showActual /><SubmissionDiagnostic submission={result} /></>}
          </article>)}
        </div>}
      </div>
      {hasSubmissionTab && <div className="cw-test-tabpanel" role="tabpanel" id={`${tabId}-panel-submission`}
        aria-labelledby={`${tabId}-tab-submission`} hidden={activeTab !== 'submission'} tabIndex={0}>
        {submissionResult}
      </div>}
    </div>
    <div className="cw-test-actions" aria-label="코드 실행 작업">
      <button type="button" className="cw-btn" disabled={disabled || busy || !hasCases || !language || !code?.trim()} onClick={execute}>
        {busy ? '테스트 실행 중…' : user ? '테스트 및 실행' : '로그인 후 테스트'}
      </button>
      {renderSubmitAction?.({ testing: busy, showSubmissionResult })}
    </div>
  </section>;
}
