import {useState} from 'react';
import {Link} from 'react-router-dom';
import useApiData from '../lib/useApiData';
import {formatServerDate} from '../lib/date';
import './AdminOperations.css';

const statusLabels = {QUEUED: '채점 대기', RUNNING: '채점 중', SYSTEM_ERROR: '시스템 오류'};

function waitingTime(seconds) {
  if (seconds < 60) return seconds + '초';
  if (seconds < 3600) return Math.floor(seconds / 60) + '분';
  return Math.floor(seconds / 3600) + '시간 ' + Math.floor(seconds % 3600 / 60) + '분';
}

export default function AdminOperations({dark}) {
  const [status, setStatus] = useState('QUEUED');
  const [page, setPage] = useState(0);
  const [language, setLanguage] = useState('');
  const summary = useApiData('/api/admin/operations');
  const catalog = useApiData('/api/languages');
  const results = useApiData('/api/admin/operations/submissions?' + new URLSearchParams({status, page, language}));
  const counts = summary.data;
  function selectStatus(value) { setStatus(value); setPage(0); }
  function refresh() { summary.reload(); results.reload(); }
  return <main className={'cw-page ops-page ' + (dark ? 'dark' : 'light')}><div className="cw-wrap">
    <header className="cw-head"><h1 className="cw-title">채점 운영 현황</h1>
      <button className="cw-btn" onClick={refresh} disabled={summary.loading || results.loading}>새로고침</button></header>
    {summary.error && <p role="alert">{summary.error}</p>}
    <div className="ops-metrics">
      {[
        ['채점 대기', counts?.queued, 'QUEUED'],
        ['최장 대기 시간', counts && waitingTime(counts.oldestSeconds), 'QUEUED'],
        ['채점 중', counts?.running, 'RUNNING'],
        ['최근 24시간 시스템 오류', counts?.systemErrors, 'SYSTEM_ERROR'],
      ].map(([label, value, filter]) => <button className="cw-card ops-metric" key={label} onClick={() => { setLanguage(''); selectStatus(filter); }}>
        <span>{label}</span><strong>{value ?? '—'}</strong></button>)}
      <Link className="cw-card ops-metric" to="/admin/review?status=PENDING_REVIEW"><span>검수 대기</span><strong>{counts?.pendingReview ?? '—'}</strong></Link>
    </div>
    <section className="cw-card ops-detail"><div className="ops-tools">
      <h2 className="cw-card-title">제출 목록</h2>
      <label>채점 상태<select className="cw-select" value={status} onChange={e => selectStatus(e.target.value)}>
        {Object.entries(statusLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
      <label>언어<select className="cw-select" value={language} onChange={e => {setLanguage(e.target.value); setPage(0);}}>
        <option value="">모든 언어</option>{(catalog.data || []).map(item => <option key={item.id} value={item.id}>{item.label}</option>)}</select></label>
    </div>
    {catalog.error && <p role="alert">{catalog.error}</p>}
    {results.error && <p role="alert">{results.error}</p>}
    {results.loading && <p role="status">제출 목록을 불러오는 중…</p>}
    {results.data && (results.data.items.length ? <div className="ops-table"><table>
      <thead><tr><th>제출</th><th>문제</th><th>언어</th><th>접수 시각</th><th>{status === 'SYSTEM_ERROR' ? '판정 시각' : '최근 워커 신호'}</th></tr></thead>
      <tbody>{results.data.items.map(row => <tr key={row.id}>
        <td>{row.contest ? '#' + row.id : <Link to={'/submissions/' + row.id}>#{row.id}</Link>}</td>
        <td>{row.contest ? '코딩테스트' : <Link to={'/problems/' + row.problemNumber}>{row.problemNumber}</Link>}</td>
        <td>{row.language}</td><td>{formatServerDate(row.submittedAt)}</td>
        <td>{formatServerDate(status === 'SYSTEM_ERROR' ? row.judgedAt : row.heartbeatAt)}</td></tr>)}</tbody>
    </table></div> : <p className="cw-desc">이 조건에 해당하는 제출이 없습니다.</p>)}
    <nav className="cw-pager" aria-label="제출 목록 페이지"><button className="cw-btn sm" disabled={!page || results.loading} onClick={() => setPage(page - 1)}>이전</button>
      <span>{page + 1}</span><button className="cw-btn sm" disabled={!results.data?.hasNext || results.loading} onClick={() => setPage(page + 1)}>다음</button></nav>
    </section>
  </div></main>;
}
