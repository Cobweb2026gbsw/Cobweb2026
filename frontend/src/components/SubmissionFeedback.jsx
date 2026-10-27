import {useEffect,useState} from 'react';
import {useAuth} from '../context/AuthContext';
import {api} from '../lib/api';
import {labelStatus} from '../lib/problemModel';
import './SubmissionFeedback.css';

const pending=status=>['QUEUED','RUNNING'].includes(status);

/** 제출 화면을 유지하면서 현재 계정의 실제 판정만 폴링합니다. 입력·정답은 표시하지 않습니다. */
export default function SubmissionFeedback({id,onPendingChange}){
  const {epoch}=useAuth(),[revision,setRevision]=useState(0),[state,setState]=useState(null);
  useEffect(()=>{
    if(!id)return;
    let active=true,timer;const controller=new AbortController();setState(null);onPendingChange?.(true);
    async function poll(){
      try{const data=await api(`/api/submissions/${id}`,'GET',null,{signal:controller.signal});if(!active)return;setState({id,epoch,data});if(pending(data.status))timer=setTimeout(poll,1500);else onPendingChange?.(false);}
      catch(error){if(active&&!error.stale&&error.name!=='AbortError'){setState({id,epoch,error:error.message});onPendingChange?.(false);}}
    }
    poll();return()=>{active=false;clearTimeout(timer);controller.abort();};
  },[id,epoch,revision,onPendingChange]);
  if(!id)return null;
  const visible=state?.id===id&&state.epoch===epoch?state:null,data=visible?.data,verdict=labelStatus(data?.status),cases=data?.cases||[],passed=cases.filter(item=>item.status==='ACCEPTED').length;
  return <div className="pd-submission-feedback" role="region" aria-label="채점 결과">
    <div className="pd-feedback-head"><h3>제출 #{id}</h3>{data&&<span className={`cw-badge ${verdict.tone}`}>{data.status}</span>}</div>
    {visible?.error?<><p className="cw-status-message error" role="alert">{visible.error}</p><button type="button" className="cw-btn" onClick={()=>setRevision(value=>value+1)}>채점 결과 다시 불러오기</button></>:<p role="status" className={`pd-feedback-verdict ${verdict.tone}`}>{!data?'채점 결과를 불러오는 중…':data.status==='ACCEPTED'?'정답입니다. 해결 완료로 기록되었습니다.':verdict.label}</p>}
    {data&&<>{pending(data.status)?<p className="cw-hint">이 화면에서 자동 갱신합니다. 작성한 코드는 그대로 유지됩니다.</p>:<p className="cw-hint">{cases.length}개 케이스 중 {passed}개 통과 · {data.score??'—'}점{data.execution_time_ms!=null&&` · ${data.execution_time_ms} ms`}</p>}
      {data.reusedFromSubmissionId!=null&&<p className="cw-hint">동일한 코드와 채점 조건의 기존 판정(#{data.reusedFromSubmissionId})을 재사용했습니다.</p>}
      {cases.length>0&&<details className="pd-feedback-cases"><summary>테스트케이스 결과 {cases.length}개</summary><ol>{cases.map((item,index)=><li key={index}><span>#{index+1}</span><span className={`cw-badge ${labelStatus(item.status).tone}`}>{labelStatus(item.status).label}</span><span>{item.execution_time_ms==null?'—':`${item.execution_time_ms} ms`}</span></li>)}</ol><p className="cw-hint">비공개 입력과 정답은 공개하지 않습니다.</p></details>}</>}
  </div>;
}
