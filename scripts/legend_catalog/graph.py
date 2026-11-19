"""최단 경로와 그래프. 작은 그래프의 답은 전수 탐색 또는 독립 알고리즘으로 확인한다."""

import itertools
import random


def graph(n, edges):
    adjacent = [[] for _ in range(n)]
    for u, v, w in edges:
        adjacent[u].append((v, w))
    return adjacent


def distances(adjacent, start):
    import heapq

    best = [float("inf")] * len(adjacent)
    best[start] = 0
    heap = [(0, start)]
    while heap:
        cost, u = heapq.heappop(heap)
        if cost != best[u]:
            continue
        for v, w in adjacent[u]:
            new = cost + w
            if new < best[v]:
                best[v] = new
                heapq.heappush(heap, (new, v))
    return best


def dijkstra(n, edges, start):
    return [-1 if d == float("inf") else d for d in distances(graph(n, edges), start)]


def via(n, edges, start, waypoint, target):
    adjacent = graph(n, edges)
    cost = distances(adjacent, start)[waypoint] + distances(adjacent, waypoint)[target]
    return -1 if cost == float("inf") else cost


def kth(n, edges, start, k):
    import heapq

    adjacent = graph(n, edges)
    popped = [0] * n
    answer = [-1] * n
    heap = [(0, start)]
    while heap:
        cost, u = heapq.heappop(heap)
        if popped[u] == k:
            continue
        popped[u] += 1
        if popped[u] == k:
            answer[u] = cost
        for v, w in adjacent[u]:
            if popped[v] < k:
                heapq.heappush(heap, (cost + w, v))
    return answer


def bellman(n, edges, start):
    best = [float("inf")] * n
    best[start] = 0
    for _ in range(n - 1):
        updated = False
        for u, v, w in edges:
            if best[u] + w < best[v]:
                best[v] = best[u] + w
                updated = True
        if not updated:
            break
    return [-1000000000 if d == float("inf") else d for d in best]


def negative(n, edges):
    best = [0] * n
    for _ in range(n):
        updated = False
        for u, v, w in edges:
            if best[u] + w < best[v]:
                best[v] = best[u] + w
                updated = True
        if not updated:
            return False
    return True


def floyd(n, edges):
    d = [[float("inf")] * n for _ in range(n)]
    for u in range(n):
        d[u][u] = 0
    for u, v, w in edges:
        d[u][v] = min(d[u][v], w)
    for k in range(n):
        for u in range(n):
            if d[u][k] == float("inf"):
                continue
            for v in range(n):
                d[u][v] = min(d[u][v], d[u][k] + d[k][v])
    return [[-1 if c == float("inf") else c for c in row] for row in d]


def path(n, edges, start, target):
    adjacent = graph(n, edges)
    back = distances(graph(n, [(v, u, w) for u, v, w in edges]), target)
    if back[start] == float("inf"):
        return []
    route = [start]
    while route[-1] != target:
        u = route[-1]
        route.append(min(v for v, w in adjacent[u] if back[u] == w + back[v]))
    return route


def kruskal(n, edges):
    parent = list(range(n))
    size = [1] * n

    def root(x):
        while x != parent[x]:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    count = total = 0
    for u, v, w in sorted(edges, key=lambda e: e[2]):
        a, b = root(u), root(v)
        if a != b:
            if size[a] < size[b]:
                a, b = b, a
            parent[b] = a
            size[a] += size[b]
            count += 1
            total += w
    return total if count == n - 1 else -1


def prim(n, edges):
    import heapq

    adjacent = graph(n, edges + [[v, u, w] for u, v, w in edges])
    visited = set()
    heap = [(0, 0)]
    cost = 0
    while heap:
        weight, u = heapq.heappop(heap)
        if u in visited:
            continue
        visited.add(u)
        cost += weight
        for v, w in adjacent[u]:
            if v not in visited:
                heapq.heappush(heap, (w, v))
    return cost if len(visited) == n else -1


def reconstruct(matrix):
    n = len(matrix)
    total = 0
    for i in range(n):
        for j in range(i + 1, n):
            required = True
            for k in range(n):
                if k == i or k == j:
                    continue
                via_k = matrix[i][k] + matrix[k][j]
                if via_k < matrix[i][j]:
                    return -1
                if via_k == matrix[i][j]:
                    required = False
            if required:
                total += matrix[i][j]
    return total


def path_oracle(n, edges, start, target):
    paths = []

    def visit(route, cost):
        if route[-1] == target:
            paths.append((cost, route))
            return
        for u, v, w in edges:
            if u == route[-1] and v not in route:
                visit(route + [v], cost + w)

    visit([start], 0)
    return min(paths) if paths else (None, [])


def kth_oracle(n, edges, start, k):
    # 양의 가중치. 정점당 k개 경로를 찾으려면 단순 경로와 사이클을 합쳐 nk개 이하 간선이면 충분하다.
    limit = n * k * max((w for _, _, w in edges), default=1)
    ways = [[0] * n for _ in range(limit + 1)]
    ways[0][start] = 1
    counts = [0] * n
    answer = [-1] * n
    for cost, row in enumerate(ways):
        for u, count in enumerate(row):
            counts[u] += count
            if answer[u] < 0 and counts[u] >= k:
                answer[u] = cost
        for u, v, w in edges:
            if cost + w <= limit:
                ways[cost + w][v] = min(k, ways[cost + w][v] + row[u])
    return answer


def mst_oracle(n, edges):
    best = None
    for chosen in itertools.combinations(edges, n - 1):
        seen = {0}
        for _ in range(n):
            for u, v, _ in chosen:
                if u in seen or v in seen:
                    seen.update((u, v))
        if len(seen) == n:
            cost = sum(row[2] for row in chosen)
            best = cost if best is None else min(best, cost)
    return -1 if best is None else best


def populate(add):
    rng = random.Random(1346)
    graphs = [
        (4, [[0, 1, 2], [0, 2, 5], [1, 2, 1], [2, 3, 1]], 0),
        (3, [[0, 1, 4]], 1),
        (1, [], 0),
        (2, [[0, 1, 5], [0, 1, 2]], 0),
        (4, [[0, 2, 1], [0, 1, 1], [1, 3, 1], [2, 3, 1]], 0),
    ]
    for i in range(12):
        n = 2 + i % 5
        edges = [[rng.randrange(n), rng.randrange(n), rng.randint(1, 8)] for _ in range(i % 8 + 2)]
        graphs.append((n, edges, rng.randrange(n)))
    nh = 1200
    chain = [[i, i + 1, 1] for i in range(nh - 1)]
    typed = "int int[][] int"
    directed = "`edges`의 각 [u,v,w]는 u에서 v로 가는 비용 w인 방향 간선입니다. 평행 간선과 자기 루프가 있을 수 있습니다. "
    bound = "1 ≤ n ≤ 20,000, 간선 수 m ≤ 80,000, 1 ≤ w ≤ 1000. 정점 번호는 0부터 n−1까지입니다."
    route_cost = lambda n, e, s, t: path_oracle(n, e, s, t)[0]
    dists = lambda n, e, s: [(-1 if (d := route_cost(n, e, s, v)) is None else d) for v in range(n)]
    add(
        "다익스트라 최단 경로",
        dijkstra,
        typed,
        "int[]",
        directed
        + "`start`에서 각 정점까지 최소 비용을 정점 번호순으로 반환하세요. 시작점은 0, 도달할 수 없는 정점은 −1입니다.",
        bound,
        "O((n+m) log(m+2))",
        graphs,
        ("0→1→2→3의 누적 비용은 0, 2, 3, 4입니다.", "1에서 나가는 간선이 없어 1만 비용 0입니다."),
        oracle=dists,
        helpers=(graph, distances),
        stress=[([nh, chain, 0], list(range(nh)))],
    )
    trips = [[n, e, s, 1 if n > 1 else 0, n - 1] for n, e, s in graphs]

    def through(n, e, s, v, t):
        a, b = route_cost(n, e, s, v), route_cost(n, e, v, t)
        return -1 if a is None or b is None else a + b

    add(
        "특정 정점 경유 최단 경로",
        via,
        typed + " int int",
        "int",
        directed
        + "출발점 `start`에서 `waypoint`를 적어도 한 번 거쳐 `target`에 이르는 최소 비용을 구하세요. 이동 중 정점을 다시 방문할 수 있습니다. 경로가 없으면 −1을 반환하세요.",
        bound,
        "O((n+m) log(m+2))",
        trips,
        ("0에서 1까지 2, 1에서 3까지 2이므로 합은 4입니다.", "1에서 출발해 2에 도달할 수 없습니다."),
        oracle=through,
        helpers=(graph, distances),
    )
    ks = [[2, [[0, 1, 2], [0, 1, 2], [1, 0, 1]], 0, 2], [1, [], 0, 2]] + [
        [n, e, s, i % 4 + 1] for i, (n, e, s) in enumerate(graphs)
    ]
    add(
        "K번째 최단 경로",
        kth,
        typed + " int",
        "int[]",
        directed
        + "시작점에서 각 정점까지 가능한 모든 보행을 비용순으로 나열할 때 k번째 비용을 정점 번호순 배열로 반환하세요. 간선의 순서가 다르면 다른 보행이며 동일한 비용도 각각 셉니다. 간선을 반복할 수 있고 시작점의 길이 0 보행도 포함합니다. 보행이 k개 미만이면 −1입니다.",
        "1 ≤ n ≤ 1000, m ≤ 5000, 1 ≤ k ≤ 20, 1 ≤ w ≤ 1000.",
        "O(km log(km+2)+n)",
        ks,
        (
            "0의 두 번째 보행은 왕복 비용 3, 1은 비용 2인 간선이 두 개입니다.",
            "빈 보행 하나만 있으므로 두 번째 값은 −1입니다.",
        ),
        oracle=kth_oracle,
        helpers=(graph,),
    )
    neggraphs = [[4, [[0, 1, 4], [0, 2, 7], [1, 2, -3]], 0], [2, [[1, 0, -1]], 0]] + [
        [n, [[min(u, v), max(u, v), w - 4] for u, v, w in e if u != v], s] for n, e, s in graphs
    ]
    add(
        "벨만-포드 알고리즘",
        bellman,
        typed,
        "int[]",
        directed
        + "음수 간선을 허용합니다. 시작점에서 도달 가능한 음수 사이클은 주어지지 않습니다. 시작점에서 각 정점까지의 최단 거리를 번호순으로 반환하세요. 도달 불가 값은 −1000000000입니다.",
        "1 ≤ n ≤ 500, m ≤ 3000, −1000 ≤ w ≤ 1000.",
        "O(nm+n)",
        neggraphs,
        ("0→1→2의 비용은 1이며 정점 3에는 갈 수 없습니다.", "0에서 정점 1로 향하는 간선이 없습니다."),
        oracle=lambda n, e, s: [
            -1000000000 if (d := route_cost(n, e, s, v)) is None else d for v in range(n)
        ],
    )
    signed = [[3, [[1, 2, -2], [2, 1, 1]]], [2, [[0, 1, -1]]]] + [
        [n, [[u, v, w - 4] for u, v, w in e]] for n, e, s in graphs
    ]

    def neg_oracle(n, e):
        for u, v, w in e:
            back = route_cost(n, e, v, u)
            if back is not None and w + back < 0:
                return True
        return False

    add(
        "음수 사이클 탐지",
        negative,
        "int int[][]",
        "bool",
        directed
        + "어느 연결 성분에서든 가중치 합이 음수인 방향 사이클이 하나 이상 있으면 true를 반환하세요.",
        "1 ≤ n ≤ 500, m ≤ 3000, −1000 ≤ w ≤ 1000.",
        "O(nm+n)",
        signed,
        ("1→2→1의 비용 합은 −1입니다.", "음수 간선은 있지만 출발점으로 돌아오는 경로가 없습니다."),
        oracle=neg_oracle,
    )
    pairs = [[n, e] for n, e, _ in graphs]
    add(
        "플로이드-워셜",
        floyd,
        "int int[][]",
        "int[][]",
        directed
        + "모든 정점 쌍의 최단 거리를 행렬로 반환하세요. i행 j열은 i에서 j까지의 거리이며 자기 자신은 0, 도달 불가는 −1입니다.",
        "1 ≤ n ≤ 150, m ≤ 12000, 1 ≤ w ≤ 1000.",
        "O(n³+m)",
        pairs,
        (
            "0→2는 직접 가는 비용 5보다 1을 거치는 비용 3이 작습니다.",
            "0→1만 비용 4이고 모든 대각 원소는 0입니다.",
        ),
        oracle=lambda n, e: [dists(n, e, s) for s in range(n)],
    )
    add(
        "최소 비용 경로 복원",
        path,
        typed + " int",
        "int[]",
        directed
        + "start에서 target까지 최저 비용 경로의 정점 번호를 순서대로 반환하세요. 동률이면 정점 배열이 사전순으로 가장 작은 경로를 선택합니다. 도달할 수 없으면 빈 배열입니다.",
        bound,
        "O((n+m) log(m+2))",
        [[n, e, s, n - 1] for n, e, s in graphs],
        ("0→1→2→3의 비용이 4로 가장 작습니다.", "1에서 2까지 가는 경로가 없습니다."),
        oracle=lambda n, e, s, t: path_oracle(n, e, s, t)[1],
        helpers=(graph, distances),
    )
    undirected = "`edges`의 [u,v,w]는 비용 w인 무방향 간선입니다. 모든 정점을 연결하는 트리의 최소 간선 비용 합을 구하세요. 간선이 평행하거나 자기 루프일 수 있습니다. 연결할 수 없으면 −1을 반환하세요."
    for title, ref, h, method in [
        ("최소 스패닝 트리", kruskal, (), "크루스칼"),
        ("최소 스패닝 트리 2", prim, (graph,), "프림"),
    ]:
        add(
            title,
            ref,
            "int int[][]",
            "int",
            undirected + " " + method + " 알고리즘을 사용하세요.",
            bound,
            "O((m+n) log(m+2))",
            pairs,
            ("0–1, 1–2, 2–3을 고르면 전체 비용은 4입니다.", "정점 2가 나머지 정점과 연결되지 않습니다."),
            oracle=mst_oracle,
            helpers=h,
            stress=[([nh, chain], nh - 1)],
        )
    matrices = [
        ([[[0, 2, 5], [2, 0, 3], [5, 3, 0]]], 5),
        ([[[0, 2, 9], [2, 0, 3], [9, 3, 0]]], -1),
        ([[[0]]], 0),
    ]
    for n in range(2, 10):
        matrices.extend(
            [
                ([[[abs(i - j) for j in range(n)] for i in range(n)]], n - 1),
                ([[[0 if i == j else 2 for j in range(n)] for i in range(n)]], n * (n - 1)),
            ]
        )
    add(
        "도로 네트워크 재구성",
        reconstruct,
        "int[][]",
        "int",
        "대칭 행렬 matrix[i][j]는 두 도시 사이의 최단 거리라고 보고되었습니다. 같은 도시의 거리는 0, 다른 도시의 거리는 양수입니다. 이 행렬을 정확히 만드는 무방향 도로망의 최소 총 도로 길이를 반환하세요. 가능한 도로망이 없으면 −1입니다.",
        "1 ≤ 도시 수 n ≤ 150, 거리 ≤ 50,000.",
        "O(n³)",
        matrices,
        (
            "거리 5인 0–2는 길이 2와 3인 두 도로로 갈 수 있어 총 5면 충분합니다.",
            "0에서 1을 거쳐 2로 가면 5이므로 거리 9와 모순됩니다.",
        ),
    )
