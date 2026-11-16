{ 제출 main.pas는 프로그램 전체가 아닌 아래 형식의 함수 선언입니다.
  function sol(const matrix: TMatrix; const target: TTarget): Boolean; }
program CobwebFunctionHarness;
{$mode objfpc}
type
  TMatrix = array[0..14, 0..14] of LongInt;
  TTarget = array[0..1] of LongInt;

{$I /source/main.pas}

var
  matrix: TMatrix;
  target: TTarget;
  row, column: LongInt;
begin
  for row := 0 to 14 do
    for column := 0 to 14 do
      Read(matrix[row, column]);
  Read(target[0], target[1]);
  if sol(matrix, target) then
    WriteLn('true')
  else
    WriteLn('false');
end.
