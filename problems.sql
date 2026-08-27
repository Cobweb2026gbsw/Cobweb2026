-- "체인 레이저" 문제를 추가하는 개발용 시드 데이터입니다.
-- 실행 전 아래 두 값을 현재 DB의 운영자 계정과 사용 가능한 문제 번호로 맞춰야 합니다.
SET @chain_laser_author_id = 1;
SET @chain_laser_problem_number = 1000;

-- 이 문제는 표준 입력/출력 비교가 아니라 Python 함수 sol()의 반환값을 검사해야 하므로 SPECIAL 채점으로 등록합니다.
-- 같은 problem_number로 다시 실행해도 문제 본문과 설정을 최신 내용으로 갱신하고, 문제 ID를 @chain_laser_problem_id에 보관합니다.
INSERT INTO problems (
    problem_number,
    problem_title,
    rank_type,
    rank_int,
    description,
    input_description,
    output_description,
    constraints_text,
    time_limit_ms,
    memory_limit_mb,
    judge_type,
    status,
    visibility,
    author_id,
    group_id,
    source_name,
    source_url,
    published_at
) VALUES (
    @chain_laser_problem_number,
    '체인 레이저',
    'ELITE',
    5,
    CONCAT(
        '`0`과 `1`로 이루어진 `15 × 15` 크기의 행렬 `matrix`와 목표 좌표 `target`이 주어진다.', CHAR(10), CHAR(10),
        '레이저는 행렬의 중앙인 `(7, 7)`에서 최초로 발사된다. 레이저가 발사되면 발사점을 기준으로 상하좌우 네 방향으로 행렬의 끝까지 나아간다.', CHAR(10), CHAR(10),
        '레이저가 값이 `1`인 칸에 닿으면 그 칸은 새로운 발사점이 된다. 새로운 발사점에서도 상하좌우로 레이저가 발사되며, 이러한 연쇄 반응은 새로운 발사점이 더 이상 생기지 않을 때까지 계속된다.', CHAR(10), CHAR(10),
        '레이저는 값이 `1`인 칸을 만나도 멈추지 않고 그대로 통과한다. 같은 칸에서는 레이저를 한 번만 발사한다.', CHAR(10), CHAR(10),
        '레이저가 목표 좌표 `target`에 도달할 수 있으면 `True`, 도달할 수 없으면 `False`를 반환하라.'
    ),
    CONCAT(
        '입력은 다음 두 인자로 전달된다.', CHAR(10), CHAR(10),
        '```python', CHAR(10),
        'matrix: list[list[int]]', CHAR(10),
        'target: tuple[int, int]', CHAR(10),
        '```', CHAR(10), CHAR(10),
        '`matrix`는 15개의 행과 15개의 열을 가지는 정수 행렬이다. ',
        '`target`은 `(행 번호, 열 번호)` 형태의 목표 좌표다.'
    ),
    CONCAT(
        '다음 함수 형식에 맞춰 목표 좌표에 레이저가 도달할 수 있는지 반환한다.', CHAR(10), CHAR(10),
        '```python', CHAR(10),
        'def sol(', CHAR(10),
        '    matrix: list[list[int]],', CHAR(10),
        '    target: tuple[int, int]', CHAR(10),
        ') -> bool:', CHAR(10),
        '```', CHAR(10), CHAR(10),
        '도달할 수 있으면 `True`, 그렇지 않으면 `False`를 반환한다.'
    ),
    CONCAT(
        '- `matrix`의 크기는 항상 `15 × 15`이다.', CHAR(10),
        '- `matrix`의 모든 원소는 `0` 또는 `1`이다.', CHAR(10),
        '- `target`은 `(행 번호, 열 번호)` 형태의 유효한 좌표이다.', CHAR(10),
        '- 행과 열의 번호는 `0`부터 시작한다.', CHAR(10),
        '- 중앙 `(7, 7)`은 그곳의 값과 관계없이 최초 발사점이다.', CHAR(10),
        '- 목표 좌표의 값이 `0`이어도 레이저가 그 칸을 지나가면 도달한 것으로 판단한다.'
    ),
    1000,
    256,
    'SPECIAL',
    'PUBLISHED',
    'PUBLIC',
    @chain_laser_author_id,
    NULL,
    NULL,
    NULL,
    CURRENT_TIMESTAMP
)
ON DUPLICATE KEY UPDATE
    id = LAST_INSERT_ID(id),
    problem_title = VALUES(problem_title),
    rank_type = VALUES(rank_type),
    rank_int = VALUES(rank_int),
    description = VALUES(description),
    input_description = VALUES(input_description),
    output_description = VALUES(output_description),
    constraints_text = VALUES(constraints_text),
    time_limit_ms = VALUES(time_limit_ms),
    memory_limit_mb = VALUES(memory_limit_mb),
    judge_type = VALUES(judge_type),
    status = VALUES(status),
    visibility = VALUES(visibility),
    author_id = VALUES(author_id),
    group_id = VALUES(group_id),
    source_name = VALUES(source_name),
    source_url = VALUES(source_url),
    published_at = VALUES(published_at);

SET @chain_laser_problem_id = LAST_INSERT_ID();

-- 문제 공개 이력은 최초 한 번만 남깁니다.
INSERT INTO problem_status_history (problem_id, status, cause, checker_id)
SELECT
    @chain_laser_problem_id,
    'PUBLISHED',
    '체인 레이저 문제 최초 공개',
    @chain_laser_author_id
WHERE NOT EXISTS (
    SELECT 1
    FROM problem_status_history
    WHERE problem_id = @chain_laser_problem_id
      AND status = 'PUBLISHED'
      AND cause = '체인 레이저 문제 최초 공개'
);

-- 문제 화면에 표시할 공개 예제입니다.
INSERT INTO problem_examples (problem_id, input_text, output_text, explanation, display_order)
VALUES
(
    @chain_laser_problem_id,
    CONCAT(
        '```python', CHAR(10),
        'matrix = [[0] * 15 for _ in range(15)]', CHAR(10),
        'matrix[7][2] = 1', CHAR(10),
        'matrix[2][2] = 1', CHAR(10), CHAR(10),
        'target = (2, 12)', CHAR(10),
        '```'
    ),
    '```python\nTrue\n```',
    CONCAT(
        '중앙에서 발사된 레이저가 `(7, 2)`에 도달하고, `(7, 2)`에서 발사된 레이저가 `(2, 2)`에 도달한다. ',
        '마지막으로 `(2, 2)`에서 가로 방향으로 발사된 레이저가 목표 `(2, 12)`에 도달한다.', CHAR(10), CHAR(10),
        '```text', CHAR(10),
        '(7, 7) → (7, 2) → (2, 2) → (2, 12)', CHAR(10),
        '```'
    ),
    1
),
(
    @chain_laser_problem_id,
    CONCAT(
        '```python', CHAR(10),
        'matrix = [[0] * 15 for _ in range(15)]', CHAR(10),
        'matrix[7][2] = 1', CHAR(10),
        'matrix[2][2] = 1', CHAR(10), CHAR(10),
        'target = (3, 12)', CHAR(10),
        '```'
    ),
    '```python\nFalse\n```',
    '연쇄 반응으로 7번째 행, 7번째 열, 2번째 열, 2번째 행까지 레이저가 지나간다. 그러나 목표 `(3, 12)`가 속한 행과 열에는 레이저가 발사되지 않으므로 목표에 도달할 수 없다.',
    2
)
ON DUPLICATE KEY UPDATE
    input_text = VALUES(input_text),
    output_text = VALUES(output_text),
    explanation = VALUES(explanation);

-- 문제의 핵심 분류와 지원 언어입니다.
INSERT INTO tags (name)
VALUES ('구현'), ('그래프 탐색'), ('너비 우선 탐색')
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO problem_tags (problem_id, tag_id)
SELECT @chain_laser_problem_id, id
FROM tags
WHERE name IN ('구현', '그래프 탐색', '너비 우선 탐색')
ON DUPLICATE KEY UPDATE tag_id = VALUES(tag_id);

INSERT INTO problem_languages (problem_id, language)
VALUES (@chain_laser_problem_id, 'PYTHON')
ON DUPLICATE KEY UPDATE language = VALUES(language);
