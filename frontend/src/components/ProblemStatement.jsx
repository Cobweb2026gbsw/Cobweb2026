import Markdown,{Example} from './Markdown';
import {formatFunctionExampleInput} from '../lib/exampleInputFormat';
import '../pages/ProblemDetail.css';
import './ProblemComponents.css';
import './ProblemStatement.css';

function safeSourceUrl(value){
  if(typeof value!=='string'||/[\u0000-\u001f\u007f-\u009f]/.test(value))return null;
  try{const url=new URL(value);return ['http:','https:'].includes(url.protocol)&&!url.username&&!url.password?url.href:null;}catch{return null;}
}

export default function ProblemStatement({problem}){
  if(!problem)return null;
  const tags=Array.isArray(problem.tags)?problem.tags.filter(tag=>typeof tag==='string'&&tag.trim()):[],sourceUrl=safeSourceUrl(problem.sourceUrl),sourceName=typeof problem.sourceName==='string'?problem.sourceName.trim():'';
  const fields=[['문제 설명',problem.description],['입력',problem.input_description??problem.inputDescription],['출력',problem.output_description??problem.outputDescription],['제한',problem.constraints_text??problem.constraints]];
  const examples=(problem.examples||[]).map(example=>{
    const input=formatFunctionExampleInput(problem,example.input_text??example.input);
    return input===null?example:{...example,input_text:input,input};
  });
  return <article className="cw-card pd-statement" tabIndex={0} aria-label="문제 설명 스크롤 영역"><dl className="pd-meta"><div><dt>시간 제한</dt><dd>{problem.time_limit_ms??problem.timeLimitMs} ms</dd></div><div><dt>메모리 제한</dt><dd>{problem.memory_limit_mb??problem.memoryLimitMb} MB</dd></div><div><dt>실행 형식</dt><dd>{problem.execution_mode??problem.executionMode}</dd></div></dl>
    {fields.filter(([,content])=>content).map(([label,content])=><section key={label}><h2>{label}</h2><Markdown>{content}</Markdown></section>)}
    {examples.some((example,index)=>example!==problem.examples[index])&&<p className="cw-hint">예제 입력에는 JSON의 args 포장 대신 함수에 전달할 인자 값을 표시합니다. 여러 인자는 매개변수 이름으로 구분합니다.</p>}
    {examples.map((example,index)=><Example key={index} example={example} index={index}/>)}
    {tags.length>0&&<ul className="cw-problem-tags" aria-label="문제 태그">{tags.map((tag,index)=><li className="cw-badge mint" key={`${tag}:${index}`}>{tag}</li>)}</ul>}
    {(sourceName||sourceUrl)&&<p className="cw-hint">출처: {sourceUrl?<a href={sourceUrl} target="_blank" rel="noopener noreferrer">{sourceName||sourceUrl}</a>:sourceName}</p>}
  </article>;
}
