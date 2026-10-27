import { useEffect, useState } from "react";
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

  /* 테마를 저장하고, 페이지 바깥 영역(body) 색도 함께 맞춥니다. */
  useEffect(() => {
    try {
      localStorage.setItem(THEME_KEY, dark ? "dark" : "light");
    } catch {
      /* 저장소 접근 불가 시 무시 */
    }
    document.body.style.backgroundColor = dark ? "#121414" : "#dbfffd";
  }, [dark]);

  return <AppRoutes dark={dark} setDark={setDark} />;
}
