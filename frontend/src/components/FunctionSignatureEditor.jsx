import {GENERIC_TYPES} from '../lib/genericFunction.js';

/** 공개 서명과 비공개 테스트는 별개입니다. 기존 테스트·시작 코드를 자동으로 지우지 않습니다. */
export default function FunctionSignatureEditor({value,onChange,disabled=false}){
  const update=(key,next)=>onChange({...value,[key]:next});
  const parameter=(index,key,next)=>update('parameters',value.parameters.map((row,i)=>i===index?{...row,[key]:next}:row));
  return <fieldset className="cw-card pe-section" disabled={disabled}>
    <legend>함수 입력 규격</legend>
    {!value?<><p className="cw-hint">기존 matrix/target → bool 규격입니다. 다른 함수가 필요하면 범용 규격을 지정하세요.</p><button type="button" className="cw-btn" onClick={()=>onChange({name:'sol',parameters:[],returnType:'int'})}>범용 함수 규격 사용</button></>:<>
      <div className="cw-form-grid"><label className="pe-field"><span>함수 이름</span><select className="cw-select" value={value.name} onChange={e=>update('name',e.target.value)}><option value="sol">sol</option><option value="solution">solution</option></select></label>
        <label className="pe-field"><span>반환형</span><select className="cw-select" value={value.returnType} onChange={e=>update('returnType',e.target.value)}>{GENERIC_TYPES.map(type=><option key={type}>{type}</option>)}</select></label></div>
      {value.parameters.map((row,index)=><div className="cw-form-grid" key={index}>
        <label className="pe-field"><span>매개변수 {index+1} 이름</span><input className="cw-input" maxLength={32} value={row.name} onChange={e=>parameter(index,'name',e.target.value)}/></label>
        <label className="pe-field"><span>매개변수 {index+1} 자료형</span><select className="cw-select" value={row.type} onChange={e=>parameter(index,'type',e.target.value)}>{GENERIC_TYPES.map(type=><option key={type}>{type}</option>)}</select></label>
        <button type="button" className="cw-btn danger sm" onClick={()=>update('parameters',value.parameters.filter((_,i)=>i!==index))}>매개변수 {index+1} 삭제</button>
      </div>)}
      <button type="button" className="cw-btn sm" disabled={value.parameters.length>=6} onClick={()=>update('parameters',[...value.parameters,{name:`arg${value.parameters.length+1}`,type:'int'}])}>매개변수 추가</button>
      <p className="cw-hint">인자는 0~6개입니다. 테스트 입력은 <code>{'{"args":[...]}'}</code>, 기대값은 반환형에 맞는 JSON입니다. int는 부호 있는 32비트 정수, string은 ASCII 문자입니다. int[][]·string[][]는 행 길이가 서로 달라도 됩니다. 정식 채점은 입력·정답 각각 UTF-8 16 MiB, 배열 슬롯 합계 100만 개까지이며 수동 테스트는 각 64 KiB입니다. 문자열의 공백·줄바꿈과 배열 순서는 유지하며 실수는 1e-9 오차 이내로 비교합니다. 서명을 바꿔도 기존 테스트·직접 작성한 시작 코드는 자동 변경하지 않습니다.</p>
    </>}
  </fieldset>;
}
