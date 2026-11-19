"""자릿수 DP, 최적화된 점화식, 정수론과 다항식 합성곱."""
import itertools
import math
import random


def count_digits(lower, upper, divisor):
    from functools import lru_cache
    def count(value):
        if value<=0:
            return 0
        digits=[int(c) for c in str(value)]
        @lru_cache(None)
        def visit(i,prev,remainder,tight):
            if i==len(digits):
                return int(prev!=10 and remainder==0)
            stop=digits[i] if tight else 9
            answer=0
            for d in range(stop+1):
                if d==prev:
                    continue
                nxt=10 if prev==10 and d==0 else d
                answer+=visit(i+1,nxt,(remainder+d)%divisor,tight and d==stop)
            return answer
        return visit(0,10,0,True)
    return str(count(int(upper))-count(int(lower)-1))


def line_dp(values, penalty):
    from collections import deque
    # j번째 후보 직선의 기울기는 -2 prefix[j], 절편은 dp[j]+prefix[j]^2입니다.
    hull=deque([(0,0)])
    x=cost=0
    def at(line,x):
        return line[0]*x+line[1]
    for value in values:
        x+=value
        while len(hull)>1 and at(hull[1],x)<=at(hull[0],x):
            hull.popleft()
        cost=x*x+penalty+at(hull[0],x)
        m,b=-2*x,cost+x*x
        while len(hull)>1:
            p,q=hull[-2],hull[-1]
            if (q[1]-p[1])*(q[0]-m)<(b-q[1])*(p[0]-q[0]):
                break
            hull.pop()
        hull.append((m,b))
    return str(cost)


def partition(values, groups):
    n=len(values)
    prefix=[0]
    for x in values:
        prefix.append(prefix[-1]+x)
    inf=2**62
    prev=[0]+[inf]*n
    for g in range(1,groups+1):
        cur=[inf]*(n+1)
        def fill(l,r,lo,hi):
            if l>r:
                return
            mid=(l+r)//2
            best=lo
            for j in range(lo,min(hi,mid-1)+1):
                price=prev[j]+(prefix[mid]-prefix[j])**2
                if price<cur[mid]:
                    cur[mid]=price
                    best=j
            fill(l,mid-1,lo,best)
            fill(mid+1,r,best,hi)
        fill(g,n,g-1,n-1)
        prev=cur
    return str(prev[n])


def merge_stones(weights):
    n=len(weights)
    sums=[0]
    for v in weights:
        sums.append(sums[-1]+v)
    dp=[[0]*n for _ in range(n)]
    opt=[[i]*n for i in range(n)]
    for length in range(2,n+1):
        for l in range(n-length+1):
            r=l+length-1
            cost=2**62
            for k in range(opt[l][r-1],min(r-1,opt[l+1][r])+1):
                price=dp[l][k]+dp[k+1][r]
                if price<cost:
                    cost=price
                    opt[l][r]=k
            dp[l][r]=cost+sums[r+1]-sums[l]
    return str(dp[0][-1])


def divisible(limit, divisors):
    from math import gcd
    unique=[]
    for d in sorted(set(divisors)):
        if d<=limit and all(d%x for x in unique):
            unique.append(d)
    def count(start,period):
        result=0
        for j in range(start,len(unique)):
            d=unique[j]
            # 먼저 나누어 불필요하게 큰 최소공배수의 곱을 피합니다.
            factor=d//gcd(period,d)
            if period<=limit//factor:
                new=period*factor
                result+=limit//new-count(j+1,new)
        return result
    return count(0,1)


def congruences(remainders, moduli):
    from math import gcd
    value,period=0,1
    for a,m in zip(remainders,moduli):
        g=gcd(period,m)
        if (a-value)%g:
            return []
        modulus=m//g
        factor=0
        if modulus>1:
            reduced=((a-value)//g)%modulus
            factor=reduced*pow((period//g)%modulus,-1,modulus)%modulus
        value+=period*factor
        period*=modulus
    return [str(value),str(period)]


def convolution(first, second):
    prime=998244353
    if not first or not second:
        return []
    size=1
    while size<len(first)+len(second)-1:
        size*=2
    def transform(a,inverse):
        j=0
        for i in range(1,size):
            bit=size//2
            while j&bit:
                j^=bit
                bit//=2
            j^=bit
            if i<j:
                a[i],a[j]=a[j],a[i]
        width=2
        while width<=size:
            root=pow(3,(prime-1)//width,prime)
            if inverse:
                root=pow(root,prime-2,prime)
            half=width//2
            for start in range(0,size,width):
                w=1
                for i in range(start,start+half):
                    u,v=a[i],a[i+half]*w%prime
                    a[i]=(u+v)%prime
                    a[i+half]=(u-v)%prime
                    w=w*root%prime
            width*=2
        if inverse:
            normalizer=pow(size,prime-2,prime)
            for i in range(size):
                a[i]=a[i]*normalizer%prime
    a=first+[0]*(size-len(first))
    b=second+[0]*(size-len(second))
    transform(a,False)
    transform(b,False)
    c=[x*y%prime for x,y in zip(a,b)]
    transform(c,True)
    return c[:len(first)+len(second)-1]


def slow_partition(v,k):
    best=None
    for cuts in itertools.combinations(range(1,len(v)),k-1):
        score=sum(sum(v[l:r])**2 for l,r in zip((0,)+cuts,cuts+(len(v),)))
        best=score if best is None else min(best,score)
    return best


def populate(add):
    r=random.Random(1424)
    digit=[['1','20',2],['0','0',1],['99','111',1],['10','10',1]]
    for k in range(17):
        start=r.randrange(3000)
        digit.append([str(start),str(start+r.randint(2,600)),r.randrange(1,16)])
    def digit_oracle(a,b,d):
        return str(sum(x>0 and all(i!=j for i,j in zip(str(x),str(x)[1:])) and sum(map(int,str(x)))%d==0 for x in range(int(a),int(b)+1)))
    add("Digit DP","lower:string upper:string divisor:int","string","양 끝을 포함하는 [lower,upper]에서 인접한 두 십진 숫자가 항상 다르고 자릿수의 합이 divisor의 배수인 양의 정수를 셉니다. 선행 0은 자릿수에 포함하지 않습니다.\n조건을 만족하는 개수를 선행 0 없는 십진 문자열로 반환하세요. 없으면 \"0\"입니다.","lower와 upper는 0~999999999999999999를 나타내는 표준 십진 문자열이며 lower≤upper, 1≤divisor≤100입니다.",count_digits,digit,digit_oracle,stress=[(['0','9'*18,1],str(sum(9**i for i in range(1,19))))])
    lines=[[[3,1,3],5],[[7],0],[[2,2,2],8]]
    divs=[[[1,2,3,4],2],[[5],1],[[0,0,0],2]]
    stones=[[[40,30,30,50]],[[0]],[[1,2]]]
    for n in range(2,17):
        v=[r.randint(1,30) for _ in range(n)]
        lines.append([v,r.randint(0,100)])
        d=[r.randrange(12) for _ in range(min(n,12))]
        divs.append([d,r.randint(1,len(d))])
        stones.append([d])
    def slow_line(v,p):
        dp=[0]
        for i in range(1,len(v)+1):
            dp.append(min(dp[j]+sum(v[j:i])**2+p for j in range(i)))
        return str(dp[-1])
    add("Convex Hull Trick DP","values:int[] penalty:int","string","양의 정수 배열을 하나 이상의 비어 있지 않은 연속 구간으로 나누세요. 각 구간 비용은 (구간 원소 합)² + penalty입니다. 접두사 합의 증가 순서를 활용할 수 있습니다.\n전체 비용의 최솟값을 선행 0 없는 십진 문자열로 반환하세요.","1≤길이≤100,000, 1≤원소≤10,000, 모든 원소의 합≤1,000,000, 0≤penalty≤1,000,000,000. 직선 교차 비교의 곱에는 충분한 정밀도를 사용하세요.",line_dp,lines,slow_line,stress=[([[5000]*200,1000000000],slow_line([5000]*200,1000000000)),([[1]*3500,0],'3500')])
    add("분할 정복 DP 최적화","values:int[] groups:int","string","음이 아닌 정수 배열을 정확히 groups개의 비어 있지 않은 연속 구간으로 나누세요. 구간 하나의 비용은 그 구간 합의 제곱입니다. 분할점 최적 인덱스가 단조라는 성질을 활용할 수 있습니다.\n가능한 최소 비용을 십진 문자열로 반환하세요.","1≤n≤12,000, 1≤groups≤min(n,80). 각 원소는 0~10,000, 전체 합은 1,000,000 이하입니다.",partition,divs,lambda v,k:str(slow_partition(v,k)),stress=[([[1000]*1000,10],'100000000000')])
    def brute_stones(v):
        from functools import lru_cache
        @lru_cache(None)
        def best(l,h):
            if h-l==1:return 0
            return sum(v[l:h])+min(best(l,k)+best(k,h) for k in range(l+1,h))
        return str(best(0,len(v)))
    add("Knuth Optimization","weights:int[]","string","왼쪽부터 놓인 파일 중 서로 이웃한 두 묶음만 합칠 수 있습니다. 두 묶음을 합치는 비용은 두 무게의 합이고, 새 묶음의 무게도 그 합입니다.\n모든 파일을 한 묶음으로 만드는 최소 총비용을 십진 문자열로 반환하세요. 파일이 하나면 \"0\"입니다.","1≤파일 수≤800. 각 무게는 0~1,000,000입니다.",merge_stones,stones,brute_stones,stress=[([[1000000]*512],str(512*9*1000000))])
    inc=[[20,[2,3]],[0,[1]],[100,[4,2,2]],[99,[]]]
    crt=[[[2,3],[3,5]],[[0,1],[2,4]],[[-1],[5]],[[],[]],[[16,4],[1,3]]]
    poly=[[[1,2],[3,4]],[[],[1]],[[998244352],[998244352]]]
    for k in range(17):
        inc.append([r.randint(0,2000),[r.randint(1,25) for _ in range(k)]])
        mods=[r.randint(1,12) for _ in range(k%5+1)]
        crt.append([[r.randint(-30,30) for _ in mods],mods])
        poly.append([[r.randrange(998244353) for _ in range(k+1)],[r.randrange(998244353) for _ in range(k%5+1)]])
    add("포함-배제 원리","limit:int divisors:int[]","int","1부터 limit까지의 정수 중 divisors에 있는 하나 이상의 수로 나누어떨어지는 수를 셉니다. 중복된 약수로 여러 번 나누어져도 한 번만 셉니다.\n조건을 만족하는 개수를 반환하세요. 빈 범위나 빈 약수 목록의 결과는 0입니다.","0≤limit≤2,000,000,000, 약수의 개수는 0~18, 각 약수는 1~2,000,000,000입니다.",divisible,inc,lambda n,ds:sum(any(i%d==0 for d in ds) for i in range(1,n+1)),stress=[([2000000000,[2,3,6,1999999999]],1333333334)])
    def brute_crt(a,m):
        period=math.lcm(*m)
        return next(([str(x),str(period)] for x in range(period) if all((x-i)%j==0 for i,j in zip(a,m))),[])
    add("중국인의 나머지 정리","remainders:int[] moduli:int[]","string[]","모든 i에 대해 x≡remainders[i] (mod moduli[i])를 만족하는 정수 x를 찾으세요. 법은 서로소가 아닐 수 있습니다.\n해가 있으면 [가장 작은 음이 아닌 해, 양의 최소 주기]를 십진 문자열 두 개로 반환하세요. 해가 없으면 빈 배열이고, 식이 없으면 [\"0\",\"1\"]입니다.","두 배열의 길이는 같고 0~50입니다. 1≤법≤1,000,000,000, 나머지는 부호 있는 32비트 정수이며 모든 법의 최소공배수는 2⁶² 이하입니다. 중간 곱은 먼저 법으로 축소하면 부호 있는 64비트 안에서 계산할 수 있습니다.",congruences,crt,brute_crt,stress=[([[-1,-1],[1000000007-10,1000000000]],[str(math.lcm(999999997,1000000000)-1),str(math.lcm(999999997,1000000000))])])
    def naive_poly(a,b):
        if not a or not b: return []
        c=[0]*(len(a)+len(b)-1)
        for i,x in enumerate(a):
            for j,y in enumerate(b): c[i+j]=(c[i+j]+x*y)%998244353
        return c
    add("NTT 다항식 곱셈","first:int[] second:int[]","int[]","두 다항식의 계수는 상수항부터 차수 오름차순입니다. 두 다항식을 곱하고 각 계수를 998244353으로 나눈 나머지를 구하세요.\n낮은 차수부터 길이 len(first)+len(second)−1의 계수 배열을 반환하세요. 뒤쪽 0도 유지하며 입력 중 하나가 빈 배열이면 빈 배열입니다.","각 계수는 0~998244352, 각 배열 길이는 0~65,536입니다. 998244353의 원시근으로 3을 사용할 수 있습니다.",convolution,poly,naive_poly,stress=[([[1]*2048,[1]*2048],list(range(1,2049))+list(range(2047,0,-1)))])
