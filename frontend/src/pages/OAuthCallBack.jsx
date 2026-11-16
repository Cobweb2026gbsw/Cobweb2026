import { useEffect } from "react";
import { Link, useNavigate } from "react-router-dom";
import ThemeToggle from "../components/ThemeToggle";
import { useAuth } from "../context/AuthContext";
import "./OAuthCallBack.css";

export default function OAuthCallBack({ dark, setDark }) {
  const navigate = useNavigate();
  const { user, loading } = useAuth();
  useEffect(() => { if (!loading && user) navigate("/dashboard", { replace: true }); }, [user, loading, navigate]);
  return <div className={"OAuthPage " + (dark ? "dark" : "light")}><ThemeToggle dark={dark} setDark={setDark} /><h1>CobWeb</h1>
    <p role="status">{loading ? "로그인 정보를 확인하고 있습니다…" : user ? "로그인됐습니다." : "인증을 완료하지 못했습니다. 다시 로그인해 주세요."}</p>
    {!loading && !user && <Link to="/login">로그인으로 돌아가기</Link>}
  </div>;
}
