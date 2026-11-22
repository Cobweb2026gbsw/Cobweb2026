import {useRef,useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {api} from '../lib/api';
import useApiData,{useMutationGuard} from '../lib/useApiData';

function copyKey() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
  const bytes = globalThis.crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  const hex = Array.from(bytes,b=>b.toString(16).padStart(2,'0')).join('');
  return [hex.slice(0,8),hex.slice(8,12),hex.slice(12,16),hex.slice(16,20),hex.slice(20)].join('-');
}

export default function ContestCopyForm({base,gid,permissions}) {
  const [target,setTarget]=useState(String(gid)),[start,setStart]=useState(''),[end,setEnd]=useState('');
  const [busy,setBusy]=useState(false),[error,setError]=useState('');
  const request=useRef({}),flight=useRef(false),guard=useMutationGuard(base),navigate=useNavigate();
  const {data:groups,error:groupError}=useApiData('/api/groups/mine');
  const canExport=permissions.includes('EDIT_PROBLEMS');
  const eligible=(groups || []).filter(g=>String(g.id)!==String(gid) && canExport &&
    ['CREATE_CONTESTS','MANAGE_CONTESTS','CREATE_PROBLEMS','REVIEW_PROBLEMS'].every(p=>g.permissions?.includes(p)));
  const options=[...(permissions.includes('CREATE_CONTESTS') ? [{id:gid,groupName:'현재 그룹'}] : []),...eligible];
  const chosen=options.some(g=>String(g.id)===target) ? target : String(options[0]?.id??'');
  async function copy(event) {
    event.preventDefault();
    if(flight.current || !chosen)return;
    const current=guard();
    const from=new Date(start),to=new Date(end);
    if(!Number.isFinite(+from)||!Number.isFinite(+to)||from>=to){setError('시작과 종료 시각을 확인해 주세요.');return;}
    flight.current=true;setBusy(true);setError('');
    try {
      const signature=[chosen,start,end].join('|');
      if(request.current.signature!==signature)request.current={signature,key:copyKey()};
      const id=await api(base+'/copies','POST',{targetGroupId:Number(chosen),startsAt:from.toISOString(),endsAt:to.toISOString(),requestId:request.current.key});
      if(current())navigate('/groups/'+chosen+'/contests/'+id);
    } catch(failure){if(current()&&!failure.stale)setError(failure.message);}
    finally{flight.current=false;if(current())setBusy(false);}
  }
  return <form className="gp-form" onSubmit={copy}>
    <label className="cw-label">대상 그룹<select className="cw-select" value={chosen} onChange={e=>setTarget(e.target.value)}>
      {options.map(g=><option key={g.id} value={g.id}>{g.groupName}</option>)}
    </select></label>
    <label className="cw-label">새 시작 시각<input className="cw-input" type="datetime-local" required value={start} onChange={e=>setStart(e.target.value)} /></label>
    <label className="cw-label">새 종료 시각<input className="cw-input" type="datetime-local" required value={end} onChange={e=>setEnd(e.target.value)} /></label>
    <p className="gp-muted">출제 순서와 시험 설정을 복사합니다. 제출·성적·질문·공지는 새로 시작합니다.</p>
    {chosen && chosen!==String(gid) && <p className="gp-muted">선택한 그룹에 문제와 채점 자료를 그룹 전용으로 복사합니다.</p>}
    {(error||groupError) && <p className="gp-error" role="alert">{error||groupError}</p>}
    <button className="cw-btn" disabled={busy || !chosen}>새 일정으로 복제</button>
  </form>;
}
