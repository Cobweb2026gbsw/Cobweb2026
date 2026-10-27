import {useEffect,useRef,useState} from 'react';
import {Link,useLocation,useNavigate,useParams} from 'react-router-dom';
import {useAuth} from '../context/AuthContext';
import {api} from '../lib/api';
import useApiData,{useMutationGuard} from '../lib/useApiData';
import {FILE_NAMES,languageHelp,sourceBytes,starterCode} from '../lib/problemModel';
import {preferredLanguage,rememberLanguage} from '../lib/editorLanguagePreference';
import useProblemDrafts from '../lib/useProblemDrafts';
import {draftOwner} from '../lib/problemDraftCache';
import CodeEditor from '../components/CodeEditor';
import ProblemStatement from '../components/ProblemStatement';
import ProblemAttribution from '../components/ProblemAttribution';
import ProblemWorkspace from '../components/ProblemWorkspace';
import SubmissionFeedback from '../components/SubmissionFeedback';
import TestRunPanel from '../components/TestRunPanel';
import './common.css';
import './ProblemDetail.css';

function DetailPage({dark}){
  const params=useParams(),number=params.id??params.number,gid=params.gid;const navigate=useNavigate(),location=useLocation(),{user,epoch,loading}=useAuth();
  const problem=useApiData(`/api/problems/${number}`),catalog=useApiData('/api/languages');
  const [language,setLanguage]=useState(''),[codes,setCodes]=useState({}),[error,setError]=useState(''),[busy,setBusy]=useState(false);
  const [submissionId,setSubmissionId]=useState(null),flight=useRef(false),guard=useMutationGuard(number,epoch);
  const drafts=useProblemDrafts({problem:problem.data,number,user,loading,epoch});
  useEffect(()=>{setCodes({});setLanguage('');setError('');setBusy(false);setSubmissionId(null);},[number,epoch]);
  useEffect(()=>{if(problem.data)setLanguage(old=>preferredLanguage(problem.data.languages||[],old));},[problem.data]);
  const allowed=(catalog.data||[]).filter(l=>problem.data?.languages?.includes(l.id));
  // 미작성 언어만 공개 시작 코드를 사용합니다. 빈 문자열도 사용자가 쓴 초안이므로
  // 지운 코드를 자동으로 되살리거나 언어 변경 시 다른 언어의 풀이를 덮지 않습니다.
  const code=Object.prototype.hasOwnProperty.call(codes,language)?codes[language]:Object.prototype.hasOwnProperty.call(drafts.codes,language)?drafts.codes[language]:starterCode(problem.data,language);
  const changeCode=value=>{setCodes(previous=>({...previous,[language]:value}));drafts.change(language,value);};
  async function submit(){
    if(busy||flight.current)return;
    const current=guard();setError('');
    if(!user){navigate('/login',{state:{from:location.pathname}});return;}
    if(!code.trim()){setError('풀이 코드를 입력하세요.');return;}
    if(sourceBytes(code)>65536){setError('코드는 UTF-8 64 KiB 이하여야 합니다.');return;}
    rememberLanguage(language);flight.current=true;setBusy(true);setSubmissionId(null);
    try{const id=await api('/api/submissions','POST',{problemNumber:Number(number),language,sourceCode:code});if(current())setSubmissionId(id);}
    catch(failure){if(current()&&!failure.stale){setError(failure.message);setBusy(false);}}
    finally{flight.current=false;}
  }
  const p=problem.data;
  return <main className={`cw-page pd-page ${dark?'dark':'light'}`}><div className="cw-wrap"><header className="cw-head"><div><p className="cw-eyebrow">PROBLEM / {number}</p><h1 className="cw-title">{p?`${number}. ${p.problem_title}`:'문제 불러오는 중…'}</h1><ProblemAttribution problem={p}/></div><div className="cw-head-actions"><Link className="cw-btn" to={gid?`/groups/${gid}`:'/problems'}>{gid?'그룹으로':'문제 목록'}</Link><Link className="cw-btn" to={`/forum/new?problemNumber=${number}`}>질문 작성</Link></div></header>
    {(problem.error||catalog.error||error)&&<p className="cw-status-message error" role="alert">{problem.error||catalog.error||error}</p>}
    {p&&<ProblemWorkspace statement={<ProblemStatement problem={p}/>}><div className="pd-side">
      <section className="cw-card pd-workspace">
        <div className="pd-toolbar"><label className="pd-lang">언어<select id="pd-lang" name="language" className="cw-select" value={language} disabled={busy} onChange={event=>{setLanguage(event.target.value);rememberLanguage(event.target.value);}}>{allowed.map(item=><option value={item.id} key={item.id}>{item.label}</option>)}</select></label><button className="cw-btn" type="button" disabled={busy} onClick={()=>{if(confirm('현재 언어의 저장 초안을 삭제하고 시작 코드로 되돌릴까요?')){const initial=starterCode(p,language);setCodes(previous=>({...previous,[language]:initial}));drafts.reset(language,initial);}}}>초기화</button></div>
        <p className="cw-hint">{languageHelp(language,p.execution_mode,p)}</p>
        <CodeEditor value={code} onChange={changeCode} language={language} fileName={FILE_NAMES[language]} disabled={busy} id="source"/>
        <p className="cw-hint">작성 코드는 이 브라우저에 계정·문제·언어별로 최대 30일 자동 저장합니다(코드당 64 KiB, 최근 50개·총 2 MiB). 초기화하면 현재 언어의 저장 초안을 삭제합니다. 공용 기기에서는 이용 후 삭제하세요. 제출된 코드는 DB에도 보관합니다.</p>
        <p className="cw-hint" role="status">{({pending:'초안 저장 중…',saved:'이 브라우저에 초안을 저장했습니다.',restored:'이 브라우저의 저장 초안을 복원했습니다.',reset:'현재 언어의 저장 초안을 삭제했습니다.',storage:'브라우저 저장소를 사용할 수 없어 자동 저장하지 못했습니다. 코드를 별도로 복사해 주세요.',size:'64 KiB를 초과해 현재 변경은 저장되지 않았습니다. 마지막으로 저장한 초안은 유지됩니다.'})[drafts.status]||(!drafts.enabled?'인증 정보 확인 중에는 자동 저장을 기다립니다.':'')}</p>
      </section>
      <section className="cw-card pd-result"><TestRunPanel problem={p} number={number} language={language} code={code} disabled={busy}/></section>
      <section className="cw-card pd-result"><h2 className="cw-card-title">실제 채점</h2><p className="cw-hint">제출하면 격리된 채점 워커가 비공개 테스트를 실행하고 결과를 아래에 표시합니다.</p><div className="pd-actions"><button className="cw-btn primary" disabled={busy||!language||!code.trim()} onClick={submit}>{busy?'채점 중…':user?'제출 및 채점':'로그인 후 제출'}</button></div><SubmissionFeedback id={submissionId} onPendingChange={setBusy}/></section>
    </div></ProblemWorkspace>}
  </div></main>;
}
export default function ProblemDetail(props){const params=useParams(),{epoch,user,loading}=useAuth();return <DetailPage key={`${params.id??params.number}:${params.gid??''}:${epoch}:${loading?'loading':draftOwner(user)||'unverified'}`} {...props}/>;}
