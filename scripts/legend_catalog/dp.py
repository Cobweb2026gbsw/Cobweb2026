"""동적 계획법 심화. 복원 시 동률 기준과 불가능한 상태의 반환 규칙을 고정한다."""

import itertools
import random
from functools import lru_cache


def lis(numbers):
    from bisect import bisect_left

    tails = []
    for x in numbers:
        pos = bisect_left(tails, x)
        if pos == len(tails):
            tails.append(x)
        else:
            tails[pos] = x
    return len(tails)


def restore(numbers):
    from bisect import bisect_left

    tails = []
    suffix = [0] * len(numbers)
    for i in range(len(numbers) - 1, -1, -1):
        x = -numbers[i]
        pos = bisect_left(tails, x)
        if pos == len(tails):
            tails.append(x)
        else:
            tails[pos] = x
        suffix[i] = pos + 1
    remaining = len(tails)
    answer = []
    for i, x in enumerate(numbers):
        if remaining and suffix[i] >= remaining and (not answer or answer[-1] < x):
            answer.append(x)
            remaining -= 1
    return answer


def matrix_chain(dimensions):
    n = len(dimensions) - 1
    dp = [[0] * n for _ in range(n)]
    for length in range(2, n + 1):
        for i in range(n - length + 1):
            j = i + length - 1
            dp[i][j] = min(
                dp[i][k] + dp[k + 1][j] + dimensions[i] * dimensions[k + 1] * dimensions[j + 1]
                for k in range(i, j)
            )
    return dp[0][-1]


def merge_files(sizes):
    n = len(sizes)
    if n < 2:
        return 0
    sums = [0]
    for value in sizes:
        sums.append(sums[-1] + value)
    dp = [[0] * n for _ in range(n)]
    split = [[i] * n for i in range(n)]
    for span in range(2, n + 1):
        for i in range(n - span + 1):
            j = i + span - 1
            # 양의 구간 합 비용에서는 최적 분할점이 이웃 구간의 최적점 사이에 있다.
            left = split[i][j - 1]
            right = min(split[i + 1][j], j - 1)
            best, k = min((dp[i][m] + dp[m + 1][j], m) for m in range(left, right + 1))
            dp[i][j] = best + sums[j + 1] - sums[i]
            split[i][j] = k
    return dp[0][-1]


def tour(costs):
    from functools import lru_cache

    n = len(costs)
    if n == 1:
        return 0

    @lru_cache(None)
    def travel(u, mask):
        if mask == (1 << n) - 1:
            return costs[u][0] if costs[u][0] >= 0 else float("inf")
        best = float("inf")
        for v, w in enumerate(costs[u]):
            if w >= 0 and not (mask >> v & 1):
                best = min(best, w + travel(v, mask | 1 << v))
        return best

    answer = travel(0, 1)
    return -1 if answer == float("inf") else answer


def knapsack(capacity, items):
    dp = [0] * (capacity + 1)
    for weight, value, count in items:
        size = 1
        while count:
            take = min(count, size)
            cost, gain = take * weight, take * value
            for w in range(capacity, cost - 1, -1):
                dp[w] = max(dp[w], dp[w - cost] + gain)
            size *= 2
            count -= take
    return dp[-1]


def independent_tree(weights, edges):
    n = len(weights)
    adj = [[] for _ in weights]
    for u, v in edges:
        adj[u].append(v)
        adj[v].append(u)
    parent = [-1] * n
    order = [0]
    for u in order:
        for v in adj[u]:
            if v != parent[u]:
                parent[v] = u
                order.append(v)
    yes = weights[:]
    no = [0] * n
    for u in reversed(order[1:]):
        p = parent[u]
        yes[p] += no[u]
        no[p] += max(no[u], yes[u])
    return max(yes[0], no[0])


def independent(n, edges):
    return independent_tree([1] * n, edges)


def towns(populations, edges):
    return independent_tree(populations, edges)


def cover(n, offers):
    full = (1 << n) - 1
    dp = [float("inf")] * (full + 1)
    dp[0] = 0
    for mask, price in offers:
        for s in range(full, -1, -1):
            covered = s | mask
            dp[covered] = min(dp[covered], dp[s] + price)
    return -1 if dp[full] == float("inf") else dp[full]


def winning(stones, moves):
    win = [False] * (stones + 1)
    for count in range(1, stones + 1):
        win[count] = any(step <= count and not win[count - step] for step in moves)
    return win[-1]


def lis_oracle(a):
    for length in range(len(a), -1, -1):
        for indices in itertools.combinations(range(len(a)), length):
            values = [a[i] for i in indices]
            if all(x < y for x, y in itertools.pairwise(values)):
                return values


def chain_oracle(dim):
    if len(dim) <= 2:
        return 0
    return min(
        dim[i - 1] * dim[i] * dim[i + 1] + chain_oracle(dim[:i] + dim[i + 1 :])
        for i in range(1, len(dim) - 1)
    )


@lru_cache(None)
def merge_oracle(s):
    if len(s) < 2:
        return 0
    return min(
        s[i] + s[i + 1] + merge_oracle(s[:i] + (s[i] + s[i + 1],) + s[i + 2 :]) for i in range(len(s) - 1)
    )


def tour_oracle(costs):
    if len(costs) == 1:
        return 0
    possible = []
    for p in itertools.permutations(range(1, len(costs))):
        route = (0,) + p + (0,)
        legs = [costs[a][b] for a, b in itertools.pairwise(route)]
        if min(legs) >= 0:
            possible.append(sum(legs))
    return min(possible, default=-1)


def knap_oracle(cap, items):
    return max(
        sum(k * v for k, (w, v, c) in zip(combo, items))
        for combo in itertools.product(*(range(c + 1) for w, v, c in items))
        if sum(k * w for k, (w, v, c) in zip(combo, items)) <= cap
    )


def tree_oracle(weights, edges, dominate):
    n = len(weights)
    best = 0
    for mask in range(1 << n):
        if any(mask >> u & mask >> v & 1 for u, v in edges):
            continue
        if dominate:
            covered = mask
            for u, v in edges:
                if mask >> u & 1:
                    covered |= 1 << v
                if mask >> v & 1:
                    covered |= 1 << u
            if covered != (1 << n) - 1:
                continue
        best = max(best, sum(w for u, w in enumerate(weights) if mask >> u & 1))
    return best


def cover_oracle(n, offers):
    from functools import reduce
    from operator import or_

    return min(
        (
            sum(p for _, p in group)
            for size in range(len(offers) + 1)
            for group in itertools.combinations(offers, size)
            if reduce(or_, [m for m, _ in group], 0) == (1 << n) - 1
        ),
        default=-1,
    )


def win_oracle(n, moves):
    @lru_cache(None)
    def win(k):
        return any(not win(k - v) for v in moves if v <= k)

    return win(n)


def populate(add):
    rng = random.Random(1376)
    arrays = [[3, 1, 4, 2, 5], [2, 2, 2], [], [9], [4, 3, 2, 1], [-3, -1, 0]] + [
        [rng.randrange(-8, 9) for _ in range(n)] for n in range(3, 14)
    ]
    for title, ref, kind, desc, notes in [
        (
            "가장 긴 증가 부분 수열 2",
            lis,
            "int",
            "numbers의 일부 항을 원래 순서로 선택하여 값이 엄격하게 증가하는 가장 긴 부분 수열의 길이를 반환하세요. 같은 값은 연속해서 선택할 수 없습니다.",
            ("3, 4, 5를 고르면 길이 3입니다.", "2가 여러 번 나와도 최대 길이는 1입니다."),
        ),
        (
            "가장 긴 증가 부분 수열 복원",
            restore,
            "int[]",
            "엄격하게 증가하는 가장 긴 부분 수열 하나를 값 배열로 반환하세요. 길이가 같으면 선택한 인덱스 배열이 사전순으로 가장 작은 것을 택하세요. 입력이 비면 빈 배열입니다.",
            ("인덱스 [0,2,4]의 값 [3,4,5]가 가장 먼저 선택됩니다.", "세 개 중 첫 번째 2를 택합니다."),
        ),
    ]:
        big = list(range(5000, 0, -1))
        add(
            title,
            ref,
            "int[]",
            kind,
            desc,
            "0 ≤ 길이 n ≤ 100000, 각 원소의 절댓값 ≤ 1000000.",
            "O(n log(n+2))",
            [[a] for a in arrays],
            notes,
            oracle=(lambda a: len(lis_oracle(a))) if kind == "int" else lis_oracle,
            stress=[([big], 1 if kind == "int" else [5000])],
        )
    dims = [[10, 30, 5, 60], [7, 9], [1, 1, 1, 1], [3, 8, 2, 4]] + [
        [rng.randint(1, 20) for _ in range(2 + i % 6)] for i in range(14)
    ]
    add(
        "행렬 곱셈 순서",
        matrix_chain,
        "int[]",
        "int",
        "i번째 행렬의 크기는 dimensions[i]×dimensions[i+1]입니다. 행렬의 순서를 지키며 괄호를 배치해 모든 행렬을 곱하세요. a×b와 b×c 행렬을 곱하는 비용이 abc일 때 최소 총 곱셈 횟수를 반환하세요.",
        "행렬 수 n은 1~100, 차원은 1~100입니다.",
        "O(n³)",
        [[a] for a in dims],
        ("먼저 10×30과 30×5를 곱하면 총 비용이 1500+3000=4500입니다.", "행렬 하나는 곱셈이 필요 없습니다."),
        oracle=chain_oracle,
    )
    files = [[40, 30, 30, 50], [7], [], [3, 4]] + [
        [rng.randint(1, 15) for _ in range(2 + i % 6)] for i in range(14)
    ]
    add(
        "파일 합치기",
        merge_files,
        "int[]",
        "int",
        "sizes는 순서가 고정된 파일들의 크기입니다. 한 번에 이웃한 두 파일만 합칠 수 있고, 두 크기의 합만큼 비용을 내며 그 합이 새 파일 크기가 됩니다. 하나로 합치는 최소 누적 비용을 반환하세요. 파일이 없으면 0입니다.",
        "0 ≤ 파일 수 n ≤ 500, 각 크기는 1~1000입니다.",
        "O(n²)",
        [[a] for a in files],
        ("40+30과 30+50을 따로 합친 뒤 70+80을 합치면 비용 300입니다.", "파일 하나는 합칠 필요가 없습니다."),
        oracle=lambda a: merge_oracle(tuple(a)),
        stress=[([[3] * 256], 3 * 256 * 8)],
    )
    costs = [[[0, 2, 6], [3, 0, 4], [1, 5, 0]], [[0, 3], [-1, 0]], [[0]], [[0, 0], [0, 0]]]
    for i in range(12):
        n = 2 + i % 5
        costs.append([[0 if u == v else rng.randint(-1, 6) for v in range(n)] for u in range(n)])
    add(
        "외판원 순회",
        tour,
        "int[][]",
        "int",
        "costs[u][v]는 u에서 v로 가는 비용이며 −1은 도로가 없다는 뜻입니다. 도시 0에서 시작해 모든 도시를 정확히 한 번씩 방문하고 0으로 돌아오는 최소 비용을 반환하세요. 순회가 없으면 −1, 도시가 하나면 0입니다.",
        "도시 수 1 ≤ n ≤ 14, costs는 n×n이며 대각선은 0, 도로 비용은 0~100000입니다.",
        "O(n² 2ⁿ)",
        [[a] for a in costs],
        ("0→1→2→0은 비용 2+4+1=7입니다.", "1에서 0으로 돌아오는 도로가 없습니다."),
        oracle=tour_oracle,
        stress=[([[[0 if u == v else 1 for v in range(12)] for u in range(12)]], 12)],
    )
    packs = [[7, [[3, 5, 2], [2, 4, 3]]], [1, [[2, 10, 3]]], [0, []]]
    for i in range(14):
        packs.append(
            [i + 1, [[rng.randint(1, 7), rng.randint(1, 20), rng.randrange(4)] for _ in range(i % 4 + 1)]]
        )
    add(
        "배낭 문제 2",
        knapsack,
        "int int[][]",
        "int",
        "각 종류 [무게,가치,보유 개수]에서 보유량 이내로 골라 총 무게를 capacity 이내로 제한할 때 최대 총 가치를 반환하세요. 한 종류를 여러 개 고를 수 있고 하나도 고르지 않아도 됩니다.",
        "종류 수 n ≤ 100, 0 ≤ capacity ≤ 5000, 무게와 가치는 1~1000, 각 보유량 c는 0~64입니다.",
        "O(n×capacity×log(c+2))",
        packs,
        ("무게 3 하나와 무게 2 둘은 무게 7, 가치 13입니다.", "모든 물건이 배낭보다 무겁습니다."),
        oracle=knap_oracle,
    )
    trees = [[4, [[0, 1], [1, 2], [1, 3]]], [1, []], [2, [[0, 1]]]]
    for i in range(14):
        n = 3 + i % 7
        trees.append([n, [[u, rng.randrange(u)] for u in range(1, n)]])
    tree_limits = "1 ≤ n ≤ 100000, 정점은 0부터 n−1까지입니다. 무방향 간선 n−1개로 이루어진 연결 트리입니다."
    add(
        "트리 독립 집합",
        independent,
        "int int[][]",
        "int",
        "edges에 있는 간선으로 직접 이어진 두 정점은 동시에 고를 수 없습니다. 트리에서 고를 수 있는 최대 정점 수를 반환하세요.",
        tree_limits,
        "O(n)",
        trees,
        ("1을 고르지 않으면 0, 2, 3을 함께 선택할 수 있습니다.", "정점 하나를 고를 수 있습니다."),
        oracle=lambda n, e: tree_oracle([1] * n, e, False),
        helpers=(independent_tree,),
        stress=[([3000, [[i, i + 1] for i in range(2999)]], 1500)],
    )
    village = [[[2, 8, 3, 2], trees[0][1]], [[9], []]] + [
        [[rng.randint(1, 15) for _ in range(n)], edges] for n, edges in trees
    ]
    add(
        "우수 마을 선정",
        towns,
        "int[] int[][]",
        "int",
        "인구가 populations인 마을들이 트리를 이룹니다. 이웃한 두 마을은 동시에 우수 마을이 될 수 없고 선정되지 않은 각 마을은 우수 마을 하나 이상과 이웃해야 합니다. 선정한 마을의 최대 총인구를 반환하세요.",
        tree_limits + " 각 마을의 인구는 1~1000입니다.",
        "O(n)",
        village,
        (
            "중심 마을 1의 인구 8은 나머지 마을들의 합 7보다 많습니다.",
            "유일한 마을을 선정하면 인구 합은 9입니다.",
        ),
        oracle=lambda w, e: tree_oracle(w, e, True),
        helpers=(independent_tree,),
    )
    masks = [[3, [[3, 5], [4, 2], [7, 9]]], [2, [[1, 3]]], [0, []]]
    for i in range(15):
        n = i % 5 + 1
        masks.append([n, [[rng.randrange(1 << n), rng.randrange(1, 12)] for _ in range(i % 7 + 1)]])
    add(
        "비트마스크 집합 상태",
        cover,
        "int int[][]",
        "int",
        "기술 0..n−1을 모두 배우려 합니다. 각 강좌 [mask,price]에서 mask의 i번째 비트가 1이면 기술 i를 배웁니다. 강좌를 각각 최대 한 번 선택할 때 모든 기술을 배우는 최소 비용을 반환하세요. 가능하지 않으면 −1입니다.",
        "0 ≤ n ≤ 15, 강좌 수 q ≤ 70, 0 ≤ mask < 2ⁿ, 1 ≤ price ≤ 100000.",
        "O(q 2ⁿ)",
        masks,
        (
            "마스크 3과 4를 합쳐 모든 비트를 7의 비용으로 얻습니다.",
            "두 번째 기술을 가르치는 강좌가 없습니다.",
        ),
        oracle=cover_oracle,
    )
    games = [[4, [1, 3]], [6, [1, 2, 3]], [0, [1]], [3, []]] + [
        [i, sorted({rng.randint(1, 8) for _ in range(5)})] for i in range(1, 17)
    ]
    add(
        "게임의 승패 결정",
        winning,
        "int int[]",
        "bool",
        "돌 stones개를 두 사람이 번갈아 가져갑니다. 한 번에 moves 중 하나만큼 가져가며 남은 돌보다 많이 가져갈 수 없습니다. 가능한 수가 없는 사람이 패배합니다. 양쪽이 최적으로 플레이할 때 선공이 이기면 true입니다.",
        "0 ≤ stones ≤ 100000, moves는 서로 다른 1~1000 사이 정수 최대 50개입니다.",
        "O((stones+1)(|moves|+1))",
        games,
        (
            "4개에서 1개나 3개를 가져가면 상대에게 승리 상태를 넘깁니다.",
            "2개를 가져가 4개를 남기면 상대가 무엇을 해도 마지막 돌을 선공이 가져갑니다.",
        ),
        oracle=win_oracle,
        stress=[([100000, [1, 2, 3]], False)],
    )
