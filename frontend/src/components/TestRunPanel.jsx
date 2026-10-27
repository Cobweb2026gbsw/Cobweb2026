import {useEffect,useId,useRef,useState} from 'react';
import {useAuth} from '../context/AuthContext';
import {api,getSessionEpoch} from '../lib/api';
import {ProtocolError,RequestError} from '../lib/errors';
import {validFunctionSignature} from '../lib/genericFunction';
import {labelStatus,sourceBytes,STATUS} from '../lib/problemModel';
import SubmissionDiagnostic from './SubmissionDiagnostic';
import './TestRunPanel.css';

const pending=status=>['QUEUED','RUNNING'].includes(status);
const defaults={int:'0',float:'0',bool:'false',string:'""','int[]':'[]','string[]':'[]','int[][]':'[]','string[][]':'[]'};
const signatureFor=problem=>problem?.execution_mode==='FUNCTION'&&problem.functionAbi==='GENERIC_V1'&&validFunctionSignature(problem.functionSignature)?problem.functionSignature:null;
function typed(value,type){
  switch(type){
    case 'int':return Number.isInteger(value)&&value>=-2147483648&&value<=2147483647;
    case 'float':return typeof value==='number'&&Number.isFinite(value);
    case 'bool':return typeof value==='boolean';
    case 'string':return typeof value==='string'&&value.length<=16384&&/^[\x00-\x7f]*$/.test(value);
    case 'int[]':case 'string[]':case 'int[][]':case 'string[][]':return Array.isArray(value)&&value.length<=1000000&&value.every(item=>typed(item,type.slice(0,-2)));
    default:return false;
  }
}
function parseValue(text,type,label){
  if(sourceBytes(text)>65536)throw new RequestError(`${label}은 UTF-8 64 KiB 이하여야 합니다.`);
  let value;try{value=JSON.parse(text);}catch{throw new RequestError(`${label}에 올바른 JSON 값을 입력하세요.`);}
  if(!typed(value,type))throw new RequestError(`${label}은 ${type} 형식과 문제의 범위를 지켜야 합니다.`);
  return value;
}
function rowsFor(problem){
  const signature=signatureFor(problem),examples=Array.isArray(problem?.examples)?problem.examples.slice(0,3):[];
  return (examples.length?examples:[{}]).map(example=>{
    const input=typeof example.input_text==='string'?example.input_text:'',expected=typeof example.output_text==='string'?example.output_text:'';
    let args=[];try{const parsed=JSON.parse(input);if(Array.isArray(parsed.args))args=parsed.args;}catch{/* 예제가 없는 문제는 자료형에 맞는 기본값을 사용합니다. */}
    return {input,expected,values:signature?signature.parameters.map((parameter,index)=>index<args.length?JSON.stringify(args[index]):defaults[parameter.type]):[]};
  });
}
const sameScope=(left,right)=>left&&right&&left.number===right.number&&left.epoch===right.epoch&&left.language===right.language&&left.code===right.code;

/** 공개 예제/사용자가 쓴 입력만 별도 테스트 큐로 보냅니다. 정식 제출·해결 통계와 분리됩니다. */
export default function TestRunPanel({problem,number,language,code,disabled=false}){
  const {user,epoch,loading}=useAuth(),signature=signatureFor(problem);
  const [rows,setRows]=useState(()=>rowsFor(problem)),[run,setRun]=useState(null),[activeTab,setActiveTab]=useState('cases');
  const tabId=useId(),tabs=useRef({}),validationAlert=useRef(null);
  const mounted=useRef(false),generation=useRef(0),flight=useRef(false),controller=useRef(null),timer=useRef(null);
  const scope=useRef(null);scope.current={number:String(number),epoch,language,code};
  function stop(){generation.current++;flight.current=false;controller.current?.abort();controller.current=null;clearTimeout(timer.current);timer.current=null;}
  useEffect(()=>{mounted.current=true;return()=>{mounted.current=false;stop();};},[]);
  useEffect(()=>{stop();setRun(null);setActiveTab('cases');},[number,epoch,language,code]);
  useEffect(()=>{stop();setRows(rowsFor(problem));setRun(null);setActiveTab('cases');},[number,problem]);
  const visible=sameScope(run?.scope,scope.current)?run:null,busy=Boolean(visible&&pending(visible.status));
  // 입력 오류가 있는 패널을 먼저 연 다음 포커스를 옮겨 숨겨진 오류가 생기지 않게 합니다.
  useEffect(()=>{if(run?.errorKind==='validation'&&sameScope(run.scope,scope.current))validationAlert.current?.focus();},[run]);
  const edit=(index,change)=>{setRows(previous=>previous.map((row,i)=>i===index?{...row,...change}:row));setRun(null);};
  function tabKey(event,tab){
    const order=['cases','output'],index=order.indexOf(tab);
    const next=event.key==='ArrowRight'?order[(index+1)%order.length]:event.key==='ArrowLeft'?order[(index+order.length-1)%order.length]:event.key==='Home'?order[0]:event.key==='End'?order.at(-1):null;
    if(next){event.preventDefault();setActiveTab(next);tabs.current[next]?.focus();}
  }
  async function execute(){
    if(disabled||flight.current)return;
    const snapshot={...scope.current};
    let cases;
    try{
      if(loading)throw new RequestError('로그인 정보를 확인 중입니다. 잠시 후 다시 시도해주세요.');
      if(!user)throw new RequestError('테스트를 실행하려면 로그인해주세요.');
      if(!Number.isSafeInteger(Number(number))||Number(number)<=0)throw new RequestError('문제 번호를 확인해주세요.');
      if(!language||!problem?.languages?.includes(language))throw new RequestError('문제에서 허용한 언어를 선택하세요.');
      if(typeof code!=='string'||!code.trim())throw new RequestError('풀이 코드를 입력하세요.');
      if(sourceBytes(code)>65536)throw new RequestError('코드는 UTF-8 64 KiB 이하여야 합니다.');
      cases=rows.map((row,index)=>{
        const input=signature?JSON.stringify({args:signature.parameters.map((parameter,parameterIndex)=>parseValue(row.values[parameterIndex],parameter.type,`테스트 ${index+1}의 ${parameter.name}`))}):row.input;
        if(sourceBytes(input)>65536||sourceBytes(row.expected)>65536)throw new RequestError('각 테스트의 입력과 예상 출력은 UTF-8 64 KiB 이하여야 합니다.');
        if(signature)parseValue(row.expected,signature.returnType,`테스트 ${index+1}의 예상 출력`);
        return {input,expected:row.expected};
      });
    }catch(error){setActiveTab('cases');setRun({scope:snapshot,error:error.message,errorKind:'validation'});return;}
    flight.current=true;const ticket=++generation.current,session=getSessionEpoch(),abort=new AbortController();controller.current=abort;
    const current=()=>mounted.current&&!abort.signal.aborted&&generation.current===ticket&&session===getSessionEpoch()&&sameScope(snapshot,scope.current);
    const update=data=>{if(current())setRun({scope:snapshot,snapshot:cases,...data});};
    const fail=error=>{if(current()&&!error.stale&&error.name!=='AbortError'){flight.current=false;setActiveTab('output');update({error:error.message});}};
    setActiveTab('output');
    update({status:'QUEUED',results:[]});
    try{
      const id=await api(`/api/problems/${number}/test-runs`,'POST',{language,sourceCode:code,cases},{signal:abort.signal});
      if(!current())return;
      if(!Number.isSafeInteger(id)||id<=0)throw new ProtocolError(undefined,{requestMayHaveCompleted:true});
      async function poll(){
        if(!current())return;
        try{
          const data=await api(`/api/test-runs/${id}`,'GET',null,{signal:abort.signal});if(!current())return;
          if(data?.id!==id||data.problemNumber!==Number(number)||data.language!==language||!['QUEUED','RUNNING','COMPLETED','SYSTEM_ERROR'].includes(data.status)||!Array.isArray(data.results)||data.results.length>cases.length)throw new ProtocolError();
          if(data.results.some(result=>!Object.hasOwn(STATUS,result?.status)||!(result.actual==null||typeof result.actual==='string'&&sourceBytes(result.actual)<=65536)))throw new ProtocolError();
          update({id,status:data.status,results:data.results,diagnostic:data.diagnostic});
          if(pending(data.status))timer.current=setTimeout(poll,1000);else flight.current=false;
        }catch(error){fail(error);}
      }
      await poll();
    }catch(error){fail(error);}
  }
  return <section className="cw-test-run-panel" aria-label="코드 테스트">
    <h2 className="cw-card-title">코드 테스트</h2>
    <p className="cw-hint">공개 예제나 직접 입력한 테스트 최대 3개로 실행합니다. 정식 제출·해결 기록·정답률에는 반영하지 않습니다.</p>
    <p className="cw-hint">수동 테스트의 각 입력·예상 출력은 64 KiB 이내여야 합니다.</p>
    {signature&&<p className="cw-hint">매개변수별로 JSON 값만 입력하세요. 문자열은 <code>"문자열"</code>, 배열은 <code>[1,2]</code>, 참/거짓은 <code>true</code>·<code>false</code>입니다. 예상 출력도 함수 반환값의 JSON입니다.</p>}
    <div className="cw-test-tabs" role="tablist" aria-label="코드 테스트 보기">{[['cases','테스트 케이스'],['output','실행 출력']].map(([tab,label])=><button key={tab} type="button" role="tab" id={`${tabId}-tab-${tab}`} aria-controls={`${tabId}-panel-${tab}`} aria-selected={activeTab===tab} tabIndex={activeTab===tab?0:-1} ref={element=>{tabs.current[tab]=element;}} onClick={()=>setActiveTab(tab)} onKeyDown={event=>tabKey(event,tab)}>{label}</button>)}</div>
    {/* 두 패널을 유지해 입력값을 보존하고, 같은 높이의 영역 안에서만 스크롤합니다. */}
    <div className="cw-test-scroll">
    <div className="cw-test-tabpanel" role="tabpanel" id={`${tabId}-panel-cases`} aria-labelledby={`${tabId}-tab-cases`} hidden={activeTab!=='cases'} tabIndex={0}>
    {visible?.errorKind==='validation'&&<p ref={validationAlert} role="alert" tabIndex={-1} className="cw-status-message error">{visible.error}</p>}
    <div className="cw-test-inputs">{rows.map((row,index)=><fieldset key={index} disabled={disabled||busy}>
      <legend>테스트 {index+1}</legend>
      {signature?signature.parameters.length?signature.parameters.map((parameter,parameterIndex)=><label className="cw-label" key={parameter.name}>테스트 {index+1} · {parameter.name} ({parameter.type})<textarea className="cw-input cw-test-argument" aria-label={`테스트 ${index+1} · ${parameter.name} (${parameter.type})`} value={row.values[parameterIndex]} onChange={event=>{const values=[...row.values];values[parameterIndex]=event.target.value;edit(index,{values});}} rows={2}/></label>):<p className="cw-hint">인자 없음 — 함수만 호출합니다.</p>:<label className="cw-label">테스트 {index+1} 입력<textarea className="cw-input" aria-label={`테스트 ${index+1} 입력`} value={row.input} onChange={event=>edit(index,{input:event.target.value})} rows={3}/></label>}
      <label className="cw-label">테스트 {index+1} 예상 출력<textarea className="cw-input" aria-label={`테스트 ${index+1} 예상 출력`} value={row.expected} onChange={event=>edit(index,{expected:event.target.value})} rows={2}/></label>
      <button type="button" className="cw-btn sm" disabled={rows.length===1} onClick={()=>{setRows(previous=>previous.filter((_,i)=>i!==index));setRun(null);}}>테스트 {index+1} 삭제</button>
    </fieldset>)}</div>
    </div>
    <div className="cw-test-tabpanel" role="tabpanel" id={`${tabId}-panel-output`} aria-labelledby={`${tabId}-tab-output`} hidden={activeTab!=='output'} tabIndex={0} aria-busy={busy}>
    {(!visible||visible.errorKind==='validation')&&<p className="cw-hint cw-test-empty">테스트를 실행하면 예상 출력과 실제 출력을 여기에서 비교할 수 있습니다.</p>}
    {visible?.error&&visible.errorKind!=='validation'&&<p role="alert" className="cw-status-message error">{visible.error}</p>}
    {busy&&<p role="status" className="cw-hint">{visible.status==='RUNNING'?'코드를 실행하고 있습니다.':'테스트 실행을 기다리고 있습니다.'} 이 화면에서 자동 갱신합니다.</p>}
    {visible?.status==='SYSTEM_ERROR'&&<p role="alert" className="cw-status-message error">테스트 실행을 완료하지 못했습니다. 잠시 후 다시 시도해주세요.</p>}
    {visible?.status==='COMPLETED'&&<div className="cw-test-results" role="region" aria-label="테스트 실행 결과">
      <p className="cw-hint">테스트 실행 #{visible.id} · 현재 화면에서 실행한 코드와 입력의 결과입니다.</p>
      {visible.results.length===0&&<SubmissionDiagnostic submission={{status:'COMPILATION_ERROR',diagnostic:visible.diagnostic}}/>}
      {visible.results.map((result,index)=><article key={index} className="cw-test-result" aria-label={`테스트 ${index+1} 결과`}>
        <div className="cw-test-result-head"><h3>테스트 {index+1}</h3><span className={`cw-badge ${labelStatus(result.status).tone}`}>{labelStatus(result.status).label}</span>{Number.isFinite(result.timeMs)&&result.timeMs>=0&&<span className="cw-hint">{result.timeMs} ms</span>}</div>
        <div className="cw-test-comparison"><div><h4>예상 출력</h4><pre>{visible.snapshot[index].expected}</pre></div><div><h4>실제 출력</h4><pre>{result.actual==null?'실제 출력 없음':result.actual}</pre></div></div>
        <SubmissionDiagnostic submission={result}/>
      </article>)}
    </div>}
    </div>
    </div>
    <div className="cw-test-actions"><button type="button" className="cw-btn" disabled={disabled||busy||rows.length>=3} onClick={()=>{setActiveTab('cases');setRows(previous=>[...previous,...rowsFor({...problem,examples:[]})]);setRun(null);}}>테스트 추가</button><button type="button" className="cw-btn primary" disabled={disabled||busy||!language||!code?.trim()} onClick={execute}>{busy?'테스트 실행 중…':user?'테스트 실행':'로그인 후 테스트'}</button></div>
  </section>;
}
