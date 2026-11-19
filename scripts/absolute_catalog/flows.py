"""일반 매칭과 최소 절단. 소규모 판정은 부분집합과 간선 유량을 열거한다."""
import itertools
import random


def blossom(n, edges):
    from collections import deque
    adj = [[] for _ in range(n)]
    for u,v in edges:
        adj[u].append(v)
        adj[v].append(u)
    mate = [-1]*n
    def augment(start):
        prev, base, seen = [-1]*n, list(range(n)), [False]*n
        seen[start] = True
        queue = deque([start])
        def common(a,b):
            path = set()
            while True:
                a = base[a]
                path.add(a)
                if mate[a] < 0:
                    break
                a = prev[mate[a]]
            while base[b] not in path:
                b = prev[mate[base[b]]]
            return base[b]
        def mark(v, root, child, inside):
            while base[v] != root:
                inside.add(base[v]); inside.add(base[mate[v]])
                prev[v], child = child, mate[v]
                v = prev[child]
        while queue:
            u = queue.popleft()
            for v in adj[u]:
                if base[u] == base[v] or mate[u] == v:
                    continue
                if v == start or (mate[v] >= 0 and prev[mate[v]] >= 0):
                    center = common(u,v)
                    inside = set()
                    mark(u,center,v,inside)
                    mark(v,center,u,inside)
                    for w in range(n):
                        if base[w] in inside:
                            base[w] = center
                            if not seen[w]:
                                seen[w] = True
                                queue.append(w)
                elif prev[v] < 0:
                    prev[v] = u
                    if mate[v] < 0:
                        while v >= 0:
                            p = prev[v]
                            nxt = mate[p] if p >= 0 else -1
                            mate[v] = p
                            if p >= 0:
                                mate[p] = v
                            v = nxt
                        return True
                    seen[mate[v]] = True
                    queue.append(mate[v])
        return False
    return sum(augment(i) for i in range(n) if mate[i] < 0)


def circulation(n, edges):
    import heapq
    source, sink = n, n+1
    adj = [[] for _ in range(n+2)]
    def arc(a,b,cap,price):
        adj[a].append([b,cap,price,len(adj[b])])
        adj[b].append([a,0,-price,len(adj[a])-1])
    balance, cost = [0]*n, 0
    for a,b,low,high,price in edges:
        balance[b] += low
        balance[a] -= low
        cost += low*price
        arc(a,b,high-low,price)
    need = 0
    for v,x in enumerate(balance):
        if x > 0:
            arc(source,v,x,0)
            need += x
        if x < 0:
            arc(v,sink,-x,0)
    potential = [0]*len(adj)
    while need:
        dist = [float("inf")]*len(adj)
        prev = [None]*len(adj)
        dist[source] = 0
        queue = [(0,source)]
        while queue:
            d,u = heapq.heappop(queue)
            if d != dist[u]:
                continue
            for i,(v,cap,price,_) in enumerate(adj[u]):
                nd = d + price + potential[u] - potential[v]
                if cap and nd < dist[v]:
                    dist[v],prev[v] = nd,(u,i)
                    heapq.heappush(queue,(nd,v))
        if prev[sink] is None:
            return -1
        for i,d in enumerate(dist):
            if d < float("inf"):
                potential[i] += d
        take,v = need,sink
        while v != source:
            u,i = prev[v]
            take = min(take,adj[u][i][1])
            v = u
        v = sink
        while v != source:
            u,i = prev[v]
            edge = adj[u][i]
            edge[1] -= take
            adj[v][edge[3]][1] += take
            cost += take*edge[2]
            v = u
        need -= take
    return cost


class Residual:
    def __init__(self,n):
        self.adj = [[] for _ in range(n)]

    def arc(self,u,v,c,back=0):
        a,b = self.adj[u],self.adj[v]
        a.append([v,c,len(b)])
        b.append([u,back,len(a)-1])

    def reachable(self,s):
        seen = {s}
        stack = [s]
        while stack:
            for v,c,_ in self.adj[stack.pop()]:
                if c and v not in seen:
                    seen.add(v)
                    stack.append(v)
        return seen

    def flow(self,s,t):
        total = 0
        while True:
            depth = [-1]*len(self.adj)
            order = [s]
            depth[s] = 0
            for u in order:
                for v,c,_ in self.adj[u]:
                    if c and depth[v] < 0:
                        depth[v] = depth[u]+1
                        order.append(v)
            if depth[t] < 0:
                return total
            current = [0]*len(depth)
            def push(u,limit):
                if u == t:
                    return limit
                while current[u] < len(self.adj[u]):
                    e = self.adj[u][current[u]]
                    v,cap,back = e
                    if cap and depth[v] == depth[u]+1:
                        sent = push(v,min(limit,cap))
                        if sent:
                            e[1] -= sent
                            self.adj[v][back][1] += sent
                            return sent
                    current[u] += 1
                return 0
            while True:
                sent = push(s,10**18)
                if not sent:
                    break
                total += sent


def gomory(n,edges,queries):
    parents, cost = [0]*n, [0]*n
    for s in range(1,n):
        t = parents[s]
        g = Residual(n)
        for u,v,c in edges:
            g.arc(u,v,c,c)
        value = g.flow(s,t)
        side = g.reachable(s)
        for u in range(s+1,n):
            if parents[u] == t and u in side:
                parents[u] = s
        if parents[t] in side:
            parents[s],parents[t] = parents[t],s
            cost[s],cost[t] = cost[t],value
        else:
            cost[s] = value
    tree = [[] for _ in range(n)]
    for u in range(1,n):
        p = parents[u]
        tree[p].append((u,cost[u]))
        tree[u].append((p,cost[u]))
    result = []
    for s,t in queries:
        stack = [(s,-1,10**18)]
        while stack:
            v,p,smallest = stack.pop()
            if v == t:
                result.append(smallest)
                break
            stack.extend((w,v,min(smallest,c)) for w,c in tree[v] if w!=p)
    return result


def global_cut(n,edges):
    weight = [[0]*n for _ in range(n)]
    for u,v,c in edges:
        weight[u][v] += c
        weight[v][u] += c
    active = list(range(n))
    answer = 10**18
    while len(active)>1:
        attached = [0]*n
        used = set()
        for i in range(len(active)):
            v = max((u for u in active if u not in used),key=attached.__getitem__)
            if i == len(active)-1:
                answer = min(answer,attached[v])
                for u in active:
                    weight[prev][u] += weight[v][u]
                    weight[u][prev] = weight[prev][u]
                active.remove(v)
                break
            used.add(v)
            for u in active:
                attached[u] += weight[v][u]
            prev = v
    return answer


def candidate_edges(n,edges,s,t):
    g = Residual(n)
    for u,v,c in edges:
        g.arc(u,v,c)
    g.flow(s,t)
    reached = [g.reachable(v) for v in range(n)]
    # s,u를 포함하는 잔여 폐포가 t,v 모두를 제외할 수 있는지 확인한다.
    return [i for i,(u,v,_) in enumerate(edges)
            if v not in reached[s] and t not in reached[u] and v not in reached[u]]


def match_oracle(n, edges):
    from functools import lru_cache
    pairs = {tuple(sorted(e)) for e in edges}
    @lru_cache(None)
    def search(mask):
        if not mask:
            return 0
        i = (mask & -mask).bit_length()-1
        rest = mask ^ (1<<i)
        return max([search(rest)]+[1+search(rest^(1<<j)) for j in range(i+1,n)
                                    if rest>>j&1 and (i,j) in pairs])
    return search((1<<n)-1)


def circulation_oracle(n,edges):
    best = None
    for flow in itertools.product(*(range(lo,hi+1) for _,_,lo,hi,_ in edges)):
        net = [0]*n
        cost = 0
        for amount,(u,v,_,_,price) in zip(flow,edges):
            net[u] -= amount; net[v] += amount; cost += amount*price
        if not any(net) and (best is None or cost < best):
            best = cost
    return -1 if best is None else best


def cut_oracle(n, edges, s=None, t=None, directed=False):
    best, items = None, set()
    for mask in range(1,(1<<n)-1):
        if s is not None and (not (mask>>s & 1) or mask>>t & 1):
            continue
        cut = {i for i,(u,v,_) in enumerate(edges) if
               ((mask>>u&1) and not (mask>>v&1)) or (not directed and (mask>>v&1) and not (mask>>u&1))}
        value = sum(edges[i][2] for i in cut)
        if best is None or value < best:
            best,items = value,cut
        elif value == best:
            items.update(cut)
    return sorted(items) if directed else best


def populate(add):
    rnd=random.Random(1436)
    triangle=[[0,1],[1,2],[0,2],[2,3]]
    matching=[[4,triangle],[1,[]]]
    circ=[[3,[[0,1,2,3,4],[1,2,0,2,1],[2,0,0,2,2]]],[2,[[0,1,1,1,1]]]]
    weighted=[[0,1,3],[1,2,2],[0,2,4]]
    gh=[[3,weighted,[[0,1],[0,2]]],[2,[],[[0,1]]]]
    cut=[[3,weighted],[2,[]]]
    cand=[[3,[[0,1,2],[1,2,2],[0,2,1]],0,2],[3,[[1,0,3]],0,2]]
    for _ in range(16):
        n=rnd.randint(3,7)
        simple=[[u,v] for v in range(n) for u in range(v) if rnd.randrange(2)]
        matching.append([n,simple])
        directed=[rnd.sample(range(n),2) for _ in range(rnd.randint(3,7))]
        circ.append([n,[[u,v,lo,lo+rnd.randrange(3),rnd.randrange(7)] for (u,v),lo in zip(directed,[rnd.randrange(2) for _ in directed])]])
        edges=[[u,v,rnd.randrange(1,8)] for u,v in directed]
        gh.append([n,edges,[rnd.sample(range(n),2) for _ in range(9)]])
        cut.append([n,edges]); cand.append([n,edges,*rnd.sample(range(n),2)])
    n=101
    cycle=[[i,(i+1)%n,4] for i in range(n)]
    # 완전 이분 그래프 K(8,112)의 u-v 절단 값은 서로 다른 두 끝점 차수 중 작은 값이다.
    bipartite=[[u,v,1] for u in range(8) for v in range(8,120)]
    pairs=[rnd.sample(range(120),2) for _ in range(3000)]
    paired_edges=[[u,v] for v in range(250) for u in range(v)
                  if (u%2==0 and v==u+1) or rnd.random()<0.4]
    rnd.shuffle(paired_edges)
    leaf_flows=[[0,v,30,30,1] for v in range(1,80)]+[[v,0,0,30,3] for v in range(1,80)]
    leaf_flows.extend([[u,v,0,30,10] for u,v in [rnd.sample(range(1,80),2) for _ in range(242)]])
    layered=[[u,u+1,1] for u in range(139) for _ in range(13)]+[[u+1,u,7] for u in range(139)]
    add("일반 그래프 최대 매칭","Blossom","n:int edges:int[][]","int",
        "정점이 겹치지 않도록 고른 무방향 간선 집합을 매칭이라고 합니다. 일반 그래프에서 고를 수 있는 최대 간선 수를 구하세요. 홀수 사이클, 고립 정점, 여러 연결 요소가 있을 수 있습니다.",
        "1 ≤ n ≤ 250. [u,v]는 서로 다른 정점을 잇습니다. 중복 간선은 없습니다.","O(N³)",blossom,match_oracle,matching,
        [([n,[[i,(i+1)%n] for i in range(n)]],n//2),([250,paired_edges],125)])
    add("최소 비용 순환 유량","Min-Cost Circulation","n:int edges:int[][]","int",
        "방향 간선 [u,v,lower,upper,price]마다 lower 이상 upper 이하의 정수 유량을 선택하세요. 모든 정점에서 유입 합과 유출 합이 같아야 합니다. 유량×단위 비용의 전체 합의 최솟값을 반환하고, 불가능하면 −1을 반환하세요.",
        "2 ≤ n ≤ 80, 0 ≤ 간선 수 ≤ 400. u≠v, 평행 간선 허용. 0 ≤ lower ≤ upper ≤ 30, 0 ≤ price ≤ 1,000. F는 하한 적용 후 보충할 총 유량입니다.","O((F+1)(N+M) log N)",circulation,circulation_oracle,circ,
        [([60,[[i,(i+1)%60,1 if i==0 else 0,3,2] for i in range(60)]],120),
         ([80,leaf_flows],79*30*4)])
    add("고모리-후 트리","Gomory-Hu","n:int edges:int[][] queries:int[][]","int[]",
        "무방향 용량 그래프 [u,v,c]가 한 번 주어집니다. 각 [s,t]에서 두 정점을 분리하는 최소 절단 용량을 입력 순서대로 구하세요. 고모리-후 트리는 단 n−1회의 최대 유량 계산으로 모든 쌍의 절단 값을 표현합니다.",
        "2 ≤ n ≤ 120, 0 ≤ M ≤ 1,000, 1 ≤ Q ≤ 3,000. 모든 끝점은 다르고 용량은 1 이상 10,000 이하입니다. 평행 간선 허용, 이미 분리된 쌍의 답은 0입니다.","O(N·MaxFlow(N,M)+NQ)",gomory,
        lambda n,e,qs:[cut_oracle(n,e,u,v) for u,v in qs],gh,
        [([n,cycle,[[0,i] for i in range(1,n)]],[8]*(n-1)),([120,bipartite,pairs],[112 if u<8 and v<8 else 8 for u,v in pairs])],(Residual,))
    add("간선 연결성 분석","Min-Cut","n:int edges:int[][]","int",
        "무방향 그래프를 두 개의 비어 있지 않은 정점 집합으로 나누세요. 두 집합을 연결하는 모든 간선을 없애는 비용의 총합이 가장 작을 때 그 값을 반환하세요. [u,v,c]에서 c는 간선 한 개의 삭제 비용입니다.",
        "2 ≤ n ≤ 250, 0 ≤ 간선 수 ≤ 20,000. 자기 루프 없음, 평행 간선 허용. 1 ≤ c ≤ 10,000, 이미 연결되지 않은 그래프의 답은 0입니다.","O(N³+M)",global_cut,cut_oracle,cut,
        [([n,cycle],8),([250,[[u,v,1] for u in range(60) for v in range(60,250)]],60)])
    add("최소 절단의 모든 후보","Flow 응용","n:int edges:int[][] s:int t:int","int[]",
        "방향 용량 간선 [u,v,c]가 주어집니다. s를 포함하고 t를 제외한 집합 S에서 밖으로 나가는 간선 용량의 합을 절단 값으로 정의합니다. 하나 이상의 최소 절단에서 S에서 밖으로 나가는 간선의 입력 인덱스를 오름차순으로 반환하세요.",
        "2 ≤ n ≤ 140, 0 ≤ M ≤ 2,000. s≠t. 자기 루프 없음, 평행 간선 허용. 1 ≤ c ≤ 10,000.","O(MaxFlow(N,M)+N(N+M))",candidate_edges,
        lambda n,e,s,t:cut_oracle(n,e,s,t,True),cand,[([n,[[i,i+1,1] for i in range(n-1)],0,n-1],list(range(n-1))),
         ([140,layered,0,139],list(range(139*13)))],(Residual,))
