import {FILE_NAMES} from '../lib/problemModel';
import './SubmissionDiagnostic.css';

const submittedFiles=new Set(Object.values(FILE_NAMES));
const expectedPhase={COMPILATION_ERROR:'COMPILE',RUNTIME_ERROR:'RUN'};
const positive=value=>Number.isSafeInteger(value)&&value>0&&value<=1000000;

/** 서버가 허용한 원인·제출 파일 위치만 표시합니다. 원문 stderr/HTML을 렌더링하지 않습니다. */
export default function SubmissionDiagnostic({submission}){
  const phase=expectedPhase[submission?.status],diagnostic=submission?.diagnostic;
  if(!phase||!diagnostic||diagnostic.phase!==phase||typeof diagnostic.message!=='string'||!diagnostic.message.trim())return null;
  const message=diagnostic.message.slice(0,2048);
  const errorType=typeof diagnostic.errorType==='string'&&/^[A-Za-z][A-Za-z0-9_.]{0,79}$/.test(diagnostic.errorType)?diagnostic.errorType:null;
  const locations=Array.isArray(diagnostic.locations)?diagnostic.locations.slice(0,5).filter(item=>submittedFiles.has(item?.file)&&positive(item?.line)&&(!Object.hasOwn(item,'column')||item.column==null||positive(item.column))):[];
  return <section className="cw-submission-diagnostic" aria-label={phase==='COMPILE'?'컴파일 오류 원인':'실행 오류 원인'}>
    <h3>{phase==='COMPILE'?'컴파일 오류 원인':'실행 오류 원인'}</h3>
    {errorType&&<p className="cw-diagnostic-type">{errorType}</p>}
    <pre className="cw-diagnostic-message">{message}</pre>
    {locations.length>0&&<ul aria-label="제출 코드 오류 위치">{locations.map((item,index)=><li key={index}><code>{item.file}</code> · {item.line}행{item.column!=null?` ${item.column}열`:''}</li>)}</ul>}
    <p className="cw-hint">컴파일러·실행기의 메시지에서 분류한 원인과 제출 코드 위치입니다. 비공개 테스트와 내부 경로 보호를 위해 원문 로그는 표시하지 않습니다.</p>
  </section>;
}
