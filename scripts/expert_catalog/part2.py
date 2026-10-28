"""EXPERT 033~064: DP·그래프·트리 명세와 독립 검산 오라클.

populate는 공통 생성기의 add에만 내용을 전달한다. DB나 기존 문제집을 수정하지 않는다.
"""
from __future__ import annotations

from collections import deque
from functools import lru_cache
from math import comb
import random
import textwrap

MOD = 1_000_000_007


def _fib(n):
    """기준 풀이의 행렬 곱셈과 다른 fast-doubling 검산."""
    if n == 0:
        return 0, 1
    a, b = _fib(n // 2)
    c = a * (2 * b - a) % MOD
    d = (a * a + b * b) % MOD
    return (d, (c + d) % MOD) if n % 2 else (c, d)


def _stairs_score(scores):
    n = len(scores)
    answer = -1
    # 1·2칸 이동의 모든 선택을 독립적으로 열거한다.
    def visit(position, path):
        nonlocal answer
        if position == n:
            answer = max(answer, sum(scores[x - 1] for x in path))
            return
        for step in (1, 2):
            nxt = position + step
            if nxt <= n and not (len(path) >= 2 and path[-2:] == [nxt - 2, nxt - 1]):
                visit(nxt, path + [nxt])
    visit(0, [])
    return max(answer, 0)


def _to_one_path(n):
    queue = deque([[n]])
    visited = {n}
    while queue:
        path = queue.popleft()
        v = path[-1]
        if v == 1:
            return [[len(path) - 1], path]
        candidates = {v - 1}
        if v % 2 == 0:
            candidates.add(v // 2)
        if v % 3 == 0:
            candidates.add(v // 3)
        for nxt in sorted(candidates):
            if nxt not in visited:
                visited.add(nxt)
                queue.append(path + [nxt])


def _min_coins(coins, amount):
    queue = deque([(0, 0)])
    seen = {0}
    while queue:
        value, distance = queue.popleft()
        if value == amount:
            return distance
        for coin in coins:
            nxt = value + coin
            if nxt <= amount and nxt not in seen:
                seen.add(nxt)
                queue.append((nxt, distance + 1))
    return -1


def _coin_combinations(coins, amount):
    @lru_cache(None)
    def count(i, remain):
        if i == len(coins):
            return int(remain == 0)
        return sum(count(i + 1, remain - k * coins[i])
                   for k in range(remain // coins[i] + 1)) % MOD
    return count(0, amount)


def _tiles_square(n):
    # 닫힌식 (2^(n+1) + (-1)^n) / 3의 유한체 계산.
    return (pow(2, n + 1, MOD) + (-1 if n % 2 else 1)) * pow(3, MOD - 2, MOD) % MOD


def _deletion_sum(numbers):
    return max(sum(numbers[l:r]) - min(0, min(numbers[l:r]))
               if r - l > 1 else numbers[l]
               for l in range(len(numbers)) for r in range(l + 1, len(numbers) + 1))


def _lis(numbers, sign=1):
    lengths = []
    for i, value in enumerate(numbers):
        lengths.append(1 + max([lengths[j] for j in range(i)
                                if sign * numbers[j] < sign * value], default=0))
    return max(lengths, default=0)


def _triangle(triangle):
    @lru_cache(None)
    def best(row, col):
        if row == len(triangle) - 1:
            return triangle[row][col]
        return triangle[row][col] + max(best(row + 1, col), best(row + 1, col + 1))
    return best(0, 0)


def _blocked_paths(grid):
    @lru_cache(None)
    def count(row, col):
        if row >= len(grid) or col >= len(grid[0]) or grid[row][col]:
            return 0
        if row == len(grid) - 1 and col == len(grid[0]) - 1:
            return 1
        return (count(row + 1, col) + count(row, col + 1)) % MOD
    return count(0, 0)


def _knapsack(weights, values, capacity):
    return max(sum(values[i] for i in range(len(weights)) if mask >> i & 1)
               for mask in range(1 << len(weights))
               if sum(weights[i] for i in range(len(weights)) if mask >> i & 1) <= capacity)


def _tree_info(n, edges, root):
    # BFS인 기준 풀이와 달리, 각 정점에서 루트까지 유일한 경로를 독립 탐색한다.
    adjacency = [[] for _ in range(n + 1)]
    for a, b in edges:
        adjacency[a].append(b)
        adjacency[b].append(a)
    parents, depths = [0] * n, [0] * n
    for node in range(1, n + 1):
        if node == root:
            continue
        stack = [(node, 0, [])]
        while stack:
            v, before, path = stack.pop()
            if v == root:
                parents[node - 1] = path[1] if len(path) > 1 else root
                depths[node - 1] = len(path)
                break
            for nxt in adjacency[v]:
                if nxt != before:
                    stack.append((nxt, v, path + [v]))
    return parents, depths


def _traversal(values, left, right, root, mode):
    if root == -1:
        return []
    if mode == "level":
        rows = {}
        stack = [(root, 0, "")]
        while stack:
            node, depth, path = stack.pop()
            rows.setdefault(depth, []).append((path, values[node]))
            for child, letter in ((left[node], "0"), (right[node], "1")):
                if child != -1:
                    stack.append((child, depth + 1, path + letter))
        return [[v for _, v in sorted(rows[d])] for d in sorted(rows)]
    result = []
    def visit(node):
        if node == -1:
            return
        if mode == "pre":
            result.append(values[node])
        visit(left[node])
        if mode == "in":
            result.append(values[node])
        visit(right[node])
        if mode == "post":
            result.append(values[node])
    visit(root)
    return result


def _is_bst(values, left, right, root):
    ordered = _traversal(values, left, right, root, "in")
    return all(a < b for a, b in zip(ordered, ordered[1:]))


def _diameter(n, edges):
    adjacency = [[] for _ in range(n + 1)]
    for a, b in edges:
        adjacency[a].append(b)
        adjacency[b].append(a)
    answer = 0
    for start in range(1, n + 1):
        queue = deque([(start, -1, 0)])
        while queue:
            node, before, distance = queue.popleft()
            answer = max(answer, distance)
            queue.extend((nxt, node, distance + 1) for nxt in adjacency[node] if nxt != before)
    return answer


def _bipartite(n, edges):
    # 작은 검증 그래프의 모든 색칠을 열거한다.
    return any(all((mask >> (a - 1) & 1) != (mask >> (b - 1) & 1) for a, b in edges)
               for mask in range(1 << n))


def _undirected_cycle(n, edges):
    adjacency = [[] for _ in range(n + 1)]
    for identity, (a, b) in enumerate(edges):
        adjacency[a].append((b, identity))
        adjacency[b].append((a, identity))
    seen = set()
    def visit(node, previous_edge):
        seen.add(node)
        for nxt, identity in adjacency[node]:
            if identity == previous_edge:
                continue
            if nxt in seen or visit(nxt, identity):
                return True
        return False
    return any(visit(node, -1) for node in range(1, n + 1) if node not in seen)


def _directed_cycle(n, edges):
    # 도달 행렬의 대각 성분으로 검산한다. 중복 간선은 존재 여부만 남긴다.
    reachable = [[False] * n for _ in range(n)]
    for a, b in edges:
        reachable[a - 1][b - 1] = True
    for middle in range(n):
        for a in range(n):
            for b in range(n):
                reachable[a][b] |= reachable[a][middle] and reachable[middle][b]
    return any(reachable[i][i] for i in range(n))


def _topological(n, edges):
    remaining = set(range(1, n + 1))
    answer = []
    while remaining:
        node = min(v for v in remaining if not any(b == v and a in remaining for a, b in edges))
        remaining.remove(node)
        answer.append(node)
    return answer


def _connected(n, edges):
    reachable = {1}
    previous_size = -1
    while len(reachable) != previous_size:
        previous_size = len(reachable)
        for a, b in edges:
            if a in reachable or b in reachable:
                reachable.update((a, b))
    return len(reachable) == n


def populate(add):
    rng = random.Random(2026102802)
    ints = lambda values: [[value] for value in values]
    add("계단 오르기", [("n", "int")], "int",
        "높이 0인 바닥에서 출발하여 한 번에 계단 1칸 또는 2칸을 올라갑니다. 정확히 `n`번째 계단에 도착하는 서로 다른 이동 순서의 수를 `1,000,000,007`로 나눈 나머지를 반환하세요. `n=0`일 때 아무 이동도 하지 않는 한 가지 순서가 있습니다.",
        "- 0 ≤ n ≤ 1000000\n- 이동 순서가 다르면 서로 다른 경우입니다.",
        """
        a, b = 1, 1
        for _ in range(n):
            a, b = b, (a + b) % 1000000007
        return a
        """, ints([0, 1, 2, 3, 4, 5, 8, 10, 20, 45, 100, 1000, 1000000]), lambda n: _fib(n + 1)[0])

    score_cases = [[[10, 20, 15, 25, 10, 20]], [[5]], [[0]], [[10, 20]], [[10, 1, 10]], [[1, 1, 1]], [[100, 1, 1, 100]]]
    score_cases += [[[rng.randrange(0, 31) for _ in range(n)]] for n in range(4, 11)]
    add("계단 오르기 1", [("scores", "int[]")], "int",
        "바닥에서 시작해 한 번에 1칸 또는 2칸 올라가며 밟은 계단의 점수를 얻습니다. `scores[i]`는 `i+1`번째 계단의 점수입니다. 연속된 세 계단을 모두 밟을 수 없고 마지막 계단은 반드시 밟아야 합니다. 얻을 수 있는 최대 총점을 반환하세요. 바닥은 계단으로 세지 않습니다.",
        "- 1 ≤ scores 길이 ≤ 100000\n- 0 ≤ scores[i] ≤ 1000",
        """
        n = len(scores)
        if n == 1:
            return scores[0]
        dp = [0] * (n + 1)
        dp[1], dp[2] = scores[0], scores[0] + scores[1]
        for i in range(3, n + 1):
            dp[i] = max(dp[i - 2], dp[i - 3] + scores[i - 2]) + scores[i - 1]
        return dp[n]
        """, score_cases, _stairs_score)

    add("피보나치 수열 3", [("n", "int")], "int",
        "`F(0)=0`, `F(1)=1`, `F(n)=F(n-1)+F(n-2)`입니다. 행렬 거듭제곱으로 매우 큰 `n`에 대해서도 빠르게 `F(n)`을 계산하고 `1,000,000,007`로 나눈 나머지를 반환하세요. 거듭제곱은 분할 정복으로 계산하는 풀이를 연습하세요.",
        "- 0 ≤ n ≤ 2147483647\n- 중간 곱셈은 64비트 정수로 계산한 뒤 나머지를 취하세요.",
        """
        mod = 1000000007
        def multiply(a, b):
            return [[sum(a[i][k] * b[k][j] for k in range(2)) % mod for j in range(2)] for i in range(2)]
        result = [[1, 0], [0, 1]]
        power = [[1, 1], [1, 0]]
        while n:
            if n & 1:
                result = multiply(result, power)
            power = multiply(power, power)
            n //= 2
        return result[0][1]
        """, ints([0, 1, 2, 3, 10, 46, 100, 1000, 1000000, 1000000000, 2000000000, 2147483647]), lambda n: _fib(n)[0])

    one_cases = ints([1, 2, 3, 4, 5, 6, 7, 10, 11, 16, 27, 100, 1000])
    one_body = textwrap.dedent("""
        dp = [0] * (n + 1)
        for value in range(2, n + 1):
            candidates = [dp[value - 1]]
            if value % 2 == 0:
                candidates.append(dp[value // 2])
            if value % 3 == 0:
                candidates.append(dp[value // 3])
            dp[value] = 1 + min(candidates)
        """).strip()
    add("1로 만들기", [("n", "int")], "int",
        "양의 정수 `n`에 1 빼기, 2로 나누기, 3으로 나누기를 적용하여 1을 만드세요. 나눗셈은 현재 값이 해당 수로 나누어떨어질 때만 사용할 수 있습니다. 필요한 최소 연산 횟수를 반환하세요.",
        "- 1 ≤ n ≤ 1000000", one_body + "\nreturn dp[n]", one_cases, lambda n: _to_one_path(n)[0][0])
    add("1로 만들기 1", [("n", "int")], "int[][]",
        "`n`을 1로 만드는 연산은 1 빼기, 나누어떨어질 때 2 또는 3으로 나누기입니다. `[[최소 연산 횟수], [n부터 1까지의 실제 값]]` 형태로 반환하세요. 최소 횟수의 경로가 여러 개면 값의 배열이 사전순으로 가장 작은 경로를 선택합니다. 예를 들어 `n=4`는 `[[2],[4,2,1]]`입니다.",
        "- 1 ≤ n ≤ 1000000\n- n=1의 결과는 [[0],[1]]입니다.",
        one_body + "\n" + textwrap.dedent("""

        path = [n]
        value = n
        while value > 1:
            candidates = [value - 1]
            if value % 2 == 0:
                candidates.append(value // 2)
            if value % 3 == 0:
                candidates.append(value // 3)
            value = min(v for v in candidates if dp[v] == dp[value] - 1)
            path.append(value)
        return [[dp[n]], path]
        """).strip(), one_cases, _to_one_path)

    coin_cases = [[[1, 3, 4], 6], [[2], 3], [[], 0], [[], 7], [[7], 0], [[7], 21], [[2, 4], 8], [[5, 1, 2], 11], [[4, 6], 7], [[3, 5], 17], [[2, 7, 9], 25], [[1], 35], [[8, 3, 11], 30]]
    add("최소 동전 개수 1", [("coins", "int[]"), ("amount", "int")], "int",
        "서로 다른 양의 동전 단위 `coins`를 각각 제한 없이 사용할 수 있습니다. 합이 정확히 `amount`가 되는 최소 동전 수를 DP로 구하세요. 만들 수 없으면 -1을 반환하고 `amount=0`이면 0을 반환합니다. 동전 단위는 작은 동전의 배수라는 보장이 없으므로 큰 동전부터 고르는 방법만으로는 해결되지 않습니다.",
        "- 0 ≤ coins 길이 ≤ 100\n- 1 ≤ coins[i] ≤ 10000, 중복 없음\n- 0 ≤ amount ≤ 10000",
        """
        dp = [amount + 1] * (amount + 1)
        dp[0] = 0
        for value in range(1, amount + 1):
            for coin in coins:
                if coin <= value:
                    dp[value] = min(dp[value], dp[value - coin] + 1)
        return dp[amount] if dp[amount] <= amount else -1
        """, coin_cases, _min_coins)
    add("동전 조합", [("coins", "int[]"), ("amount", "int")], "int",
        "서로 다른 동전 단위 `coins`를 각각 0개 이상 사용하여 합이 `amount`가 되는 조합의 수를 반환하세요. 동전을 고르는 순서만 다른 경우는 같은 조합입니다. 답은 `1,000,000,007`로 나눈 나머지이며, 금액 0을 만드는 빈 조합은 한 가지입니다.",
        "- 0 ≤ coins 길이 ≤ 100\n- 1 ≤ coins[i] ≤ 10000, 중복 없음\n- 0 ≤ amount ≤ 10000",
        """
        dp = [0] * (amount + 1)
        dp[0] = 1
        for coin in coins:
            for value in range(coin, amount + 1):
                dp[value] = (dp[value] + dp[value - coin]) % 1000000007
        return dp[amount]
        """, coin_cases + [[[1, 2, 3, 4, 5], 50], [list(range(1, 21)), 200]], _coin_combinations)

    tile_cases = ints([0, 1, 2, 3, 4, 5, 8, 10, 20, 100, 1000, 1000000])
    add("타일 채우기", [("n", "int")], "int",
        "`2×n` 직사각형을 회전 가능한 `1×2` 도미노로 빈틈과 겹침 없이 채우는 경우의 수를 `1,000,000,007`로 나눈 나머지로 반환하세요. 타일 배치가 다르면 다른 경우이며 빈 판(`n=0`)을 채우는 방법은 한 가지입니다.",
        "- 0 ≤ n ≤ 1000000",
        """
        a, b = 1, 1
        for _ in range(n):
            a, b = b, (a + b) % 1000000007
        return a
        """, tile_cases, lambda n: _fib(n + 1)[0])
    add("타일 채우기 1", [("n", "int")], "int",
        "`2×n` 직사각형을 `1×2`, `2×1` 도미노와 `2×2` 정사각 타일로 채우는 경우의 수를 반환하세요. 같은 종류의 타일끼리 구별하지 않고 답은 `1,000,000,007`로 나눈 나머지입니다. `n=0`의 답은 1입니다.",
        "- 0 ≤ n ≤ 1000000",
        """
        if n == 0:
            return 1
        a, b = 1, 1
        for _ in range(2, n + 1):
            a, b = b, (b + 2 * a) % 1000000007
        return b
        """, tile_cases, _tiles_square)

    arrays = [[1], [-5], [0], [1, -2, 0, 3], [1, -2, -2, 3], [-5, -1, -3], [2, 2, 2], [5, -10, 7], [0, -1, 0], [3, -1, 4, -2, 5], [-1, 2, -3, 4, -5, 6], [1, 2, 3, 4]]
    arrays += [[rng.randrange(-20, 21) for _ in range(n)] for n in (7, 10, 16)]
    add("최대 연속 합 1", [("numbers", "int[]")], "int",
        "비어 있지 않은 연속 구간을 하나 선택한 뒤 그 구간에서 원소를 최대 한 개 삭제할 수 있습니다. 삭제 후에도 원소가 하나 이상 남아야 합니다. 남은 원소의 합으로 가능한 최댓값을 반환하세요. 삭제는 선택 사항이고, 삭제 후 원래 인덱스가 떨어져 있어도 원래 선택한 구간 안의 원소이면 됩니다.",
        "- 1 ≤ numbers 길이 ≤ 100000\n- -10000 ≤ numbers[i] ≤ 10000",
        """
        keep = numbers[0]
        deleted = -10**30
        answer = keep
        for value in numbers[1:]:
            deleted = max(deleted + value, keep)
            keep = max(value, keep + value)
            answer = max(answer, keep, deleted)
        return answer
        """, [[a] for a in arrays], _deletion_sum)
    lis_arrays = [[], [1], [2, 2, 2], [1, 2, 3], [3, 2, 1], [10, 20, 10, 30, 20, 50], [-3, -1, -2, 0], [0, 0, 1, 0], [5, 1, 4, 2, 3], [-1, -2, -3], [2, 1, 2, 1, 2], list(range(20))]
    lis_arrays += [[rng.randrange(-10, 11) for _ in range(n)] for n in (7, 15, 30)]
    subsequence_body = """
        import bisect
        tails = []
        for value in numbers:
            value *= SIGN
            index = bisect.bisect_left(tails, value)
            if index == len(tails):
                tails.append(value)
            else:
                tails[index] = value
        return len(tails)
        """
    add("가장 긴 증가 부분 수열", [("numbers", "int[]")], "int",
        "배열에서 일부 원소를 원래 순서를 유지하며 선택한 부분 수열 중, 값이 엄격히 증가하는 가장 긴 길이를 반환하세요. 연속할 필요는 없고 같은 값은 증가로 인정하지 않습니다. 빈 배열의 답은 0입니다.",
        "- 0 ≤ numbers 길이 ≤ 100000\n- -1000000000 ≤ numbers[i] ≤ 1000000000",
        subsequence_body.replace("SIGN", "1"), [[a] for a in lis_arrays], _lis)
    add("가장 긴 감소 부분 수열", [("numbers", "int[]")], "int",
        "원래 순서를 유지하는 부분 수열 중 값이 엄격히 감소하는 가장 긴 길이를 반환하세요. 같은 값은 감소로 인정하지 않으며 빈 배열의 답은 0입니다. 연속된 구간만 찾는 문제가 아닙니다.",
        "- 0 ≤ numbers 길이 ≤ 100000\n- -1000000000 ≤ numbers[i] ≤ 1000000000",
        subsequence_body.replace("SIGN", "-1"), [[a] for a in lis_arrays], lambda a: _lis(a, -1))

    triangle_cases = [[[[7], [3, 8], [8, 1, 0], [2, 7, 4, 4], [4, 5, 2, 6, 5]]], [[[5]]], [[[-7]]], [[[0], [0, 0]]], [[[1], [-2, 3]]]]
    triangle_cases += [[[[rng.randrange(-20, 21) for _ in range(row + 1)] for row in range(height)]] for height in range(3, 11)]
    add("정수 삼각형", [("triangle", "int[][]")], "int",
        "첫 행에 원소 1개, 둘째 행에 2개가 있는 정수 삼각형이 주어집니다. 꼭대기에서 출발하여 `(행, 열)`에서 다음 행의 같은 열 또는 바로 오른쪽 열로 내려갑니다. 마지막 행까지 선택한 원소 합의 최댓값을 반환하세요. 음수도 포함될 수 있습니다.",
        "- 1 ≤ triangle 행 수 ≤ 1000\n- i번째 행의 길이는 i+1\n- -1000 ≤ 각 원소 ≤ 1000",
        """
        dp = triangle[-1][:]
        for row in range(len(triangle) - 2, -1, -1):
            dp = [triangle[row][col] + max(dp[col], dp[col + 1]) for col in range(row + 1)]
        return dp[0]
        """, triangle_cases, _triangle)
    dimensions = [[1, 1], [1, 5], [5, 1], [2, 2], [2, 3], [3, 2], [3, 3], [5, 7], [8, 8], [10, 12], [100, 100], [1000, 1000]]
    add("격자 경로의 수", [("n", "int"), ("m", "int")], "int",
        "행이 `n`개, 열이 `m`개인 격자의 왼쪽 위 `(0,0)`에서 오른쪽 아래 `(n-1,m-1)`로 이동합니다. 한 번에 오른쪽 한 칸 또는 아래쪽 한 칸으로만 움직일 때 경로 수를 `1,000,000,007`로 나눈 나머지를 반환하세요. `1×1` 격자에는 이동하지 않는 경로 한 개가 있습니다.",
        "- 1 ≤ n, m ≤ 1000",
        """
        dp = [1] * m
        for _ in range(1, n):
            for col in range(1, m):
                dp[col] = (dp[col] + dp[col - 1]) % 1000000007
        return dp[-1]
        """, dimensions, lambda n, m: comb(n + m - 2, n - 1) % MOD)
    blocked = [[[0]], [[1]], [[0, 0, 0]], [[0], [1], [0]], [[0, 0], [0, 0]], [[0, 1], [1, 0]], [[1, 0], [0, 0]], [[0, 0], [0, 1]]]
    blocked += [[[int(rng.random() < 0.25) for _ in range(cols)] for _ in range(rows)] for rows, cols in ((2, 3), (3, 2), (4, 4), (5, 7), (8, 8), (10, 12))]
    add("격자 경로의 수 1", [("grid", "int[][]")], "int",
        "`grid`의 0은 이동 가능한 칸, 1은 장애물입니다. 왼쪽 위에서 오른쪽 아래로 오른쪽 또는 아래쪽 한 칸씩 이동하는 경로 수를 `1,000,000,007`로 나눈 나머지를 반환하세요. 출발점이나 도착점이 막혀 있거나 경로가 없으면 0입니다.",
        "- 1 ≤ 행 수, 열 수 ≤ 1000\n- 행 수 × (열 수 + 1) ≤ 1000000\n- 직사각형 행렬, 각 값은 0 또는 1",
        """
        width = len(grid[0])
        dp = [0] * width
        dp[0] = 1
        for row in grid:
            for col in range(width):
                if row[col] == 1:
                    dp[col] = 0
                elif col > 0:
                    dp[col] = (dp[col] + dp[col - 1]) % 1000000007
        return dp[-1]
        """, [[g] for g in blocked], _blocked_paths)
    bags = [[[6, 4, 3, 5], [13, 8, 6, 12], 7], [[], [], 0], [[], [], 5], [[1], [8], 0], [[2], [3], 1], [[2], [3], 2], [[1, 1], [2, 3], 1], [[1, 2], [0, 0], 3]]
    bags += [[[rng.randint(1, 10) for _ in range(n)], [rng.randint(0, 25) for _ in range(n)], rng.randint(0, 25)] for n in range(3, 10)]
    add("배낭 문제", [("weights", "int[]"), ("values", "int[]"), ("capacity", "int")], "int",
        "물건 `i`의 무게는 `weights[i]`, 가치는 `values[i]`입니다. 각 물건을 최대 한 번 고르고 총 무게가 `capacity`를 넘지 않도록 할 때 총 가치의 최댓값을 반환하세요. 무게·가치가 같아도 인덱스가 다르면 서로 다른 물건이며 아무것도 고르지 않아도 됩니다.",
        "- 0 ≤ 물건 수 ≤ 200, 두 배열의 길이는 같음\n- 1 ≤ weights[i] ≤ 10000\n- 0 ≤ values[i] ≤ 1000000\n- 0 ≤ capacity ≤ 10000",
        """
        dp = [0] * (capacity + 1)
        for weight, value in zip(weights, values):
            for remain in range(capacity, weight - 1, -1):
                dp[remain] = max(dp[remain], dp[remain - weight] + value)
        return dp[capacity]
        """, bags, _knapsack)

    # 049~052는 같은 입력 트리라도 서로 다른 루트를 검증한다.
    trees = [[1, [], 1], [2, [[1, 2]], 1], [2, [[1, 2]], 2], [3, [[1, 2], [2, 3]], 1], [3, [[1, 2], [1, 3]], 2]]
    for n in range(4, 13):
        edges = [[node, rng.randrange(1, node)] for node in range(2, n + 1)]
        rng.shuffle(edges)
        trees.append([n, edges, rng.randrange(1, n + 1)])
    tree_constraints = "- 1 ≤ n ≤ 100000\n- 정점 번호는 1..n, edges는 [u,v]인 간선 n-1개\n- 자기 간선·중복 간선이 없는 연결된 무방향 트리\n- 1 ≤ root ≤ n"
    tree_prefix = textwrap.dedent("""
        from collections import deque
        adjacency = [[] for _ in range(n + 1)]
        for a, b in edges:
            adjacency[a].append(b)
            adjacency[b].append(a)
        parent = [0] * (n + 1)
        depth = [-1] * (n + 1)
        depth[root] = 0
        queue = deque([root])
        while queue:
            node = queue.popleft()
            for nxt in adjacency[node]:
                if depth[nxt] == -1:
                    parent[nxt] = node
                    depth[nxt] = depth[node] + 1
                    queue.append(nxt)
        """).strip()
    parent_cases = [[n, e] for n, e, _ in trees]
    # 두 정점 트리의 루트 중복을 제거해도 10개 이상 남는다.
    add("트리의 부모 찾기", [("n", "int"), ("edges", "int[][]")], "int[]",
        "루트가 1인 무방향 트리에서 각 정점의 부모를 반환하세요. 결과 길이는 `n`이고 결과의 `i-1`번째 값은 정점 `i`의 부모 번호입니다. 루트 1의 부모는 0으로 표시합니다.",
        tree_constraints.replace("\n- 1 ≤ root ≤ n", ""),
        "root = 1\n" + tree_prefix + "\nreturn parent[1:]", parent_cases, lambda n, e: _tree_info(n, e, 1)[0])
    add("트리의 깊이", [("n", "int"), ("edges", "int[][]"), ("root", "int")], "int[]",
        "`root`를 루트로 삼은 트리에서 각 정점의 깊이를 반환하세요. 깊이는 루트에서 해당 정점까지의 간선 수입니다. 결과의 `i-1`번째 값은 정점 `i`의 깊이이며 루트 깊이는 0입니다.",
        tree_constraints, tree_prefix + "\nreturn depth[1:]", trees, lambda n, e, r: _tree_info(n, e, r)[1])
    add("트리의 높이", [("n", "int"), ("edges", "int[][]"), ("root", "int")], "int",
        "`root`를 루트로 삼았을 때 가장 깊은 리프까지의 간선 수인 트리 높이를 반환하세요. 정점이 하나뿐이면 높이는 0입니다.",
        tree_constraints, tree_prefix + "\nreturn max(depth)", trees, lambda n, e, r: max(_tree_info(n, e, r)[1]))
    add("트리의 리프 개수", [("n", "int"), ("edges", "int[][]"), ("root", "int")], "int",
        "`root`를 루트로 삼은 트리에서 자식이 없는 정점인 리프의 개수를 반환하세요. 정점 하나뿐인 트리에서는 루트도 리프이므로 답은 1입니다. 루트가 아닌 정점의 부모 방향은 자식으로 세지 않습니다.",
        tree_constraints,
        tree_prefix + "\nreturn sum(not any(parent[child] == node for child in adjacency[node]) for node in range(1, n + 1))",
        trees, lambda n, e, r: sum(node not in _tree_info(n, e, r)[0] for node in range(1, n + 1)))

    binary = [[[], [], [], -1], [[7], [-1], [-1], 0], [[2, 1], [1, -1], [-1, -1], 0], [[1, 2], [-1, -1], [1, -1], 0], [[2, 1, 3], [1, -1, -1], [2, -1, -1], 0], [[2, 3, 1], [1, -1, -1], [2, -1, -1], 0], [[2, 2], [1, -1], [-1, -1], 0]]
    for n in range(4, 12):
        left, right = [-1] * n, [-1] * n
        available = [(0, 0), (0, 1)]
        for node in range(1, n):
            position = rng.randrange(len(available))
            parent, side = available.pop(position)
            (left if side == 0 else right)[parent] = node
            available.extend(((node, 0), (node, 1)))
        values = [rng.randrange(-10, 11) for _ in range(n)]
        binary.append([values, left, right, 0])
    # 루트가 0이 아닌 완전한 트리도 포함한다.
    binary.append([[1, 3, 2], [-1, -1, 0], [-1, -1, 1], 2])
    binary_params = [("values", "int[]"), ("left", "int[]"), ("right", "int[]"), ("root", "int")]
    binary_constraints = "- 0 ≤ 정점 수 n ≤ 100000, 세 배열 길이는 n으로 같음\n- 정점 인덱스는 0..n-1, left[i]·right[i]는 자식 인덱스 또는 -1\n- n=0이면 root=-1, 그 외 0 ≤ root < n\n- 모든 정점이 root에서 한 번씩 도달되는 올바른 이진 트리\n- -1000000000 ≤ values[i] ≤ 1000000000"
    binary_description = "`values[i]`는 정점 i의 값이고 `left[i]`, `right[i]`는 각각 왼쪽·오른쪽 자식 인덱스입니다. 자식이 없으면 -1입니다. 정점 번호가 아니라 방문한 **값**을 반환하며 값은 중복될 수 있습니다. 빈 트리의 결과는 빈 배열입니다. "
    add("이진 트리 전위 순회", binary_params, "int[]", binary_description + "루트 → 왼쪽 부분 트리 → 오른쪽 부분 트리의 전위 순회 결과를 반환하세요.", binary_constraints,
        """
        if root == -1:
            return []
        answer, stack = [], [root]
        while stack:
            node = stack.pop()
            answer.append(values[node])
            if right[node] != -1:
                stack.append(right[node])
            if left[node] != -1:
                stack.append(left[node])
        return answer
        """, binary, lambda v, l, r, root: _traversal(v, l, r, root, "pre"))
    add("이진 트리 중위 순회", binary_params, "int[]", binary_description + "왼쪽 부분 트리 → 루트 → 오른쪽 부분 트리의 중위 순회 결과를 반환하세요.", binary_constraints,
        """
        answer, stack, node = [], [], root
        while stack or node != -1:
            while node != -1:
                stack.append(node)
                node = left[node]
            node = stack.pop()
            answer.append(values[node])
            node = right[node]
        return answer
        """, binary, lambda v, l, r, root: _traversal(v, l, r, root, "in"))
    add("이진 트리 후위 순회", binary_params, "int[]", binary_description + "왼쪽 부분 트리 → 오른쪽 부분 트리 → 루트의 후위 순회 결과를 반환하세요.", binary_constraints,
        """
        if root == -1:
            return []
        answer, stack = [], [(root, False)]
        while stack:
            node, expanded = stack.pop()
            if expanded:
                answer.append(values[node])
            else:
                stack.append((node, True))
                if right[node] != -1:
                    stack.append((right[node], False))
                if left[node] != -1:
                    stack.append((left[node], False))
        return answer
        """, binary, lambda v, l, r, root: _traversal(v, l, r, root, "post"))
    add("이진 트리 레벨 순회", binary_params, "int[][]", binary_description + "루트의 깊이 0부터 깊이별로 방문한 값을 2차원 배열로 반환하세요. 같은 깊이에서는 왼쪽에서 오른쪽 순서입니다. 예를 들어 루트 값 2의 왼쪽 값이 1, 오른쪽 값이 3이면 `[[2],[1,3]]`입니다.", binary_constraints,
        """
        from collections import deque
        if root == -1:
            return []
        answer, queue = [], deque([root])
        while queue:
            row = []
            for _ in range(len(queue)):
                node = queue.popleft()
                row.append(values[node])
                if left[node] != -1:
                    queue.append(left[node])
                if right[node] != -1:
                    queue.append(right[node])
            answer.append(row)
        return answer
        """, binary, lambda v, l, r, root: _traversal(v, l, r, root, "level"))
    bst_cases = binary + [[[10, 5, 15, 6], [1, -1, 3, -1], [2, -1, -1, -1], 0], [[0, -1000000000, 1000000000], [1, -1, -1], [2, -1, -1], 0]]
    add("이진 탐색 트리 검사", binary_params, "bool",
        "자식 배열로 표현한 이진 트리가 엄격한 이진 탐색 트리인지 반환하세요. 모든 정점에서 왼쪽 부분 트리의 **모든 값**은 현재 값보다 작고 오른쪽 부분 트리의 모든 값은 커야 합니다. 같은 값이 두 번 나오면 올바른 BST가 아닙니다. 빈 트리는 올바른 BST로 봅니다. 자식 인덱스가 없으면 -1이며 배열 표현은 제한 사항을 따릅니다.",
        binary_constraints,
        """
        if root == -1:
            return True
        stack = [(root, -10**30, 10**30)]
        while stack:
            node, low, high = stack.pop()
            value = values[node]
            if not low < value < high:
                return False
            if left[node] != -1:
                stack.append((left[node], low, value))
            if right[node] != -1:
                stack.append((right[node], value, high))
        return True
        """, bst_cases, _is_bst)
    add("트리의 지름", [("n", "int"), ("edges", "int[][]")], "int",
        "가중치가 없는 무방향 트리에서 두 정점 사이 거리의 최댓값인 지름을 반환하세요. 거리는 유일한 경로의 간선 수입니다. 정점이 하나뿐이면 답은 0입니다.",
        tree_constraints.replace("\n- 1 ≤ root ≤ n", ""),
        """
        from collections import deque
        adjacency = [[] for _ in range(n + 1)]
        for a, b in edges:
            adjacency[a].append(b)
            adjacency[b].append(a)
        def farthest(start):
            distances = [-1] * (n + 1)
            distances[start] = 0
            queue = deque([start])
            while queue:
                node = queue.popleft()
                for nxt in adjacency[node]:
                    if distances[nxt] == -1:
                        distances[nxt] = distances[node] + 1
                        queue.append(nxt)
            last = max(range(1, n + 1), key=lambda v: distances[v])
            return last, distances[last]
        endpoint, _ = farthest(1)
        return farthest(endpoint)[1]
        """, parent_cases, _diameter)

    graphs = [[1, []], [1, [[1, 1]]], [2, []], [2, [[1, 2]]], [2, [[1, 2], [1, 2]]], [2, [[1, 2], [2, 1]]], [3, [[1, 2], [2, 3], [3, 1]]], [4, [[1, 2], [2, 3], [3, 4], [4, 1]]], [4, [[1, 2], [3, 4]]], [5, [[2, 3], [3, 4], [4, 2]]], [6, [[1, 2], [2, 3], [4, 5], [5, 6]]], [7, [[1, 2], [2, 3], [3, 4], [4, 5], [5, 6], [6, 7]]]]
    graphs += [[n, [[rng.randint(1, n), rng.randint(1, n)] for _ in range(n + 2)]] for n in (4, 5, 6, 7)]
    graph_constraints = "- 1 ≤ n ≤ 100000, 정점 번호 1..n\n- 0 ≤ edges 길이 ≤ 200000, 각 간선은 [u,v]\n- 자기 간선과 중복 간선이 포함될 수 있음"
    add("이분 그래프 판별", [("n", "int"), ("edges", "int[][]")], "bool",
        "무방향 그래프의 정점을 두 집합으로 나누어 모든 간선의 양 끝이 서로 다른 집합에 속하게 할 수 있는지 반환하세요. 연결되지 않은 모든 요소를 검사해야 합니다. 자기 간선이 있으면 false이고 중복 간선은 판정에 영향을 주지 않습니다. 간선이 없는 그래프는 true입니다.",
        graph_constraints,
        """
        from collections import deque
        adjacency = [[] for _ in range(n + 1)]
        for a, b in edges:
            adjacency[a].append(b)
            adjacency[b].append(a)
        color = [-1] * (n + 1)
        for start in range(1, n + 1):
            if color[start] != -1:
                continue
            color[start] = 0
            queue = deque([start])
            while queue:
                node = queue.popleft()
                for nxt in adjacency[node]:
                    if color[nxt] == color[node]:
                        return False
                    if color[nxt] == -1:
                        color[nxt] = 1 - color[node]
                        queue.append(nxt)
        return True
        """, graphs, _bipartite)
    add("사이클 판별", [("n", "int"), ("edges", "int[][]")], "bool",
        "무방향 그래프에 사이클이 하나라도 존재하면 true를 반환하세요. 각 입력 간선은 구별되는 간선입니다. 따라서 자기 간선 하나는 길이 1 사이클이며, 같은 두 정점을 잇는 간선이 두 번 주어지면 길이 2 사이클입니다. `[u,v]`와 `[v,u]`도 평행 간선 두 개로 셉니다. 연결되지 않은 요소도 검사합니다.",
        graph_constraints,
        """
        parent = list(range(n + 1))
        size = [1] * (n + 1)
        def find(node):
            while parent[node] != node:
                parent[node] = parent[parent[node]]
                node = parent[node]
            return node
        for a, b in edges:
            a, b = find(a), find(b)
            if a == b:
                return True
            if size[a] < size[b]:
                a, b = b, a
            parent[b] = a
            size[a] += size[b]
        return False
        """, graphs, _undirected_cycle)
    directed_prefix = textwrap.dedent("""
        from collections import deque
        adjacency = [[] for _ in range(n + 1)]
        degree = [0] * (n + 1)
        for a, b in edges:
            adjacency[a].append(b)
            degree[b] += 1
        queue = deque(v for v in range(1, n + 1) if degree[v] == 0)
        visited = 0
        while queue:
            node = queue.popleft()
            visited += 1
            for nxt in adjacency[node]:
                degree[nxt] -= 1
                if degree[nxt] == 0:
                    queue.append(nxt)
        """).strip()
    add("사이클 판별 1", [("n", "int"), ("edges", "int[][]")], "bool",
        "`edges`의 `[u,v]`가 u에서 v로 향하는 방향 간선일 때, 방향 사이클이 존재하는지 반환하세요. 자기 간선도 사이클입니다. 같은 방향의 중복 간선만으로는 사이클이 생기지 않지만 서로 반대 방향의 두 간선은 사이클을 만듭니다. 연결되지 않은 부분도 검사합니다.",
        graph_constraints, directed_prefix + "\nreturn visited != n", graphs, _directed_cycle)
    dags = [[1, []], [2, []], [2, [[2, 1]]], [3, [[1, 3], [2, 3]]], [4, [[4, 2], [2, 1], [4, 3]]], [3, [[1, 2], [1, 2]]], [5, [[5, 4], [4, 3], [3, 2], [2, 1]]]]
    for n in range(4, 11):
        order = list(range(1, n + 1))
        rng.shuffle(order)
        edges = [[order[i], order[j]] for i in range(n) for j in range(i + 1, n) if rng.random() < 0.3]
        dags.append([n, edges])
    add("위상 정렬", [("n", "int"), ("edges", "int[][]")], "int[]",
        "방향 비순환 그래프에서 모든 간선 `[u,v]`의 u가 v보다 앞에 나오는 정점 배열을 반환하세요. 현재 선택 가능한 정점 중 가장 작은 번호부터 선택하여 사전순으로 가장 작은 위상 정렬을 만드세요. 연결되지 않은 정점도 모두 포함하며 중복 간선이 있어도 각 정점은 한 번만 반환합니다.",
        graph_constraints + "\n- 사이클이 없는 DAG임이 보장됨",
        """
        import heapq
        adjacency = [[] for _ in range(n + 1)]
        degree = [0] * (n + 1)
        for a, b in edges:
            adjacency[a].append(b)
            degree[b] += 1
        heap = [v for v in range(1, n + 1) if degree[v] == 0]
        heapq.heapify(heap)
        answer = []
        while heap:
            node = heapq.heappop(heap)
            answer.append(node)
            for nxt in adjacency[node]:
                degree[nxt] -= 1
                if degree[nxt] == 0:
                    heapq.heappush(heap, nxt)
        return answer
        """, dags, _topological)
    add("선수 과목", [("n", "int"), ("edges", "int[][]")], "bool",
        "과목 번호는 1..n입니다. `[u,v]`는 과목 v를 듣기 전에 과목 u를 먼저 마쳐야 한다는 관계입니다. 모든 과목을 수강할 수 있는 순서가 존재하는지 반환하세요. 자기 자신을 먼저 들어야 하는 관계는 불가능하고 중복 관계는 한 번의 조건과 같습니다. 선수 과목이 없는 과목도 수강 대상에 포함됩니다.",
        graph_constraints, directed_prefix + "\nreturn visited == n", graphs + dags, lambda n, e: not _directed_cycle(n, e))
    add("도시 연결", [("n", "int"), ("edges", "int[][]")], "bool",
        "도시 1..n 사이의 양방향 도로 `edges`가 주어집니다. 모든 도시 쌍 사이를 도로를 따라 이동할 수 있으면 true, 서로 도달할 수 없는 도시가 있으면 false를 반환하세요. 자기 도로와 중복 도로는 연결 여부에 영향을 주지 않습니다. 도시가 하나이면 도로가 없어도 true입니다.",
        graph_constraints,
        """
        adjacency = [[] for _ in range(n + 1)]
        for a, b in edges:
            adjacency[a].append(b)
            adjacency[b].append(a)
        seen, stack = {1}, [1]
        while stack:
            node = stack.pop()
            for nxt in adjacency[node]:
                if nxt not in seen:
                    seen.add(nxt)
                    stack.append(nxt)
        return len(seen) == n
        """, graphs, _connected)
