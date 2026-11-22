import { useEffect } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import useSubmissionVerdict, { isJudging } from '../lib/useSubmissionVerdict';
import { adminQueueReturn } from '../lib/adminQueueNavigation';
import { STATUS, labelStatus, formatDate } from '../lib/problemModel';
import SubmissionDiagnostic from '../components/SubmissionDiagnostic';
import SubmissionSource from '../components/SubmissionSource';
import VerdictGuidance from '../components/VerdictGuidance';
import { submissionListUrl, submissionProblemLink } from '../lib/submissionNavigation';
import './common.css';
import './SubmissionResult.css';

function ResultPage({ dark }) {
  const { id } = useParams();
  const { permissions } = useAuth();
  const location = useLocation();
  const { data: sub, error, retry } = useSubmissionVerdict(id);
  useEffect(() => {
    window.scrollTo({ top: 0, left: 0, behavior: 'instant' });
  }, [id]);
  const verdict = labelStatus(sub?.status);
  const cases = sub?.cases ?? [];
  const passed = cases.filter((c) => c.status === 'ACCEPTED').length;
  const problem = submissionProblemLink(sub, location.state?.problemNumber, location.state?.problemOrigin);
  const queue = permissions?.canManage && adminQueueReturn(location.state?.adminQueueReturn);
  const listReturn = submissionListUrl(new URLSearchParams(location.search).get('from'));
  const appealState = { submissionReturn: location.pathname + location.search, submissionState: location.state };
  const metrics = sub
    ? [
        ['언어', sub.language],
        ['실행시간', sub.execution_time_ms == null ? '—' : sub.execution_time_ms + ' ms'],
        ['제출시각', formatDate(sub.submitted_at)],
        ['판정시각', formatDate(sub.judged_at)],
      ]
    : [];

  return (
    <main className={`cw-page sr-page ${dark ? 'dark' : 'light'}`}>
      <div className="cw-wrap">
        <header className="cw-head">
          <div>
            <p className="cw-eyebrow">SUBMISSION / #{id}</p>
            <h1 className="cw-title">제출 결과</h1>
            <p className="cw-desc">제출한 코드의 판정과 테스트 결과를 확인하세요.</p>
          </div>
          <div className="cw-head-actions">
            <Link className="cw-btn" to={queue || listReturn}>
              {queue ? '채점 운영 현황' : '내 제출'}
            </Link>
            {problem && (
              <Link className="cw-btn primary" to={problem.to} state={problem.state}>
                {problem.label}
              </Link>
            )}
            {sub?.canReadOwnSource && (
              <Link
                className="cw-btn"
                to={`/issues?problem=${sub.problemNumber}&submission=${id}`}
                state={appealState}
              >
                채점 이의제기
              </Link>
            )}
          </div>
        </header>
        {error && (
          <div className="cw-card">
            <p className="cw-status-message error" role="alert">
              {error}
            </p>
            <button type="button" className="cw-btn" onClick={retry}>
              채점 결과 다시 불러오기
            </button>
          </div>
        )}
        {!sub && !error && <p className="cw-load">판정을 불러오는 중…</p>}
        {sub && (
          <>
            <div className="sr-top">
              <section className={`cw-card sr-verdict ${verdict.tone}`}>
                <span className={`cw-badge ${verdict.tone}`}>{sub.status}</span>
                <h2>{verdict.label}</h2>
                <p>
                  {isJudging(sub.status)
                    ? '채점 중입니다. 결과가 나오면 자동으로 갱신됩니다.'
                    : `${cases.length}개 케이스 중 ${passed}개 통과`}
                </p>
                <div className="sr-score">
                  <span>점수</span>
                  <strong>{sub.score ?? '—'}</strong>
                </div>
              </section>
              <section className="cw-card sr-progress">
                <h2 className="cw-card-title">제출 정보</h2>
                <dl className="sr-metrics">
                  {metrics.map(([label, value]) => (
                    <div key={label}>
                      <dt>{label}</dt>
                      <dd>{value}</dd>
                    </div>
                  ))}
                </dl>
              </section>
            </div>
            <VerdictGuidance status={sub.status} />
            <SubmissionDiagnostic submission={sub} />
            {sub.canReadOwnSource && <SubmissionSource id={id} />}
            <section className="cw-card sr-legend">
              <h2 className="cw-card-title">채점 상태 안내</h2>
              <div className="sr-legend-list">
                {Object.entries(STATUS).map(([key, status]) => (
                  <span className={`cw-badge ${status.tone}`} key={key}>
                    {status.label}
                  </span>
                ))}
              </div>
            </section>
            <section className="cw-card sr-tests" style={{ marginTop: 20 }}>
              <h2 className="cw-card-title">테스트케이스 결과</h2>
              <p className="cw-hint">비공개 입력과 정답은 공개하지 않습니다.</p>
              <div className="sr-test-table">
                <div className="sr-test-row sr-test-head">
                  <span>케이스</span>
                  <span>결과</span>
                  <span>실행시간</span>
                </div>
                {cases.map((item, i) => (
                  <div className="sr-test-row" key={i}>
                    <span>#{i + 1}</span>
                    <span className={`cw-badge ${labelStatus(item.status).tone}`}>
                      {labelStatus(item.status).label}
                    </span>
                    <span>{item.execution_time_ms == null ? '—' : item.execution_time_ms + ' ms'}</span>
                  </div>
                ))}
              </div>
            </section>
            {sub.sourceCode != null && (
              <section className="cw-card sr-code" style={{ marginTop: 20 }}>
                <h2 className="cw-card-title">다시보기 허용된 시험 답안</h2>
                <pre className="cw-code-preview">{sub.sourceCode}</pre>
              </section>
            )}
          </>
        )}
      </div>
    </main>
  );
}
export default function SubmissionResult(props) {
  const { id } = useParams(),
    { epoch } = useAuth();
  return <ResultPage key={`${id}:${epoch}`} {...props} />;
}
