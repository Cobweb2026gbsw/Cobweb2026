import {useEffect,useRef,useState} from 'react';
import {getSessionEpoch} from './api';
import {sourceBytes} from './problemModel';
import {draftContext,draftOwner,loadProblemDrafts,removeProblemDraft,saveProblemDraft} from './problemDraftCache';

// 서버에서 현재 계정의 문제 조회에 성공한 뒤에만 로컬 초안을 읽습니다.
// 시험 화면은 이 훅을 사용하지 않으며, 다시보기 금지 계약을 유지합니다.
export default function useProblemDrafts({problem,number,user,loading,epoch}){
  const owner=loading?'':draftOwner(user),context=draftContext(problem);
  const scope=owner&&problem&&Number(problem.problem_number)===Number(number)?{owner,number:Number(number),context}:null;
  const key=scope?JSON.stringify(scope):'';
  const [state,setState]=useState({key:'',codes:{},status:'idle'}),pending=useRef(null);
  useEffect(()=>{
    if(!scope){setState({key:'',codes:{},status:'idle'});return;}
    const cached=loadProblemDrafts(scope,problem.languages||[]);
    const work={key,scope,epoch,codes:{},timer:null};pending.current=work;
    setState({key,codes:cached.codes,status:!cached.available?'storage':Object.keys(cached.codes).length?'restored':'idle'});
    const flush=()=>{
      clearTimeout(work.timer);work.timer=null;
      if(pending.current!==work||getSessionEpoch()!==work.epoch)return;
      let status='saved';
      for(const [language,code] of Object.entries(work.codes)){
        const result=saveProblemDraft(work.scope,language,code);
        if(!result.ok)status=result.reason==='size'?'size':'storage';
      }
      if(Object.keys(work.codes).length)setState(previous=>previous.key===key?{...previous,status:previous.status==='size'?'size':status}:previous);
      work.codes={};
    };
    work.flush=flush;
    const hidden=()=>{if(document.visibilityState==='hidden')flush();};
    window.addEventListener('pagehide',flush);document.addEventListener('visibilitychange',hidden);
    return()=>{flush();pending.current=null;window.removeEventListener('pagehide',flush);document.removeEventListener('visibilitychange',hidden);};
  },[key,epoch]);
  function change(language,code){
    const work=pending.current;
    if(!work||work.key!==key||getSessionEpoch()!==epoch)return;
    setState(previous=>({...previous,key,codes:{...(previous.key===key?previous.codes:{}),[language]:code},status:sourceBytes(code)>65536?'size':'pending'}));
    if(sourceBytes(code)>65536){delete work.codes[language];return;}
    work.codes[language]=code;clearTimeout(work.timer);work.timer=setTimeout(work.flush,300);
  }
  function reset(language,code){
    const work=pending.current;if(!work||work.key!==key||getSessionEpoch()!==epoch)return;
    delete work.codes[language];const removed=removeProblemDraft(scope,language);
    setState(previous=>({...previous,key,codes:{...(previous.key===key?previous.codes:{}),[language]:code},status:removed?'reset':'storage'}));
  }
  return {codes:state.key===key?state.codes:{},status:state.key===key?state.status:'idle',change,reset,enabled:Boolean(scope)};
}
