import {useEffect, useRef, useState} from 'react';
import {Link, useSearchParams} from 'react-router-dom';
import {useAuth} from '../context/AuthContext';
import {api} from '../lib/api';
import {CONTEST_STATUS, formatDate} from '../lib/groupContracts';
import './common.css';
import './Contests.css';
import './Groups.css';

export default function Contests({dark}) {
  const {epoch} = useAuth();
  return <ContestList key={epoch} dark={dark}/>;
}

function ContestList({dark}) {
  const {user} = useAuth();
  const [params, setParams] = useSearchParams();
  const selected = params.get('status');
  const tab = Object.hasOwn(CONTEST_STATUS, selected) ? selected : 'ACTIVE';
  const [schedule,setSchedule] = useState({groups:[], loading:true, error:''});
  const retry=useRef(null);

  useEffect(() => {
    if (!user) return;
    const controller = new AbortController();
    const {signal} = controller;
    const groups = new Map();
    let readingMembership = false;
    let membershipError = '';

    function publish() {
      if (!signal.aborted) setSchedule({
        groups:[...groups.values()].map(group=>({...group})),
        loading:readingMembership && groups.size===0,
        error:membershipError,
      });
    }
    async function readGroup(group) {
      if (group.pending) return;
      group.pending=true;
      group.failed=false;
      publish();
      try {
        group.items=await api('/api/groups/'+group.id+'/contests','GET',null,{signal});
      } catch (failure) {
        if (signal.aborted || failure.stale) return;
        group.items=[];
        group.failed=true;
      } finally {
        group.pending=false;
        if (groups.get(group.id)===group) publish();
      }
    }
    async function readMembership() {
      if (readingMembership) return;
      readingMembership=true;
      membershipError='';
      publish();
      try {
        const joined=await api('/api/groups/mine','GET',null,{signal});
        if (signal.aborted) return;
        const membership=new Set(joined.map(group=>group.id));
        for (const id of groups.keys()) if (!membership.has(id)) groups.delete(id);
        for (const {id,groupName} of joined) {
          if (!groups.has(id)) groups.set(id,{id,items:[],pending:false,failed:false});
          groups.get(id).groupName=groupName;
          void readGroup(groups.get(id));
        }
      } catch (failure) {
        if (signal.aborted || failure.stale) return;
        groups.clear();
        membershipError=failure.message;
      } finally {
        readingMembership=false;
        publish();
      }
    }
    retry.current=()=>{
      if (membershipError) void readMembership();
      else for (const group of groups.values()) if (group.failed) void readGroup(group);
    };
    void readMembership();
    const timer=setInterval(readMembership,30000);
    return ()=>{controller.abort();clearInterval(timer);retry.current=null;};
  }, [user]);

  const items=schedule.groups.flatMap(group=>group.items.map(contest=>({...contest,gid:group.id,groupName:group.groupName})));
  const loading=schedule.loading || schedule.groups.some(group=>group.pending);
  const failed=schedule.groups.filter(group=>group.failed).length;
  const error=schedule.error || (failed ? `${failed}개 그룹의 시험 일정을 불러오지 못했어요.` : '');
  const list = items.filter(item => item.status === tab).sort((a, b) =>
    tab === 'ENDED' ? new Date(b.startsAt) - new Date(a.startsAt) : new Date(a.startsAt) - new Date(b.startsAt));
  return <main className={`cw-page ct-page ${dark ? 'dark' : 'light'}`}><div className="cw-wrap">
    <header className="cw-head">
      <div><h1 className="cw-title">코딩 테스트</h1>
        <p className="cw-desc">가입한 그룹의 시험 일정을 확인하고 진행 중인 시험에 참여하세요.</p></div>
      <Link to="/groups" className="cw-btn">그룹 찾기</Link>
    </header>
    <div className="ct-tabs" role="group" aria-label="시험 상태">
      {Object.entries(CONTEST_STATUS).map(([key, value]) =>
        <button key={key} aria-pressed={tab === key} className={tab === key ? 'on' : ''}
          onClick={() => setParams(key === 'ACTIVE' ? {} : {status:key})}>
          {value.label} {!loading && !error && <b>{items.filter(item => item.status === key).length}</b>}
        </button>)}
    </div>
    {error && <div className="cw-card ct-retry">
      <p role="alert">{error}</p><button className="cw-btn" onClick={() => retry.current?.()}>다시 시도</button>
    </div>}
    {loading && <p role="status">코딩 테스트를 불러오는 중입니다.</p>}
    {!loading && !error && !list.length && <p className="cw-card ct-empty">{CONTEST_STATUS[tab].label} 상태의 시험이 없습니다.</p>}
    {list.length > 0 && <div className="ct-list">{list.map(item =>
      <Link className="cw-card ct-card" key={`${item.gid}:${item.id}`} to={`/groups/${item.gid}/contests/${item.id}`}>
        <div className="ct-card-top"><span className={'cw-badge ' + CONTEST_STATUS[item.status].tone}>{CONTEST_STATUS[item.status].label}</span>
          <span className="ct-id">{item.groupName} · #{item.id}</span></div>
        <h2>{item.title}</h2>
        <dl><div><dt>기간</dt><dd>{formatDate(item.startsAt)} ~ {formatDate(item.endsAt)}</dd></div>
          <div><dt>문제</dt><dd>{item.problemCount}개</dd></div>
          <div><dt>종료 후 다시 보기</dt><dd>{item.replayAllowed ? '응시자에게 허용' : '비허용'}</dd></div></dl>
      </Link>)}</div>}
  </div></main>;
}
