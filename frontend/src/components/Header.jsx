import { useState } from 'react';
import { Link, NavLink, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import Icon from './Icon';
import { getSessionEpoch } from '../lib/api';
import './Header.css';

const NAV=[['/problems','문제'],['/groups','그룹'],['/contests','코테'],['/forum','커뮤니티'],['/notices','공지']];
export default function Header({dark,setDark}){
  const {user,logout,permissions,sessionNotice}=useAuth(),navigate=useNavigate();
  const [q,setQ]=useState(''),[menu,setMenu]=useState(false),[busy,setBusy]=useState(false),[error,setError]=useState('');
  const exit=async()=>{setBusy(true);setError('');const pending=logout(),session=getSessionEpoch();try{await pending;}catch(failure){if(!failure.stale&&session===getSessionEpoch())setError(failure.message);}finally{if(session===getSessionEpoch()){setBusy(false);setMenu(false);navigate('/');}}};
  return <>{sessionNotice&&<div className="cw-session-notice" role="alert">{sessionNotice} <Link to="/login">다시 로그인</Link></div>}<header className={`cw-header ${dark?'dark':'light'}`}>
    {error&&<span role="alert">{error}</span>}
    <div className="cw-header-left"><Link to="/" className="cw-header-logo">CobWeb</Link>
      <button className="cw-header-btn cw-menu-toggle" type="button" aria-expanded={menu} aria-controls="main-nav" onClick={()=>setMenu(!menu)}>메뉴</button>
      <nav id="main-nav" aria-label="주 메뉴" className={menu?'open':''}>
        {NAV.map(([to,label])=><NavLink key={to} to={to} onClick={()=>setMenu(false)} className={({isActive})=>isActive?'active':''}>{label}</NavLink>)}
        {user&&<NavLink to="/dashboard" onClick={()=>setMenu(false)}>내 활동</NavLink>}
        {user&&(permissions.canCreate||permissions.canManage)&&<NavLink to="/problems/new" onClick={()=>setMenu(false)}>문제 제작</NavLink>}
        {permissions.canManage&&<><NavLink to="/admin/review" onClick={()=>setMenu(false)}>문제 관리</NavLink><NavLink to="/admin/community" onClick={()=>setMenu(false)}>글 관리</NavLink></>}
      </nav>
    </div>
    <form className="cw-header-search" role="search" onSubmit={event=>{event.preventDefault();navigate('/problems?q='+encodeURIComponent(q.trim()));}}>
      <input value={q} maxLength={200} onChange={event=>setQ(event.target.value)} placeholder="문제 검색" aria-label="문제 검색"/><button aria-label="검색"><Icon name="search"/></button></form>
    <div className="cw-header-right"><button className="cw-header-btn" onClick={()=>setDark(!dark)} aria-label="테마 전환">{dark?'Light':'Dark'}</button>
      {user?<><Link className="cw-header-btn" to="/me">{user.nickname||user.username}</Link><button className="cw-header-btn primary" onClick={exit} disabled={busy}>로그아웃</button></>:
      <><Link className="cw-header-btn" to="/login">로그인</Link><Link className="cw-header-btn primary" to="/signup">가입</Link></>}
    </div>
  </header></>;
}
