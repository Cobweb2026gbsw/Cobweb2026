import useApiData from '../lib/useApiData';

export default function SubmissionSource({id}) {
  const {data,loading,error,reload}=useApiData(`/api/submissions/${id}/source`);
  return <section className="cw-card submission-source" aria-label={`제출 #${id} 코드`} aria-busy={loading}>
    <h2 className="cw-card-title">제출 코드</h2>
    {loading ? <p role="status">코드를 불러오는 중…</p>
      : error ? <><p role="alert">{error}</p><button type="button" className="cw-btn sm" onClick={reload}>코드 다시 불러오기</button></>
      : <pre className="cw-code-preview" tabIndex={0}><code>{data}</code></pre>}
  </section>;
}
