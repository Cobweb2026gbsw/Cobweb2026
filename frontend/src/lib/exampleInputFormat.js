import {validFunctionSignature} from './genericFunction.js';

// 채점/저장 형식은 그대로 두고, 확인된 공개 함수 규격의 예제만 읽기 쉽게 표시합니다.
// 레거시·일반 입출력·잘못된 JSON은 해석을 추측하지 않고 원문을 유지합니다.
function matchesType(value,type){
  switch(type){
    case 'int':return Number.isInteger(value)&&value>=-2147483648&&value<=2147483647;
    case 'float':return typeof value==='number'&&Number.isFinite(value);
    case 'bool':return typeof value==='boolean';
    case 'string':return typeof value==='string'&&value.length<=16384&&/^[\x00-\x7f]*$/.test(value);
    case 'int[]':case 'string[]':case 'int[][]':case 'string[][]':return Array.isArray(value)&&value.length<=1000000&&value.every(item=>matchesType(item,type.slice(0,-2)));
    default:return false;
  }
}

/** null이면 원문을 표시합니다. 반환 문자열은 HTML/Markdown이 아닌 예제의 일반 텍스트입니다. */
export function formatFunctionExampleInput(problem,input){
  if((problem?.execution_mode??problem?.executionMode)!=='FUNCTION'||problem.functionAbi!=='GENERIC_V1'||
    !validFunctionSignature(problem.functionSignature)||typeof input!=='string'||
    input.length>65536||new TextEncoder().encode(input).length>65536)return null;
  let value;
  try{value=JSON.parse(input);}catch{return null;}
  const parameters=problem.functionSignature.parameters;
  if(!value||Array.isArray(value)||Object.keys(value).length!==1||!Object.hasOwn(value,'args')||
    !Array.isArray(value.args)||value.args.length!==parameters.length||
    !value.args.every((item,index)=>matchesType(item,parameters[index].type)))return null;
  if(!parameters.length)return '인자 없음';
  if(parameters.length===1)return JSON.stringify(value.args[0]);
  return parameters.map((parameter,index)=>`${parameter.name}: ${JSON.stringify(value.args[index])}`).join('\n');
}
