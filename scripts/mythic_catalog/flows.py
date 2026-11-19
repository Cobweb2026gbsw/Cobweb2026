"""유량 네트워크와 이분 그래프 배정."""
import itertools
import random


def shortest_augment(n, edges):
    from collections import deque
    residual = [{} for _ in range(n)]
    for u,v,cap in edges:
        residual[u][v] = residual[u].get(v,0) + cap
        residual[v].setdefault(u,0)
    total = 0
    while True:
        prev = [-1]*n
        prev[0] = 0
        queue = deque([0])
        while queue and prev[-1]<0:
            v = queue.popleft()
            for w,capacity in residual[v].items():
                if capacity and prev[w]<0:
                    prev[w] = v
                    queue.append(w)
        if prev[-1]<0:
            return total
        w, added = n-1, 2**63
        while w:
            v = prev[w]
            added = min(added,residual[v][w])
            w = v
        w = n-1
        while w:
            v = prev[w]
            residual[v][w] -= added
            residual[w][v] += added
            w = v
        total += added


def blocking_flow(n, edges):
    from collections import deque
    graph=[[] for _ in range(n)]
    for u,v,capacity in edges:
        if u==v:
            continue
        a=[v,capacity,len(graph[v])]
        b=[u,0,len(graph[u])]
        graph[u].append(a)
        graph[v].append(b)
    total=0
    while True:
        depth=[-1]*n
        depth[0]=0
        queue=deque([0])
        while queue:
            v=queue.popleft()
            for w,cap,_ in graph[v]:
                if cap and depth[w]<0:
                    depth[w]=depth[v]+1
                    queue.append(w)
        if depth[-1]<0:
            return total
        cursor=[0]*n
        def push(v,limit):
            if v==n-1:
                return limit
            while cursor[v]<len(graph[v]):
                edge=graph[v][cursor[v]]
                w,cap,rev=edge
                if cap and depth[w]==depth[v]+1:
                    sent=push(w,min(cap,limit))
                    if sent:
                        edge[1]-=sent
                        graph[w][rev][1]+=sent
                        return sent
                cursor[v]+=1
            return 0
        while True:
            sent=push(0,2**63)
            if not sent:
                break
            total+=sent


def matching(left_size, right_size, pairs):
    graph=[[] for _ in range(left_size)]
    for a,b in pairs:
        graph[a].append(b)
    right=[-1]*right_size
    def augment(v,seen):
        for w in graph[v]:
            if w in seen:
                continue
            seen.add(w)
            if right[w]<0 or augment(right[w],seen):
                right[w]=v
                return True
        return False
    for v in range(left_size):
        augment(v,set())
    return right,graph


def match_size(left_size, right_size, pairs):
    right,_=matching(left_size,right_size,pairs)
    return sum(v>=0 for v in right)


def cover(left_size, right_size, pairs):
    right,graph=matching(left_size,right_size,pairs)
    unmatched=set(range(left_size))-set(right)
    seen_left=set(unmatched)
    seen_right=set()
    stack=list(unmatched)
    while stack:
        v=stack.pop()
        for w in graph[v]:
            if right[w]!=v and w not in seen_right:
                seen_right.add(w)
                if right[w]>=0 and right[w] not in seen_left:
                    seen_left.add(right[w])
                    stack.append(right[w])
    return [sorted(set(range(left_size))-seen_left),sorted(seen_right)]


def cheap_flow(n, edges):
    from heapq import heappush,heappop
    graph=[[] for _ in range(n)]
    for u,v,capacity,cost in edges:
        if u==v:
            continue
        a=[v,capacity,cost,len(graph[v])]
        b=[u,0,-cost,len(graph[u])]
        graph[u].append(a)
        graph[v].append(b)
    potential=[0]*n
    flow=price=0
    inf=2**62
    while True:
        dist=[inf]*n
        dist[0]=0
        prev=[None]*n
        queue=[(0,0)]
        while queue:
            weight,u=heappop(queue)
            if weight!=dist[u]:
                continue
            for i,(v,cap,cost,_) in enumerate(graph[u]):
                step=weight+cost+potential[u]-potential[v]
                if cap and step<dist[v]:
                    dist[v],prev[v]=step,(u,i)
                    heappush(queue,(step,v))
        if prev[-1] is None:
            return [flow,price]
        for i,d in enumerate(dist):
            if d<inf:
                potential[i]+=d
        v,added=n-1,inf
        while v:
            u,i=prev[v]
            added=min(added,graph[u][i][1])
            v=u
        v=n-1
        while v:
            u,i=prev[v]
            edge=graph[u][i]
            edge[1]-=added
            graph[v][edge[3]][1]+=added
            price+=added*edge[2]
            v=u
        flow+=added


def vertex_flow(limits, edges):
    n=len(limits)
    network=[[2*i,2*i+1,cap] for i,cap in enumerate(limits)]
    network.extend([2*a+1,2*b,cap] for a,b,cap in edges)
    return blocking_flow(2*n,network)


def assignment(capacities, job_count, offers):
    workers=len(capacities)
    sink=workers+job_count+1
    edges=[[0,i+1,c,0] for i,c in enumerate(capacities)]
    edges.extend([workers+j+1,sink,1,0] for j in range(job_count))
    edges.extend([w+1,workers+j+1,1,c] for w,j,c in offers)
    return cheap_flow(sink+1,edges)


def cut(n, edges):
    return min(sum(c for u,v,c in edges if mask>>u&1 and not mask>>v&1)
               for mask in range(1,1<<n,2) if not (mask>>(n-1)&1))


def edge_oracle(n, edges):
    # 모든 합법적인 간선 유량을 열거하고 순 유량과 비용을 비교한다.
    best=(0,0)
    for chosen in itertools.product(*(range(cap+1) for _,_,cap,_ in edges)):
        net=[0]*n
        cost=0
        for (u,v,_,c),x in zip(edges,chosen):
            net[u]+=x;net[v]-=x;cost+=x*c
        if not any(net[1:-1]) and net[0]>=0:
            best=max(best,(net[0],-cost))
    return [best[0],-best[1]]


def cover_oracle(l,r,edges):
    candidates=[]
    for mask in range(1<<(l+r)):
        if all(mask>>u&1 or mask>>(l+v)&1 for u,v in edges):
            left=[i for i in range(l) if mask>>i&1]
            right=[i for i in range(r) if mask>>(l+i)&1]
            candidates.append((len(left)+len(right), -len(left), left, right))
    _,_,a,b=min(candidates)
    return [a,b]


def assignments_oracle(caps,n,offers):
    best=(0,0)
    choices=[[(None,0)] for _ in range(n)]
    for w,j,p in offers: choices[j].append((w,p))
    for picked in itertools.product(*choices):
        jobs=[w for w,_ in picked if w is not None]
        if all(jobs.count(i)<=c for i,c in enumerate(caps)):
            best=max(best,(len(jobs),-sum(p for _,p in picked)))
    return [best[0],-best[1]]


def populate(add):
    r=random.Random(1403)
    graphs=[[4,[[0,1,3],[0,2,2],[1,2,1],[1,3,2],[2,3,3]]],[3,[]],[2,[[0,1,2],[0,1,3],[1,0,1],[0,0,3]]]]
    for i in range(15):
        n=r.randrange(2,8)
        graphs.append([n,[[r.randrange(n),r.randrange(n),r.randrange(4)] for _ in range(i+1)]])
    directed="정점은 0부터 n−1까지이며 n≥2입니다. 평행·역방향 간선과 자기 루프를 허용합니다. 입력 간선은 각각 독립적인 용량을 가집니다."
    statement="각 [u,v,c]는 u에서 v로 최대 c를 보낼 수 있는 간선입니다. 정점 0이 소스, n−1이 싱크이며 그 밖의 정점에서는 유입량과 유출량이 같아야 합니다.\n소스에서 싱크까지 보낼 수 있는 최대 유량을 정수로 반환하세요."
    for title,fn,limit,last in [("최대 유량 기초",shortest_augment,80,60),("디닉 알고리즘",blocking_flow,350,150)]:
        add(title,"n:int edges:int[][]","int",statement,directed+f" n≤{limit}, 간선 수≤5,000, 0≤c≤10,000.",fn,graphs,cut,stress=[([last+2,[[0,i,7] for i in range(1,last+1)]+[[i,last+1,5] for i in range(1,last+1)]],5*last)])
    bip=[[3,2,[[0,0],[1,0],[1,1],[2,1]]],[0,3,[]],[2,2,[[0,0],[1,1]]]]
    for i in range(16):
        l,h=r.randint(1,5),r.randint(1,5)
        bip.append([l,h,[[a,b] for a in range(l) for b in range(h) if r.random()<0.45]])
    lim="각 부분의 정점 번호는 0부터 시작합니다. 0≤left_size,right_size≤350, 0≤간선 수≤20,000. pairs는 유효한 [왼쪽,오른쪽] 쌍입니다."
    ids=[[i,i] for i in range(200)]
    add("이분 매칭","left_size:int right_size:int pairs:int[][]","int","양쪽 정점이 서로 겹치지 않도록 pairs에서 간선을 선택합니다.\n선택할 수 있는 간선의 최대 개수를 반환하세요.",lim,match_size,bip,lambda l,h,e:sum(map(len,cover_oracle(l,h,e))),dependencies=(matching,),stress=[([200,200,ids],200)])
    add("최소 정점 커버","left_size:int right_size:int pairs:int[][]","int[][]","모든 이분 그래프 간선이 적어도 한 끝점에서 선택되도록 최소 개수의 정점을 고르세요. 최소 커버 중 왼쪽 정점을 최대한 많이 포함하는 커버는 유일합니다.\n[선택한 왼쪽 번호들, 선택한 오른쪽 번호들]을 반환하며, 각 목록은 오름차순입니다.",lim,cover,bip,cover_oracle,dependencies=(matching,),stress=[([200,200,ids],[list(range(200)),[]])])
    priced=[[4,[[0,1,2,2],[1,3,1,9],[0,2,1,3],[2,3,2,1],[1,2,1,1]]],[2,[]],[3,[[0,1,1,9],[0,1,2,0],[1,2,2,1]]]]
    for k in range(17):
        n=r.randint(2,5)
        priced.append([n,[[r.randrange(n),r.randrange(n),r.randrange(3),r.randrange(5)] for _ in range(1+k%7)]])
    add("최소 비용 최대 유량","n:int edges:int[][]","int[]","간선 [u,v,c,w]에는 용량 c와 단위당 비용 w가 있습니다. 정점 0에서 n−1로 유량을 최대로 보낸 후, 그 최대 유량을 보내는 방법 중 총비용을 최소화하세요.\n[최대 유량, 최소 총비용]을 반환하세요. 흐름이 없으면 [0,0]입니다.",directed+" n≤90, 간선 수≤600, 0≤c≤10, 0≤w≤100.",cheap_flow,priced,edge_oracle,stress=[([62,[[0,i,2,i] for i in range(1,61)]+[[i,61,2,0] for i in range(1,61)]],[120,3660])])
    vc=[[[4,1,4],[[0,1,4],[1,2,4]]],[[0,5],[[0,1,4]]]]
    for k in range(14):
        n=r.randint(2,5)
        vc.append([[r.randrange(6) for _ in range(n)],[[r.randrange(n),r.randrange(n),r.randrange(5)] for _ in range(1+k%6)]])
    def vertex_oracle(c,e):
        n=len(c)
        return cut(2*n,[[2*i,2*i+1,x] for i,x in enumerate(c)]+[[2*a+1,2*b,x] for a,b,x in e])
    add("정점 용량 네트워크","limits:int[] edges:int[][]","int","방향 간선 [u,v,c]의 용량 외에도 정점 i를 통과할 수 있는 총유량이 limits[i]로 제한됩니다. 소스 0과 싱크 n−1의 용량도 적용됩니다.\n0에서 n−1로 전달할 수 있는 최대 유량을 반환하세요.",directed+" n=limits 길이≤150. 0≤간선 수≤2,000, 0≤간선·정점 용량≤10,000.",vertex_flow,vc,vertex_oracle,dependencies=(blocking_flow,),stress=[([[10]*100,[[i,i+1,12] for i in range(99)]],10)])
    jobs=[[[1,2],3,[[0,0,1],[0,1,0],[1,0,0],[1,2,3]]],[[0],1,[[0,0,4]]]]
    for k in range(14):
        l,h=r.randint(1,4),r.randint(1,4)
        jobs.append([[r.randrange(4) for _ in range(l)],h,[[w,j,r.randrange(7)] for w in range(l) for j in range(h) if r.random()<0.5]])
    add("유량을 이용한 작업 배정","capacities:int[] job_count:int offers:int[][]","int[]","작업자 w는 최대 capacities[w]개의 서로 다른 작업을 맡을 수 있습니다. 각 작업은 한 명에게만 배정하고, [w,j,c]는 w가 작업 j를 비용 c로 맡을 수 있다는 뜻입니다. 우선 배정 개수를 최대화하고 그 안에서 비용을 최소화하세요.\n[배정된 개수, 최소 총비용]을 반환하세요.","작업자·작업 번호는 각각 0부터 시작합니다. 각각 1~200명/개, 0≤capacities[w]≤20, 0≤c≤1,000, 0≤제안 수≤5,000.",assignment,jobs,assignments_oracle,dependencies=(cheap_flow,),stress=[([[1]*80,80,[[i,i,5] for i in range(80)]],[80,400])])
