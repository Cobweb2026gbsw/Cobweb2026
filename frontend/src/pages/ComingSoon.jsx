import { Link } from "react-router-dom";
import "./common.css";

/* 아직 만들지 않은 페이지(대회, 포럼, 대시보드)와 404 화면에서 함께 씁니다. */
export default function ComingSoon({ dark, title = "준비 중입니다", desc = "곧 만나볼 수 있어요." }) {
  return (
    <main className={`cw-page ${dark ? "dark" : "light"}`}>
      <div className="cw-wrap" style={{ textAlign: "center", paddingTop: 80 }}>
        <p className="cw-eyebrow">COBWEB</p>
        <h1 className="cw-title">{title}</h1>
        <p className="cw-desc" style={{ margin: "14px auto 28px" }}>{desc}</p>
        <Link to="/" className="cw-btn primary">홈으로 돌아가기</Link>
      </div>
    </main>
  );
}
