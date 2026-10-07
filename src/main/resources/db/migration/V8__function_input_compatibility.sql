-- V6에서 언어가 확장되기 전에 작성된 FUNCTION 테스트는 Python 전용 형식일 수 있습니다.
-- 테스트나 언어 행을 삭제하지 않고, 실제 어댑터 ABI에 맞지 않는 문제만 Python 전용으로 표시합니다.
ALTER TABLE problems ADD COLUMN function_abi_compatible BOOLEAN NOT NULL DEFAULT FALSE;

SET @function_input_schema = '{"type":"object","required":["matrix","target"],"properties":{"matrix":{"type":"array","minItems":15,"maxItems":15,"items":{"type":"array","minItems":15,"maxItems":15,"items":{"type":"integer","enum":[0,1]}}},"target":{"type":"array","minItems":2,"maxItems":2,"items":{"type":"integer","minimum":0,"maximum":14}}}}';

UPDATE problems p SET function_abi_compatible=TRUE
WHERE p.execution_mode='FUNCTION'
  AND EXISTS (SELECT 1 FROM problem_test_cases active_case
              WHERE active_case.problem_id=p.id AND active_case.is_active=TRUE)
  AND NOT EXISTS (
    SELECT 1 FROM problem_test_cases t
    WHERE t.problem_id=p.id AND t.is_active=TRUE AND (
        CASE WHEN JSON_VALID(t.input_data)
             THEN JSON_SCHEMA_VALID(@function_input_schema,t.input_data)
             ELSE 0 END = 0
        OR BINARY t.expected_output NOT IN (BINARY 'true',BINARY 'false')
    )
  );
