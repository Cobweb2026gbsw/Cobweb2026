import "./ThemeToggle.css";

/* 헤더가 없는 인증 페이지(로그인·가입 등) 오른쪽 위에 떠 있는 테마 전환 버튼 */
export default function ThemeToggle({ dark, setDark }) {
  return (
    <button
      type="button"
      className={`cw-theme-toggle ${dark ? "dark" : "light"}`}
      onClick={() => setDark(!dark)}
      aria-label={dark ? "라이트 모드로 전환" : "다크 모드로 전환"}
    >
      {dark ? "Light" : "Dark"}
    </button>
  );
}
