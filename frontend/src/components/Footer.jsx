import {Link} from 'react-router-dom';
import './Footer.css';

const LINKS=[['/problems','문제'],['/groups','그룹'],['/forum','커뮤니티'],['/notices','공지']];

/** 실제 서비스에 있는 경로만 안내하며 계정·관리자 권한과 무관하게 한 번 표시합니다. */
export default function Footer({dark}) {
  return <footer className={`cw-footer ${dark?'dark':'light'}`}>
    <div className="cw-footer-inner">
      <div className="cw-footer-brand">
        <Link to="/" className="cw-footer-logo">CobWeb</Link>
        <p>문제를 풀고, 배운 내용을 함께 나누는 공간.</p>
      </div>
      <nav className="cw-footer-nav" aria-label="푸터 메뉴">
        {LINKS.map(([to,label])=><Link to={to} key={to}>{label}</Link>)}
      </nav>
      <small className="cw-footer-year">CobWeb · {new Date().getFullYear()}</small>
    </div>
  </footer>;
}
