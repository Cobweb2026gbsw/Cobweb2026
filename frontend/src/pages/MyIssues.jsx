import {useState} from 'react';
import {Link,useLocation,useSearchParams} from 'react-router-dom';
import {useAuth} from '../context/AuthContext';
import {api} from '../lib/api';
import {BackToContest,categoryLabel,contestScope,IssueDetails,Queue,useOperation} from '../components/ModerationTools';
import {validProblemNumber} from '../lib/problemNavigation';
import './common.css';
import './Moderation.css';

function IssueReturn({params, scope}) {
  const {state} = useLocation();
  if (scope.has('contestId')) return <BackToContest scope={scope}/>;

  const submission = params.get('submission');
  const problem = params.get('problem');
  let to = '/dashboard', label = '내 활동으로', returnState;
  if (submission && /^[1-9]\d*$/.test(submission)) {
    const path = '/submissions/' + submission;
    const origin = state?.submissionReturn;
    const matches = typeof origin === 'string' && origin.split('?')[0] === path;
    to = matches ? origin : path;
    label = '제출 상세로';
    returnState = matches ? state.submissionState : undefined;
  } else if (validProblemNumber(problem)) {
    const path = '/problems/' + Number(problem);
    const origin = state?.problemReturn;
    const sourcePath = typeof origin === 'string' ? origin.split('?')[0] : '';
    const matches = sourcePath === path || (/^\/groups\/[1-9]\d*\/problems\/[1-9]\d*$/.test(sourcePath) && sourcePath.endsWith(path));
    to = matches ? origin : path;
    label = '문제로 돌아가기';
    returnState = matches ? state.problemState : undefined;
  }
  return <Link className="cw-btn" to={to} state={returnState}>{label}</Link>;
}

function Issues({dark,params}){
  const scope=contestScope(params),url='/api/issues?'+scope;
  const [problem,setProblem]=useState(params.get('problem')||''),[submission,setSubmission]=useState(params.get('submission')||'');
  const [category,setCategory]=useState(params.has('submission')?'JUDGING_APPEAL':'PROBLEM_ERROR'),[body,setBody]=useState('');
  const [refresh,setRefresh]=useState(0),[page,setPage]=useState(0),action=useOperation();
  const {user,loading}=useAuth();
  function send(event){
    event.preventDefault();
    action.run(()=>api(url,'POST',{category,problemNumber:Number(problem),submissionId:category==='JUDGING_APPEAL'?Number(submission):null,body}),
      id=>{setBody('');setPage(0);setRefresh(n=>n+1);return '#'+id+'번으로 접수되었습니다.';});
  }
  return <main className={'cw-page moderation-page '+(dark?'dark':'light')}><div className="cw-wrap">
    <header className="cw-head"><h1>{scope.has('contestId')?'시험 문제 신고·이의제기':'문제 신고·채점 이의제기'}</h1><IssueReturn params={params} scope={scope}/></header>
    {loading?<p>불러오는 중…</p>:!user?<Link className="cw-btn" to="/login">로그인</Link>:<>
      <form className="cw-card ops-form" onSubmit={send}><h2>새 접수</h2><div className="ops-fields">
        <label>분류<select className="cw-select" value={category} onChange={e=>setCategory(e.target.value)}>{Object.entries(categoryLabel).map(([key,label])=><option key={key} value={key}>{label}</option>)}</select></label>
        <label>문제 번호<input className="cw-input" type="number" min="1" required value={problem} onChange={e=>setProblem(e.target.value)}/></label>
        {category==='JUDGING_APPEAL'&&<label>내 제출 번호<input className="cw-input" type="number" min="1" required value={submission} onChange={e=>setSubmission(e.target.value)}/></label>}
      </div><label>내용<textarea className="cw-textarea" required maxLength={2000} rows="4" value={body} onChange={e=>setBody(e.target.value)}/></label>
      {action.feedback}<button className="cw-btn primary" disabled={action.busy||!body.trim()}>접수하기</button></form>
      <h2>내 접수 내역</h2><Queue url={url} refresh={refresh} page={page} setPage={setPage} render={i=><IssueDetails key={i.id} issue={i}/>}/>
    </>}
  </div></main>;
}
export default function MyIssues(props){const {epoch}=useAuth(),[params]=useSearchParams();return <Issues key={epoch+':'+params.toString()} params={params} {...props}/>;}
