import {useEffect,useRef,useState} from 'react';
import {api} from '../lib/api';
import useApiData,{useMutationGuard} from '../lib/useApiData';
import {formatDate} from '../lib/groupContracts';

const verdicts={ACCEPTED:'정답',UNSOLVED:'미해결',PENDING:'채점 중',SYSTEM_ERROR:'채점 오류',UNATTEMPTED:'미제출'};

export function ContestParticipantScorecard({base,userId}) {
  const {data,error,reload}=useApiData(base+'/scorecard?userId='+userId);
  useEffect(()=>{const tick=setInterval(reload,10000);return ()=>clearInterval(tick);},[reload]);
  return <><h4>문제별 성적표</h4>{error && <p className="gp-error" role="alert">{error}</p>}
    <div className="gc-results"><table><thead><tr><th>문제</th><th>결과</th><th>시도</th><th>첫 정답 제출 시각</th></tr></thead>
      <tbody>{data?.map(c=><tr key={c.problemNumber}><td>#{c.problemNumber} {c.problemTitle}</td><td>{verdicts[c.verdict]}</td><td>{c.attempts}</td><td>{formatDate(c.firstAcceptedAt)}</td></tr>)}</tbody>
    </table></div></>;
}

function csvCell(value) {
  let text=String(value??'');
  if (/^[\s\uFEFF]*[=+@-]/u.test(text) || /^[\t\r\n]/.test(text)) text="'"+text;
  return '"'+text.replaceAll('"','""')+'"';
}

// 각 페이지를 내려받을 때마다 서버에서 해당 시험 결과 조회 권한을 다시 검사합니다.
export function ContestScorecardExport({base}) {
  const [busy,setBusy]=useState(false),[error,setError]=useState('');
  const flight=useRef(null),guard=useMutationGuard(base);
  useEffect(()=>()=>flight.current?.abort(),[base]);
  async function download() {
    if(flight.current)return;
    const current=guard(),controller=new AbortController();
    flight.current=controller;setBusy(true);setError('');
    try {
      const lines=[['참가자','문제 번호','문제명','결과','시도 횟수','첫 정답 제출 시각(UTC)'].map(csvCell).join(',')];
      let after=0;
      for(;;) {
        const rows=await api(base+'/scorecard?afterUser='+after,'GET',null,{signal:controller.signal});
        if(!current() || controller.signal.aborted)return;
        if(!rows.length)break;
        for(const c of rows)lines.push([c.username,c.problemNumber,c.problemTitle,verdicts[c.verdict],c.attempts,c.firstAcceptedAt].map(csvCell).join(','));
        if(new Set(rows.map(c=>c.userId)).size<50)break;
        const next=rows.at(-1).userId;
        if(next<=after)throw new Error('성적표 페이지를 확인할 수 없습니다.');
        after=next;
      }
      const blob=new Blob(['\uFEFF',lines.join('\r\n')],{type:'text/csv;charset=utf-8'});
      const url=URL.createObjectURL(blob),link=document.createElement('a');
      link.href=url;link.download='contest-'+base.split('/').at(-1)+'-scorecard.csv';link.click();
      setTimeout(()=>URL.revokeObjectURL(url),1000);
    } catch(failure){if(current()&&!failure.stale&&!controller.signal.aborted)setError(failure.message);}
    finally{flight.current=null;if(current())setBusy(false);}
  }
  return <><button className="cw-btn sm" disabled={busy} onClick={download}>{busy?'CSV 준비 중…':'문제별 성적표 CSV'}</button>{error && <p role="alert" className="gp-error">{error}</p>}</>;
}
