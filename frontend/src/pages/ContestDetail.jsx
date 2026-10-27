import {useEffect, useState} from 'react';
import {Link, useNavigate, useParams} from 'react-router-dom';
import {useAuth} from '../context/AuthContext';
import {api} from '../lib/api';
import './common.css';
import './Contests.css';

/** 예전 /contests/:id 주소도 허위 대회가 아닌 실제 소속 그룹 시험으로 연결합니다. */
export default function ContestDetail({dark}) {
  const {id} = useParams(), {user, epoch} = useAuth(), navigate = useNavigate(), [message, setMessage] = useState('시험을 찾는 중입니다.');
  useEffect(() => {if (!user) {setMessage('로그인한 뒤 그룹 시험을 열어 주세요.'); return;} const controller = new AbortController(); setMessage('시험을 찾는 중입니다.'); async function resolve() {try {const groups = await api('/api/groups/mine', 'GET', null, {signal: controller.signal}); for (const group of groups) {const contests = await api('/api/groups/' + group.id + '/contests', 'GET', null, {signal: controller.signal}); if (controller.signal.aborted) return; if (contests.some(contest => String(contest.id) === id)) {navigate(`/groups/${group.id}/contests/${id}`, {replace: true}); return;}} if (!controller.signal.aborted) setMessage('가입한 그룹에서 해당 시험을 찾을 수 없습니다.');} catch (failure) {if (!controller.signal.aborted && !failure.stale) setMessage(failure.message);}} resolve(); return () => controller.abort();}, [id, user, epoch, navigate]);
  return <main className={`cw-page ct-page ${dark ? 'dark' : 'light'}`}><div className="cw-wrap"><section className="cw-card ct-empty"><h1>그룹 코딩 테스트</h1><p role="status">{message}</p><Link to="/contests" className="cw-btn">내 시험 목록</Link></section></div></main>;
}
