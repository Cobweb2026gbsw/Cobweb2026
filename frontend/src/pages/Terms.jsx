import Markdown from '../components/Markdown';
import { TERMS_VERSION } from '../components/TermsConsent';
import useApiData from '../lib/useApiData';
import terms from '../content/service-terms.md?raw';
import './Terms.css';

const koreanDate = new Intl.DateTimeFormat('ko-KR', {dateStyle: 'long', timeZone: 'UTC'});

export default function Terms({ dark }) {
  const {data, loading, error, reload} = useApiData('/api/terms/current');
  return <main className={'cw-page terms-page ' + (dark ? 'dark' : 'light')}>
    <div className="cw-wrap">
      <header className="cw-head"><div>
        <h1 className="cw-title">이용약관</h1>
        <p className="cw-desc">약관 버전 {TERMS_VERSION}</p>
      </div></header>
      <article className="cw-card terms-document" aria-label="이용약관 전문">
        {loading && <p role="status">시행일을 확인하고 있습니다.</p>}
        {error && <div role="alert" className="terms-status">시행일과 운영자 정보를 불러오지 못했습니다.
          <button className="cw-btn" onClick={reload}>다시 시도</button></div>}
        {data && data.version !== TERMS_VERSION && <p role="alert">이용약관이 변경되었습니다. 새로고침하여 최신 약관을 확인해 주세요.</p>}
        {data?.version === TERMS_VERSION && <dl className="terms-details">
          <div><dt>시행일</dt><dd>{data.effectiveDate
            ? <time dateTime={data.effectiveDate}>{koreanDate.format(new Date(data.effectiveDate + 'T00:00:00Z'))}</time>
            : '첫 운영 배포 시 확정'}</dd></div>
          <div><dt>운영자</dt><dd>{data.operator}</dd></div>
          <div><dt>문의</dt><dd><a href={'mailto:' + data.contactEmail}>{data.contactEmail}</a></dd></div>
        </dl>}
        <Markdown>{terms}</Markdown>
      </article>
    </div>
  </main>;
}
