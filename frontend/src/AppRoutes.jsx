import {lazy, Suspense} from 'react';
import { Navigate, Outlet, Route, Routes, useLocation } from 'react-router-dom';
import { useAuth } from './context/AuthContext';
import Header from './components/Header';
import Footer from './components/Footer';
import RenderErrorBoundary from './components/RenderErrorBoundary';
import Main from './pages/Main';
import Login from './pages/Login';
import Signup from './pages/Signup';
import PasswordReset from './pages/PasswordReset';
import OAuthCallBack from './pages/OAuthCallBack';
import ProblemList from './pages/ProblemList';
import ProblemReview from './pages/ProblemReview';
import MySubmissions from './pages/MySubmissions';
import SubmissionResult from './pages/SubmissionResult';
import Ranking from './pages/Ranking';
import Profile from './pages/Profile';
import ProfileEdit from './pages/ProfileEdit';
import Dashboard from './pages/Dashboard';
import LearningReport from './pages/LearningReport';
import Steps from './pages/Steps';
import ComingSoon from './pages/ComingSoon';
import ForumList from './pages/ForumList';
import ForumPost from './pages/ForumPost';
import ForumWrite from './pages/ForumWrite';
import Groups from './pages/Groups';
import GroupDetail from './pages/GroupDetail';
import GroupContestCreate from './pages/GroupContestCreate';
import Contests from './pages/Contests';
import ContestDetail from './pages/ContestDetail';
import './pages/common.css';

const ProblemDetail=lazy(()=>import('./pages/ProblemDetail'));
const ProblemEditor=lazy(()=>import('./pages/ProblemEditor'));
const GroupContest=lazy(()=>import('./pages/GroupContest'));

// 메인을 제외한 화면은 인증 페이지까지 공통 푸터를 한 번만 렌더링합니다.
// 푸터를 fixed로 띄우지 않으므로 긴 문제·편집기·모달의 본문을 덮지 않습니다.
function AppShell({dark}) {
  const {epoch}=useAuth(),location=useLocation();
  const isHome=location.pathname==='/';
  // 목록 내 필터·탭 이동은 포커스와 이미 읽은 자료를 유지합니다.
  const keepListMounted = ['/problems', '/problems/mine', '/contests'].includes(location.pathname);
  const routeKey = keepListMounted ? location.pathname : location.key + ':' + location.pathname + location.search;
  return <div className={`cw-app-shell ${dark?'dark':'light'}${isHome?' cw-home-shell':''}`}>
    <div className="cw-app-content"><RenderErrorBoundary key={`${epoch}:${routeKey}`} dark={dark}><Outlet/></RenderErrorBoundary></div>
    {!isHome&&<Footer dark={dark}/>}
  </div>;
}
function WithHeader({dark,setDark}) {
  const {epoch}=useAuth(), location=useLocation();
  // 계정 또는 경로가 바뀌면 개인 폼·비공개 자료·타이머를 함께 폐기합니다.
  return <><Header dark={dark} setDark={setDark}/><div className="cw-route-content" key={epoch+':'+location.pathname}><Suspense fallback={<p className="cw-loading" role="status">화면을 불러오는 중…</p>}><Outlet/></Suspense></div></>;
}
function RequireAuth({manage=false}) {
  const {user,loading,permissions}=useAuth(), location=useLocation();
  if(loading)return <p className="cw-loading" role="status">로그인 상태 확인 중…</p>;
  if(!user)return <Navigate to="/login" replace state={{from:location.pathname+location.search}}/>;
  if(manage&&!permissions.canManage)return <p className="cw-loading" role="alert">운영자 권한이 필요합니다.</p>;
  return <Outlet/>;
}
export default function AppRoutes({dark,setDark}){
  const theme={dark,setDark};
  return <Routes>
    <Route element={<AppShell dark={dark}/>}>
    <Route path="/login" element={<Login {...theme}/>}/><Route path="/signup" element={<Signup {...theme}/>}/>
    <Route path="/password-reset" element={<PasswordReset {...theme}/>}/><Route path="/oauth/callback" element={<OAuthCallBack {...theme}/>}/>
    <Route element={<WithHeader {...theme}/>}>
      <Route path="/" element={<Main dark={dark}/>}/>
      <Route path="/app.html" element={<Navigate to="/" replace/>}/>
      <Route path="/problems" element={<ProblemList dark={dark}/>}/>
      <Route path="/problems/:id" element={<ProblemDetail dark={dark}/>}/>
      <Route path="/steps" element={<Steps dark={dark}/>}/>
      <Route path="/ranking" element={<Ranking dark={dark}/>}/>
      <Route path="/users" element={<Profile dark={dark}/>}/>
      <Route path="/users/:nickname" element={<Profile dark={dark}/>}/>
      <Route path="/forum" element={<ForumList dark={dark}/>}/>
      <Route path="/community" element={<ForumList dark={dark}/>}/>
      <Route path="/notices" element={<ForumList dark={dark} notices/>}/>
      <Route path="/forum/:id" element={<ForumPost dark={dark}/>}/>
      <Route path="/groups" element={<Groups dark={dark}/>}/>
      <Route path="/create" element={<Navigate to="/problems/new" replace/>}/>
      <Route path="/admin" element={<Navigate to="/admin/review" replace/>}/>
      <Route element={<RequireAuth/>}>
        <Route path="/problems/mine" element={<ProblemList dark={dark} mine/>}/>
        <Route path="/problems/new" element={<ProblemEditor dark={dark} mode="new"/>}/>
        <Route path="/problems/:id/edit" element={<ProblemEditor dark={dark} mode="edit"/>}/>
        <Route path="/submissions" element={<MySubmissions dark={dark}/>}/>
        <Route path="/submissions/:id" element={<SubmissionResult dark={dark}/>}/>
        <Route path="/forum/new" element={<ForumWrite dark={dark}/>}/>
        <Route path="/forum/:id/edit" element={<ForumWrite dark={dark}/>}/>
        <Route path="/dashboard" element={<Dashboard dark={dark}/>}/>
        <Route path="/dashboard/learning-report" element={<LearningReport dark={dark}/>}/>
        <Route path="/me" element={<Profile dark={dark} isMe/>}/>
        <Route path="/me/edit" element={<ProfileEdit dark={dark}/>}/>
        <Route path="/groups/:gid" element={<GroupDetail dark={dark}/>}/>
        <Route path="/groups/:gid/mkct" element={<GroupContestCreate dark={dark}/>}/>
        <Route path="/groups/:gid/problems/:id" element={<ProblemDetail dark={dark}/>}/>
        <Route path="/groups/:gid/contests/:cid" element={<GroupContest dark={dark}/>}/>
        <Route path="/contests" element={<Contests dark={dark}/>}/>
        <Route path="/contests/:id" element={<ContestDetail dark={dark}/>}/>
      </Route>
      <Route element={<RequireAuth manage/>}>
        <Route path="/admin/review" element={<ProblemReview dark={dark} isAdmin/>}/>
        <Route path="/admin/community" element={<ForumList dark={dark} admin/>}/>
        <Route path="/admin/community/:id" element={<ForumPost dark={dark} admin/>}/>
      </Route>
      <Route path="*" element={<ComingSoon dark={dark} title="페이지를 찾을 수 없어요" desc="주소를 다시 확인해 주세요."/>}/>
    </Route>
    </Route>
  </Routes>;
}
