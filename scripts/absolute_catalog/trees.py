"""동적 포리스트, 부분 영속성, 트리 분해. 작은 포리스트는 직접 탐색해 채점한다."""
from collections import deque
import random


class LinkCut:
    def __init__(self, values):
        n = len(values) + 1
        self.left, self.right, self.parent = ([0] * n for _ in range(3))
        self.reverse = [False] * n
        self.value = [0] + values[:]
        self.total = self.value[:]
        self.heaviest = list(range(n))

    def pull(self, x):
        left, right = self.left[x], self.right[x]
        self.total[x] = self.total[left] + self.value[x] + self.total[right]
        candidates = [x]
        if left:
            candidates.append(self.heaviest[left])
        if right:
            candidates.append(self.heaviest[right])
        self.heaviest[x] = max(candidates, key=self.value.__getitem__)

    def flip(self, x):
        if x:
            self.left[x], self.right[x] = self.right[x], self.left[x]
            self.reverse[x] = not self.reverse[x]

    def push(self, x):
        if self.reverse[x]:
            self.flip(self.left[x])
            self.flip(self.right[x])
            self.reverse[x] = False

    def aux_root(self, x):
        p = self.parent[x]
        return not p or (self.left[p] != x and self.right[p] != x)

    def rotate(self, x):
        p = self.parent[x]
        g = self.parent[p]
        if not self.aux_root(p):
            if self.left[g] == p:
                self.left[g] = x
            else:
                self.right[g] = x
        self.parent[x] = g
        if self.left[p] == x:
            middle = self.right[x]
            self.right[x], self.left[p] = p, middle
        else:
            middle = self.left[x]
            self.left[x], self.right[p] = p, middle
        self.parent[p] = x
        if middle:
            self.parent[middle] = p
        self.pull(p)
        self.pull(x)

    def splay(self, x):
        path = [x]
        while not self.aux_root(path[-1]):
            path.append(self.parent[path[-1]])
        for node in reversed(path):
            self.push(node)
        while not self.aux_root(x):
            p = self.parent[x]
            if not self.aux_root(p):
                g = self.parent[p]
                same = (self.left[g] == p) == (self.left[p] == x)
                self.rotate(p if same else x)
            self.rotate(x)

    def access(self, x):
        child, node = 0, x
        while node:
            self.splay(node)
            self.right[node] = child
            self.pull(node)
            child, node = node, self.parent[node]
        self.splay(x)

    def root_at(self, x):
        self.access(x)
        self.flip(x)

    def link(self, u, v):
        self.root_at(u)
        self.parent[u] = v

    def cut(self, u, v):
        self.root_at(u)
        self.access(v)
        self.left[v] = self.parent[u] = 0
        self.pull(v)

    def path(self, u, v):
        self.root_at(u)
        self.access(v)


def path_queries(values, ops):
    tree = LinkCut(values)
    answers = []
    for kind, a, b in ops:
        u = a + 1
        if kind == 0:
            tree.link(u, b + 1)
        elif kind == 1:
            tree.cut(u, b + 1)
        elif kind == 2:
            tree.access(u)
            tree.value[u] = b
            tree.pull(u)
        else:
            tree.path(u, b + 1)
            answers.append(tree.total[b + 1])
    return answers


def growing_mst(n, edges):
    nodes = LinkCut([-1] * n + [w for _, _, w in edges])
    parent = list(range(n))
    size = [1] * n
    def find(x):
        while x != parent[x]:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x
    count, cost, answers = n, 0, []
    for i, (u, v, w) in enumerate(edges):
        edge = n + i + 1
        a, b = find(u), find(v)
        install = a != b
        if install:
            if size[a] < size[b]:
                a, b = b, a
            parent[b] = a
            size[a] += size[b]
            count -= 1
        elif u != v:
            nodes.path(u + 1, v + 1)
            old = nodes.heaviest[v + 1]
            if nodes.value[old] > w:
                s, t, old_weight = edges[old - n - 1]
                nodes.cut(s + 1, old)
                nodes.cut(t + 1, old)
                cost -= old_weight
                install = True
        if install:
            nodes.link(u + 1, edge)
            nodes.link(v + 1, edge)
            cost += w
        answers.append(cost if count == 1 else -1)
    return answers


def offline_connected(n, ops):
    q = len(ops)
    periods = [[] for _ in range(4 * q)]
    def store(node, lo, hi, a, b, edge):
        if b <= lo or hi <= a:
            return
        if a <= lo and hi <= b:
            periods[node].append(edge)
            return
        mid = (lo + hi) // 2
        store(2 * node, lo, mid, a, b, edge)
        store(2 * node + 1, mid, hi, a, b, edge)
    starts = {}
    for i, (kind, u, v) in enumerate(ops):
        edge = tuple(sorted((u, v)))
        if kind == 0:
            starts[edge] = i
        elif kind == 1:
            store(1, 0, q, starts.pop(edge), i, edge)
    for edge, start in starts.items():
        store(1, 0, q, start, q, edge)
    parent = list(range(n))
    size = [1] * n
    history, answers = [], []
    def find(u):
        while parent[u] != u:
            u = parent[u]
        return u
    def visit(node, lo, hi):
        checkpoint = len(history)
        for u, v in periods[node]:
            u, v = find(u), find(v)
            if u == v:
                continue
            if size[u] < size[v]:
                u, v = v, u
            history.append((u, v))
            size[u] += size[v]
            parent[v] = u
        if hi == lo + 1:
            kind, u, v = ops[lo]
            if kind == 2:
                answers.append(int(find(u) == find(v)))
        else:
            mid = (lo + hi) // 2
            visit(node * 2, lo, mid)
            visit(node * 2 + 1, mid, hi)
        while len(history) > checkpoint:
            u, v = history.pop()
            size[u] -= size[v]
            parent[v] = v
    visit(1, 0, q)
    return answers


def tree_kth(values, parents, queries):
    ordered = sorted(set(values))
    rank = {v: i for i, v in enumerate(ordered)}
    n, width = len(values), len(ordered)
    left, right, count = [0], [0], [0]
    def add(old, lo, hi, pos):
        node = len(left)
        left.append(left[old])
        right.append(right[old])
        count.append(count[old] + 1)
        if hi - lo > 1:
            mid = (lo + hi) // 2
            if pos < mid:
                left[node] = add(left[old], lo, mid, pos)
            else:
                right[node] = add(right[old], mid, hi, pos)
        return node
    up = [[0] + list(parents)]
    for _ in range(1, n.bit_length()):
        prev = up[-1]
        up.append([prev[prev[v]] for v in range(n)])
    version, depth = [], [0] * n
    for v, value in enumerate(values):
        p = up[0][v]
        version.append(add(version[p] if v else 0, 0, width, rank[value]))
        if v:
            depth[v] = depth[p] + 1
    def lca(a, b):
        if depth[a] < depth[b]:
            a, b = b, a
        diff = depth[a] - depth[b]
        for i, level in enumerate(up):
            if diff >> i & 1:
                a = level[a]
        if a == b:
            return a
        for level in reversed(up):
            if level[a] != level[b]:
                a, b = level[a], level[b]
        return up[0][a]
    ans = []
    for u, v, k in queries:
        p = lca(u, v)
        a, b, c, d = version[u], version[v], version[p], version[up[0][p]] if p else 0
        lo, hi = 0, width
        while hi - lo > 1:
            mid = (lo + hi) // 2
            smaller = count[left[a]] + count[left[b]] - count[left[c]] - count[left[d]]
            if smaller >= k:
                a, b, c, d = left[a], left[b], left[c], left[d]
                hi = mid
            else:
                a, b, c, d = right[a], right[b], right[c], right[d]
                k -= smaller
                lo = mid
        ans.append(ordered[lo])
    return ans


def distance_pairs(parents, k):
    n = len(parents) + 1
    adj = [[] for _ in range(n)]
    for v, p in enumerate(parents, 1):
        adj[v].append(p)
        adj[p].append(v)
    removed, size, parent = [False]*n, [0]*n, [-1]*n
    components, answer = [0], 0
    while components:
        start = components.pop()
        order = [start]
        parent[start] = -1
        for v in order:
            for w in adj[v]:
                if w != parent[v] and not removed[w]:
                    order.append(w)
                    parent[w] = v
        middle = start
        for v in reversed(order):
            size[v] = 1
            largest = 0
            for w in adj[v]:
                if not removed[w] and parent[w] == v:
                    size[v] += size[w]
                    largest = max(largest, size[w])
            if 2 * max(largest, len(order) - size[v]) <= len(order):
                middle = v
        removed[middle] = True
        frequency = {0: 1}
        for w in adj[middle]:
            if removed[w]:
                continue
            components.append(w)
            stack, distances = [(w, middle, 1)], []
            while stack:
                v, p, dist = stack.pop()
                if dist > k:
                    continue
                answer += frequency.get(k - dist, 0)
                distances.append(dist)
                stack.extend((u, v, dist + 1) for u in adj[v] if u != p and not removed[u])
            for dist in distances:
                frequency[dist] = frequency.get(dist, 0) + 1
    return answer


def route(adj, s, t):
    seen, queue = {s: None}, deque([s])
    while queue:
        v = queue.popleft()
        if v == t:
            path = []
            while v is not None:
                path.append(v)
                v = seen[v]
            return path
        for w in adj[v]:
            if w not in seen:
                seen[w] = v
                queue.append(w)
    return []


def forest_oracle(values, ops):
    adj = [set() for _ in values]
    ans = []
    for kind, a, b in ops:
        if kind == 0:
            adj[a].add(b)
            adj[b].add(a)
        elif kind == 1:
            adj[a].remove(b)
            adj[b].remove(a)
        elif kind == 2:
            values[a] = b
        else:
            ans.append(sum(values[i] for i in route(adj, a, b)))
    return ans


def mst_oracle(n, edges):
    answers = []
    for end in range(1, len(edges)+1):
        groups = [{i} for i in range(n)]
        total = 0
        for u,v,w in sorted(edges[:end], key=lambda e:e[2]):
            a = next(g for g in groups if u in g)
            b = next(g for g in groups if v in g)
            if a is not b:
                a.update(b)
                groups.remove(b)
                total += w
        answers.append(total if len(groups)==1 else -1)
    return answers


def connected_oracle(n, ops):
    adj = [set() for _ in range(n)]
    ans = []
    for kind,u,v in ops:
        if kind == 2:
            ans.append(int(bool(route(adj,u,v))))
        elif kind == 0:
            adj[u].add(v)
            adj[v].add(u)
        else:
            adj[u].remove(v)
            adj[v].remove(u)
    return ans


def parent_adj(parents):
    adj = [[] for _ in range(len(parents)+1)]
    for i,p in enumerate(parents,1):
        adj[p].append(i)
        adj[i].append(p)
    return adj


def populate(add):
    rng = random.Random(1431)
    forest = [[[2,3,-1],[[0,0,1],[3,0,1],[0,1,2],[2,1,8],[3,0,2],[1,1,2],[3,2,2]]],[[4],[[3,0,0]]]]
    dynamic = [[3,[[0,0,1],[2,0,2],[0,1,2],[2,0,2],[1,1,2],[2,0,2]]],[1,[[2,0,0]]]]
    mst = [[3,[[0,1,4],[1,2,7],[0,2,2]]],[1,[[0,0,1]]]]
    kth = [[[5,1,4],[0,0],[[1,2,2],[0,0,1]]],[[7],[],[[0,0,1]]]]
    centers = [[[0,1,1],2],[[],0]]
    for _ in range(14):
        n = rng.randint(2,9)
        ops, general = [], []
        adj = [set() for _ in range(n)]
        active = set()
        for step in range(22):
            u,v = sorted(rng.sample(range(n),2))
            if step % 4 == 0:
                ops.append([3,u,u])
            elif v in adj[u]:
                ops.append([1,u,v]); adj[u].remove(v); adj[v].remove(u)
            elif not route(adj,u,v):
                ops.append([0,u,v]); adj[u].add(v); adj[v].add(u)
            elif rng.randrange(2):
                ops.append([3,u,v])
            else:
                ops.append([2,u,rng.randint(-5,5)])
            if step % 3 == 0:
                general.append([2,u,v])
            elif (u,v) in active:
                general.append([1,u,v]); active.remove((u,v))
            else:
                general.append([0,u,v]); active.add((u,v))
        weights = [rng.randint(-8,8) for _ in range(n)]
        forest.append([weights,ops]); dynamic.append([n,general])
        mst.append([n,[[rng.randrange(n),rng.randrange(n),rng.randrange(12)] for _ in range(23)]])
        parents = [rng.randrange(v) for v in range(1,n)]
        adj = parent_adj(parents)
        queries = []
        for _ in range(9):
            u,v = rng.randrange(n),rng.randrange(n)
            queries.append([u,v,rng.randint(1,len(route(adj,u,v)))])
        kth.append([weights,parents,queries]); centers.append([parents,rng.randrange(n+1)])
    n = 10000
    path = list(range(n-1))
    add("동적 트리 경로 쿼리","Link-Cut Tree","values:int[] ops:int[][]","int[]",
        "초기에 각 정점은 고립되어 있으며 값은 values입니다. ops의 각 행 [t,u,x]는 t=0이면 u와 x 연결, 1이면 그 간선 삭제, 2이면 u의 값을 x로 교체, 3이면 u에서 x까지 양 끝을 포함한 단순 경로의 값 합을 묻습니다. 질의 결과를 순서대로 반환하세요.",
        "1 ≤ 정점 수, 연산 수 ≤ 20,000. 값은 −10,000 이상 10,000 이하입니다. 연결은 서로 다른 트리 사이, 삭제는 기존 간선, 합 질의는 연결된 두 정점에서만 이루어집니다.","O((N+Q) log N) 분할상환",path_queries,forest_oracle,forest,
        [([[1]*n,[[0,i,i+1] for i in path]+[[3,0,i] for i in range(n)]],list(range(1,n+1)))],(LinkCut,))
    add("동적 최소 스패닝 트리","Dynamic MST","n:int edges:int[][]","int[]",
        "간선이 없는 n개 정점에 무방향 가중 간선 [u,v,w]를 한 개씩 영구 추가합니다. 매 추가 직후의 최소 신장 트리 가중치 합을 반환하세요. 아직 연결되지 않았다면 해당 위치에는 −1을 넣습니다. 평행 간선과 자기 루프도 들어옵니다.",
        "1 ≤ n, 간선 수 ≤ 20,000. 0 ≤ w ≤ 50,000. 정점 번호는 0부터 n−1입니다.","O((N+M) log(N+M)) 분할상환",growing_mst,mst_oracle,mst,
        [([n,[[i,i+1,2] for i in path]+[[i,i+1,1] for i in path]],[-1]*(n-2)+[2*(n-1)]+list(range(2*n-3,n-2,-1)))],(LinkCut,))
    add("완전 동적 연결성","Rollback DSU","n:int ops:int[][]","int[]",
        "처음에 빈 무방향 그래프에 [0,u,v]로 간선을 추가하고 [1,u,v]로 삭제합니다. [2,u,v]마다 연결되어 있으면 1, 아니면 0을 순서대로 반환하세요. 모든 명령은 미리 주어집니다.",
        "1 ≤ n, 명령 수 ≤ 40,000. 자기 루프 추가·중복 추가·존재하지 않는 간선 삭제는 없습니다. 같은 정점은 항상 연결됩니다.","O(Q log Q log N)",offline_connected,connected_oracle,dynamic,
        [([n,[[0,i,i+1] for i in path]+[op for i in range(0,n-1,2)
              for op in ([1,i,i+1],[2,0,n-1],[0,i,i+1],[2,0,n-1])]], [0,1]*(n//2))])
    add("트리 경로의 K번째 수","Persistent Tree","values:int[] parents:int[] queries:int[][]","int[]",
        "정점 0이 루트이며 정점 i>0의 부모는 parents[i−1]입니다. 각 [u,v,k]에서 경로 양 끝을 포함한 정점 값을 중복을 유지하여 정렬했을 때 1부터 세는 k번째 값을 반환하세요.",
        "1 ≤ n, 질의 수 ≤ 20,000. 부모 번호는 자식보다 작고, 값은 −10⁹ 이상 10⁹ 이하입니다. k는 경로상의 유효한 순위입니다.","O((N+Q) log N)",tree_kth,
        lambda values,parents,queries:[sorted(values[w] for w in route(parent_adj(parents),u,v))[k-1] for u,v,k in queries],kth,
        [([list(range(n)),path,[[0,i,i+1] for i in range(n)]],list(range(n)))])
    add("트리의 중심 분해","Centroid Decomposition","parents:int[] k:int","int",
        "정점 0이 루트이고 i>0의 부모는 parents[i−1]입니다. 간선 수로 잰 거리가 정확히 k인 서로 다른 두 정점의 순서 없는 쌍을 세세요.",
        "1 ≤ n ≤ 40,000, 0 ≤ k ≤ n. 각 부모는 자식보다 작은 정점 번호입니다. k=0이면 결과는 0입니다.","O(N log N)",distance_pairs,
        lambda parents,k:sum(len(route(parent_adj(parents),u,v))==k+1 for v in range(len(parents)+1) for u in range(v)),centers,
        [([path,99],n-99), ([[0]*39999,2],39999*39998//2)])
