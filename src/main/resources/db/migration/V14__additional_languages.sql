-- 기존 문제와 제출·정답 코드는 보존하고 새 언어의 허용 행만 추가합니다.
-- 공통 matrix/target ABI를 만족하지 않는 레거시 FUNCTION 문제는 계속 Python 전용입니다.
INSERT IGNORE INTO problem_languages(problem_id,language)
SELECT p.id,l.language FROM problems p CROSS JOIN (
    SELECT 'PASCAL' AS language UNION ALL SELECT 'PHP' UNION ALL SELECT 'DART'
) l WHERE p.execution_mode='STDIN'
       OR (p.execution_mode='FUNCTION' AND p.function_abi_compatible=TRUE);

-- 엄랭에는 함수 선언이 없으므로 sol 어댑터가 아닌 표준 입출력으로만 채점합니다.
INSERT IGNORE INTO problem_languages(problem_id,language)
SELECT p.id,'UMJUNSIK' FROM problems p WHERE p.execution_mode='STDIN';
