"""영속 자료구조, 구간 최적화, 트리 분해와 오프라인 질의."""
import random


def version_sum(values, operations):
    nodes=[]
    def store(left,right,value):
        nodes.append((left,right,value))
        return len(nodes)-1
    def build(l,r):
        if l+1==r:
            return store(-1,-1,values[l])
        m=(l+r)//2
        a,b=build(l,m),build(m,r)
        return store(a,b,nodes[a][2]+nodes[b][2])
    def replace(k,l,r,i,x):
        if l+1==r:
            return store(-1,-1,x)
        m=(l+r)//2
        a,b,_=nodes[k]
        if i<m:
            a=replace(a,l,m,i,x)
        else:
            b=replace(b,m,r,i,x)
        return store(a,b,nodes[a][2]+nodes[b][2])
    def total(k,l,r,start,stop):
        if start<=l and r<=stop:
            return nodes[k][2]
        if r<=start or stop<=l:
            return 0
        m=(l+r)//2
        return total(nodes[k][0],l,m,start,stop)+total(nodes[k][1],m,r,start,stop)
    n=len(values)
    roots=[build(0,n)]
    answer=[]
    for mode,v,a,b in operations:
        if mode==0:
            roots.append(replace(roots[v],0,n,a,b))
        else:
            answer.append(total(roots[v],0,n,a,b+1))
    return answer


def different(values, ranges):
    from math import isqrt
    block=max(1,isqrt(len(values)))
    order=sorted(range(len(ranges)),key=lambda i:(ranges[i][0]//block,
                 ranges[i][1] if ranges[i][0]//block%2==0 else -ranges[i][1]))
    answer=[0]*len(ranges)
    counts={}
    def move(i,step):
        x=values[i]
        counts[x]=counts.get(x,0)+step
        if not counts[x]:
            del counts[x]
    lo,hi=0,-1
    for i in order:
        a,b=ranges[i]
        while lo>a:
            lo-=1
            move(lo,1)
        while hi<b:
            hi+=1
            move(hi,1)
        while lo<a:
            move(lo,-1)
            lo+=1
        while hi>b:
            move(hi,-1)
            hi-=1
        answer[i]=len(counts)
    return answer


def paths(values, parents, operations, combine, neutral):
    n=len(values)
    children=[[] for _ in range(n)]
    depth=[0]*n
    for v in range(1,n):
        p=parents[v]
        children[p].append(v)
        depth[v]=depth[p]+1
    size=[1]*n
    heavy=[-1]*n
    for v in range(n-1,0,-1):
        p=parents[v]
        size[p]+=size[v]
        if heavy[p]<0 or size[v]>size[heavy[p]]:
            heavy[p]=v
    base=1
    while base<n:
        base*=2
    seg=[neutral]*(2*base)
    pos=[0]*n
    head=[0]*n
    i=0
    for start in range(n):
        if start and start==heavy[parents[start]]:
            continue
        v=start
        while v>=0:
            pos[v]=i
            head[v]=start
            seg[base+i]=values[v]
            i+=1
            v=heavy[v]
    for k in range(base-1,0,-1):
        seg[k]=combine(seg[2*k],seg[2*k+1])
    def interval(a,b):
        l,r=base+pos[a],base+pos[b]+1
        result=neutral
        while l<r:
            if l&1:
                result=combine(result,seg[l])
                l+=1
            if r&1:
                r-=1
                result=combine(result,seg[r])
            l//=2
            r//=2
        return result
    answers=[]
    for mode,a,b in operations:
        if mode==0:
            k=base+pos[a]
            seg[k]=b
            while k>1:
                k//=2
                seg[k]=combine(seg[2*k],seg[2*k+1])
        else:
            result=neutral
            while head[a]!=head[b]:
                if depth[head[a]]<depth[head[b]]:
                    a,b=b,a
                result=combine(result,interval(head[a],a))
                a=parents[head[a]]
            if depth[a]>depth[b]:
                a,b=b,a
            answers.append(combine(result,interval(a,b)))
    return answers


def tree_max(values, parents, operations):
    return paths(values,parents,operations,max,-2**31)


def tree_sum(values, parents, operations):
    return paths(values,parents,operations,lambda a,b:a+b,0)


def ceiling(values, operations):
    n=len(values)
    top=[0]*(4*n)
    second=[-2**63]*(4*n)
    count=[0]*(4*n)
    total=[0]*(4*n)
    def pull(v):
        a,b=2*v,2*v+1
        total[v]=total[a]+total[b]
        if top[a]==top[b]:
            top[v],second[v],count[v]=top[a],max(second[a],second[b]),count[a]+count[b]
        else:
            if top[a]<top[b]:
                a,b=b,a
            top[v],second[v],count[v]=top[a],max(second[a],top[b]),count[a]
    def build(v,l,r):
        if l+1==r:
            top[v]=total[v]=values[l]
            count[v]=1
            return
        m=(l+r)//2
        build(2*v,l,m)
        build(2*v+1,m,r)
        pull(v)
    def lower(v,x):
        if top[v]>x:
            total[v]-=(top[v]-x)*count[v]
            top[v]=x
    def act(v,l,r,a,b,x,update):
        if r<=a or b<=l:
            return 0
        if update and top[v]<=x:
            return 0
        if a<=l and r<=b:
            if not update:
                return total[v]
            if second[v]<x:
                lower(v,x)
                return 0
        lower(2*v,top[v])
        lower(2*v+1,top[v])
        m=(l+r)//2
        result=act(2*v,l,m,a,b,x,update)+act(2*v+1,m,r,a,b,x,update)
        pull(v)
        return result
    build(1,0,n)
    result=[]
    for mode,l,r,x in operations:
        value=act(1,0,n,l,r+1,x,mode==0)
        if mode==1:
            result.append(value)
    return result


def timeline(n, operations):
    size=1
    while size<len(operations):
        size*=2
    intervals=[[] for _ in range(2*size)]
    def attach(l,r,edge):
        l+=size
        r+=size
        while l<r:
            if l&1:
                intervals[l].append(edge)
                l+=1
            if r&1:
                r-=1
                intervals[r].append(edge)
            l//=2
            r//=2
    open_at={}
    for t,(mode,u,v) in enumerate(operations):
        edge=(min(u,v),max(u,v))
        if mode==0:
            open_at[edge]=t
        elif mode==1:
            attach(open_at.pop(edge),t,edge)
    for edge,t in open_at.items():
        attach(t,len(operations),edge)
    parent=[-1]*n
    history=[]
    def root(u):
        while parent[u]>=0:
            u=parent[u]
        return u
    def visit(k):
        previous=len(history)
        for a,b in intervals[k]:
            a,b=root(a),root(b)
            if a==b:
                continue
            if parent[a]>parent[b]:
                a,b=b,a
            history.append((a,b,parent[a],parent[b]))
            parent[a]+=parent[b]
            parent[b]=a
        if k<size:
            visit(2*k)
            visit(2*k+1)
        elif k-size<len(operations):
            mode,u,v=operations[k-size]
            if mode==2:
                result.append(int(root(u)==root(v)))
        while len(history)>previous:
            a,b,pa,pb=history.pop()
            parent[a],parent[b]=pa,pb
    result=[]
    visit(1)
    return result


def earliest(n, edges, pairs):
    m=len(edges)
    low=[-1]*len(pairs)
    high=[m+1]*len(pairs)
    while True:
        groups={}
        for i in range(len(pairs)):
            if high[i]-low[i]>1:
                mid=(low[i]+high[i])//2
                groups.setdefault(mid,[]).append(i)
        if not groups:
            break
        parent=[-1]*n
        def root(v):
            while parent[v]>=0:
                if parent[parent[v]]>=0:
                    parent[v]=parent[parent[v]]
                v=parent[v]
            return v
        for step in range(m+1):
            if step:
                a,b=(root(v) for v in edges[step-1])
                if a!=b:
                    if parent[a]>parent[b]:
                        a,b=b,a
                    parent[a]+=parent[b]
                    parent[b]=a
            for i in groups.get(step,()):
                a,b=pairs[i]
                if root(a)==root(b):
                    high[i]=step
                else:
                    low[i]=step
    return [-1 if h>m else h for h in high]


def versions_naive(values,ops):
    versions=[values];result=[]
    for mode,v,a,b in ops:
        if mode==0:
            copy=versions[v][:];copy[a]=b;versions.append(copy)
        else: result.append(sum(versions[v][a:b+1]))
    return result


def naive_tree(values,parents,ops,fn):
    values=values[:];result=[]
    for mode,a,b in ops:
        if mode==0: values[a]=b;continue
        x,y=[],[]
        while a>=0: x.append(a);a=parents[a]
        while b>=0: y.append(b);b=parents[b]
        common=next(v for v in x if v in y)
        result.append(fn(values[v] for v in x[:x.index(common)+1]+y[:y.index(common)]))
    return result


def naive_range(vals,ops):
    vals=vals[:];out=[]
    for mode,l,r,x in ops:
        if mode==0: vals[l:r+1]=[min(a,x) for a in vals[l:r+1]]
        else: out.append(sum(vals[l:r+1]))
    return out


def connected(n,edges,a,b):
    graph=[set() for _ in range(n)]
    for u,v in edges: graph[u].add(v);graph[v].add(u)
    seen={a};q=[a]
    while q:
        v=q.pop()
        for w in graph[v]-seen:
            seen.add(w);q.append(w)
    return b in seen


def timeline_naive(n,ops):
    edges=set(); out=[]
    for m,a,b in ops:
        edge=tuple(sorted((a,b)))
        if m==0: edges.add(edge)
        elif m==1: edges.remove(edge)
        else: out.append(int(connected(n,edges,a,b)))
    return out


def populate(add):
    r=random.Random(1417)
    lo="1≤배열 길이≤50,000, 0≤연산 수≤50,000. 인덱스는 0부터 시작하고 모든 구간 [l,r]은 양 끝을 포함하며 0≤l≤r<n입니다."
    vals=" 각 원소와 갱신 값은 −10,000 이상 10,000 이하입니다."
    past=[[[2,3],[[0,0,0,9],[1,0,0,1],[1,1,0,1],[0,0,1,-3],[1,2,0,1]]],[[0],[]]]
    ranges=[[[1,2,1,3],[[0,2],[1,3],[0,0]]],[[7],[]]]
    tree=[[[2,-1,5],[-1,0,0],[[1,1,2],[0,0,7],[1,1,2]]],[[-4],[-1],[[1,0,0]]]]
    limits=[[[9,6,3],[[0,0,2,6],[1,0,2,0],[0,0,2,3],[1,0,2,0]]],[[1],[]]]
    for k in range(18):
        n=r.randint(1,15)
        v=[r.randint(-10,10) for _ in range(n)]
        par=[-1]+[r.randrange(i) for i in range(1,n)]
        a,b,c,d=[],[],[],[]
        versions=1
        for j in range(k+4):
            x,y=sorted(r.choices(range(n),k=2))
            z=r.randint(-10,10)
            if r.randrange(2):
                a.append([0,r.randrange(versions),x,z]);versions+=1
                c.append([0,x,z]);d.append([0,x,y,z])
            else:
                a.append([1,r.randrange(versions),x,y])
                c.append([1,x,y]);d.append([1,x,y,0])
            b.append([x,y])
        past.append([v,a]);ranges.append([v,b]);tree.append([v,par,c]);limits.append([v,d])
    n=2000
    chain=[-1]+list(range(n-1))
    add("Persistent Segment Tree","values:int[] operations:int[][]","int[]","0번 버전은 values입니다. [0,v,i,x]는 버전 v의 i번째 값을 x로 바꾼 새 버전을 만들어 다음 번호를 붙입니다. 기존 버전은 그대로 읽을 수 있습니다. [1,v,l,r]는 버전 v의 구간 합 질의입니다.\n질의에 대한 합만 입력 순서대로 반환하세요.",lo+vals+" v는 해당 시점에 존재하는 버전입니다.",version_sum,past,versions_naive,stress=[([[1]*n,[[0,0,i,i] for i in range(n)]+[[1,i+1,0,n-1] for i in range(n)]],[n-1+i for i in range(n)])])
    add("Mo's Algorithm","values:int[] ranges:int[][]","int[]","배열은 변하지 않습니다. 질의를 오프라인으로 재정렬해 처리할 수 있습니다.\n각 [l,r] 안에 나타나는 서로 다른 정수의 개수를 원래 질의 순서대로 반환하세요.",lo+" 각 원소는 부호 있는 32비트 정수입니다.",different,ranges,lambda v,qs:[len(set(v[l:h+1])) for l,h in qs],stress=[([list(range(n)),[[i,n-1] for i in range(n)]],list(range(n,0,-1)))])
    for title,fn,agg,label in [("Heavy-Light Decomposition",tree_max,max,"최댓값"),("트리 경로 합 쿼리",tree_sum,sum,"합")]:
        add(title,"values:int[] parents:int[] operations:int[][]","int[]","정점 i의 초기 값은 values[i]입니다. [0,i,x]는 값을 x로 대입하고 [1,a,b]는 a부터 b까지 유일한 단순 경로의 양 끝 정점을 포함한 모든 정점의 "+label+"을 묻습니다.\n각 질의의 "+label+"을 입력 순서대로 반환하세요.", "1≤n≤50,000, 0≤연산 수≤50,000. parents[0]=−1, i>0이면 0≤parents[i]<i. 모든 정점 번호는 0부터 n−1입니다."+vals,fn,tree,lambda v,p,q:naive_tree(v,p,q,agg),dependencies=(paths,),stress=[([[1]*n,chain,[[1,i,n-1] for i in range(n)]],[1]*n if agg==max else list(range(n,0,-1)))])
    add("세그먼트 트리 Beats 기초","values:int[] operations:int[][]","int[]","연산 [0,l,r,x]는 구간의 각 값 a를 min(a,x)로 변경합니다. [1,l,r,0]은 현재 구간 합을 묻습니다.\n모든 합 질의의 답을 입력 순서대로 반환하세요.",lo+vals,ceiling,limits,naive_range,stress=[([list(range(n)),[[0,0,n-1,n//2],[1,0,n-1,0]]],[sum(min(i,n//2) for i in range(n))])])
    dyn=[[3,[[0,0,1],[2,0,2],[0,1,2],[2,0,2],[1,1,0],[2,0,2]]],[1,[[2,0,0]]]]
    pbs=[[4,[[1,2],[0,1]],[[0,2],[1,1],[2,3]]],[1,[],[]]]
    for k in range(15):
        m=r.randint(2,8)
        ops=[];active=set();cons=[];qs=[]
        for j in range(k+8):
            a,b=r.sample(range(m),2)
            edge=tuple(sorted((a,b)))
            if r.randrange(2): ops.append([2,a,b])
            elif edge in active: ops.append([1,a,b]);active.remove(edge)
            else: ops.append([0,a,b]);active.add(edge)
            cons.append([a,b]);qs.append(r.choices(range(m),k=2))
        dyn.append([m,ops]);pbs.append([m,cons,qs])
    graph_l="1≤n≤50,000. 정점 번호는 0부터 n−1입니다. 연산·간선·질의 배열 각각 최대 50,000개입니다."
    add("오프라인 동적 연결성","n:int operations:int[][]","int[]","처음에 간선이 없는 무방향 그래프입니다. [0,u,v]는 간선 추가, [1,u,v]는 기존 간선 삭제, [2,u,v]는 두 정점의 연결 여부 질의입니다. 연산은 배열 순서대로 적용됩니다.\n질의 시점에 연결되어 있으면 1, 아니면 0을 질의 순서대로 반환하세요.",graph_l+" 중복 추가, 없는 간선 삭제, 자기 루프 추가는 주어지지 않습니다.",timeline,dyn,timeline_naive,stress=[([n,[[0,i,i+1] for i in range(n-1)]+[[2,0,n-1],[1,0,1],[2,0,n-1]]],[1,0])])
    def when(n,edges,pairs):return [next((i for i in range(len(edges)+1) if connected(n,edges[:i],a,b)),-1) for a,b in pairs]
    add("병렬 이분 탐색","n:int edges:int[][] pairs:int[][]","int[]","비어 있는 무방향 그래프에 edges의 간선을 순서대로 하나씩 추가합니다. 각 [a,b]가 처음 연결되는 순간을 찾으세요.\n필요한 간선의 최소 개수를 질의 순서대로 반환하세요. a=b는 0, 끝까지 연결되지 않으면 −1입니다.",graph_l,earliest,pbs,when,stress=[([n,[[i,i+1] for i in range(n-1)],[[0,i] for i in range(n)]],list(range(n)))])
