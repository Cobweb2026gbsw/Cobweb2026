import {validFunctionSignature} from './genericFunction.js';

const tolerance = '실수 반환값은 절대 또는 상대 오차 1e-9 이내를 허용합니다.';
const integerLimit = '- 정수 인자와 정수 결과는 부호 있는 32비트 범위입니다.';
const stringLimits = [
  '- 문자열 인자와 반환 문자열은 ASCII 범위만 다룹니다. 한 문자는 ASCII 코드 하나이며 Unicode 입력은 주어지지 않습니다.',
  '- 문자열 인자와 반환 문자열은 ASCII 범위만 다룹니다. Unicode 입력은 주어지지 않습니다.',
];
const inputFormats = [
  '함수의 반환값을 제출하세요. 표준 출력으로 출력하는 문제가 아닙니다. 입력은 함수 인자 순서의 JSON `args` 배열이며 예시의 출력은 반환값을 JSON으로 나타낸 것입니다.',
  '함수의 반환값을 제출하세요. 표준 출력으로 출력하는 문제가 아닙니다. 입력 JSON의 `args` 배열은 함수 인자 순서이며, 예시의 출력은 반환값의 JSON 표현입니다.',
];

export function statementFields(problem) {
  let description = problem.description;
  let input = problem.input_description ?? problem.inputDescription;
  let output = problem.output_description ?? problem.outputDescription;
  let constraints = problem.constraints_text ?? problem.constraints;
  const signature = problem.functionSignature;
  if (problem.functionAbi === 'GENERIC_V1' && validFunctionSignature(signature)) {
    const types = [signature.returnType, ...signature.parameters.map(parameter => parameter.type)];
    if (signature.returnType !== 'float' && output?.endsWith(tolerance)) {
      output = output.slice(0, -tolerance.length).trimEnd();
    }
    if (constraints) {
      const baseTypes = types.map(type => type.split('[')[0]);
      constraints = constraints.split('\n').filter(line =>
        (line !== integerLimit || baseTypes.includes('int')) &&
        (!stringLimits.includes(line) || baseTypes.includes('string'))
      ).join('\n');
    }
    const inputFormat = inputFormats.find(format => description?.endsWith('\n\n' + format));
    if (inputFormat) {
      description = description.slice(0, -inputFormat.length).trimEnd().replace(/^## 문제\n\n/, '');
      const repeated = signature.returnType + ' 형식으로 ' + description;
      if (output?.startsWith(description + '\n\n')) {
        output = output.slice(description.length + 2);
      } else if (output === repeated || output === repeated + ' ' + tolerance) {
        output = '`' + signature.returnType + '` 형식으로 반환하세요.' + (output.endsWith(tolerance) ? ' ' + tolerance : '');
      }
    }
    if (!signature.parameters.length && input === '인자가 없는 함수를 호출합니다. 입력 JSON은 `{"args":[]}`입니다.') {
      input = '인자가 없습니다.';
    }
  }
  return [['문제 설명', description], ['입력', input], ['출력', output], ['제한', constraints]];
}
