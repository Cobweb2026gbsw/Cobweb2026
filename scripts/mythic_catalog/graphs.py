"""트리 조상, 연결성, 논리식, 오일러 경로."""
import itertools
import random


def ancestor(parents, a, b):
    path = set()
    while a != -1:
        path.add(a)
        a = parents[a]
    while b not in path:
        b = parents[b]
    return b


def many_ancestors(parents, pairs):
    n = len(parents)
    depth = [0] * n
    first = [0] + parents[1:]
    for v in range(1, n):
        depth[v] = depth[parents[v]] + 1
    jumps = [first]
    for _ in range(1, n.bit_length()):
        prev = jumps[-1]
        jumps.append([prev[p] for p in prev])
    answer = []
    for a, b in pairs:
        if depth[a] < depth[b]:
            a, b = b, a
        diff = depth[a] - depth[b]
        for k in range(len(jumps)):
            if diff >> k & 1:
                a = jumps[k][a]
        if a != b:
            for row in reversed(jumps):
                if row[a] != row[b]:
                    a, b = row[a], row[b]
            a = first[a]
        answer.append(a)
    return answer


def components(n, edges):
    graph = [[] for _ in range(n)]
    for a, b in edges:
        graph[a].append(b)
    index, low = [-1] * n, [0] * n
    active, pending, groups = [False] * n, [], []
    tick = 0
    for root in range(n):
        if index[root] != -1:
            continue
        stack = [(root, iter(graph[root]))]
        index[root] = low[root] = tick
        tick += 1
        pending.append(root)
        active[root] = True
        while stack:
            v, children = stack[-1]
            w = next(children, None)
            if w is None:
                stack.pop()
                if stack:
                    parent = stack[-1][0]
                    low[parent] = min(low[parent], low[v])
                if low[v] == index[v]:
                    group = []
                    while True:
                        u = pending.pop()
                        active[u] = False
                        group.append(u)
                        if u == v:
                            break
                    groups.append(sorted(group))
            elif index[w] == -1:
                index[w] = low[w] = tick
                tick += 1
                active[w] = True
                pending.append(w)
                stack.append((w, iter(graph[w])))
            elif active[w]:
                low[v] = min(low[v], index[w])
    return sorted(groups)


def satisfiable(n, clauses):
    implications = []
    for a, b in clauses:
        x = 2 * (abs(a) - 1) + (a < 0)
        y = 2 * (abs(b) - 1) + (b < 0)
        implications.extend([[x ^ 1, y], [y ^ 1, x]])
    groups = components(2 * n, implications)
    label = [0] * (2 * n)
    for k, group in enumerate(groups):
        for v in group:
            label[v] = k
    return all(label[2*i] != label[2*i+1] for i in range(n))


def cuts(n, edges):
    graph = [[] for _ in range(n)]
    for i, (a,b) in enumerate(edges):
        graph[a].append((b,i))
        graph[b].append((a,i))
    depth, low, parent = [-1]*n, [0]*n, [-1]*n
    tree_edges, children = [-1]*n, [0]*n
    points, bridges, tick = set(), [], 0
    for root in range(n):
        if depth[root] >= 0:
            continue
        depth[root] = low[root] = tick
        tick += 1
        stack = [(root, iter(graph[root]))]
        while stack:
            v, neighbors = stack[-1]
            item = next(neighbors, None)
            if item is None:
                stack.pop()
                p = parent[v]
                if p == -1:
                    if children[v] >= 2:
                        points.add(v)
                else:
                    low[p] = min(low[p], low[v])
                    if parent[p] != -1 and low[v] >= depth[p]:
                        points.add(p)
                    if low[v] > depth[p]:
                        bridges.append(tree_edges[v])
            else:
                w, edge = item
                if edge == tree_edges[v]:
                    continue
                if depth[w] >= 0:
                    low[v] = min(low[v], depth[w])
                else:
                    parent[w], tree_edges[w] = v, edge
                    children[v] += 1
                    depth[w] = low[w] = tick
                    tick += 1
                    stack.append((w, iter(graph[w])))
    return sorted(points), sorted(bridges)


def cut_vertices(n, edges):
    return cuts(n, edges)[0]


def cut_edges(n, edges):
    return cuts(n, edges)[1]


def trail(n, edges, start):
    graph = [[] for _ in range(n)]
    balance = [0]*n
    for a,b in edges:
        graph[a].append(b)
        balance[a] += 1
        balance[b] -= 1
    if any(balance) and not (balance[start]==1 and balance.count(1)==1 and balance.count(-1)==1
                             and all(abs(x)<=1 for x in balance)):
        return []
    for line in graph:
        line.sort(reverse=True)
    path, result = [start], []
    while path:
        row = graph[path[-1]]
        if row:
            path.append(row.pop())
        else:
            result.append(path.pop())
    return result[::-1] if len(result)==len(edges)+1 else []


def common(parents,a,b):
    def lineage(v):
        result=[]
        while v >= 0:
            result.append(v)
            v=parents[v]
        return result[::-1]
    shared=0
    for x,y in zip(lineage(a),lineage(b)):
        if x!=y: break
        shared=x
    return shared


def closure(n,edges):
    reach=[[i==j for j in range(n)] for i in range(n)]
    for a,b in edges: reach[a][b]=True
    for k in range(n):
        for i in range(n):
            for j in range(n):
                reach[i][j] |= reach[i][k] and reach[k][j]
    return sorted({tuple(j for j in range(n) if reach[i][j] and reach[j][i]) for i in range(n)})


def cut_oracle(n,edges,skip_v=-1,skip_e=-1):
    links=[set() for _ in range(n)]
    for i,(a,b) in enumerate(edges):
        if i!=skip_e and skip_v not in (a,b): links[a].add(b);links[b].add(a)
    remain=set(range(n))-{skip_v}
    total=0
    while remain:
        stack=[remain.pop()]
        total+=1
        while stack:
            neighbors=remain & links[stack.pop()]
            remain-=neighbors
            stack.extend(neighbors)
    return total


def trail_oracle(n,edges,start):
    def visit(v,remaining):
        if not remaining: return [v]
        for i in sorted(remaining,key=lambda k:edges[k][1]):
            if edges[i][0]==v:
                tail=visit(edges[i][1],remaining-{i})
                if tail: return [v]+tail
        return []
    return visit(start,set(range(len(edges))))


def populate(add):
    r=random.Random(1396)
    tree_cases=[[[-1,0,0,1,1,2],3,4],[[-1],0,0]] 
    many=[[[-1,0,0,1,1],[[3,4],[2,4],[4,4]]],[[-1],[]]]
    for n in range(2,16):
        parents=[-1]+[r.randrange(v) for v in range(1,n)]
        tree_cases.append([parents,r.randrange(n),r.randrange(n)])
        many.append([parents,[[r.randrange(n),r.randrange(n)] for _ in range(n)]])
    trees="정점 번호는 0부터 n−1까지입니다. parents[0]=−1이며 1≤v<n이면 0≤parents[v]<v입니다."
    chain=[-1]+list(range(1999))
    add("최소 공통 조상(LCA)","parents:int[] a:int b:int","int","루트 트리에서 정점 자신도 자신의 조상에 포함합니다.\n정점 a와 b의 공통 조상 중 깊이가 가장 큰 정점 번호를 반환하세요.",trees+" 1≤n≤100,000. a와 b는 유효한 정점입니다.",ancestor,tree_cases,common,stress=[([chain,1999,137],137)])
    add("희소 테이블을 이용한 LCA","parents:int[] pairs:int[][]","int[]","한 트리에 조상 점프 테이블을 한 번 만든 뒤 여러 최소 공통 조상 질의에 답하세요. 자신도 조상입니다.\n각 [a,b]의 가장 깊은 공통 조상 번호를 질의 순서대로 반환하세요.",trees+" 1≤n≤100,000, 0≤질의 수≤100,000. 각 쌍은 유효한 정점입니다.",many_ancestors,many,lambda p,qs:[common(p,a,b) for a,b in qs],stress=[([chain,[[i,1999] for i in range(2000)]],list(range(2000)))])
    graph=[[5,[[0,1],[1,0],[1,2],[2,3],[3,2]]],[3,[]],[2,[[0,1],[0,1],[1,1]]]]
    for n in range(2,14): graph.append([n,[[r.randrange(n),r.randrange(n)] for _ in range(n+2)]])
    chain_edges=[[i,i+1] for i in range(1999)]
    lim="1≤n≤100,000, 0≤간선 수≤150,000. 정점은 0부터 n−1이며 평행 간선과 자기 루프를 허용합니다."
    add("강한 연결 요소(Tarjan)","n:int edges:int[][]","int[][]","방향 간선 [u,v] 목록에서 서로 왕복할 수 있는 정점들을 최대 집합으로 묶으세요.\n각 강한 연결 요소의 정점을 오름차순으로 정렬하고, 각 요소의 첫 정점이 작은 순서대로 반환하세요.",lim,components,graph,lambda n,e:[list(c) for c in closure(n,e)],stress=[([2000,chain_edges+[[1999,0]]],[list(range(2000))])])
    sat=[[2,[[1,2],[-1,2],[1,-2]]],[1,[[1,1],[-1,-1]]],[2,[]]]
    for k in range(16):
        n=1+r.randrange(6)
        sat.append([n,[[r.choice([-1,1])*r.randint(1,n) for _ in range(2)] for _ in range(k+1)]])
    def brute_sat(n,cs):
        return any(all(any(bits[abs(x)-1]==(x>0) for x in c) for c in cs) for bits in itertools.product([False,True],repeat=n))
    add("2-SAT 충족 가능성","n:int clauses:int[][]","bool","각 [a,b]는 두 리터럴의 논리합입니다. 양수 i는 변수 i, 음수 −i는 그 부정입니다.\n모든 절을 동시에 참으로 만드는 대입이 있으면 true를 반환하세요. 절이 없으면 true입니다.","1≤n≤50,000, 0≤절 수≤100,000. 1≤|a|,|b|≤n.",satisfiable,sat,brute_sat,dependencies=(components,),stress=[([2000,[[-i,i+1] for i in range(1,2000)]+[[1,1],[-2000,-2000]]],False)])
    for is_vertex,title,fn,desc in [(True,"단절점 찾기",cut_vertices,"삭제할 정점 번호"),(False,"단절선 찾기",cut_edges,"삭제할 간선의 입력 인덱스")]:
        def oracle(n,e):
            base=cut_oracle(n,e)
            return [i for i in range(n if is_vertex else len(e)) if (cut_oracle(n,e,skip_v=i) if is_vertex else cut_oracle(n,e,skip_e=i))>base]
        add(title,"n:int edges:int[][]","int[]","무방향 간선 [u,v] 목록에서 "+("정점 하나와 그에 닿은 모든 간선" if is_vertex else "간선 한 개")+"을 제거해 전체 연결 요소 수가 증가하는 경우를 찾으세요.\n해당 "+desc+"를 오름차순 배열로 반환하세요.",lim,fn,graph,oracle,dependencies=(cuts,),stress=[([2000,chain_edges],list(range(1,1999) if is_vertex else range(1999)))])
    routes=[[3,[[0,1],[1,0],[0,2]],0],[2,[[1,0]],0],[2,[],1],[3,[[0,0],[1,1]],0]]
    for k in range(16):
        n=r.randrange(2,6)
        routes.append([n,[[r.randrange(n),r.randrange(n)] for _ in range(k%7+1)],r.randrange(n)])
    add("오일러 경로","n:int edges:int[][] start:int","int[]","방향 간선 [u,v] 각각을 정확히 한 번 지나는 경로를 start에서 시작하세요.\n가능한 정점 번호열 중 사전순으로 가장 작은 것을 반환하세요. 경로가 없으면 빈 배열, 간선이 없으면 [start]입니다.",lim+" start는 유효한 정점입니다.",trail,routes,trail_oracle,stress=[([2000,chain_edges,0],list(range(2000)))])
