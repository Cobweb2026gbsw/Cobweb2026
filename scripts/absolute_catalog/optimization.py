"""오프라인 지배 질의, 직선 최적화, 평면 및 문자열 알고리즘."""
import random


def dominance(points):
    # 좌표가 모두 같은 점들을 묶어 같은 좌표의 양방향 지배를 별도로 복원한다.
    groups = {}
    for i,key in enumerate(map(tuple,points)):
        groups.setdefault(key,[]).append(i)
    ordered = sorted(groups)
    zrank = {z:i+1 for i,z in enumerate(sorted({z for _,_,z in ordered}))}
    size = [len(groups[key]) for key in ordered]
    count = [v-1 for v in size]
    bit = [0]*(len(zrank)+1)
    def update(i,v):
        while i<len(bit):
            bit[i]+=v
            i+=i&-i
    def prefix(i):
        total=0
        while i:
            total+=bit[i]
            i-=i&-i
        return total
    def cdq(lo,hi):
        if hi-lo<2:
            return
        mid=(lo+hi)//2
        cdq(lo,mid)
        cdq(mid,hi)
        left=sorted(range(lo,mid),key=lambda i:ordered[i][1])
        right=sorted(range(mid,hi),key=lambda i:ordered[i][1])
        end=0
        for j in right:
            while end<len(left) and ordered[left[end]][1]<=ordered[j][1]:
                i=left[end]
                update(zrank[ordered[i][2]],size[i])
                end+=1
            count[j]+=prefix(zrank[ordered[j][2]])
        for i in left[:end]:
            update(zrank[ordered[i][2]],-size[i])
    cdq(0,len(ordered))
    ans=[0]*len(points)
    for key,total in zip(ordered,count):
        for i in groups[key]:
            ans[i]=total
    return ans


class LineVersions:
    """정수 x 구간의 최소 직선. 튜플을 공유해 이전 버전을 보존한다."""
    def __init__(self,left,right):
        self.left,self.right=left,right

    def insert(self,node,line,lo=None,hi=None):
        if lo is None:
            lo,hi=self.left,self.right
        if node is None:
            return line,None,None
        best,lchild,rchild=node
        mid=(lo+hi)//2
        def value(f,x):
            return f[0]*x+f[1]
        if value(line,mid)<value(best,mid):
            best,line=line,best
        if lo<hi:
            if value(line,lo)<value(best,lo):
                lchild=self.insert(lchild,line,lo,mid)
            elif value(line,hi)<value(best,hi):
                rchild=self.insert(rchild,line,mid+1,hi)
        return best,lchild,rchild

    def minimum(self,node,x):
        value=float("inf")
        lo,hi=self.left,self.right
        while node is not None:
            (m,b),left,right=node
            value=min(value,m*x+b)
            if lo==hi:
                break
            mid=(lo+hi)//2
            if x<=mid:
                node,hi=left,mid
            else:
                node,lo=right,mid+1
        return value


def line_queries(ops):
    tree=LineVersions(-10000,10000)
    root=None
    out=[]
    for kind,u,v in ops:
        if kind==0:
            root=tree.insert(root,(u,v))
        else:
            out.append(tree.minimum(root,u))
    return out


def union_area(rects):
    if not rects:
        return 0
    ys=sorted({y for x1,y1,x2,y2 in rects for y in (y1,y2)})
    index={y:i for i,y in enumerate(ys)}
    events=sorted((x,delta,index[y1],index[y2]) for x1,y1,x2,y2 in rects
                  for x,delta in ((x1,1),(x2,-1)))
    cover=[0]*(4*len(ys))
    length=[0]*len(cover)
    def adjust(node,lo,hi,a,b,delta):
        if hi<=a or b<=lo:
            return
        if a<=lo and hi<=b:
            cover[node]+=delta
        else:
            mid=(lo+hi)//2
            adjust(2*node,lo,mid,a,b,delta)
            adjust(2*node+1,mid,hi,a,b,delta)
        if cover[node]:
            length[node]=ys[hi]-ys[lo]
        elif hi-lo==1:
            length[node]=0
        else:
            length[node]=length[2*node]+length[2*node+1]
    ans,prev=0,events[0][0]
    for x,delta,a,b in events:
        ans+=(x-prev)*length[1]
        adjust(1,0,len(ys)-1,a,b,delta)
        prev=x
    return ans


def stream_matches(patterns,ops):
    go=[[-1]*26]
    ends=[]
    for word in patterns:
        u=0
        for char in word:
            c=ord(char)-97
            if go[u][c]<0:
                go[u][c]=len(go)
                go.append([-1]*26)
            u=go[u][c]
        ends.append(u)
    fail=[0]*len(go)
    bfs=[]
    for c,v in enumerate(go[0]):
        if v<0:
            go[0][c]=0
        else:
            bfs.append(v)
    for u in bfs:
        for c,v in enumerate(go[u]):
            if v<0:
                go[u][c]=go[fail[u]][c]
            else:
                fail[v]=go[fail[u]][c]
                bfs.append(v)
    children=[[] for _ in go]
    for v in bfs:
        children[fail[v]].append(v)
    start,end=[0]*len(go),[0]*len(go)
    tick=0
    stack=[(0,False)]
    while stack:
        v,exit_node=stack.pop()
        if exit_node:
            end[v]=tick
        else:
            tick+=1
            start[v]=tick
            stack.append((v,True))
            stack.extend((w,False) for w in children[v])
    bit=[0]*(len(go)+2)
    def update(i,delta):
        while i<len(bit):
            bit[i]+=delta
            i+=i&-i
    active=[False]*len(patterns)
    state,ans=0,[]
    for kind,x in ops:
        if kind==2:
            state=go[state][x]
            hits=0
            i=start[state]
            while i:
                hits+=bit[i]
                i-=i&-i
            ans.append(hits)
        elif active[x]!=(kind==0):
            active[x]=kind==0
            delta=1 if active[x] else -1
            update(start[ends[x]],delta)
            update(end[ends[x]]+1,-delta)
    return ans


def transport(links,rate,fee):
    tree=LineVersions(0,max(rate))
    versions=[tree.insert(None,(0,0))]
    dist,dp=[0],[0]
    for v,(parent,length) in enumerate(links,1):
        distance=dist[parent]+length
        best=tree.minimum(versions[parent],rate[v])+fee[v]+rate[v]*distance
        dp.append(best)
        dist.append(distance)
        versions.append(tree.insert(versions[parent],(-distance,best)))
    return [str(cost) for cost in dp]


def simple_lines(ops):
    lines,out=[],[]
    for kind,u,v in ops:
        if kind==0:
            lines.append((u,v))
        else:
            out.append(min(m*u+b for m,b in lines))
    return out


def stream_oracle(patterns,ops):
    active=set()
    text=""
    out=[]
    for kind,x in ops:
        if kind==0:
            active.add(x)
        elif kind==1:
            active.discard(x)
        else:
            text+=chr(97+x)
            out.append(sum(text.endswith(patterns[i]) for i in active))
    return out


def trip_oracle(links,rate,fee):
    dp=[0]
    for v in range(1,len(rate)):
        options=[]
        p,d=v,0
        while p:
            p,length=links[p-1]
            d+=length
            options.append(dp[p]+d*rate[v]+fee[v])
        dp.append(min(options))
    return [str(cost) for cost in dp]


def populate(add):
    rng=random.Random(1446)
    dominance_cases=[[[[0,0,0],[0,0,0],[1,0,2],[0,-1,3]]],[[[3,4,5]]]]
    lines=[[[[0,2,1],[1,3,0],[0,-1,6],[1,3,0]]],[[[0,0,0],[1,-10000,0]]]]
    areas=[[[[0,0,3,2],[1,1,4,3]]],[[]]]
    strings=[[["a","ba"],[[0,0],[0,1],[2,1],[2,0],[1,0],[2,0]]],[["z"],[[2,25]]]]
    trips=[[[[0,4],[1,3],[0,1]],[0,2,5,1],[0,0,1,0]],
           [[[0,100],[1,100]],[0,1000000000,999999999],[0,0,123]],
           [[],[0],[0]]]
    for _ in range(16):
        n=rng.randint(4,24)
        dominance_cases.append([[[rng.randint(-3,3) for _ in range(3)] for _ in range(n)]])
        ops=[[0,rng.randint(-10,10),rng.randint(-10,10)]]
        for _ in range(n):
            ops.append([0,rng.randint(-10,10),rng.randint(-10,10)] if rng.randrange(2) else [1,rng.randint(-12,12),0])
        lines.append([ops])
        rects=[]
        for _ in range(n):
            x1,x2=sorted(rng.sample(range(-6,7),2))
            y1,y2=sorted(rng.sample(range(-6,7),2))
            rects.append([x1,y1,x2,y2])
        areas.append([rects])
        patterns=["".join(rng.choice("abc") for _ in range(rng.randint(1,5))) for _ in range(n)]
        ops=[[0,0]]
        for _ in range(50):
            kind=rng.choice([0,1,2,2])
            ops.append([kind,rng.randrange(3 if kind==2 else n)])
        strings.append([patterns,ops])
        trips.append([[[rng.randrange(i),rng.randrange(1,11)] for i in range(1,n)],
                      [0]+[rng.randrange(11) for _ in range(n-1)],[0]+[rng.randrange(10) for _ in range(n-1)]])
    trip_rng=random.Random(1450)
    for _ in range(8):
        n=trip_rng.randint(20,80)
        trips.append([[[trip_rng.randrange(i),trip_rng.randint(1,100)] for i in range(1,n)],
                      [0]+[trip_rng.choice([0,trip_rng.randrange(11),trip_rng.randint(1,10**9)]) for _ in range(n-1)],
                      [0]+[trip_rng.randint(0,10000) for _ in range(n-1)]])
    n=10000
    strips=[[i-15000,i%17000-10000,i-14999,20000-i%11000] for i in range(30000)]
    # 두 줄기의 정점을 번갈아 번호로 부여한다. 홀수 줄기의 비용 0인 조상은
    # 짝수 줄기로 건너갈 수 없어 짝수 도시는 루트에서 한 번에 운송해야 한다.
    branched=[[max(0,i-2),100] for i in range(1,20000)]
    rates=[0]+[0 if i%2 else 10**9-i for i in range(1,20000)]
    fees=[0]+[0 if i%2 else 1 for i in range(1,20000)]
    # 요율이 엄밀히 감소하면 모든 구간의 마지막 요율이 가장 낮으므로 직송이 최적이다.
    chain=[[i-1,100] for i in range(1,20000)]
    decreasing=[0]+[10**9-i+1 for i in range(1,20000)]
    # 반대로 요율이 증가하면 모든 간선을 각 도착 도시의 요율로 지불하는 방법이 최적이다.
    increasing=[0]+[50000*i for i in range(1,20000)]
    add("3차원 부분 순서","CDQ Divide & Conquer","points:int[][]","int[]",
        "각 점 i에 대해 세 좌표가 각각 그 점 이하인 다른 점 j의 개수를 세어 입력 순서대로 반환하세요. 좌표가 같은 두 점도 서로 다른 인덱스면 서로를 셉니다.",
        "1 ≤ 점 수 N ≤ 40,000. 각 행은 [x,y,z], 각 좌표의 절댓값은 10⁹ 이하입니다.","O(N log² N)",dominance,
        lambda points:[sum(i!=j and all(a<=b for a,b in zip(q,p)) for j,q in enumerate(points)) for i,p in enumerate(points)],dominance_cases,
        [([[[i,i,i] for i in range(39999,-1,-1)]],list(range(39999,-1,-1)))])
    add("동적 볼록 껍질 쿼리","Dynamic CHT","ops:int[][]","int[]",
        "직선과 질의가 섞여 들어옵니다. [0,m,b]면 직선 y=mx+b를 영구 추가하며 [1,x,0]이면 그 시점까지 추가된 직선 중 x에서의 최소 y값을 기록합니다. 기울기와 x에는 정렬 조건이 없습니다. 질의 순서대로 답을 반환하세요.",
        "1 ≤ Q ≤ 40,000. −10,000 ≤ m,b,x ≤ 10,000. 첫 연산은 직선 추가입니다.","O(Q log W), W=20,001",line_queries,simple_lines,lines,
        [([[[0,j,j] for j in range(n)]+[[1,j,0] for j in range(-n,n)]],[min(0,(n-1)*(j+1)) for j in range(-n,n)])],(LineVersions,))
    add("직사각형 합집합 넓이","Sweep Line","rects:int[][]","int",
        "축에 평행한 직사각형 [x1,y1,x2,y2]들의 합집합 넓이를 구하세요. 겹치는 영역은 한 번만 세며 빈 입력의 넓이는 0입니다.",
        "0 ≤ N ≤ 30,000, −20,000 ≤ x1<x2 ≤ 20,000, −20,000 ≤ y1<y2 ≤ 20,000.","O(N log N)",union_area,
        lambda rects:len({(x,y) for a,b,c,d in rects for x in range(a,c) for y in range(b,d)}),areas,
        [([[[i,0,i+2,n] for i in range(n)]],(n+1)*n),
         ([strips],sum(d-b for a,b,c,d in strips))])
    add("온라인 문자열 패턴 처리","고급 문자열 자료구조","patterns:string[] ops:int[][]","int[]",
        "처음에는 빈 텍스트이고 모든 패턴이 비활성입니다. [0,i]는 패턴 i 활성화, [1,i]는 비활성화입니다. 이미 같은 상태이면 그대로 둡니다. [2,c]마다 알파벳 c(0=a,…,25=z)를 텍스트 끝에 붙이고 현재 텍스트의 접미사인 활성 패턴의 개수를 반환 배열에 추가하세요. 동일한 패턴도 인덱스별로 셉니다.",
        "1 ≤ 패턴 수, 연산 수 ≤ 40,000. 패턴은 길이 1~64의 영문 소문자 문자열이고 전체 길이 L은 최대 40,000입니다.","O(26L+Q log(L+1))",stream_matches,stream_oracle,strings,
        [([["a"*i for i in range(1,65)],[[0,i] for i in range(64)]+[[2,0] for _ in range(n)]],[min(i+1,64) for i in range(n)]),
         ([["a"*64]*625,[[0,i] for i in range(625)]+[[2,0]]*39375],[0]*63+[625]*39312)])
    add("최종 문제: 통합 최적화","그래프+DP+자료구조","links:int[][] rate:int[] fee:int[]","string[]",
        "도시 0을 루트로 하는 운송 트리입니다. links[i−1]=[p,d]이면 도시 i의 부모는 p이고 간선 길이는 d입니다. 루트에서 도시 i까지의 거리를 D[i]라 합시다. 도시 i의 엄밀한 조상 a에서 i로 한 번에 운송하면 fee[i]+rate[i]·(D[i]−D[a])를 지불합니다. 여러 번 운송을 이어도 됩니다. 루트에서 각 도시까지의 최소 요금을 도시 번호 순서대로 반환하세요. 각 요금은 선행 0 없는 십진 문자열로 표현하며, 비용이 0이면 `\"0\"`을 넣습니다.",
        "1 ≤ N ≤ 20,000. rate와 fee의 길이는 N, links의 길이는 N−1입니다. 0 ≤ p<i, 1 ≤ d ≤ 100. 0 ≤ rate[i] ≤ 1,000,000,000, 0 ≤ fee[i] ≤ 10,000. rate[0]=fee[0]=0. 분기한 다른 하위 트리에서 출발할 수 없습니다. 거리와 요율의 곱, 누적 요금은 64비트 정수 범위입니다.","O(N log(R+2)), R=최대 요율",transport,trip_oracle,trips,
        [([[[i,1] for i in range(n-1)],[0]+[5]*(n-1),[0]+[9]*(n-1)],["0"]+[str(5*i+9) for i in range(1,n)]),
         ([[[0,50],[0,2],[2,8]],[0,0,5,8],[0,0,0,1]],["0","0","10","75"]),
         ([branched,rates,fees],["0"]+[str(0 if i%2 else (i//2)*100*rates[i]+1) for i in range(1,20000)]),
         ([chain,decreasing,[0]*20000],[str(i*100*price) for i,price in enumerate(decreasing)]),
         ([chain,increasing,[0]*20000],[str(2500000*i*(i+1)) for i in range(20000)])],(LineVersions,))
