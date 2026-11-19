"""고급 자료구조. 조회 결과는 작은 배열을 직접 갱신하는 오라클과 대조한다."""

import random


def components(n):
    parent = list(range(n))
    sizes = [1] * n

    def root(x):
        while x != parent[x]:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    def unite(x, y):
        a, b = root(x), root(y)
        if a != b:
            if sizes[a] < sizes[b]:
                a, b = b, a
            parent[b] = a
            sizes[a] += sizes[b]
        return sizes[a]

    return root, unite


def disjoint(n, operations):
    root, unite = components(n)
    answers = []
    for kind, x, y in operations:
        if kind == 0:
            unite(x, y)
        else:
            answers.append(int(root(x) == root(y)))
    return answers


def friends(pairs):
    ids = {}
    _, unite = components(2 * len(pairs))
    answers = []
    for x, y in pairs:
        i = ids.setdefault(x, len(ids))
        j = ids.setdefault(y, len(ids))
        answers.append(unite(i, j))
    return answers


def heaps(operations):
    import heapq

    small, large, counts, answers = [], [], {}, []
    for kind, x in operations:
        if kind == 0:
            heapq.heappush(small, x)
            heapq.heappush(large, -x)
            counts[x] = counts.get(x, 0) + 1
            continue
        heap, sign = (small, 1) if kind == 1 else (large, -1)
        while heap and counts[sign * heap[0]] == 0:
            heapq.heappop(heap)
        if heap:
            value = sign * heapq.heappop(heap)
            counts[value] -= 1
            answers.append(value)
        else:
            answers.append(-1)
    return answers


def medians(numbers):
    import heapq

    smaller, larger, answer = [], [], []
    for value in numbers:
        heapq.heappush(smaller, -value)
        heapq.heappush(larger, -heapq.heappop(smaller))
        if len(larger) > len(smaller):
            heapq.heappush(smaller, -heapq.heappop(larger))
        answer.append(-smaller[0])
    return answer


def tree(numbers):
    size = 1
    while size < len(numbers):
        size *= 2
    nodes = [0] * (2 * size)
    nodes[size : size + len(numbers)] = numbers
    for i in range(size - 1, 0, -1):
        nodes[i] = nodes[2 * i] + nodes[2 * i + 1]
    return nodes[1:]


def indexed_sum(n):
    cells = [0] * (n + 1)

    def add(pos, change):
        pos += 1
        while pos <= n:
            cells[pos] += change
            pos += pos & -pos

    def prefix(pos):
        total = 0
        pos += 1
        while pos > 0:
            total += cells[pos]
            pos -= pos & -pos
        return total

    return add, prefix


def replace_sum(numbers, operations):
    update, prefix = indexed_sum(len(numbers))
    for i, x in enumerate(numbers):
        update(i, x)
    answer = []
    for kind, a, b in operations:
        if kind == 0:
            update(a, b - numbers[a])
            numbers[a] = b
        else:
            answer.append(prefix(b) - prefix(a - 1))
    return answer


def min_queries(numbers, queries):
    size = len(numbers)
    cells = [0] * size + numbers[:]
    for i in range(size - 1, 0, -1):
        cells[i] = min(cells[2 * i], cells[2 * i + 1])
    answer = []
    for left, right in queries:
        left += size
        right += size + 1
        minimum = float("inf")
        while left < right:
            if left % 2:
                minimum = min(minimum, cells[left])
                left += 1
            if right % 2:
                right -= 1
                minimum = min(minimum, cells[right])
            left //= 2
            right //= 2
        answer.append(minimum)
    return answer


def fenwick(numbers, operations):
    update, prefix = indexed_sum(len(numbers))
    for i, x in enumerate(numbers):
        update(i, x)
    answer = []
    for kind, i, x in operations:
        if kind == 0:
            update(i, x)
        else:
            answer.append(prefix(i))
    return answer


def range_sums(numbers, operations):
    n = len(numbers)
    changes, first = indexed_sum(n)
    moments, second = indexed_sum(n)

    def point(pos, delta):
        changes(pos, delta)
        moments(pos, pos * delta)

    def prefix(pos):
        return (pos + 1) * first(pos) - second(pos)

    previous = 0
    for pos, x in enumerate(numbers):
        point(pos, x - previous)
        previous = x
    answer = []
    for kind, left, right, delta in operations:
        if kind == 0:
            point(left, delta)
            point(right + 1, -delta)
        else:
            answer.append(prefix(right) - prefix(left - 1))
    return answer


def statistics(operations):
    values = sorted({x for kind, x in operations if kind <= 1})
    indices = {x: i for i, x in enumerate(values)}
    counts = [0] * len(values)
    cells = [0] * (len(values) + 1)
    total = 0
    answers = []
    for kind, x in operations:
        if kind == 2:
            if x > total:
                answers.append(-1)
                continue
            pos = 0
            step = 1 << (len(values).bit_length() - 1)
            while step:
                to = pos + step
                if to < len(cells) and cells[to] < x:
                    pos = to
                    x -= cells[to]
                step //= 2
            answers.append(values[pos])
        else:
            pos = indices[x]
            if kind == 1 and counts[pos] == 0:
                continue
            delta = 1 if kind == 0 else -1
            counts[pos] += delta
            total += delta
            pos += 1
            while pos < len(cells):
                cells[pos] += delta
                pos += pos & -pos
    return answers


def labels(n, ops):
    groups = list(range(n))
    out = []
    for k, a, b in ops:
        if k:
            out.append(int(groups[a] == groups[b]))
        else:
            old, new = groups[b], groups[a]
            groups = [new if x == old else x for x in groups]
    return out


def network(pairs):
    groups = []
    sizes = []
    for a, b in pairs:
        joined = {a, b}
        keep = []
        for group in groups:
            if a in group or b in group:
                joined.update(group)
            else:
                keep.append(group)
        groups = keep + [joined]
        sizes.append(len(joined))
    return sizes


def naive(numbers, ops, mode):
    values = numbers[:]
    answer = []
    for op in ops:
        if mode == "min":
            answer.append(min(values[op[0] : op[1] + 1]))
        elif mode == "sum":
            k, a, b = op
            if k:
                answer.append(sum(values[a : b + 1]))
            else:
                values[a] = b
        elif mode == "prefix":
            k, a, b = op
            if k:
                answer.append(sum(values[: a + 1]))
            else:
                values[a] += b
        else:
            k, a, b, delta = op
            if k:
                answer.append(sum(values[a : b + 1]))
            else:
                for i in range(a, b + 1):
                    values[i] += delta
    return answer


def sorted_ops(operations, mode):
    bag = []
    result = []
    for k, x in operations:
        if k == 0:
            bag.append(x)
            bag.sort()
        elif mode == "heap":
            result.append(bag.pop(0 if k == 1 else -1) if bag else -1)
        elif k == 1:
            if x in bag:
                bag.remove(x)
        else:
            result.append(bag[x - 1] if x <= len(bag) else -1)
    return result


def populate(add):
    rng = random.Random(1356)
    group_cases = [[3, [[0, 0, 1], [1, 1, 0], [1, 0, 2]]], [1, [[1, 0, 0]]]]
    for i in range(12):
        n = 2 + i % 5
        group_cases.append(
            [n, [[rng.randrange(2), rng.randrange(n), rng.randrange(n)] for _ in range(i + 1)]]
        )
    add(
        "서로소 집합",
        disjoint,
        "int int[][]",
        "int[]",
        "0부터 n−1까지의 원소가 각각 별도 집합에 있습니다. [0,x,y]는 두 원소의 집합을 합치고 [1,x,y]는 같은 집합인지 조회합니다. 조회 순서대로 같으면 1, 다르면 0을 반환하세요.",
        "1 ≤ n ≤ 100000, 연산 수 q ≤ 100000. x와 y는 유효한 원소 번호입니다.",
        "O(n+q α(n))",
        group_cases,
        ("0과 1을 합친 뒤 두 조회는 각각 참과 거짓입니다.", "원소는 항상 자신과 같은 집합에 있습니다."),
        oracle=labels,
        helpers=(components,),
    )
    friendships = [[[["amy", "bo"], ["bo", "cy"], ["amy", "cy"]]], [[["a", "a"], ["b", "c"]]]] + [
        [[[str(a), str(b)] for _, a, b in ops]] for _, ops in group_cases
    ]
    add(
        "친구 네트워크",
        friends,
        "string[][]",
        "int[]",
        "각 행 [a,b]가 입력 순서대로 두 사람 사이에 친구 관계를 추가합니다. 친구의 친구도 같은 네트워크에 속합니다. 관계를 추가할 때마다 a의 네트워크 인원수를 차례로 반환하세요.",
        "관계 수 q ≤ 50000. 이름은 길이 1~16의 ASCII 영소문자 또는 숫자입니다. 같은 관계와 자기 자신과의 관계도 가능합니다.",
        "O(q α(q)) 기대",
        friendships,
        (
            "처음 두 사람, 다음 세 사람, 마지막에도 같은 세 명입니다.",
            "a의 집합은 1명, 별개로 만난 b와 c의 집합은 2명입니다.",
        ),
        oracle=network,
        helpers=(components,),
    )
    ops = [[[[0, 4], [0, 1], [2, 0], [1, 0], [1, 0]]], [[[0, 3], [0, 3], [1, 0], [2, 0]]], [[]]]
    for i in range(12):
        ops.append([[[k, rng.randint(-5, 5) if k == 0 else 0] for k in rng.choices(range(3), k=i + 3)]])
    add(
        "최소 힙과 최대 힙",
        heaps,
        "int[][]",
        "int[]",
        "빈 다중집합에 [0,x]는 x 한 개를 삽입합니다. [1,0]은 최솟값, [2,0]은 최댓값 한 개를 꺼냅니다. 꺼낸 값들을 명령 순서대로 반환하세요. 비어 있으면 −1을 기록합니다.",
        "명령 수 q ≤ 100000, |x| ≤ 1000000.",
        "O(q log(q+2))",
        ops,
        (
            "4와 1에서 최댓값 4, 최솟값 1, 빈 집합의 −1을 꺼냅니다.",
            "같은 3을 두 번 넣어 두 번 모두 3을 꺼냅니다.",
        ),
        oracle=lambda operations: sorted_ops(operations, "heap"),
    )
    arrays = [[[5, 1, 4, 2]], [[9, 9]], [[]], [[0]], [[-4, -2, -7]]] + [
        [[rng.randint(-9, 9) for _ in range(i + 1)]] for i in range(12)
    ]
    add(
        "중앙값 유지하기",
        medians,
        "int[]",
        "int[]",
        "numbers를 왼쪽부터 한 개씩 읽습니다. 지금까지 읽은 값들을 정렬했을 때의 중앙값을 매번 반환하세요. 원소 수가 짝수이면 가운데 두 값 중 작은 값입니다.",
        "0 ≤ 원소 수 n ≤ 100000, 각 값의 절댓값 ≤ 1000000.",
        "O(n log(n+2))",
        arrays,
        ("각 접두 구간의 중앙값은 5, 1, 4, 2입니다.", "두 값이 같아 중앙값도 계속 9입니다."),
        oracle=lambda a: [sorted(a[:i])[(i - 1) // 2] for i in range(1, len(a) + 1)],
        stress=[([list(range(4000))], [i // 2 for i in range(4000)])],
    )

    def tree_oracle(a):
        leaf = 1 << (len(a) - 1).bit_length()
        padded = a + [0] * (leaf - len(a))
        return [
            sum(padded[s : s + w])
            for w in [leaf >> k for k in range(leaf.bit_length())]
            for s in range(0, leaf, w)
        ]

    nonempty = [v for v in arrays if v[0]]
    add(
        "세그먼트 트리 구축",
        tree,
        "int[]",
        "int[]",
        "배열 뒤에 0을 채워 길이를 n 이상인 최소 2의 거듭제곱 P로 만드세요. 이를 왼쪽부터 리프로 사용하고 각 부모에는 두 자식의 합을 둡니다. 완성된 합 트리의 2P−1개 값을 루트부터 레벨 순서로 반환하세요.",
        "1 ≤ n ≤ 65536, 각 원소의 절댓값 ≤ 10000.",
        "O(n)",
        nonempty,
        ("루트는 12이고 두 자식은 6과 6이며 그 아래에 원래 배열이 옵니다.", "9 두 개의 부모는 18입니다."),
        oracle=tree_oracle,
    )
    base = "1 ≤ n ≤ 30000, q ≤ 30000. 모든 위치는 유효하며 구간 양 끝은 포함합니다. "
    for title, ref, mode, desc, example, notes, helpers in [
        (
            "구간 합과 값 갱신",
            replace_sum,
            "sum",
            "[0,i,x]는 numbers[i]를 x로 교체합니다. [1,l,r]은 l부터 r까지의 합을 묻습니다. 조회 결과를 순서대로 반환하세요.",
            [[1, 0, 2], [0, 1, -2], [1, 1, 2]],
            (
                "처음 합은 8, 두 번째 값을 −2로 바꾼 뒤 마지막 두 값의 합은 2입니다.",
                "원소 하나의 합은 −3입니다.",
            ),
            (indexed_sum,),
        ),
        (
            "구간 최솟값 쿼리",
            min_queries,
            "min",
            "배열을 고정한 채 각 [l,r]에 대해 numbers[l]부터 numbers[r] 중 가장 작은 값을 반환하세요. 결과는 쿼리 순서입니다.",
            [[0, 2], [1, 1]],
            ("전체 최솟값은 1이고 인덱스 1의 값은 1입니다.", "길이 1인 구간의 최솟값은 −3입니다."),
            (),
        ),
        (
            "펜윅 트리",
            fenwick,
            "prefix",
            "[0,i,x]는 numbers[i]에 x를 더합니다. [1,i,0]은 인덱스 0부터 i까지의 누적 합을 묻습니다. 펜윅 트리로 처리한 각 조회 결과를 반환하세요.",
            [[1, 2, 0], [0, 1, -2], [1, 1, 0]],
            ("처음 전체 합은 8, −2를 더한 뒤 앞 두 수의 합은 2입니다.", "처음 한 항의 누적 합은 −3입니다."),
            (indexed_sum,),
        ),
        (
            "구간 업데이트와 합",
            range_sums,
            "range",
            "[0,l,r,d]는 구간의 모든 원소에 d를 더합니다. [1,l,r,0]은 현재 구간 합을 묻습니다. 모든 조회의 답을 순서대로 반환하세요.",
            [[1, 0, 2, 0], [0, 0, 1, 2], [1, 1, 2, 0]],
            (
                "처음 합은 8, 앞 두 수에 2를 더한 후 마지막 두 수의 합은 7입니다.",
                "한 원소만 포함하는 구간의 합은 −3입니다.",
            ),
            (indexed_sum,),
        ),
    ]:
        single = {"sum": [1, 0, 0], "min": [0, 0], "prefix": [1, 0, 0], "range": [1, 0, 0, 0]}[mode]
        cases = [[[3, 1, 4], example], [[-3], [single]]]
        for (a,) in nonempty:
            n = len(a)
            queries = []
            for i in range(12):
                l, r = sorted([rng.randrange(n), rng.randrange(n)])
                k = i % 3 != 0
                if mode == "min":
                    op = [l, r]
                elif mode == "range":
                    op = [int(k), l, r, 0 if k else rng.randint(-5, 5)]
                elif mode == "prefix":
                    op = [int(k), r, 0 if k else rng.randint(-5, 5)]
                else:
                    op = [int(k), l, r if k else rng.randint(-5, 5)]
                queries.append(op)
            cases.append([a, queries])
        add(
            title,
            ref,
            "int[] int[][]",
            "int[]",
            desc,
            base + "원소·변경 인자의 절댓값은 10000 이하이며 각 조회 결과는 32비트 범위입니다.",
            "O((n+q) log(n+2))",
            cases,
            notes,
            oracle=lambda a, ops, query_mode=mode: naive(a, ops, query_mode),
            helpers=helpers,
        )
    stats = [[[[0, 7], [0, 2], [0, 7], [2, 2], [1, 7], [2, 3]]], [[[1, 4], [2, 1]]], [[]]]
    for i in range(12):
        stats.append([[[k, rng.randint(1, 5)] for k in rng.choices(range(3), k=i + 2)]])
    add(
        "순서 통계 트리",
        statistics,
        "int[][]",
        "int[]",
        "빈 다중집합에 [0,x]는 x 한 개를 삽입하고 [1,x]는 있으면 한 개를 삭제합니다. [2,k]는 현재 원소 중 중복 포함 k번째 작은 값(1부터)을 묻습니다. 조회 순서대로 답을 반환하고 k가 원소 수보다 크면 −1을 기록하세요.",
        "명령 수 q ≤ 100000, |x| ≤ 1000000, 1 ≤ k ≤ 100000.",
        "O(q log(q+2))",
        stats,
        (
            "2, 7, 7 중 두 번째는 7입니다. 7 하나를 지우면 세 번째 원소는 없습니다.",
            "없는 값을 삭제해도 집합은 비어 있습니다.",
        ),
        oracle=lambda operations: sorted_ops(operations, "stat"),
    )
