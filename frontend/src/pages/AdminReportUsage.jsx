import {useState} from 'react';
import {api} from '../lib/api';
import useApiData, {useMutationGuard} from '../lib/useApiData';
import './AdminOperations.css';

const endpoint = '/api/admin/report-usage';
export default function AdminReportUsage({dark}) {
  const {data, error, loading, reload} = useApiData(endpoint);
  const [saving, setSaving] = useState(false);
  const [failure, setFailure] = useState('');
  const guard = useMutationGuard();
  async function toggle() {
    const current = guard();
    setSaving(true); setFailure('');
    try {
      await api(endpoint, 'PATCH', {paused: !data.paused});
      if (current()) reload();
    } catch (e) { if (current() && !e.stale) setFailure(e.message); }
    finally { if (current()) setSaving(false); }
  }
  return <main className={'cw-page ops-page ' + (dark ? 'dark' : 'light')}><div className="cw-wrap">
    <header className="cw-head"><div><h1 className="cw-title">AI 리포트 사용량</h1>
      {data && <p className="cw-desc">{data.day} · 한국 시간 기준</p>}</div>
      <button className="cw-btn" onClick={reload} disabled={loading || saving}>새로고침</button></header>
    {(error || failure) && <p role="alert">{error || failure}</p>}
    {loading && <p role="status">사용량을 불러오는 중…</p>}
    {data && <><dl className="ops-metrics">{[
      ['생성 시도', data.attempts], ['성공', data.succeeded], ['실패', data.failed],
      ['진행 중', data.running], ['남은 일일 한도', data.remaining + ' / ' + data.limit],
    ].map(([label, value]) => <div className="cw-card ops-metric" key={label}><dt>{label}</dt><dd>{value}</dd></div>)}</dl>
    {data.expired > 0 && <p>처리 기한이 지난 시도 {data.expired}건</p>}
    {data.untracked > 0 && <p>상세 집계 도입 전에 접수한 시도 {data.untracked}건</p>}
    <section className="cw-card ops-detail"><h2 className="cw-card-title">신규 리포트 생성</h2>
      <p>{data.paused ? '관리자가 신규 생성을 일시 중지했습니다.' : '신규 생성을 허용하고 있습니다.'} 이미 생성된 리포트는 계속 열람할 수 있습니다.</p>
      <button className={'cw-btn ' + (data.paused ? 'primary' : '')} disabled={saving} onClick={toggle}>{data.paused ? '생성 재개' : '신규 생성 일시 중지'}</button>
    </section></>}
  </div></main>;
}
