import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import AppRoutes from "./AppRoutes";

const THEME_KEY = "cobweb:theme";

export default function App() {
  const [dark, setDark] = useState(() => {
    try {
      return localStorage.getItem(THEME_KEY) !== "light";
    } catch {
      return true;
    }
  });
  const themeTimer = useRef(null);

  // Only explicit theme changes fade; initial loads and ordinary navigation do not.
  const changeTheme = useCallback((nextDark) => {
    const root = document.documentElement;
    const animate = !window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    window.clearTimeout(themeTimer.current);
    root.classList.toggle('cw-theme-transition', animate);
    setDark(nextDark);
    if (animate) {
      themeTimer.current = window.setTimeout(() => {
        root.classList.remove('cw-theme-transition');
      }, 550);
    }
  }, []);

  useEffect(() => () => {
    window.clearTimeout(themeTimer.current);
    document.documentElement.classList.remove('cw-theme-transition');
  }, []);

  /* 테마를 저장하고, 페이지 바깥 영역(body) 색도 함께 맞춥니다. */
  useLayoutEffect(() => {
    try {
      localStorage.setItem(THEME_KEY, dark ? "dark" : "light");
    } catch {
      /* 저장소 접근 불가 시 무시 */
    }
    document.body.style.backgroundColor = dark ? "#121414" : "var(--light-bg)";
  }, [dark]);

  return <AppRoutes dark={dark} setDark={changeTheme} />;
}
