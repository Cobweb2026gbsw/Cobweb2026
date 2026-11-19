import './VerdictGuidance.css';

const GUIDANCE={
  WRONG_ANSWER:['출력과 경계값을 확인해 보세요','문제의 출력 형식과 예제를 다시 비교하고, 최솟값·최댓값·중복 값처럼 놓치기 쉬운 조건을 확인하세요. 예제를 통과해도 다른 입력에서 오답이 날 수 있습니다.'],
  TIME_LIMIT_EXCEEDED:['반복 횟수와 종료 조건을 확인해 보세요','입력 크기가 커질 때 반복 횟수가 얼마나 늘어나는지 살펴보세요. 불필요한 중복 계산과 끝나지 않는 반복문도 확인하세요.'],
  MEMORY_LIMIT_EXCEEDED:['저장하는 데이터 크기를 줄여 보세요','배열·문자열의 크기와 복사 횟수를 확인하세요. 사용이 끝난 데이터를 계속 쌓고 있지 않은지도 살펴보세요.'],
  COMPILATION_ERROR:['코드가 실행되기 전 오류가 났어요','선택한 언어와 코드 형식이 맞는지 확인하고, 아래 오류 원인과 줄 번호를 참고해 문법·이름·들여쓰기를 수정하세요. 상세 원인이 없으면 공개 예제로 다시 실행해 보세요.'],
  RUNTIME_ERROR:['실행 도중 오류가 났어요','배열 범위를 벗어난 접근, 0으로 나누기, 잘못된 입력 처리 등을 확인하세요. 상세 오류가 표시되면 해당 줄부터 살펴보세요.'],
  SYSTEM_ERROR:['채점을 완료하지 못했어요','채점 시스템의 문제로 결과를 확정하지 못했습니다. 코드의 오답으로 단정할 수 없습니다. 잠시 후 제출 상태를 다시 확인해 주세요.'],
};

export default function VerdictGuidance({status}){
  const advice=GUIDANCE[status];
  if(!advice)return null;
  return <section className="cw-verdict-guidance" aria-label="판정 안내"><h3>{advice[0]}</h3><p>{advice[1]}</p></section>;
}
