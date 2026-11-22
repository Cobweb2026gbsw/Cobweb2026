import { Link } from 'react-router-dom';

export const TERMS_VERSION = '2026-11-20';

export default function TermsConsent({ checked, onChange, disabled = false }) {
  return <div className="signup-agreement">
    <label><input type="checkbox" required checked={checked} disabled={disabled}
      onChange={event => onChange(event.target.checked)} />이용약관에 동의합니다 (필수)</label>
    <Link to="/terms" target="_blank" rel="noopener noreferrer" aria-label="이용약관 전문 보기 (새 창)">전문 보기</Link>
  </div>;
}
