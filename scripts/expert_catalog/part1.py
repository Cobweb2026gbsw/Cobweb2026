"""EXPERT 001~032: 재귀·백트래킹과 DFS/BFS 문제 정의.

기준 풀이 문자열은 다른 파일 없이 제출 가능한 sol 함수의 본문이다.
오라클은 생성 시에만 사용하며, 기준 풀이와 별도의 구현으로 작성한다.
"""
from __future__ import annotations

import collections
import itertools
import math
import random


def _fib(n):
    a, b = 0, 1
    for _ in range(n):
        a, b = b, a + b
    return a


def _hanoi(n):
    # 재귀 기준 풀이와 달리 매번 가능한 최소 원판을 옮기는 반복 시뮬레이션.
    pegs = [list(range(n, 0, -1)), [], []]
    result = []
    pairs = [(0, 2), (0, 1), (1, 2)] if n % 2 else [(0, 1), (0, 2), (1, 2)]
    for step in range((1 << n) - 1):
        a, b = pairs[step % 3]
        if not pegs[a] or pegs[b] and pegs[b][-1] < pegs[a][-1]:
            a, b = b, a
        pegs[b].append(pegs[a].pop())
        result.append([a + 1, b + 1])
    return result


def _queens(n):
    answers = []
    def visit(path):
        if len(path) == n:
            answers.append(path[:])
            return
        row = len(path)
        for col in range(n):
            if all(old != col and abs(old - col) != row - r for r, old in enumerate(path)):
                visit(path + [col])
    visit([])
    return answers


def _operators(numbers, counts):
    symbols = ''.join(op * count for op, count in zip('+-*/', counts))
    outcomes = []
    for ops in set(itertools.permutations(symbols)):
        value = numbers[0]
        for op, number in zip(ops, numbers[1:]):
            if op == '+':
                value += number
            elif op == '-':
                value -= number
            elif op == '*':
                value *= number
            else:
                value = (abs(value) // number) * (-1 if value < 0 else 1)
        outcomes.append(value)
    return [max(outcomes), min(outcomes)]


def _card_primes(digits):
    values = {int(''.join(part)) for size in range(1, len(digits) + 1)
              for part in itertools.permutations(digits, size)}
    if not values:
        return 0
    limit = max(values)
    sieve = bytearray(b'\x01') * (limit + 1)
    sieve[:min(2, limit + 1)] = b'\x00' * min(2, limit + 1)
    for p in range(2, math.isqrt(limit) + 1):
        if sieve[p]:
            sieve[p * p::p] = b'\x00' * (((limit - p * p) // p) + 1)
    return sum(bool(sieve[value]) for value in values)


def _sudoku(board):
    # 기준 풀이의 비트마스크/MRV와 달리 첫 빈칸을 순서대로 탐색한다.
    result = [row[:] for row in board]
    def solve():
        for r in range(9):
            for c in range(9):
                if result[r][c] == 0:
                    used = set(result[r]) | {result[i][c] for i in range(9)}
                    used |= {result[i][j] for i in range(r // 3 * 3, r // 3 * 3 + 3)
                             for j in range(c // 3 * 3, c // 3 * 3 + 3)}
                    for value in range(1, 10):
                        if value not in used:
                            result[r][c] = value
                            if solve():
                                return True
                    result[r][c] = 0
                    return False
        return True
    if not solve():
        raise AssertionError('유효한 스도쿠 정답이 필요합니다')
    return result


def _adjacency(n, edges):
    adjacency = [set() for _ in range(n)]
    for a, b in edges:
        adjacency[a - 1].add(b - 1)
        adjacency[b - 1].add(a - 1)
    return [sorted(row) for row in adjacency]


def _graph_dfs(n, edges, start):
    adjacency = _adjacency(n, edges)
    stack, seen, answer = [start - 1], set(), []
    while stack:
        vertex = stack.pop()
        if vertex in seen:
            continue
        seen.add(vertex)
        answer.append(vertex + 1)
        stack.extend(reversed(adjacency[vertex]))
    return answer


def _graph_dist(n, edges, start):
    # 큐 기반 기준 풀이와 다른 동시 완화 방식의 최단 거리 오라클.
    distance = [-1] * n
    distance[start - 1] = 0
    for _ in range(n):
        previous = distance[:]
        for a, b in edges:
            for source, target in ((a - 1, b - 1), (b - 1, a - 1)):
                if previous[source] >= 0 and (distance[target] < 0 or distance[target] > previous[source] + 1):
                    distance[target] = previous[source] + 1
        if distance == previous:
            break
    return distance


def _graph_bfs(n, edges, start):
    adjacency = _adjacency(n, edges)
    frontier, seen, answer = [start - 1], {start - 1}, []
    while frontier:
        following = []
        for vertex in frontier:
            answer.append(vertex + 1)
            for neighbor in adjacency[vertex]:
                if neighbor not in seen:
                    seen.add(neighbor)
                    following.append(neighbor)
        frontier = following
    return answer


def _component_sizes(n, edges):
    # DSU로 연결 요소를 구해 DFS/BFS 기준 풀이와 대조한다.
    parent = list(range(n))
    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x
    for a, b in edges:
        a, b = find(a - 1), find(b - 1)
        parent[a] = b
    return sorted(collections.Counter(find(i) for i in range(n)).values())


def _grid_sizes(grid, diagonal=False):
    rows, cols = len(grid), len(grid[0])
    active = {(r, c) for r in range(rows) for c in range(cols) if grid[r][c] == 1}
    sizes = []
    while active:
        group = {min(active)}
        while True:
            expanded = group | {(r, c) for r, c in active for a, b in group
                                if max(abs(r - a), abs(c - b)) == 1
                                and (diagonal or abs(r - a) + abs(c - b) == 1)}
            if expanded == group:
                break
            group = expanded
        sizes.append(len(group))
        active -= group
    return sorted(sizes)


def _maze_path(grid):
    rows, cols = len(grid), len(grid[0])
    if not grid[0][0] or not grid[-1][-1]:
        return [[-1]]
    # 도착점에서 거리를 만든 뒤, 좌표 사전순으로 최단 경로를 복원한다.
    distances = [[-1] * cols for _ in range(rows)]
    distances[-1][-1] = 0
    frontier = [(rows - 1, cols - 1)]
    while frontier:
        next_frontier = []
        for r, c in frontier:
            for dr, dc in ((-1, 0), (0, -1), (0, 1), (1, 0)):
                nr, nc = r + dr, c + dc
                if 0 <= nr < rows and 0 <= nc < cols and grid[nr][nc] and distances[nr][nc] < 0:
                    distances[nr][nc] = distances[r][c] + 1
                    next_frontier.append((nr, nc))
        frontier = next_frontier
    if distances[0][0] < 0:
        return [[-1]]
    r, c = 0, 0
    answer = [[distances[0][0]], [0, 0]]
    while distances[r][c]:
        candidates = sorted((nr, nc) for nr, nc in ((r - 1, c), (r, c - 1), (r, c + 1), (r + 1, c))
                            if 0 <= nr < rows and 0 <= nc < cols and grid[nr][nc]
                            and distances[nr][nc] == distances[r][c] - 1)
        r, c = candidates[0]
        answer.append([r, c])
    return answer


def _hide_path(x, target, limit):
    # 역방향 BFS 후 작은 다음 위치부터 고르는 정규 최단 경로.
    distance = [-1] * (limit + 1)
    distance[target] = 0
    queue = collections.deque([target])
    while queue:
        value = queue.popleft()
        previous = [value - 1, value + 1]
        if value % 2 == 0:
            previous.append(value // 2)
        for before in previous:
            if 0 <= before <= limit and distance[before] < 0:
                distance[before] = distance[value] + 1
                queue.append(before)
    path = [x]
    while path[-1] != target:
        value = path[-1]
        path.append(min(after for after in (value - 1, value + 1, value * 2)
                        if 0 <= after <= limit and distance[after] == distance[value] - 1))
    return [[distance[x]], path]


def _tomato(grid):
    state = [row[:] for row in grid]
    rows, cols = len(state), len(state[0])
    days = 0
    while any(0 in row for row in state):
        changed = []
        for r in range(rows):
            for c in range(cols):
                if state[r][c] == 0 and any(0 <= nr < rows and 0 <= nc < cols and state[nr][nc] == 1
                                          for nr, nc in ((r - 1, c), (r, c - 1), (r, c + 1), (r + 1, c))):
                    changed.append((r, c))
        if not changed:
            return -1
        for r, c in changed:
            state[r][c] = 1
        days += 1
    return days


def _tomato3(layers, rows, cols):
    state = [layer[:] for layer in layers]
    depth, days = len(state), 0
    while any(0 in layer for layer in state):
        changes = []
        for z in range(depth):
            for r in range(rows):
                for c in range(cols):
                    if state[z][r * cols + c] != 0:
                        continue
                    for nz, nr, nc in ((z - 1, r, c), (z + 1, r, c), (z, r - 1, c),
                                       (z, r + 1, c), (z, r, c - 1), (z, r, c + 1)):
                        if 0 <= nz < depth and 0 <= nr < rows and 0 <= nc < cols and state[nz][nr * cols + nc] == 1:
                            changes.append((z, r * cols + c))
                            break
        if not changes:
            return -1
        for z, index in changes:
            state[z][index] = 1
        days += 1
    return days


def _laser(matrix, startRow, startCol):
    # 행/열 집합의 고정점으로 계산하여 큐 기반 발사점 구현과 대조한다.
    active_rows, active_cols = {startRow}, {startCol}
    while True:
        next_rows, next_cols = set(active_rows), set(active_cols)
        for r, row in enumerate(matrix):
            for c, value in enumerate(row):
                if value and (r in active_rows or c in active_cols):
                    next_rows.add(r)
                    next_cols.add(c)
        if (next_rows, next_cols) == (active_rows, active_cols):
            break
        active_rows, active_cols = next_rows, next_cols
    rows, cols = len(matrix), len(matrix[0])
    return len(active_rows) * cols + len(active_cols) * rows - len(active_rows) * len(active_cols)


def populate(add):
    rng = random.Random(2026102801)
    ints = lambda values: [[value] for value in values]
    arrays = [[], [0], [1], [-1], [1, 2], [3, 1, 2], [2, 2, 2], [-3, 0, 4],
              [1, -1, 1, -1], list(range(20)), list(range(20, -1, -1)), [2147483647, -2147483648]]
    add('재귀 팩토리얼', [('n', 'int')], 'int',
        '재귀 함수를 사용하여 `n!`을 반환하세요. `0! = 1`이며, `n ≥ 1`이면 `n! = n × (n-1)!`입니다.',
        '- 0 ≤ n ≤ 12',
        'if n <= 1:\n    return 1\nreturn n * sol(n - 1)', ints([5, 0] + [n for n in range(1, 13) if n != 5]), math.factorial)
    add('피보나치 수열 2', [('n', 'int')], 'int',
        '`F(0)=0`, `F(1)=1`, `F(n)=F(n-1)+F(n-2)`입니다. 재귀 호출의 결과를 메모이제이션하여 `F(n)`을 반환하세요.',
        '- 0 ≤ n ≤ 46',
        '''memo = {0: 0, 1: 1}
def fibonacci(value):
    if value not in memo:
        memo[value] = fibonacci(value - 1) + fibonacci(value - 2)
    return memo[value]
return fibonacci(n)''', ints([10, 0, 1, 2, 3, 4, 5, 15, 20, 30, 40, 46]), _fib)
    add('재귀로 배열 뒤집기', [('numbers', 'int[]')], 'int[]',
        '정수 배열 `numbers`의 원소 순서를 재귀적으로 뒤집어 반환하세요. 빈 배열의 결과는 빈 배열입니다.',
        '- 0 ≤ numbers 길이 ≤ 500\n- 각 원소는 부호 있는 32비트 정수',
        '''result = numbers[:]
def reverse(left, right):
    if left >= right:
        return
    result[left], result[right] = result[right], result[left]
    reverse(left + 1, right - 1)
reverse(0, len(result) - 1)
return result''', [[a] for a in arrays], lambda numbers: list(reversed(numbers)))
    add('재귀로 자릿수 합', [('n', 'int')], 'int',
        '0 이상 정수 `n`의 십진수 각 자릿수를 재귀 호출로 더해 반환하세요. `n=0`의 결과는 0입니다.',
        '- 0 ≤ n ≤ 2147483647',
        'if n < 10:\n    return n\nreturn n % 10 + sol(n // 10)',
        ints([0, 1, 9, 10, 11, 101, 909, 12345, 99999, 1000000000, 2147483647, 100000001]),
        lambda n: sum(map(int, str(n))))
    add('하노이의 탑', [('n', 'int')], 'int[][]',
        '작은 원판이 위에 놓인 원판 `n`개를 1번 기둥에서 3번 기둥으로 옮기세요. 한 번에 맨 위 원판 하나만 옮기며 큰 원판을 작은 원판 위에 놓을 수 없습니다. 최소 횟수로 옮기는 이동을 `[출발 기둥, 도착 기둥]` 배열로 순서대로 반환하세요. 기둥 번호는 1, 2, 3입니다. `n=0`이면 `[]`를 반환하세요.',
        '- 0 ≤ n ≤ 10\n- 결과의 길이는 2^n - 1',
        '''answer = []
def move(count, source, spare, target):
    if count == 0:
        return
    move(count - 1, source, target, spare)
    answer.append([source, target])
    move(count - 1, spare, source, target)
move(n, 1, 2, 3)
return answer''', ints([2, 3, 0, 1] + list(range(4, 11))), _hanoi)
    selections = [([3, 1, 2], 2), ([1], 0), ([1], 1), ([], 0), ([2, -1], 1),
                  ([2, -1], 2), ([4, 3, 2, 1], 3), ([9, 0, -2], 0),
                  ([9, 0, -2], 3), (list(range(5)), 2), (list(range(6)), 4), ([7, -7, 0, 2], 1)]
    add('순열 생성 1', [('numbers', 'int[]'), ('r', 'int')], 'int[][]',
        '서로 다른 정수 배열 `numbers`에서 중복 없이 `r`개를 골라 나열하는 모든 순열을 반환하세요. 각 순열은 정수 배열이며, 전체 결과는 원소의 정수값을 기준으로 사전순 정렬합니다. `r=0`이면 빈 순열 하나를 뜻하는 `[[]]`입니다.',
        '- 0 ≤ numbers 길이 ≤ 7\n- 0 ≤ r ≤ numbers 길이\n- -100 ≤ 각 원소 ≤ 100, 모든 원소는 서로 다름',
        '''values = sorted(numbers)
answer, path, used = [], [], [False] * len(values)
def visit():
    if len(path) == r:
        answer.append(path[:])
        return
    for i, value in enumerate(values):
        if not used[i]:
            used[i] = True
            path.append(value)
            visit()
            path.pop()
            used[i] = False
visit()
return answer''', [[a, r] for a, r in selections],
        lambda numbers, r: [list(row) for row in itertools.permutations(sorted(numbers), r)])
    add('조합 생성 1', [('numbers', 'int[]'), ('r', 'int')], 'int[][]',
        '서로 다른 정수 배열 `numbers`에서 `r`개를 고르는 모든 조합을 백트래킹으로 생성하세요. 한 조합의 원소는 오름차순이며 전체 결과도 정수값 기준 사전순입니다. 선택 순서만 다른 경우는 같은 조합입니다. `r=0`의 결과는 `[[]]`입니다.',
        '- 0 ≤ numbers 길이 ≤ 12\n- 0 ≤ r ≤ numbers 길이\n- -100 ≤ 각 원소 ≤ 100, 모든 원소는 서로 다름',
        '''values, answer, path = sorted(numbers), [], []
def visit(start):
    if len(path) == r:
        answer.append(path[:])
        return
    for i in range(start, len(values)):
        path.append(values[i])
        visit(i + 1)
        path.pop()
visit(0)
return answer''', [[a, r] for a, r in selections],
        lambda numbers, r: [list(row) for row in itertools.combinations(sorted(numbers), r)])
    repeated = [([2, 1], 2), ([1], 0), ([1], 1), ([1], 4), ([0, -1], 1), ([0, -1], 3),
                ([3, 2, 1], 2), ([3, 2, 1], 3), ([9, -9], 0), ([5, 1, 0, -2], 2), ([2], 5), ([0, 2], 5)]
    add('중복 순열', [('numbers', 'int[]'), ('r', 'int')], 'int[][]',
        '서로 다른 정수 `numbers`에서 같은 원소를 여러 번 골라도 되는 길이 `r`의 모든 순열을 반환하세요. 결과는 정수값 기준 사전순입니다. `r=0`이면 `[[]]`를 반환하세요.',
        '- 1 ≤ numbers 길이 ≤ 5\n- 0 ≤ r ≤ 5\n- -100 ≤ 각 원소 ≤ 100, 원소는 서로 다름',
        '''values, answer, path = sorted(numbers), [], []
def visit():
    if len(path) == r:
        answer.append(path[:])
        return
    for value in values:
        path.append(value)
        visit()
        path.pop()
visit()
return answer''', [[a, r] for a, r in repeated],
        lambda numbers, r: [list(row) for row in itertools.product(sorted(numbers), repeat=r)])
    add('중복 조합', [('numbers', 'int[]'), ('r', 'int')], 'int[][]',
        '서로 다른 정수 `numbers`에서 중복 선택을 허용하여 `r`개를 고르는 모든 조합을 반환하세요. 각 조합의 원소는 비내림차순이며 전체 결과는 정수값 기준 사전순입니다. `r=0`의 결과는 `[[]]`입니다.',
        '- 1 ≤ numbers 길이 ≤ 7\n- 0 ≤ r ≤ 7\n- -100 ≤ 각 원소 ≤ 100, 원소는 서로 다름',
        '''values, answer, path = sorted(numbers), [], []
def visit(start):
    if len(path) == r:
        answer.append(path[:])
        return
    for i in range(start, len(values)):
        path.append(values[i])
        visit(i)
        path.pop()
visit(0)
return answer''', [[a, r] for a, r in repeated],
        lambda numbers, r: [list(row) for row in itertools.combinations_with_replacement(sorted(numbers), r)])
    subsets = [[[], 0], [[0], 0], [[1], 1], [[1], 0], [[1, 1], 1], [[-1, 1], 0],
               [[0, 0, 0], 0], [[1, 2, 3], 3], [[-5, 2, 3, 5], 0], [[1, 2, 3], 100],
               [[-2, -2, 4, 0], 0], [[1, 1, 1, 1, 1], 3], [[0] * 20, 0]]
    add('부분집합의 합', [('numbers', 'int[]'), ('target', 'int')], 'int',
        '`numbers`의 원소를 일부 선택하여 합이 `target`인 비어 있지 않은 부분집합의 개수를 반환하세요. 같은 값을 가진 원소라도 인덱스가 다르면 서로 다른 선택입니다. 빈 부분집합은 `target=0`이어도 세지 않습니다.',
        '- 0 ≤ numbers 길이 ≤ 20\n- -100 ≤ 각 원소 ≤ 100\n- -2000 ≤ target ≤ 2000',
        '''answer = 0
def visit(index, total, picked):
    nonlocal answer
    if index == len(numbers):
        if picked and total == target:
            answer += 1
        return
    visit(index + 1, total, picked)
    visit(index + 1, total + numbers[index], True)
visit(0, 0, False)
return answer''', subsets,
        lambda numbers, target: sum(sum(part) == target for length in range(1, len(numbers) + 1)
                                    for part in itertools.combinations(numbers, length)))
    queen_body = '''mask = (1 << n) - 1
def visit(columns, left, right):
    if columns == mask:
        return 1
    available, count = mask & ~(columns | left | right), 0
    while available:
        bit = available & -available
        available -= bit
        count += visit(columns | bit, (left | bit) << 1, (right | bit) >> 1)
    return count
return visit(0, 0, 0)'''
    add('N-Queen', [('n', 'int')], 'int',
        '`n×n` 체스판에 퀸 `n`개를 서로 공격할 수 없도록 배치하는 경우의 수를 반환하세요. 퀸은 같은 행·열·대각선으로 공격합니다. 회전·대칭으로 같은 모양이어도 위치가 다르면 다른 배치입니다. `n=0`이면 빈 배치 하나로 보아 1을 반환하세요.',
        '- 0 ≤ n ≤ 12', queen_body, ints([4, 5, 0, 1, 2, 3] + list(range(6, 13))), lambda n: len(_queens(n)))
    add('N-Queen 1', [('n', 'int')], 'int[][]',
        '`n×n` 체스판에 퀸 `n`개를 공격하지 못하도록 놓는 모든 배치를 반환하세요. 배치 하나는 각 행에 놓은 퀸의 0-based 열 번호 배열입니다. 전체 배치는 정수값 기준 사전순으로 정렬하며 회전·대칭 배치를 합치지 않습니다. `n=0`이면 `[[]]`, 불가능하면 `[]`입니다.',
        '- 0 ≤ n ≤ 10',
        '''mask, answer, path = (1 << n) - 1, [], []
def visit(columns, left, right):
    if len(path) == n:
        answer.append(path[:])
        return
    available = mask & ~(columns | left | right)
    while available:
        bit = available & -available
        available -= bit
        path.append(bit.bit_length() - 1)
        visit(columns | bit, (left | bit) << 1, (right | bit) >> 1)
        path.pop()
visit(0, 0, 0)
return answer''', ints([4, 2, 0, 1, 3] + list(range(5, 11))), _queens)
    passwords = [['atcisw', 4], ['abc', 3], ['aeiou', 3], ['bcdfg', 3], ['abcde', 3],
                 ['zyxwvu', 4], ['a', 1], ['ab', 2], ['acdefgh', 5], ['qwerty', 3],
                 ['abefghi', 4], ['abcdefghijkl', 6]]
    add('암호 만들기', [('letters', 'string'), ('length', 'int')], 'string[]',
        '서로 다른 영문 소문자 `letters` 중 `length`개를 골라 암호를 만드세요. 문자는 암호 안에서 오름차순이며 최소 한 개의 모음(`a,e,i,o,u`)과 최소 두 개의 자음을 포함해야 합니다. 가능한 암호를 사전순으로 반환하며 없으면 `[]`입니다.',
        '- 1 ≤ letters 길이 ≤ 12\n- 1 ≤ length ≤ letters 길이\n- letters의 모든 문자는 서로 다름',
        '''values, answer, path = sorted(letters), [], []
def visit(start, vowels):
    if len(path) == length:
        if vowels >= 1 and length - vowels >= 2:
            answer.append(''.join(path))
        return
    for i in range(start, len(values)):
        path.append(values[i])
        visit(i + 1, vowels + (values[i] in 'aeiou'))
        path.pop()
visit(0, 0)
return answer''', passwords,
        lambda letters, length: [''.join(part) for part in itertools.combinations(sorted(letters), length)
                                 if 1 <= sum(ch in 'aeiou' for ch in part) <= length - 2])
    operator_cases = [[[5, 6], [1, 0, 0, 0]], [[5, 6], [0, 1, 0, 0]], [[5, 6], [0, 0, 1, 0]],
                      [[5, 6], [0, 0, 0, 1]], [[1, 2, 3], [1, 1, 0, 0]],
                      [[1, 8, 3], [0, 1, 0, 1]], [[9, 2, 3, 4], [1, 0, 1, 1]],
                      [[2, 3, 4, 5], [0, 2, 1, 0]], [[1, 2, 3, 4, 5], [1, 1, 1, 1]],
                      [[9] * 8, [0, 0, 7, 0]], [[1, 2, 3, 4], [0, 0, 0, 3]],
                      [[8, 3, 2, 5, 4, 1], [2, 1, 1, 1]]]
    add('연산자 끼워 넣기', [('numbers', 'int[]'), ('counts', 'int[]')], 'int[]',
        '`numbers` 사이에 모든 연산자를 하나씩 넣어 얻을 수 있는 결과의 `[최댓값, 최솟값]`을 반환하세요. `counts`는 순서대로 `+,-,*,/`의 개수입니다. 연산자 우선순위 없이 왼쪽부터 계산하며 정수 나눗셈은 0 방향으로 소수 부분을 버립니다(예: `-7/3=-2`). 숫자의 순서는 바꾸지 않습니다.',
        '- 2 ≤ numbers 길이 ≤ 9\n- 1 ≤ 각 numbers 원소 ≤ 9\n- counts 길이는 4, 각 원소는 0 이상이며 합은 numbers 길이 - 1\n- 모든 계산 결과는 부호 있는 32비트 범위',
        '''best, worst = -2147483648, 2147483647
remaining = counts[:]
def visit(index, value):
    nonlocal best, worst
    if index == len(numbers):
        best, worst = max(best, value), min(worst, value)
        return
    number = numbers[index]
    for operation in range(4):
        if remaining[operation]:
            remaining[operation] -= 1
            if operation == 0:
                next_value = value + number
            elif operation == 1:
                next_value = value - number
            elif operation == 2:
                next_value = value * number
            else:
                next_value = (abs(value) // number) * (-1 if value < 0 else 1)
            visit(index + 1, next_value)
            remaining[operation] += 1
visit(1, numbers[0])
return [best, worst]''', operator_cases, _operators)
    add('숫자 조합 소수', [('digits', 'string')], 'int',
        '숫자 카드가 담긴 문자열 `digits`에서 한 장 이상을 골라 순서를 바꿔 만들 수 있는 서로 다른 소수의 개수를 반환하세요. 카드 한 장은 한 수를 만들 때 한 번만 사용합니다. 앞의 0은 허용하지만 같은 정수는 한 번만 셉니다(예: `01`과 `1`은 같은 수). 0과 1은 소수가 아닙니다.',
        '- 1 ≤ digits 길이 ≤ 7\n- 각 문자는 0~9',
        '''import math
values, used = set(), [False] * len(digits)
def visit(value, depth):
    if depth:
        values.add(value)
    for i, char in enumerate(digits):
        if not used[i]:
            used[i] = True
            visit(value * 10 + int(char), depth + 1)
            used[i] = False
visit(0, 0)
return sum(value >= 2 and all(value % d for d in range(2, math.isqrt(value) + 1)) for value in values)''',
        [[text] for text in ['17', '011', '0', '1', '2', '00', '22', '123', '101', '999', '0137', '0000002', '7654321']],
        _card_primes)
    solved = [[(r * 3 + r // 3 + c) % 9 + 1 for c in range(9)] for r in range(9)]
    sudoku_cases = [[solved]]
    for removed in range(1, 10):
        board = [row[:] for row in solved]
        # 열마다 빈칸이 최대 하나이므로 열 제약만으로도 정답이 유일하다.
        for index in range(removed):
            board[index // 9][index % 9] = 0
        sudoku_cases.append([board])
    sudoku_cases.append([[[5, 3, 0, 0, 7, 0, 0, 0, 0], [6, 0, 0, 1, 9, 5, 0, 0, 0],
                          [0, 9, 8, 0, 0, 0, 0, 6, 0], [8, 0, 0, 0, 6, 0, 0, 0, 3],
                          [4, 0, 0, 8, 0, 3, 0, 0, 1], [7, 0, 0, 0, 2, 0, 0, 0, 6],
                          [0, 6, 0, 0, 0, 0, 2, 8, 0], [0, 0, 0, 4, 1, 9, 0, 0, 5],
                          [0, 0, 0, 0, 8, 0, 0, 7, 9]]])
    sudoku_cases = [sudoku_cases[-1], sudoku_cases[-2]] + sudoku_cases[:-2]
    add('스도쿠 해결', [('board', 'int[][]')], 'int[][]',
        '9×9 스도쿠 `board`의 빈칸(0)을 1~9로 채워 완성된 행렬을 반환하세요. 각 행, 각 열, 그리고 3×3 구역에는 1~9가 정확히 한 번씩 있어야 합니다. 주어진 숫자는 바꿀 수 없으며 정답이 정확히 하나인 입력만 주어집니다.',
        '- board는 항상 9×9\n- 각 원소는 0~9\n- 주어진 숫자에 모순이 없고 해가 유일함',
        '''result = [row[:] for row in board]
rows, cols, boxes = [0] * 9, [0] * 9, [0] * 9
empty = []
for r in range(9):
    for c in range(9):
        value = result[r][c]
        if value:
            bit = 1 << value
            rows[r] |= bit
            cols[c] |= bit
            boxes[r // 3 * 3 + c // 3] |= bit
        else:
            empty.append((r, c))
def solve(index):
    if index == len(empty):
        return True
    best = min(range(index, len(empty)), key=lambda i: (1022 & ~(rows[empty[i][0]] | cols[empty[i][1]] | boxes[empty[i][0] // 3 * 3 + empty[i][1] // 3])).bit_count())
    empty[index], empty[best] = empty[best], empty[index]
    r, c = empty[index]
    box = r // 3 * 3 + c // 3
    options = 1022 & ~(rows[r] | cols[c] | boxes[box])
    while options:
        bit = options & -options
        options -= bit
        result[r][c] = bit.bit_length() - 1
        rows[r] |= bit
        cols[c] |= bit
        boxes[box] |= bit
        if solve(index + 1):
            return True
        rows[r] ^= bit
        cols[c] ^= bit
        boxes[box] ^= bit
    result[r][c] = 0
    empty[index], empty[best] = empty[best], empty[index]
    return False
solve(0)
return result''', sudoku_cases, _sudoku)

    graph_cases = [[1, [], 1], [2, [], 1], [2, [[1, 2]], 2], [3, [[1, 2], [2, 3]], 1],
                   [4, [[1, 2], [1, 3], [2, 4], [3, 4]], 1],
                   [5, [[1, 2], [2, 3], [4, 5]], 4], [4, [[1, 2], [2, 3], [3, 1]], 4],
                   [5, [[1, 5], [1, 4], [1, 3], [1, 2]], 1]]
    for n in range(6, 12):
        edges = [[a, b] for a in range(1, n + 1) for b in range(a + 1, n + 1) if rng.random() < .23]
        graph_cases.append([n, edges, rng.randint(1, n)])
    graph_cases = [graph_cases[4], graph_cases[5]] + graph_cases[:4] + graph_cases[6:]
    graph_rules = '- 1 ≤ n ≤ 500\n- 정점 번호는 1~n, 1 ≤ start ≤ n\n- edges의 각 행은 [u,v], 자기 간선·중복 간선 없는 무방향 그래프\n- 간선 수 ≤ 10000'
    graph_setup = '''adjacency = [[] for _ in range(n + 1)]
for u, v in edges:
    adjacency[u].append(v)
    adjacency[v].append(u)
for row in adjacency:
    row.sort()
'''
    add('그래프 DFS', [('n', 'int'), ('edges', 'int[][]'), ('start', 'int')], 'int[]',
        '무방향 그래프에서 `start`부터 깊이 우선 탐색(DFS)으로 처음 방문한 정점 번호를 순서대로 반환하세요. 아직 방문하지 않은 인접 정점 중 번호가 작은 정점을 먼저 재귀적으로 탐색합니다. 시작점과 연결되지 않은 정점은 결과에 넣지 않습니다.',
        graph_rules, graph_setup + '''answer, seen = [], set()
def visit(vertex):
    seen.add(vertex)
    answer.append(vertex)
    for neighbor in adjacency[vertex]:
        if neighbor not in seen:
            visit(neighbor)
visit(start)
return answer''', graph_cases, _graph_dfs)
    add('그래프 BFS', [('n', 'int'), ('edges', 'int[][]'), ('start', 'int')], 'int[]',
        '무방향 그래프에서 `start`부터 너비 우선 탐색(BFS)으로 방문한 정점 번호를 반환하세요. 정점을 큐에 넣는 즉시 방문 처리하고 각 정점의 인접 정점을 번호가 작은 순서로 큐에 넣습니다. 시작점과 연결되지 않은 정점은 제외합니다.',
        graph_rules, graph_setup + '''from collections import deque
queue, seen, answer = deque([start]), {start}, []
while queue:
    vertex = queue.popleft()
    answer.append(vertex)
    for neighbor in adjacency[vertex]:
        if neighbor not in seen:
            seen.add(neighbor)
            queue.append(neighbor)
return answer''', graph_cases, _graph_bfs)
    component_body = graph_setup + '''seen, sizes = set(), []
for start in range(1, n + 1):
    if start in seen:
        continue
    stack, size = [start], 0
    seen.add(start)
    while stack:
        vertex = stack.pop()
        size += 1
        for neighbor in adjacency[vertex]:
            if neighbor not in seen:
                seen.add(neighbor)
                stack.append(neighbor)
    sizes.append(size)
'''
    components = [[n, edges] for n, edges, _ in graph_cases]
    component_rules = graph_rules.replace(', 1 ≤ start ≤ n', '')
    add('연결 요소의 개수', [('n', 'int'), ('edges', 'int[][]')], 'int',
        '무방향 그래프의 연결 요소 개수를 반환하세요. 연결 요소는 간선을 따라 서로 도달할 수 있는 정점의 최대 집합이며, 간선이 없는 정점 하나도 연결 요소 하나입니다.',
        component_rules, component_body + 'return len(sizes)', components, lambda n, edges: len(_component_sizes(n, edges)))
    add('연결 요소의 크기', [('n', 'int'), ('edges', 'int[][]')], 'int[]',
        '무방향 그래프의 각 연결 요소에 포함된 정점 수를 오름차순으로 반환하세요. 고립된 정점의 크기는 1이며 같은 크기의 요소도 각각 기록합니다.',
        component_rules, component_body + 'return sorted(sizes)', components, _component_sizes)

    grids = [[[1]], [[0]], [[1, 1]], [[1, 0]], [[1], [1]], [[1, 1], [1, 1]],
             [[1, 0], [0, 1]], [[0, 1], [1, 1]], [[1, 1], [1, 0]],
             [[1, 0, 1], [1, 1, 1], [0, 0, 1]], [[1, 1, 1], [0, 0, 1], [1, 1, 1]],
             [[1, 0, 1, 1], [1, 1, 0, 1], [0, 1, 1, 1]],
             [[rng.randrange(2) for _ in range(6)] for _ in range(5)]]
    grids = [grids[9], grids[6]] + grids[:6] + grids[7:9] + grids[10:]
    grid_rules = '- 1 ≤ 행 수, 열 수 ≤ 100\n- grid는 직사각형이며 원소는 0 또는 1'
    maze_body = '''from collections import deque
rows, cols = len(grid), len(grid[0])
if not grid[0][0] or not grid[-1][-1]:
    return [[-1]]
parent = {(0, 0): None}
queue = deque([(0, 0)])
while queue:
    r, c = queue.popleft()
    if (r, c) == (rows - 1, cols - 1):
        path, current = [], (r, c)
        while current is not None:
            path.append(list(current))
            current = parent[current]
        path.reverse()
        return [[len(path) - 1]] + path
    for nr, nc in ((r - 1, c), (r, c - 1), (r, c + 1), (r + 1, c)):
        if 0 <= nr < rows and 0 <= nc < cols and grid[nr][nc] and (nr, nc) not in parent:
            parent[(nr, nc)] = (r, c)
            queue.append((nr, nc))
return [[-1]]'''
    add('미로 최단 경로', [('grid', 'int[][]')], 'int',
        '`grid`에서 1은 통로, 0은 벽입니다. 왼쪽 위 `(0,0)`에서 오른쪽 아래 `(행 수-1, 열 수-1)`까지 상하좌우로 이동할 때 최소 이동 횟수를 반환하세요. 시작 칸은 이동 횟수에 포함하지 않습니다. 시작·도착이 벽이거나 갈 수 없으면 -1입니다.',
        grid_rules, 'def path():\n' + '\n'.join('    ' + line for line in maze_body.splitlines()) + '\nreturn path()[0][0]',
        [[grid] for grid in grids], lambda grid: _maze_path(grid)[0][0])
    add('미로 최단 경로 1', [('grid', 'int[][]')], 'int[][]',
        '`grid`에서 1은 통로, 0은 벽입니다. `(0,0)`에서 `(행 수-1, 열 수-1)`까지 상하좌우로 가는 최단 경로를 `[[최소 이동 횟수], [시작 행, 시작 열], ..., [도착 행, 도착 열]]`로 반환하세요. 최단 경로가 여러 개면 좌표열을 `(행,열)` 사전순으로 비교하여 가장 작은 경로를 선택하세요. 이는 BFS에서 위·왼쪽·오른쪽·아래 순서로 이웃을 방문한 경로입니다. 갈 수 없거나 양 끝이 벽이면 `[[-1]]`입니다.',
        grid_rules, maze_body, [[grid] for grid in grids], _maze_path)
    grid_components = '''rows, cols = len(grid), len(grid[0])
seen, sizes = set(), []
for r in range(rows):
    for c in range(cols):
        if grid[r][c] == 0 or (r, c) in seen:
            continue
        stack, size = [(r, c)], 0
        seen.add((r, c))
        while stack:
            a, b = stack.pop()
            size += 1
            for dr, dc in DIRECTIONS:
                nr, nc = a + dr, b + dc
                if 0 <= nr < rows and 0 <= nc < cols and grid[nr][nc] and (nr, nc) not in seen:
                    seen.add((nr, nc))
                    stack.append((nr, nc))
        sizes.append(size)
'''
    four = '((-1, 0), (0, -1), (0, 1), (1, 0))'
    eight = 'tuple((dr, dc) for dr in (-1, 0, 1) for dc in (-1, 0, 1) if dr or dc)'
    add('단지 번호 붙이기', [('grid', 'int[][]')], 'int[]',
        '1은 집, 0은 빈칸인 격자에서 상하좌우로 연결된 집들을 한 단지로 묶으세요. 각 단지의 집 수를 오름차순으로 반환하며 집이 없으면 `[]`입니다. 대각선으로만 닿은 집은 같은 단지가 아닙니다.',
        grid_rules, grid_components.replace('DIRECTIONS', four) + 'return sorted(sizes)',
        [[grid] for grid in grids], _grid_sizes)
    add('섬의 개수', [('grid', 'int[][]')], 'int',
        '1은 육지, 0은 바다인 지도에서 상하좌우로 연결된 육지 영역(섬)의 개수를 반환하세요. 대각선은 연결로 보지 않습니다.',
        grid_rules, grid_components.replace('DIRECTIONS', four) + 'return len(sizes)',
        [[grid] for grid in grids], lambda grid: len(_grid_sizes(grid)))
    add('섬의 개수 1', [('grid', 'int[][]')], 'int',
        '1은 육지, 0은 바다인 지도에서 상하좌우 및 대각선을 포함한 8방향으로 연결된 육지 영역의 개수를 반환하세요.',
        grid_rules, grid_components.replace('DIRECTIONS', eight) + 'return len(sizes)',
        [[grid] for grid in grids], lambda grid: len(_grid_sizes(grid, True)))
    add('바이러스 전파', [('n', 'int'), ('edges', 'int[][]'), ('start', 'int')], 'int',
        '컴퓨터 번호가 1~n인 무방향 네트워크에서 `start`가 처음 감염됩니다. 연결을 따라 전파되어 추가 감염되는 컴퓨터 수를 반환하세요. 최초 감염 컴퓨터 `start` 자체는 세지 않습니다.',
        graph_rules, graph_setup + '''seen, stack = {start}, [start]
while stack:
    vertex = stack.pop()
    for neighbor in adjacency[vertex]:
        if neighbor not in seen:
            seen.add(neighbor)
            stack.append(neighbor)
return len(seen) - 1''', graph_cases,
        lambda n, edges, start: sum(distance >= 0 for distance in _graph_dist(n, edges, start)) - 1)
    add('최단 거리 배열', [('n', 'int'), ('edges', 'int[][]'), ('start', 'int')], 'int[]',
        '무방향 무가중치 그래프에서 `start`부터 모든 정점까지의 최소 간선 수를 정점 1~n 순서로 반환하세요. 시작 정점의 거리는 0, 도달할 수 없는 정점의 거리는 -1입니다. 반환 배열 인덱스 0은 정점 1에 대응합니다.',
        graph_rules, graph_setup + '''from collections import deque
distances = [-1] * (n + 1)
distances[start] = 0
queue = deque([start])
while queue:
    vertex = queue.popleft()
    for neighbor in adjacency[vertex]:
        if distances[neighbor] < 0:
            distances[neighbor] = distances[vertex] + 1
            queue.append(neighbor)
return distances[1:]''', graph_cases, _graph_dist)
    hide_cases = [[5, 17, 40], [0, 0, 0], [0, 1, 1], [1, 0, 1], [1, 2, 2], [2, 1, 2],
                  [3, 8, 20], [8, 3, 20], [4, 16, 30], [10, 11, 30], [7, 7, 20], [0, 20, 30], [19, 0, 20]]
    hide_body = '''from collections import deque
parent = [-2] * (limit + 1)
parent[x] = -1
queue = deque([x])
while queue:
    value = queue.popleft()
    if value == target:
        path = []
        while value != -1:
            path.append(value)
            value = parent[value]
        path.reverse()
        return [[len(path) - 1], path]
    for after in sorted(set((value - 1, value + 1, value * 2))):
        if 0 <= after <= limit and parent[after] == -2:
            parent[after] = value
            queue.append(after)
return [[-1], []]'''
    hide_rules = '- 0 ≤ limit ≤ 100000\n- 0 ≤ x, target ≤ limit\n- 이동은 0~limit 범위 안에서만 가능'
    add('숨바꼭질', [('x', 'int'), ('target', 'int'), ('limit', 'int')], 'int',
        '정수 위치 `x`에서 한 번에 `현재 위치-1`, `현재 위치+1`, `현재 위치×2`로 이동할 수 있습니다. 위치는 0~`limit`만 허용됩니다. `target`까지의 최소 이동 횟수를 반환하세요. 이미 목표에 있으면 0입니다.',
        hide_rules, 'def path():\n' + '\n'.join('    ' + line for line in hide_body.splitlines()) + '\nreturn path()[0][0]',
        hide_cases, lambda x, target, limit: _hide_path(x, target, limit)[0][0])
    add('숨바꼭질 1', [('x', 'int'), ('target', 'int'), ('limit', 'int')], 'int[][]',
        '`x`에서 `-1`, `+1`, `×2` 이동으로 `target`에 가는 최단 경로를 `[[이동 횟수], [시작 위치, ..., 도착 위치]]`로 반환하세요. 모든 위치는 0~`limit`이어야 합니다. 최단 경로가 여러 개면 방문 위치 배열이 정수값 기준 사전순으로 가장 작은 것을 선택하세요.',
        hide_rules, hide_body, hide_cases, _hide_path)
    tomatoes = [[[1]], [[0]], [[-1]], [[1, 0]], [[1, -1, 0]], [[0, 1, 0]],
                [[1, 0], [0, 0]], [[1, -1], [-1, 0]], [[-1, -1], [-1, -1]],
                [[1, 1], [1, 1]], [[0, 0, 0], [0, 1, 0], [0, 0, 0]],
                [[1, 0, -1, 0], [0, 0, -1, 1]], [[1, 0], [-1, 0], [0, 0]]]
    tomatoes = [tomatoes[6], tomatoes[7]] + tomatoes[:6] + tomatoes[8:]
    tomato_rules = '- 1 ≤ 행 수, 열 수 ≤ 100\n- tomatoes는 직사각형이며 원소는 -1, 0, 1 중 하나'
    add('토마토 익히기', [('tomatoes', 'int[][]')], 'int',
        '상자 `tomatoes`에서 1은 익은 토마토, 0은 익지 않은 토마토, -1은 빈칸입니다. 하루가 지날 때 그날 시작 시점에 익어 있던 토마토가 상하좌우의 익지 않은 토마토를 익힙니다. 모든 토마토가 익는 최소 일수를 반환하세요. 처음부터 익지 않은 토마토가 없으면(전부 빈칸인 경우 포함) 0, 끝내 익힐 수 없으면 -1입니다.',
        tomato_rules, '''from collections import deque
rows, cols = len(tomatoes), len(tomatoes[0])
state = [row[:] for row in tomatoes]
queue, remaining = deque(), 0
for r in range(rows):
    for c in range(cols):
        if state[r][c] == 1:
            queue.append((r, c, 0))
        elif state[r][c] == 0:
            remaining += 1
days = 0
while queue:
    r, c, day = queue.popleft()
    days = max(days, day)
    for nr, nc in ((r - 1, c), (r, c - 1), (r, c + 1), (r + 1, c)):
        if 0 <= nr < rows and 0 <= nc < cols and state[nr][nc] == 0:
            state[nr][nc] = 1
            remaining -= 1
            queue.append((nr, nc, day + 1))
return -1 if remaining else days''', [[box] for box in tomatoes], _tomato)
    tomato3_cases = [[[1], [0]], [[0], [1]], [[1], [-1], [0]], [[-1], [-1]], [[0], [0]]]
    tomato3_cases = [[layers, 1, 1] for layers in tomato3_cases]
    tomato3_cases += [[[[1, 0, 0, 0]], 2, 2], [[[1, 0, 0, 0], [0, 0, 0, 0]], 2, 2],
                      [[[1, -1, -1, 0], [-1, -1, -1, -1]], 2, 2],
                      [[[1, 1, 1, 1], [1, 1, 1, 1]], 2, 2],
                      [[[0, 0, 0, 0, 0, 1], [0, 0, 0, 0, 0, 0]], 2, 3],
                      [[[-1, 0, 0], [1, 0, -1], [0, 0, 0]], 1, 3],
                      [[[1, 0, -1, 0, 0, 0], [0, 0, 0, -1, 0, 1]], 3, 2]]
    tomato3_cases = [tomato3_cases[6], tomato3_cases[7]] + tomato3_cases[:6] + tomato3_cases[8:]
    add('토마토 익히기 1', [('layers', 'int[][]'), ('rows', 'int'), ('cols', 'int')], 'int',
        '3차원 상자의 토마토가 위·아래·왼쪽·오른쪽·앞·뒤 6방향으로 하루마다 익습니다. 1은 익음, 0은 익지 않음, -1은 빈칸입니다. `layers[z]`는 z번째 층의 `rows×cols` 격자를 행 우선으로 편 배열이며 좌표 `(z,r,c)`의 값은 `layers[z][r*cols+c]`입니다. 모든 토마토가 익는 최소 일수를 반환하세요. 처음부터 익지 않은 토마토가 없으면 0, 불가능하면 -1입니다. 같은 날 새로 익은 토마토는 다음 날부터 전파합니다.',
        '- 1 ≤ 층 수, rows, cols ≤ 30\n- 각 layers 원소 배열의 길이는 rows*cols\n- 원소는 -1, 0, 1 중 하나',
        '''from collections import deque
depth, state = len(layers), [layer[:] for layer in layers]
queue, remaining = deque(), 0
for z in range(depth):
    for r in range(rows):
        for c in range(cols):
            value = state[z][r * cols + c]
            if value == 1:
                queue.append((z, r, c, 0))
            elif value == 0:
                remaining += 1
days = 0
while queue:
    z, r, c, day = queue.popleft()
    days = max(days, day)
    for nz, nr, nc in ((z - 1, r, c), (z + 1, r, c), (z, r - 1, c), (z, r + 1, c), (z, r, c - 1), (z, r, c + 1)):
        if 0 <= nz < depth and 0 <= nr < rows and 0 <= nc < cols and state[nz][nr * cols + nc] == 0:
            state[nz][nr * cols + nc] = 1
            remaining -= 1
            queue.append((nz, nr, nc, day + 1))
return -1 if remaining else days''', tomato3_cases, _tomato3)
    laser_cases = [[[[0]], 0, 0], [[[1]], 0, 0], [[[0, 0, 0]], 0, 1], [[[0], [0], [0]], 1, 0],
                   [[[0, 0], [0, 0]], 0, 0], [[[0, 1], [0, 0]], 0, 0],
                   [[[0, 0], [0, 1]], 0, 0], [[[1, 1], [1, 1]], 1, 1],
                   [[[0, 1, 0], [0, 0, 0], [0, 1, 1]], 1, 1],
                   [[[0, 0, 0], [1, 0, 0], [1, 0, 1]], 1, 2],
                   [[[1, 0, 1, 0], [0, 1, 0, 0], [0, 0, 1, 1]], 0, 1],
                   [[[rng.randrange(2) for _ in range(8)] for _ in range(6)], 3, 4]]
    laser_cases = [laser_cases[9], laser_cases[6]] + laser_cases[:6] + laser_cases[7:9] + laser_cases[10:]
    add('연쇄 레이저 1', [('matrix', 'int[][]'), ('startRow', 'int'), ('startCol', 'int')], 'int',
        '0과 1로 이루어진 가변 크기 직사각형 `matrix`에서 `(startRow,startCol)`을 최초 발사점으로 삼습니다. 발사점에서 상하좌우 끝까지 레이저가 나아가며 1인 칸을 만나도 멈추지 않고 통과합니다. 레이저가 닿은 1인 칸은 새로운 발사점이 되어 연쇄 발사하되 같은 칸에서는 한 번만 발사합니다. 최초 발사점은 값과 관계없이 발사하고 도달한 칸에 포함합니다. 연쇄가 끝난 후 레이저가 한 번 이상 도달한 서로 다른 칸 수를 반환하세요. 값이 0인 칸도 통과하면 셉니다.',
        '- 1 ≤ 행 수, 열 수 ≤ 100\n- matrix는 직사각형, 원소는 0 또는 1\n- 0 ≤ startRow < 행 수, 0 ≤ startCol < 열 수',
        '''from collections import deque
rows, cols = len(matrix), len(matrix[0])
queue, fired = deque([(startRow, startCol)]), {(startRow, startCol)}
reached = {(startRow, startCol)}
while queue:
    r, c = queue.popleft()
    for nr, nc in [(r, column) for column in range(cols)] + [(row, c) for row in range(rows)]:
        reached.add((nr, nc))
        if matrix[nr][nc] == 1 and (nr, nc) not in fired:
            fired.add((nr, nc))
            queue.append((nr, nc))
return len(reached)''', laser_cases, _laser)
