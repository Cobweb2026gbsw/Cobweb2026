import {useCallback,useEffect,useRef,useState} from 'react';
import {api,getSessionEpoch} from './api';
import {useAuth} from '../context/AuthContext';

// 화면·계정 전환에서 이전 응답이 새 화면의 개인 자료를 덮어쓰지 못하게 합니다.
export default function useApiData(path){
  const {epoch}=useAuth();const [revision,setRevision]=useState(0);const [state,setState]=useState({data:null,error:'',loading:Boolean(path),path,epoch});
  useEffect(()=>{const controller=new AbortController();let active=true;setState({data:null,error:'',loading:Boolean(path),path,epoch});
    if(path)api(path,'GET',null,{signal:controller.signal}).then(data=>{if(active)setState({data,error:'',loading:false,path,epoch});}).catch(error=>{if(active&&!error.stale&&error.name!=='AbortError')setState({data:null,error:error.message,loading:false,path,epoch});});
    return()=>{active=false;controller.abort();};
  },[path,epoch,revision]);
  const visible=state.path===path&&state.epoch===epoch?state:{data:null,error:'',loading:Boolean(path)};
  return {...visible,reload:useCallback(()=>setRevision(x=>x+1),[])};
}
export function useMutationGuard(...dependencies){
  const generation=useRef(0);
  useEffect(()=>{generation.current++;return()=>{generation.current++;};},dependencies);
  return ()=>{const start=generation.current,session=getSessionEpoch();return()=>generation.current===start&&getSessionEpoch()===session;};
}
