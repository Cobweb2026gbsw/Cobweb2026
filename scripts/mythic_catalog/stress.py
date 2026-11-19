"""규모가 큰 비공개 입력. 경로, 절단 용량, 수열의 합 등으로 각 기대값을 직접 계산한다."""
from bisect import bisect_right


def at_scale(number):
    if 1396 <= number <= 1402:
        n = 50_000
        if number in (1396,1397):
            parents=[-1]+list(range(n-1))
            if number==1396:
                return [([parents,n-1,n//3],n//3)]
            return [([parents,[[i,n-1] for i in range(n)]],list(range(n)))]
        if number==1399:
            implications=[[-i,i+1] for i in range(1,n)]
            return [([n,[[1,1],[-n,-n]]+implications],False)]
        edges=[[i,i+1] for i in range(n-1)]
        if number==1398:
            return [([n,edges+[[n-1,0]]],[list(range(n))])]
        if number==1400:
            return [([n,edges],list(range(1,n-1)))]
        if number==1401:
            return [([n,edges],list(range(n-1)))]
        return [([n,edges,0],list(range(n)))]
    if number==1403:
        # 절반씩 나눈 두 층에 완전 이분 그래프를 넣고 잔여 용량이 작은 마지막 절단을 사용한다.
        width=39
        mid=[[i,j,5] for i in range(1,40) for j in range(40,79)]
        return [([80,[[0,i,100] for i in range(1,40)]+mid+[[j,79,17] for j in range(40,79)]],17*width)]
    if number==1404:
        width,levels=20,12
        sink=width*levels+1
        middle=[[1+h*width+i,1+(h+1)*width+j,1] for h in range(levels-1) for i in range(width) for j in range(width)]
        arcs=[[0,i+1,width] for i in range(width)]+middle+[[sink-i-1,sink,width] for i in range(width)]
        return [([sink+1,arcs],width**2)]
    if number in (1405,1406,1409):
        n=200 if number==1409 else 350
        degree=25 if number==1409 else 50
        if number==1409:
            offers=[[i,(i+d)%n,d] for i in range(n) for d in range(degree)]
            return [([[1]*n,n,offers],[n,0])]
        pairs=[[i,(i+d)%n] for i in range(n) for d in range(degree)]
        return [([n,n,pairs], n if number==1405 else [list(range(n)),[]])]
    if number==1407:
        n=22
        arcs=[[0,i+1,5,0] for i in range(n)]+[[i+1,n+j+1,5,abs(i-j)] for i in range(n) for j in range(n)]
        arcs += [[n+j+1,2*n+1,5,0] for j in range(n)]
        return [([2*n+2,arcs],[n*5,0])]
    if number==1408:
        n=150
        limits=[5_000]+[11]*(n-2)+[5_000]
        return [([limits,[[0,i,100] for i in range(1,n-1)]+[[i,n-1,100] for i in range(1,n-1)]],(n-2)*11)]
    if number==1410:
        needles=['a'*j for j in range(1,301)]+['a']*4700
        return [(['a'*16_000,needles],[16_001-len(word) for word in needles])]
    if number in (1411,1412):
        n=16_000
        answer=list(range(n-1,-1,-1)) if number==1411 else list(range(n))
        return [(['a'*n],answer)]
    if number==1413:
        n=8000
        return [(['a'*n+'b'*n],n*n+2*n)]
    if number==1414:
        return [(['ab'*8000,'c'+'ba'*7999+'z'],15998)]
    if number==1415:
        return [(['az'*8000],[[2*i,i] for i in range(2,8001)])]
    if number==1416:
        return [(['z'*15999+'a'],'a'+'z'*15999)]
    if number==1417:
        n=50_000
        # 모든 버전이 서로 다른 원소 하나만 바꾸며 원본에서 갈라진다.
        qs=[[0,0,i,i%1000] for i in range(n//2)]+[[1,i+1,0,n-1] for i in range(n//2)]
        return [([[1]*n,qs],[n-1+i%1000 for i in range(n//2)])]
    if number==1418:
        n=50_000
        # 모든 값이 다르므로 구간 길이가 답. 구간 길이와 양 끝을 계속 변화시킨다.
        pairs=[[i%23021,n-1-(i*97%23993)] for i in range(n)]
        return [( [list(range(n)),pairs],[b-a+1 for a,b in pairs])]
    if number in (1419,1420):
        n=40_000
        parents=[-1]+list(range(n-1))
        intervals=[[1,i%6000,n-1-i%4907] for i in range(n)]
        # 쉬운 산술로 검산하되 모든 질의를 트리의 긴 경로로 만든다.
        return [([[1]*n,parents,intervals], [1]*n if number==1419 else [b-a+1 for _,a,b in intervals])]
    if number==1421:
        n=50_000
        original=[i%10001-5000 for i in range(n)]
        ordered=sorted(original)
        cumulative=[0]
        for x in ordered: cumulative.append(cumulative[-1]+x)
        ops=[];answers=[]
        for cap in range(5000,-5001,-1):
            ops.extend([[0,0,n-1,cap],[1,0,n-1,0]])
            below=bisect_right(ordered,cap)
            answers.append(cumulative[below]+(n-below)*cap)
        return [([original,ops],answers)]
    if number==1422:
        n=10_000
        ops=[[0,i,i+1] for i in range(n-1)]
        for i in range(n-1):
            ops.extend([[2,0,n-1],[1,i,i+1],[2,0,n-1],[0,i,i+1]])
        return [([n,ops],[1,0]*(n-1))]
    if number==1423:
        n=50_000
        return [([n,[[i,i+1] for i in range(n-1)],[[0,i] for i in range(n)]],list(range(n)))]
    if number==1424:
        # 9와 8을 번갈아 쓰면 자릿수 합은 153이며, 51의 배수인 수 하나만 범위에 든다.
        s='98'*9
        return [([s,s,51],'1')]
    if number==1425:
        return [([[1]*100_000,0],'100000')]
    if number==1426:
        return [([[50]*12000,60],str(60*10000**2))]
    if number==1427:
        # 같은 무게의 최적 인접 병합 트리에서 잎 깊이는 9 또는 10이다.
        return [([[1_000_000]*800],str((11*800-1024)*1_000_000))]
    if number==1428:
        primes=[101,103,107,109,113,127,131,137,139,149,151,157,163,167,173,179,181,191]
        n=2_000_000_000
        expected=0
        for mask in range(1,1<<len(primes)):
            product=1
            for i,p in enumerate(primes):
                if mask>>i & 1:
                    product*=p
                    if product>n: break
            expected+=(1 if mask.bit_count()%2 else -1)*(n//product)
        return [([n,primes],expected)]
    if number==1429:
        mods=[999_999_997,1_000_000_000]*25
        return [([[-1]*50,mods],[str(mods[0]*mods[1]-1),str(mods[0]*mods[1])])]
    if number==1430:
        n=65_536
        return [([[1]*n,[1]*n],list(range(1,n+1))+list(range(n-1,0,-1)))]
    raise ValueError(number)
