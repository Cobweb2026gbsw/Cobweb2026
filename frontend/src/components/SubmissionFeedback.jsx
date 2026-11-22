import { Link, useLocation } from 'react-router-dom';
import useSubmissionVerdict, { isJudging } from '../lib/useSubmissionVerdict';
import { labelStatus } from '../lib/problemModel';
import SubmissionDiagnostic from './SubmissionDiagnostic';
import AcceptedFireworks from './AcceptedFireworks';
import VerdictGuidance from './VerdictGuidance';
import './SubmissionFeedback.css';

export default function SubmissionFeedback({ id, onPendingChange, recovered = false, nextAction, dark }) {
  const { data, error, retry } = useSubmissionVerdict(id, onPendingChange);
  const location = useLocation();
  if (!id) return null;
  const verdict = labelStatus(data?.status);
  const cases = data?.cases ?? [];
  const passed = cases.filter((item) => item.status === 'ACCEPTED').length;
  const problemOrigin = { pathname: location.pathname, search: location.search, state: location.state };
  const reusedId = data?.reusedFromSubmissionId;
  return (
    <div className="pd-submission-feedback" role="region" aria-label="채점 결과">
      {data?.status === 'ACCEPTED' && <AcceptedFireworks key={id} dark={dark} />}
      <div className="pd-feedback-head">
        <h3>제출 #{id}</h3>
        {data && <span className={`cw-badge ${verdict.tone}`}>{data.status}</span>}
      </div>
      {recovered ? (
        <p className="cw-hint">이미 제출한 코드입니다. 기존 제출 기록을 표시합니다.</p>
      ) : (
        Number.isSafeInteger(reusedId) &&
        reusedId > 0 &&
        reusedId < id && (
          <p className="cw-hint">이미 제출한 코드입니다. 기존 판정을 불러왔습니다. (이전 제출 #{reusedId})</p>
        )
      )}
      {error ? (
        <>
          <p className="cw-status-message error" role="alert">
            {error}
          </p>
          <button type="button" className="cw-btn" onClick={retry}>
            채점 결과 다시 불러오기
          </button>
        </>
      ) : (
        <p role="status" className={`pd-feedback-verdict ${verdict.tone}`}>
          {!data
            ? '채점 결과를 불러오는 중…'
            : data.status === 'ACCEPTED'
              ? '정답입니다. 해결 완료로 기록되었습니다.'
              : verdict.label}
        </p>
      )}
      {data && (
        <>
          {isJudging(data.status) ? (
            <p className="cw-hint">이 화면에서 자동 갱신합니다. 작성한 코드는 그대로 유지됩니다.</p>
          ) : (
            <p className="cw-hint">
              {cases.length}개 케이스 중 {passed}개 통과 · {data.score ?? '—'}점
              {data.execution_time_ms != null && ` · ${data.execution_time_ms} ms`}
            </p>
          )}
          <VerdictGuidance status={data.status} />
          <SubmissionDiagnostic submission={data} />
          <div className="cw-actions">
            {data.status === 'ACCEPTED' && nextAction}
            <Link className="cw-btn" to={'/submissions/' + id} state={{ problemOrigin }}>
              상세 판정 보기
            </Link>
          </div>
          {cases.length > 0 && (
            <details className="pd-feedback-cases">
              <summary>테스트케이스 결과 {cases.length}개</summary>
              <ol>
                {cases.map((item, index) => (
                  <li key={index}>
                    <span>#{index + 1}</span>
                    <span className={`cw-badge ${labelStatus(item.status).tone}`}>
                      {labelStatus(item.status).label}
                    </span>
                    <span>{item.execution_time_ms == null ? '—' : `${item.execution_time_ms} ms`}</span>
                  </li>
                ))}
              </ol>
              <p className="cw-hint">비공개 입력과 정답은 공개하지 않습니다.</p>
            </details>
          )}
        </>
      )}
    </div>
  );
}
