import {useEffect,useRef,useState} from 'react';
import {Link,useLocation,useNavigate,useParams} from 'react-router-dom';
import {useAuth} from '../context/AuthContext';
import {api} from '../lib/api';
import useApiData,{useMutationGuard} from '../lib/useApiData';
import {FILE_NAMES,languageHelp,sourceBytes,starterCode} from '../lib/problemModel';
import {preferredLanguage,rememberLanguage} from '../lib/editorLanguagePreference';
import CodeEditor from '../components/CodeEditor';
import ProblemStatement from '../components/ProblemStatement';
import ProblemAttribution from '../components/ProblemAttribution';
import ProblemWorkspace from '../components/ProblemWorkspace';
import SubmissionFeedback from '../components/SubmissionFeedback';
import './common.css';
import './ProblemDetail.css';

function DetailPage({dark}){
  const params=useParams(),number=params.id??params.number,gid=params.gid;const navigate=useNavigate(),location=useLocation(),{user,epoch}=useAuth();
  const problem=useApiData(`/api/problems/${number}`),catalog=useApiData('/api/languages');
  const [language,setLanguage]=useState(''),[codes,setCodes]=useState({}),[error,setError]=useState(''),[busy,setBusy]=useState(false);
  const [submissionId,setSubmissionId]=useState(null),flight=useRef(false),guard=useMutationGuard(number,epoch);
  useEffect(()=>{setCodes({});setLanguage('');setError('');setBusy(false);setSubmissionId(null);},[number,epoch]);
  useEffect(()=>{if(problem.data)setLanguage(old=>preferredLanguage(problem.data.languages||[],old));},[problem.data]);
  const allowed=(catalog.data||[]).filter(l=>problem.data?.languages?.includes(l.id));
  // 미작성 언어만 공개 시작 코드를 사용합니다. 빈 문자열도 사용자가 쓴 초안이므로
  // 지운 코드를 자동으로 되살리거나 언어 변경 시 다른 언어의 풀이를 덮지 않습니다.
  const code=Object.prototype.hasOwnProperty.call(codes,language)?codes[language]:starterCode(problem.data,language);
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
        <div className="pd-toolbar"><label className="pd-lang">언어<select id="pd-lang" name="language" className="cw-select" value={language} disabled={busy} onChange={event=>{setLanguage(event.target.value);rememberLanguage(event.target.value);}}>{allowed.map(item=><option value={item.id} key={item.id}>{item.label}</option>)}</select></label><button className="cw-btn" type="button" disabled={busy} onClick={()=>{if(confirm('현재 언어의 코드를 시작 코드로 되돌릴까요?'))setCodes(previous=>({...previous,[language]:starterCode(p,language)}));}}>초기화</button></div>
        <p className="cw-hint">{languageHelp(language,p.execution_mode,p)}</p>
        <CodeEditor value={code} onChange={value=>setCodes(previous=>({...previous,[language]:value}))} language={language} fileName={FILE_NAMES[language]} disabled={busy} id="source"/>
        <p className="cw-hint">선택한 언어는 이 브라우저에 기억합니다. 미제출 코드는 화면 메모리에만 보관되며 계정 전환·이탈 시 초기화됩니다. 제출된 코드는 최대 UTF-8 64 KiB로 DB에 보관합니다.</p>
      </section>
      <section className="cw-card pd-result"><h2 className="cw-card-title">실제 채점</h2><p className="cw-hint">제출하면 격리된 채점 워커가 비공개 테스트를 실행하고 결과를 아래에 표시합니다.</p><div className="pd-actions"><button className="cw-btn primary" disabled={busy||!language||!code.trim()} onClick={submit}>{busy?'채점 중…':user?'제출 및 채점':'로그인 후 제출'}</button></div><SubmissionFeedback id={submissionId} onPendingChange={setBusy}/></section>
    </div></ProblemWorkspace>}
  </div></main>;
}
export default function ProblemDetail(props){const params=useParams(),{epoch}=useAuth();return <DetailPage key={`${params.id??params.number}:${params.gid??''}:${epoch}`} {...props}/>;}
