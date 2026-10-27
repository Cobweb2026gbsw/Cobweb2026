import {useRef,useState} from 'react';
import './ProblemWorkspace.css';

export const WORKSPACE_SPLIT_KEY='cobweb.problem.split.v1';
const MIN=25,MAX=75,DEFAULT=50;
const bounded=value=>Math.min(MAX,Math.max(MIN,Math.round(value)));
function initialSplit(){try{const saved=window.localStorage.getItem(WORKSPACE_SPLIT_KEY);const value=saved===null?NaN:Number(saved);return Number.isFinite(value)?bounded(value):DEFAULT;}catch{return DEFAULT;}}

/** 세로 구분선은 마우스·터치·키보드로 조절하며, 작은 화면은 세로 배치로 유지합니다. */
export default function ProblemWorkspace({statement,children}){
  const [split,setSplit]=useState(initialSplit),[dragging,setDragging]=useState(false),grid=useRef(null),pointer=useRef(null),current=useRef(split);
  function change(value){const next=bounded(value);current.current=next;setSplit(next);}
  function save(){try{window.localStorage.setItem(WORKSPACE_SPLIT_KEY,String(current.current));}catch{/* 저장 불가 환경에서도 현재 너비는 유지합니다. */}}
  function move(event){
    if(pointer.current!==event.pointerId)return;
    const rect=grid.current.getBoundingClientRect();if(rect.width<=32)return;
    change((event.clientX-rect.left-16)/(rect.width-32)*100);
  }
  function finish(event){if(pointer.current!==event.pointerId)return;pointer.current=null;setDragging(false);save();}
  return <div ref={grid} className={`pd-grid pd-resizable${dragging?' is-resizing':''}`} style={{'--pd-left-share':`${split}fr`,'--pd-right-share':`${100-split}fr`}}>
    {statement}
    <div className="pd-splitter" role="separator" tabIndex={0} aria-label="문제 설명과 코드 영역 너비 조절" aria-orientation="vertical" aria-valuemin={MIN} aria-valuemax={MAX} aria-valuenow={split} aria-valuetext={`문제 설명 ${split}%, 코드 ${100-split}%`} title="드래그 또는 방향키로 너비 조절 · 두 번 클릭하면 초기화"
      onPointerDown={event=>{if(event.button!==0)return;event.preventDefault();pointer.current=event.pointerId;setDragging(true);try{event.currentTarget.setPointerCapture(event.pointerId);}catch{/* 포인터 캡처 미지원 브라우저 */}event.currentTarget.focus();}}
      onPointerMove={move} onPointerUp={finish} onPointerCancel={finish} onLostPointerCapture={finish}
      onDoubleClick={()=>{change(DEFAULT);save();}}
      onKeyDown={event=>{let next;if(event.key==='ArrowLeft')next=current.current-(event.shiftKey?10:2);else if(event.key==='ArrowRight')next=current.current+(event.shiftKey?10:2);else if(event.key==='Home')next=MIN;else if(event.key==='End')next=MAX;else if(event.key==='Enter')next=DEFAULT;else return;event.preventDefault();change(next);save();}}><span aria-hidden="true"/></div>
    {children}
  </div>;
}
