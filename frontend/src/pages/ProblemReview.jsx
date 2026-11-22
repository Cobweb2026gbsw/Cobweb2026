import { useEffect, useState } from 'react';
import { Link, useLocation, useSearchParams } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { api } from '../lib/api';
import useApiData, { useMutationGuard } from '../lib/useApiData';
import { rankLabel } from '../lib/problemModel';
import { validProblemNumber } from '../lib/problemNavigation';
import ProblemStatement from '../components/ProblemStatement';
import './common.css';
import './ProblemReview.css';

const reviewStatuses = ['', 'DRAFT', 'PENDING_REVIEW', 'PUBLISHED', 'REJECTED', 'ARCHIVED'];
const visibilities = ['', 'PUBLIC', 'PRIVATE', 'GROUP'];

function ReviewPage({ dark }) {
  const { permissions, epoch } = useAuth();
  const [params, setParams] = useSearchParams();
  const location = useLocation();
  const status = reviewStatuses.includes(params.get('status')) ? params.get('status') : 'PENDING_REVIEW';
  const visibility = visibilities.includes(params.get('visibility')) ? params.get('visibility') : '';
  const page = Math.min(10000, Math.max(0, Number.parseInt(params.get('page'), 10) || 0));
  const applied = params.get('search') || '';
  const selected = validProblemNumber(params.get('problem')) ? Number(params.get('problem')) : null;
  const [search, setSearch] = useState(applied);
  const [error, setError] = useState(''),
    [notice, setNotice] = useState('');
  const [cause, setCause] = useState(''),
    [busy, setBusy] = useState(false);
  const listQuery = new URLSearchParams({ page, search: applied, status, visibility });
  const list = useApiData(permissions.canManage ? '/api/admin/problems?' + listQuery : null);
  const record = useApiData(permissions.canManage && selected ? '/api/admin/problems/' + selected : null);
  const detail = record.data;
  const guard = useMutationGuard(epoch, selected);

  useEffect(() => setSearch(applied), [applied]);
  useEffect(() => {
    setError('');
    setNotice('');
    setCause('');
    setBusy(false);
  }, [selected]);
  function show(changes) {
    const filters = { page: 0, search: applied, status, visibility, ...changes };
    const next = new URLSearchParams();
    if (filters.page) next.set('page', filters.page);
    if (filters.search) next.set('search', filters.search);
    if (filters.status !== 'PENDING_REVIEW') next.set('status', filters.status);
    if (filters.visibility) next.set('visibility', filters.visibility);
    if (filters.problem) next.set('problem', filters.problem);
    if (next.toString() !== params.toString()) setParams(next);
  }
  async function action(kind, value) {
    const current = guard();
    setBusy(true);
    setError('');
    setNotice('');
    try {
      if (kind === 'visibility')
        await api('/api/admin/problems/' + selected + '/visibility', 'PATCH', { visibility: value, cause });
      else await api('/api/problems/' + selected + '/review', 'POST', { status: value, cause });
      if (current()) {
        setCause('');
        setNotice('처리가 서버에 저장됐습니다.');
        list.reload();
        record.reload();
      }
    } catch (failure) {
      if (current() && !failure.stale) setError(failure.message);
    } finally {
      if (current()) setBusy(false);
    }
  }
  if (!permissions.canManage)
    return (
      <main className={`cw-page rv-page ${dark ? 'dark' : 'light'}`}>
        <div className="cw-card rv-denied">
          <h1>운영자만 접근할 수 있습니다</h1>
          <p>실제 서버의 문제 관리 권한이 필요합니다.</p>
        </div>
      </main>
    );
  return (
    <main className={`cw-page rv-page ${dark ? 'dark' : 'light'}`}>
      <div className="cw-wrap">
        <header className="cw-head">
          <div>
            <p className="cw-eyebrow">ADMIN / LIVE REVIEW</p>
            <h1 className="cw-title">문제 검수·공개 관리</h1>
            <p className="cw-desc">
              검수 승인·반려·보관과 공개 범위를 서버에서 관리합니다. 조회나 편집기 링크 클릭만으로 공개 상태를
              변경하지 않습니다.
            </p>
          </div>
          <Link className="cw-btn" to="/admin/community">
            커뮤니티 관리
          </Link>
        </header>
        <form
          className="cw-card pl-filter"
          onSubmit={(e) => {
            e.preventDefault();
            show({ search });
          }}
        >
          <div className="cw-form-grid">
            <label>
              제목 검색
              <input className="cw-input" value={search} onChange={(e) => setSearch(e.target.value)} />
            </label>
            <label>
              상태
              <select className="cw-select" value={status} onChange={(e) => show({ status: e.target.value })}>
                <option value="">전체</option>
                {reviewStatuses.slice(1).map((s) => (
                  <option key={s}>{s}</option>
                ))}
              </select>
            </label>
            <label>
              공개 범위
              <select
                className="cw-select"
                value={visibility}
                onChange={(e) => show({ visibility: e.target.value })}
              >
                <option value="">전체</option>
                {visibilities.slice(1).map((s) => (
                  <option key={s}>{s}</option>
                ))}
              </select>
            </label>
            <button className="cw-btn primary">검색</button>
          </div>
        </form>
        {(error || list.error) && (
          <p className="cw-status-message error" role="alert">
            {error || list.error}
          </p>
        )}
        {notice && (
          <p className="rv-notice" role="status">
            {notice}
          </p>
        )}
        <div className="rv-grid">
          <aside className="cw-card rv-queue">
            <h2 className="cw-card-title">문제 목록</h2>
            {list.loading && <p>불러오는 중…</p>}
            <ul>
              {(list.data || []).map((p) => (
                <li key={p.problem_number}>
                  <button
                    type="button"
                    className={`rv-item ${selected === p.problem_number ? 'on' : ''}`}
                    onClick={() => show({ page, problem: p.problem_number })}
                  >
                    <span className="rv-code">
                      #{p.problem_number} · {p.status}
                    </span>
                    <strong>{p.problem_title}</strong>
                    <small>
                      {p.author_username} · {p.visibility}
                    </small>
                  </button>
                </li>
              ))}
            </ul>
            {!list.loading && !list.data?.length && <p className="rv-empty">해당 문제가 없습니다.</p>}
            <nav className="cw-pager">
              <button
                className="cw-btn sm"
                disabled={!page || list.loading}
                onClick={() => show({ page: page - 1 })}
              >
                이전
              </button>
              <span>{page + 1}</span>
              <button
                className="cw-btn sm"
                disabled={page >= 10000 || list.loading || (list.data || []).length < 30}
                onClick={() => show({ page: page + 1 })}
              >
                다음
              </button>
            </nav>
          </aside>
          <section className="cw-card rv-detail">
            {record.error ? (
              <p className="cw-status-message error" role="alert">
                {record.error}{' '}
                <button className="cw-btn sm" onClick={record.reload}>
                  다시 시도
                </button>
              </p>
            ) : !detail ? (
              <p className="rv-empty big">
                {selected ? '편집 자료를 불러오는 중…' : '왼쪽에서 문제를 선택하세요.'}
              </p>
            ) : (
              <>
                <div className="rv-detail-head">
                  <div>
                    <p className="cw-eyebrow">
                      #{detail.number} · {rankLabel(detail)}
                    </p>
                    <h2>{detail.title}</h2>
                    <p className="rv-meta">
                      {detail.status} · {detail.currentVisibility}
                      {detail.groupId && ` · 그룹 #${detail.groupId}`}
                    </p>
                  </div>
                  {detail.canEdit && !detail.usedInContest && (
                    <Link className="cw-btn" to={`/problems/${detail.number}/edit`}>
                      편집기로 불러오기
                    </Link>
                  )}
                </div>
                {detail.usedInContest && (
                  <p className="cw-status-message">
                    시험에 사용된 원본입니다. 내용 편집은 금지되며 공개·보관 설정은 별도로 처리합니다.
                  </p>
                )}
                <ProblemStatement problem={detail} />
                <div className="rv-body">
                  <h3>허용 언어</h3>
                  <p>{detail.languages.join(', ')}</p>
                  <h3>비공개 테스트</h3>
                  {detail.tests.map((t, index) => (
                    <details className="cw-status-message" key={index}>
                      <summary>테스트 {index + 1}</summary>
                      <h4>입력</h4>
                      <pre className="cw-code-preview">{t.input}</pre>
                      <h4>기대 출력</h4>
                      <pre className="cw-code-preview">{t.expected}</pre>
                    </details>
                  ))}
                  <h3>참고 정답 코드</h3>
                  <Link
                    className="cw-btn"
                    to={`/admin/judging?kind=REFERENCE&problem=${detail.number}`}
                    state={{ reviewReturn: location.pathname + location.search }}
                  >
                    정답 코드 전체 테스트
                  </Link>
                  {detail.referenceSolutions.map((r) => (
                    <details className="cw-status-message" key={r.language}>
                      <summary>
                        {r.language} · {r.fileName}
                      </summary>
                      <pre className="cw-code-preview">{r.sourceCode}</pre>
                    </details>
                  ))}
                </div>
                <div className="rv-actions">
                  <label className="cw-label" htmlFor="review-cause">
                    처리 사유
                  </label>
                  <textarea
                    id="review-cause"
                    className="cw-textarea"
                    value={cause}
                    maxLength={2000}
                    onChange={(e) => setCause(e.target.value)}
                  />
                  <div className="cw-actions">
                    {detail.status === 'PENDING_REVIEW' && (
                      <>
                        <button
                          className="cw-btn primary"
                          disabled={busy}
                          onClick={() => action('review', 'PUBLISHED')}
                        >
                          승인
                        </button>
                        <button
                          className="cw-btn danger"
                          disabled={busy || !cause.trim()}
                          onClick={() => action('review', 'REJECTED')}
                        >
                          반려
                        </button>
                      </>
                    )}
                    <button
                      className="cw-btn"
                      disabled={busy || !cause.trim()}
                      onClick={() => action('review', 'ARCHIVED')}
                    >
                      보관
                    </button>
                    <button className="cw-btn" disabled={busy} onClick={() => action('visibility', 'PRIVATE')}>
                      {detail.groupId ? '그룹 문제 보관' : '비공개로 전환'}
                    </button>
                    <button
                      className="cw-btn primary"
                      disabled={busy}
                      onClick={() => action('visibility', 'PUBLIC')}
                    >
                      {detail.groupId ? '그룹 내 공개' : '공개로 전환'}
                    </button>
                  </div>
                </div>
              </>
            )}
          </section>
        </div>
      </div>
    </main>
  );
}
export default function ProblemReview(props) {
  const { epoch } = useAuth();
  return <ReviewPage key={epoch} {...props} />;
}
