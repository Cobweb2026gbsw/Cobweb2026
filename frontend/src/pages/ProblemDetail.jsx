import {useEffect,useRef,useState} from 'react';
import {Link,useLocation,useNavigate,useParams} from 'react-router-dom';
import {useAuth} from '../context/AuthContext';
import {api} from '../lib/api';
import {ProtocolError} from '../lib/errors';
import useApiData,{useMutationGuard} from '../lib/useApiData';
import {FILE_NAMES,sourceBytes,starterCode} from '../lib/problemModel';
import {preferredLanguage,rememberLanguage} from '../lib/editorLanguagePreference';
import useProblemDrafts from '../lib/useProblemDrafts';
import {draftOwner} from '../lib/problemDraftCache';
import {problemTrail,problemUrl,rememberProblem,neighborDestination,neighborRequest} from '../lib/problemNavigation';
import CodeEditor from '../components/CodeEditor';
import ProblemStatement from '../components/ProblemStatement';
import ProblemAttribution from '../components/ProblemAttribution';
import ProblemWorkspace from '../components/ProblemWorkspace';
import SubmissionFeedback from '../components/SubmissionFeedback';
import TestRunPanel from '../components/TestRunPanel';
import './common.css';
import './ProblemDetail.css';

const isSubmissionId=id=>Number.isSafeInteger(id)&&id>0;

function DetailPage({dark}){
  const params=useParams(),number=params.id??params.number,gid=params.gid;const navigate=useNavigate(),location=useLocation(),{user,epoch,loading}=useAuth();
  const problem=useApiData(`/api/problems/${number}`),catalog=useApiData('/api/languages');
  const [language,setLanguage]=useState(''),[codes,setCodes]=useState({}),[error,setError]=useState(''),[busy,setBusy]=useState(false);
  const [submission,setSubmission]=useState(null),flight=useRef(false),guard=useMutationGuard(number,epoch);
  const drafts=useProblemDrafts({problem:problem.data,number,user,loading,epoch});
  const trail=problemTrail(location,number),owner=loading?'':draftOwner(user);
  const chapter=trail.listOrigin==null;
  const questions=new URLSearchParams({problemNumber:number});
  if(!chapter)questions.set('list',trail.listOrigin);
  const neighbor=useApiData(!gid && problem.data?.canLinkInForum && !trail.next
    ?neighborRequest(number,trail.from,chapter):null);
  const nextLink=trail.next?problemUrl(trail.next,trail.from):neighborDestination(neighbor.data,trail.from,chapter);
  useEffect(()=>{if(problem.data&&!gid)rememberProblem(owner,number,trail.listOrigin);},[problem.data,gid,owner,number,trail.listOrigin]);
  useEffect(()=>{setCodes({});setLanguage('');setError('');setBusy(false);setSubmission(null);},[number,epoch]);
  useEffect(()=>{if(problem.data)setLanguage(old=>preferredLanguage(problem.data.languages||[],old));},[problem.data]);
  const allowed=(catalog.data||[]).filter(l=>problem.data?.languages?.includes(l.id));
  // 미작성 언어만 공개 시작 코드를 사용합니다. 빈 문자열도 사용자가 쓴 초안이므로
  // 지운 코드를 자동으로 되살리거나 언어 변경 시 다른 언어의 풀이를 덮지 않습니다.
  const code=Object.prototype.hasOwnProperty.call(codes,language)?codes[language]:Object.prototype.hasOwnProperty.call(drafts.codes,language)?drafts.codes[language]:starterCode(problem.data,language);
  const changeCode=value=>{setCodes(previous=>({...previous,[language]:value}));drafts.change(language,value);};
  async function submit(showSubmissionResult){
    if(busy||flight.current)return;
    const current=guard();setError('');
    if(!user){navigate('/login',{state:{from:location.pathname+location.search}});return;}
    showSubmissionResult();
    if(!code.trim()){setError('풀이 코드를 입력하세요.');return;}
    if(sourceBytes(code)>65536){setError('코드는 UTF-8 64 KiB 이하여야 합니다.');return;}
    const payload=Object.freeze({problemNumber:Number(number),language,sourceCode:code});
    rememberLanguage(language);flight.current=true;setBusy(true);setSubmission(null);
    try {
      const id=await api('/api/submissions','POST',payload);
      if(!isSubmissionId(id))throw new ProtocolError(undefined,{requestMayHaveCompleted:true});
      if(current())setSubmission({id,recovered:false});
    } catch(failure) {
      if(!current()||failure.stale)return;
      if(failure.requestMayHaveCompleted===true) {
        try {
          const id=await api('/api/submissions/recover','POST',payload);
          if(!current())return;
          if(isSubmissionId(id)) {
            setSubmission({id,recovered:true});
            return;
          }
        } catch {
          // 확인할 수 없으면 원래 접수 오류를 표시합니다.
        }
      }
      if(current()){setError(failure.message);setBusy(false);}
    }
    finally{flight.current=false;}
  }
  const p=problem.data;
  const submissionResult=<>
    {error&&<p className="cw-status-message error" role="alert">{error}</p>}
    {submission&&<SubmissionFeedback id={submission.id} recovered={submission.recovered} onPendingChange={setBusy} nextAction={!gid&&<Link className="cw-btn primary" to={nextLink||trail.from} state={trail.state}>{nextLink?'다음 문제 풀기':'목록에서 다음 문제 찾기'}</Link>}/>}
    {!submission&&!error&&<p className="cw-hint cw-test-empty" role={busy?'status':undefined}>
      {busy?'코드를 제출하고 있습니다…':'제출한 코드의 채점 결과가 여기에 표시됩니다.'}
    </p>}
  </>;
  return <main className={`cw-page pd-page ${dark?'dark':'light'}`}><div className="cw-wrap"><header className="cw-head"><div><p className="cw-eyebrow">PROBLEM / {number}</p><h1 className="cw-title">{p?`${number}. ${p.problem_title}`:problem.error?'문제를 불러오지 못했어요':'문제 불러오는 중…'}</h1><ProblemAttribution problem={p}/></div><div className="cw-head-actions"><Link className="cw-btn" to={gid?`/groups/${gid}`:trail.from}>{gid?'그룹으로':'문제 목록'}</Link>{!gid&&p?.canLinkInForum&&<Link className="cw-btn" to={'/forum?'+questions} state={trail.state}>이 문제의 질문{p.questionCount!=null?` (${p.questionCount})`:''}</Link>}{p&&!gid&&nextLink&&<Link className="cw-btn" to={nextLink} state={trail.state}>다음 문제</Link>}
      {neighbor.error&&<button type="button" className="cw-btn" onClick={neighbor.reload}>다음 문제 다시 찾기</button>}</div></header>
    {(problem.error||catalog.error)&&<div className="cw-card"><p className="cw-status-message error" role="alert">{problem.error||catalog.error}</p><button type="button" className="cw-btn" onClick={()=>{if(problem.error)problem.reload();if(catalog.error)catalog.reload();}}>다시 시도</button></div>}
    {/* 언어가 정해지기 전에 편집하면 언어 키가 없는 초안이 생길 수 있어 준비 후에만 엽니다. */}
    {p&&language&&<ProblemWorkspace statement={<ProblemStatement problem={p}/>}><div className="pd-side">
      <TestRunPanel problem={p} number={number} language={language} code={code} disabled={busy} submissionResult={submissionResult}
        renderSubmitAction={({testing,showSubmissionResult})=><button className="cw-btn primary" type="button"
          disabled={busy||testing||!language||!code.trim()} onClick={()=>submit(showSubmissionResult)}>
          {busy?'채점 중…':user?'제출 및 채점':'로그인 후 제출'}
        </button>}
        renderLayout={({actions,panel})=><>
      <section className="cw-card pd-workspace">
        <div className="pd-toolbar"><label className="pd-lang">언어<select id="pd-lang" name="language" className="cw-select" value={language} disabled={busy} onChange={event=>{setLanguage(event.target.value);rememberLanguage(event.target.value);}}>{allowed.map(item=><option value={item.id} key={item.id}>{item.label}</option>)}</select></label>{actions}</div>
        <CodeEditor value={code} onChange={changeCode} language={language} fileName={FILE_NAMES[language]} disabled={busy} id="source"/>
        <p className="cw-hint" role="status">{({pending:'초안 저장 중…',saved:'이 브라우저에 초안을 저장했습니다.',restored:'이 브라우저의 저장 초안을 복원했습니다.',reset:'현재 언어의 저장 초안을 삭제했습니다.',storage:'브라우저 저장소를 사용할 수 없어 자동 저장하지 못했습니다. 코드를 별도로 복사해 주세요.',size:'64 KiB를 초과해 현재 변경은 저장되지 않았습니다. 마지막으로 저장한 초안은 유지됩니다.'})[drafts.status]||(!drafts.enabled?'인증 정보 확인 중에는 자동 저장을 기다립니다.':'')}</p>
      </section>
      <section className="cw-card pd-result">{panel}</section>
        </>}/>
    </div></ProblemWorkspace>}
  </div></main>;
}
export default function ProblemDetail(props){const params=useParams(),{epoch,user,loading}=useAuth();return <DetailPage key={`${params.id??params.number}:${params.gid??''}:${epoch}:${loading?'loading':draftOwner(user)||'unverified'}`} {...props}/>;}
