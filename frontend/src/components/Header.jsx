import { useEffect, useRef, useState } from 'react';
import { Link, NavLink, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import Icon from './Icon';
import { getSessionEpoch } from '../lib/api';
import {problemSearchTarget} from '../lib/problemNavigation';
import './Header.css';

const NAV=[['/problems','문제'],['/groups','그룹'],['/contests','코딩테스트'],['/forum','커뮤니티'],['/notices','공지']];
export default function Header({dark,setDark}){
  const {user,logout,permissions,sessionNotice}=useAuth(),navigate=useNavigate();
  const [q,setQ]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState('');
  const [searchOpen,setSearchOpen]=useState(false),searchInput=useRef(null),searchButton=useRef(null),location=useLocation();
  useEffect(()=>{if(searchOpen)searchInput.current?.focus();},[searchOpen]);
  useEffect(()=>setSearchOpen(false),[location.pathname,location.search]);
  const problemSection=/^\/(problems|steps|ranking|submissions)(\/|$)/.test(location.pathname);
  const exit=async()=>{setBusy(true);setError('');const pending=logout(),session=getSessionEpoch();try{await pending;}catch(failure){if(!failure.stale&&session===getSessionEpoch())setError(failure.message);}finally{if(session===getSessionEpoch()){setBusy(false);navigate('/');}}};
  return <>{sessionNotice&&<div className="cw-session-notice" role="alert">{sessionNotice} <Link to="/login">다시 로그인</Link></div>}<header className={`cw-header ${dark?'dark':'light'}`}>
    {error&&<span className="cw-header-error" role="alert">{error}</span>}
    <div className="cw-header-left"><Link to="/" className="cw-header-logo">CobWeb</Link>
      <nav id="main-nav" aria-label="주 메뉴">
        <button type="button" ref={searchButton} className="cw-mobile-search" aria-label="문제 검색 열기" aria-expanded={searchOpen} aria-controls="header-search" onClick={()=>setSearchOpen(value=>!value)}><Icon name="search"/>검색</button>
        {NAV.map(([to,label])=><NavLink key={to} to={to} className={({isActive})=>isActive?'active':''}>{label}</NavLink>)}
        {user&&<NavLink to="/dashboard">내 활동</NavLink>}
        {user&&(permissions.canCreate||permissions.canManage)&&<NavLink to="/problems/new">문제 제작</NavLink>}
        {permissions.canManage&&<><NavLink to="/admin/review">문제 관리</NavLink><NavLink to="/admin/community">글 관리</NavLink></>}
      </nav>
    </div>
    <form id="header-search" className={`cw-header-search${searchOpen?' is-open':''}`} role="search" onSubmit={event=>{event.preventDefault();navigate(problemSearchTarget(q));setSearchOpen(false);}} onKeyDown={event=>{if(event.key==='Escape'&&searchOpen){setSearchOpen(false);searchButton.current?.focus();}}}>
      <input ref={searchInput} value={q} maxLength={200} onChange={event=>setQ(event.target.value)} placeholder="제목, 주제 또는 문제 번호" aria-label="문제 검색"/><button aria-label="검색"><Icon name="search"/></button></form>
    <div className="cw-header-right"><button className="cw-header-btn" onClick={()=>setDark(!dark)} aria-label="테마 전환">{dark?'Light':'Dark'}</button>
      {user?<><Link className="cw-header-btn" to="/me">{user.nickname||user.username}</Link><button className="cw-header-btn primary" onClick={exit} disabled={busy}>로그아웃</button></>:
      <><Link className="cw-header-btn" to="/login">로그인</Link><Link className="cw-header-btn primary" to="/signup">가입</Link></>}
    </div>
  </header>{problemSection&&<nav className={`cw-problem-nav ${dark?'dark':'light'}`} aria-label="문제 메뉴">{[['/problems','전체 문제'],['/steps','난이도별 문제'],['/submissions','내 제출'],['/ranking','랭킹']].map(([to,label])=><NavLink key={to} to={to} end={to==='/problems'}>{label}</NavLink>)}</nav>}</>;
}
