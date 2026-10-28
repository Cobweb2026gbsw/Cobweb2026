import {useEffect,useId,useRef,useState} from 'react';
import {useAuth} from '../context/AuthContext';
import {api,getSessionEpoch} from '../lib/api';
import {ProtocolError,RequestError} from '../lib/errors';
import {sourceBytes,STATUS} from '../lib/problemModel';
import './TestRunPanel.css';

const pending=status=>['QUEUED','RUNNING'].includes(status);
const terminal=status=>Object.hasOwn(STATUS,status)&&!pending(status);
const sameScope=(left,right)=>left&&right&&left.number===right.number&&left.epoch===right.epoch&&left.language===right.language&&left.code===right.code;

/** 서버가 선택한 실제 채점 케이스 두 개를 실행합니다. 입력·정답·출력 원문은 받거나 보관하지 않습니다. */
export default function TestRunPanel({problem,number,language,code,disabled=false,renderSubmitAction}){
  const {user,epoch,loading}=useAuth();
  const [run,setRun]=useState(null),[activeTab,setActiveTab]=useState('cases');
  const tabId=useId(),tabs=useRef({}),validationAlert=useRef(null);
  const mounted=useRef(false),generation=useRef(0),flight=useRef(false),controller=useRef(null),timer=useRef(null);
  const scope=useRef(null);scope.current={number:String(number),epoch,language,code};
  function stop(){generation.current++;flight.current=false;controller.current?.abort();controller.current=null;clearTimeout(timer.current);timer.current=null;}
  useEffect(()=>{mounted.current=true;return()=>{mounted.current=false;stop();};},[]);
  useEffect(()=>{stop();setRun(null);setActiveTab('cases');},[number,epoch,language,code,problem]);
  const visible=sameScope(run?.scope,scope.current)?run:null,busy=Boolean(visible&&pending(visible.status));
  useEffect(()=>{if(run?.errorKind==='validation'&&sameScope(run.scope,scope.current))validationAlert.current?.focus();},[run]);
  function tabKey(event,tab){
    const order=['cases','output'],index=order.indexOf(tab);
    const next=event.key==='ArrowRight'?order[(index+1)%order.length]:event.key==='ArrowLeft'?order[(index+order.length-1)%order.length]:event.key==='Home'?order[0]:event.key==='End'?order.at(-1):null;
    if(next){event.preventDefault();setActiveTab(next);tabs.current[next]?.focus();}
  }
  async function execute(){
    if(disabled||flight.current)return;
    const snapshot={...scope.current};
    try{
      if(loading)throw new RequestError('로그인 정보를 확인 중입니다. 잠시 후 다시 시도해주세요.');
      if(!user)throw new RequestError('테스트를 실행하려면 로그인해주세요.');
      if(!Number.isSafeInteger(Number(number))||Number(number)<=0)throw new RequestError('문제 번호를 확인해주세요.');
      if(!language||!problem?.languages?.includes(language))throw new RequestError('문제에서 허용한 언어를 선택하세요.');
      if(typeof code!=='string'||!code.trim())throw new RequestError('풀이 코드를 입력하세요.');
      if(sourceBytes(code)>65536)throw new RequestError('코드는 UTF-8 64 KiB 이하여야 합니다.');
    }catch(error){setActiveTab('cases');setRun({scope:snapshot,error:error.message,errorKind:'validation'});return;}
    flight.current=true;const ticket=++generation.current,session=getSessionEpoch(),abort=new AbortController();controller.current=abort;
    const current=()=>mounted.current&&!abort.signal.aborted&&generation.current===ticket&&session===getSessionEpoch()&&sameScope(snapshot,scope.current);
    const update=data=>{if(current())setRun({scope:snapshot,...data});};
    const fail=error=>{if(current()&&!error.stale&&error.name!=='AbortError'){flight.current=false;setActiveTab('output');update({error:error.message});}};
    setActiveTab('output');update({status:'QUEUED',results:[]});
    try{
      const id=await api(`/api/problems/${number}/test-runs`,'POST',{language,sourceCode:code,privateSample:true},{signal:abort.signal});
      if(!current())return;
      if(!Number.isSafeInteger(id)||id<=0)throw new ProtocolError(undefined,{requestMayHaveCompleted:true});
      async function poll(){
        if(!current())return;
        try{
          const data=await api(`/api/test-runs/${id}`,'GET',null,{signal:abort.signal});if(!current())return;
          if(data?.id!==id||data.problemNumber!==Number(number)||data.language!==language||data.privateSample!==true||data.testCount!==2||!['QUEUED','RUNNING','COMPLETED','SYSTEM_ERROR'].includes(data.status)||!Array.isArray(data.results)||data.results.length>2)throw new ProtocolError();
          if(data.results.some(result=>!terminal(result?.status)||!Number.isSafeInteger(result.timeMs)||result.timeMs<0))throw new ProtocolError();
          if(pending(data.status)&&data.results.length)throw new ProtocolError();
          if(data.status==='COMPLETED'&&data.results.length!==2)throw new ProtocolError();
          // 서버 응답에 뜻밖의 필드가 끼어도 비공개 입력/정답/출력/진단을 상태나 DOM으로 옮기지 않습니다.
          const results=data.results.map(({status,timeMs})=>({status,timeMs}));
          update({id,status:data.status,results});
          if(pending(data.status))timer.current=setTimeout(poll,1000);else flight.current=false;
        }catch(error){fail(error);}
      }
      await poll();
    }catch(error){fail(error);}
  }
  return <section className="cw-test-run-panel" aria-label="코드 테스트">
    <h2 className="cw-card-title">코드 테스트</h2>
    <p className="cw-hint">실제 채점 케이스 2개로 실행합니다. 입력값은 공개하지 않습니다.</p>
    <div className="cw-test-tabs" role="tablist" aria-label="코드 테스트 보기">{[['cases','테스트 케이스'],['output','실행 출력']].map(([tab,label])=><button key={tab} type="button" role="tab" id={`${tabId}-tab-${tab}`} aria-controls={`${tabId}-panel-${tab}`} aria-selected={activeTab===tab} tabIndex={activeTab===tab?0:-1} ref={element=>{tabs.current[tab]=element;}} onClick={()=>setActiveTab(tab)} onKeyDown={event=>tabKey(event,tab)}>{label}</button>)}</div>
    {/* 탭은 같은 고정 영역 안에서 전환하며 실행 중에도 자유롭게 살펴볼 수 있습니다. */}
    <div className="cw-test-scroll">
      <div className="cw-test-tabpanel" role="tabpanel" id={`${tabId}-panel-cases`} aria-labelledby={`${tabId}-tab-cases`} hidden={activeTab!=='cases'} tabIndex={0}>
        {visible?.errorKind==='validation'&&<p ref={validationAlert} role="alert" tabIndex={-1} className="cw-status-message error">{visible.error}</p>}
        <div className="cw-test-private-cases">{[1,2].map(index=><article key={index} className="cw-test-private-case" aria-label={`비공개 테스트 ${index}`}><h3>테스트 {index}</h3><span className="cw-badge">비공개 입력</span></article>)}</div>
      </div>
      <div className="cw-test-tabpanel" role="tabpanel" id={`${tabId}-panel-output`} aria-labelledby={`${tabId}-tab-output`} hidden={activeTab!=='output'} tabIndex={0} aria-busy={busy}>
        {(!visible||visible.errorKind==='validation')&&<p className="cw-hint cw-test-empty">실행 후 통과 여부와 실행 시간을 확인할 수 있습니다.</p>}
        {visible?.error&&visible.errorKind!=='validation'&&<p role="alert" className="cw-status-message error">{visible.error}</p>}
        {busy&&<p role="status" className="cw-hint">{visible.status==='RUNNING'?'코드를 실행하고 있습니다.':'테스트 실행을 기다리고 있습니다.'}</p>}
        {visible?.status==='SYSTEM_ERROR'&&<p role="alert" className="cw-status-message error">테스트 실행을 완료하지 못했습니다. 잠시 후 다시 시도해주세요.</p>}
        {visible?.status==='COMPLETED'&&<div className="cw-test-results" role="region" aria-label="테스트 실행 결과">{visible.results.map((result,index)=><article key={index} className="cw-test-result" aria-label={`테스트 ${index+1} 결과`}><h3>테스트 {index+1}</h3><span className={`cw-badge ${result.status==='ACCEPTED'?'green':'red'}`}>{result.status==='ACCEPTED'?'통과':'실패'}</span><span className="cw-hint">{result.timeMs} ms</span></article>)}</div>}
      </div>
    </div>
    <div className="cw-test-actions" aria-label="코드 실행 작업"><button type="button" className="cw-btn" disabled={disabled||busy||!language||!code?.trim()} onClick={execute}>{busy?'테스트 실행 중…':user?'테스트 및 실행':'로그인 후 테스트'}</button>{renderSubmitAction?.({testing:busy})}</div>
  </section>;
}
