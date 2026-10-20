-- 제목은 일반문제(scope 0)와 그룹별로 고유합니다. 기존 충돌은 임의 변경하지 않고 마이그레이션을 중단합니다.
-- 생성 컬럼과 UNIQUE를 하나의 원자적 ALTER로 추가해 중복 제목이 있을 때 부분 스키마만 남지 않게 합니다.
ALTER TABLE problems ADD COLUMN title_scope BIGINT
    GENERATED ALWAYS AS (COALESCE(group_id,0)) STORED,
    ADD UNIQUE INDEX uk_problems_title_scope (title_scope,problem_title);
ALTER TABLE problems ADD COLUMN requested_visibility ENUM('PUBLIC','PRIVATE','GROUP') NOT NULL DEFAULT 'PUBLIC';
UPDATE problems SET requested_visibility='GROUP' WHERE group_id IS NOT NULL;
UPDATE problems SET requested_visibility='PRIVATE'
WHERE group_id IS NULL AND status='PUBLISHED' AND visibility='PRIVATE';

-- 참고 정답은 채점 실행용 파일 경로가 아닌 비공개 편집 메타데이터입니다.
CREATE TABLE reference_solutions (
    problem_id BIGINT NOT NULL,
    language VARCHAR(30) NOT NULL,
    file_name VARCHAR(120) NOT NULL,
    source_code LONGTEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (problem_id,language),
    CONSTRAINT fk_reference_solutions_problem FOREIGN KEY (problem_id) REFERENCES problems(id) ON DELETE CASCADE
);

-- 이전 버전에서 이미 시드된 체인 레이저만 비파괴적으로 보강합니다. 사용자 문제는 건드리지 않습니다.
INSERT IGNORE INTO reference_solutions(problem_id,language,file_name,source_code)
SELECT p.id,'PYTHON','solution.py',CONCAT(
    'def sol(matrix, target):', CHAR(10),
    '    pending = [(7, 7)]', CHAR(10),
    '    seen = {(7, 7)}', CHAR(10),
    '    while pending:', CHAR(10),
    '        row, col = pending.pop()', CHAR(10),
    '        if row == target[0] or col == target[1]:', CHAR(10),
    '            return True', CHAR(10),
    '        for next_col in range(15):', CHAR(10),
    '            point = (row, next_col)', CHAR(10),
    '            if matrix[row][next_col] == 1 and point not in seen:', CHAR(10),
    '                seen.add(point)', CHAR(10),
    '                pending.append(point)', CHAR(10),
    '        for next_row in range(15):', CHAR(10),
    '            point = (next_row, col)', CHAR(10),
    '            if matrix[next_row][col] == 1 and point not in seen:', CHAR(10),
    '                seen.add(point)', CHAR(10),
    '                pending.append(point)', CHAR(10),
    '    return False', CHAR(10)
)
FROM problems p JOIN users u ON u.id=p.author_id
WHERE p.problem_number=1000 AND p.problem_title='체인 레이저' AND u.username='cobweb-system';
