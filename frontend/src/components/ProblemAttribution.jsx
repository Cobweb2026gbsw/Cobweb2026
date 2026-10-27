import {rankLabel} from '../lib/problemModel';
import './ProblemAttribution.css';

/** 제작자 공개는 문제 공개 범위와 별개입니다. 숨긴 이름은 DOM에도 남기지 않습니다. */
export default function ProblemAttribution({problem}){
  if(!problem)return null;
  const username=problem.authorPublic===true&&typeof problem.authorUsername==='string'
    ?problem.authorUsername.trim():'';
  return <p className="cw-desc pd-byline" aria-label="문제 난이도 및 제작자"><span className="pd-rank">{rankLabel(problem)}</span><span className="pd-author"><span className="pd-author-label">제작자</span> {username||'비공개'}</span></p>;
}
