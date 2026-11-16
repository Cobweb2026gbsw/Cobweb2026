const CHAIN_SIZE = 15;
const matrixInit = 'matrix = [[0] * 15 for _ in range(15)]';

function pythonExample(text) {
  return /^\x60{3}python[ \t]*\n([\s\S]*?)\n\x60{3}$/u.exec(text.replace(/\r\n/g, '\n').trim())?.[1];
}

function coordinate(value) {
  return Number.isInteger(value) && value >= 0 && value < CHAIN_SIZE;
}

function chainInput(input) {
  try {
    const value = JSON.parse(input);
    if (value && Object.keys(value).length === 2 && Array.isArray(value.matrix) &&
        value.matrix.length === CHAIN_SIZE &&
        value.matrix.every(row => Array.isArray(row) && row.length === CHAIN_SIZE && row.every(cell => cell === 0 || cell === 1)) &&
        Array.isArray(value.target) && value.target.length === 2 && value.target.every(coordinate)) {
      return { input };
    }
  } catch {
    // 기존 체인 레이저 설명은 JSON 대신 공개 Python 예제를 사용합니다.
  }

  const displayInput = pythonExample(input);
  if (displayInput == null) return null;
  const lines = displayInput.split('\n').map(line => line.trim()).filter(Boolean);
  if (lines[0] !== matrixInit) return null;
  const targetMatch = /^target = \((\d{1,2}), (\d{1,2})\)$/u.exec(lines.at(-1));
  if (!targetMatch) return null;
  const target = targetMatch.slice(1).map(Number);
  if (!target.every(coordinate)) return null;

  // 초기화, 칸 값 대입, 목표 좌표만 데이터로 읽습니다. 문제 설명은 실행하지 않습니다.
  const matrix = Array.from({length: CHAIN_SIZE}, () => Array(CHAIN_SIZE).fill(0));
  for (const line of lines.slice(1, -1)) {
    const cell = /^matrix\[(\d{1,2})\]\[(\d{1,2})\] = ([01])$/u.exec(line);
    if (!cell) return null;
    const [row, column, value] = cell.slice(1).map(Number);
    if (!coordinate(row) || !coordinate(column)) return null;
    matrix[row][column] = value;
  }
  return {input: JSON.stringify({matrix, target}), displayInput};
}

/** 공개 예제가 사용한 표기와 실행기가 받는 형식을 분리합니다. */
export function normalizePublicTestExample(problem, input, expected) {
  if (typeof input !== 'string' || typeof expected !== 'string') return null;
  if (problem?.functionAbi !== 'CHAIN_LASER_V1') return {input, expected};
  if (input.length > 65536 || expected.length > 64) return null;
  const output = (pythonExample(expected) ?? expected).trim().toLowerCase();
  if (output !== 'true' && output !== 'false') return null;
  const data = chainInput(input);
  return data ? {...data, expected: output} : null;
}
