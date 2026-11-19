"""998244353 위의 다항식과 부분집합 합성곱. 작은 답은 계수 정의로 판정한다."""
import random

P = 998244353


def multiply(a,b):
    prime = 998244353
    needed = len(a)+len(b)-1
    if min(len(a),len(b)) <= 12:
        out = [0]*needed
        for i,x in enumerate(a):
            for j,y in enumerate(b):
                out[i+j] = (out[i+j]+x*y) % prime
        return out
    span = 1 << (needed-1).bit_length()
    def transform(values, inverse):
        j = 0
        for i in range(1,span):
            bit = span//2
            while j & bit:
                j ^= bit
                bit //= 2
            j ^= bit
            if j > i:
                values[i],values[j] = values[j],values[i]
        width = 2
        while width <= span:
            unit = pow(3,(prime-1)//width,prime)
            if inverse:
                unit = pow(unit,prime-2,prime)
            for lo in range(0,span,width):
                w = 1
                for i in range(lo,lo+width//2):
                    x,y = values[i],values[i+width//2]*w%prime
                    values[i],values[i+width//2] = (x+y)%prime,(x-y)%prime
                    w = w*unit%prime
            width *= 2
        if inverse:
            scale = pow(span,prime-2,prime)
            for i in range(span):
                values[i] = values[i]*scale%prime
    left = a + [0]*(span-len(a))
    right = b + [0]*(span-len(b))
    transform(left,False)
    transform(right,False)
    out = [x*y%prime for x,y in zip(left,right)]
    transform(out,True)
    return out[:needed]


def series_inverse(coeffs,n):
    prime = 998244353
    inv = [pow(coeffs[0],prime-2,prime)]
    while len(inv) < n:
        size = min(n,len(inv)*2)
        product = multiply(coeffs[:size],inv)[:size]
        correction = [(-x)%prime for x in product]
        correction[0] = (correction[0]+2)%prime
        inv = multiply(inv,correction)[:size]
        inv += [0]*(size-len(inv))
    return inv


def quotient(a,b):
    prime = 998244353
    def reduced(co):
        while len(co)>1 and co[-1]==0:
            co.pop()
        return co
    if len(a)<len(b):
        return [[0],a[:]]
    n = len(a)-len(b)+1
    high = multiply(a[::-1][:n],series_inverse(b[::-1],n))[:n]
    q = high[::-1]
    qb = multiply(q,b)
    r = [(x-y)%prime for x,y in zip(a,qb)]
    return [reduced(q),reduced(r)]


def inverse_answer(coeffs,n):
    return series_inverse(coeffs,n)


def exponential(coeffs,n):
    prime = 998244353
    invnum = [0,1]
    for i in range(2,n):
        invnum.append(-(prime//i)*invnum[prime%i]%prime)
    g = [1]
    while len(g)<n:
        size = min(n,len(g)*2)
        # log g의 도함수는 g'/g이므로 적분한 뒤 exp의 Newton 보정을 적용한다.
        deriv = [i*g[i]%prime for i in range(1,len(g))] or [0]
        dlog = multiply(deriv,series_inverse(g,size))
        correction = [1]
        for i in range(1,size):
            a = coeffs[i] if i < len(coeffs) else 0
            correction.append((a-dlog[i-1]*invnum[i])%prime)
        g = multiply(g,correction)[:size]
    return g


def recurrence(initial,coeffs,n):
    prime = 998244353
    q = [1]+[(-c)%prime for c in coeffs]
    p = multiply(initial,q)[:len(initial)]
    while n:
        qminus = [v if i%2==0 else (-v)%prime for i,v in enumerate(q)]
        p = multiply(p,qminus)[n%2::2]
        q = multiply(q,qminus)[::2]
        n //= 2
    return p[0]*pow(q[0],prime-2,prime)%prime


def subset_product(a,b):
    prime = 998244353
    length = len(a)
    k = length.bit_length()-1
    def ranked(values):
        table = [[0]*length for _ in range(k+1)]
        for mask,value in enumerate(values):
            table[mask.bit_count()][mask] = value
        for row in table:
            for bit in range(k):
                step = 1<<bit
                for lo in range(step,length,2*step):
                    for mask in range(lo,lo+step):
                        row[mask] = (row[mask]+row[mask-step])%prime
        return table
    f,g = ranked(a),ranked(b)
    answer = [0]*length
    for degree in range(k+1):
        h = [0]*length
        for i in range(degree+1):
            for mask,(x,y) in enumerate(zip(f[i],g[degree-i])):
                h[mask] = (h[mask]+x*y)%prime
        for bit in range(k):
            step = 1<<bit
            for lo in range(step,length,step*2):
                for mask in range(lo,lo+step):
                    h[mask] = (h[mask]-h[mask-step])%prime
        for mask,x in enumerate(h):
            if mask.bit_count()==degree:
                answer[mask] = x
    return answer


def divide_long(a,b):
    r = a[:]
    q = [0]*max(1,len(a)-len(b)+1)
    inv = pow(b[-1],P-2,P)
    for i in range(len(a)-len(b),-1,-1):
        q[i] = r[i+len(b)-1]*inv%P
        for j,y in enumerate(b):
            r[i+j] = (r[i+j]-q[i]*y)%P
    for vals in (r,q):
        while len(vals)>1 and not vals[-1]:
            vals.pop()
    return [q,r]


def coeff_inverse(c,n):
    r = [pow(c[0],P-2,P)]
    for i in range(1,n):
        r.append(-sum(c[j]*r[i-j] for j in range(1,min(i+1,len(c))))*r[0]%P)
    return r


def coeff_exp(c,n):
    # (exp f)' = f' exp f
    g = [1]
    for i in range(1,n):
        g.append(sum(j*c[j]*g[i-j] for j in range(1,min(i+1,len(c))))*pow(i,P-2,P)%P)
    return g


def rec_direct(initial,coeffs,n):
    seq=initial[:]
    while len(seq)<=n:
        seq.append(sum(v*seq[-i-1] for i,v in enumerate(coeffs))%P)
    return seq[n]


def sub_direct(a,b):
    out = []
    for mask in range(len(a)):
        term,sub = 0,mask
        while True:
            term += a[sub]*b[mask^sub]
            if not sub:
                break
            sub = (sub-1)&mask
        out.append(term%P)
    return out


def populate(add):
    rng = random.Random(1441)
    divisions=[[[1,2,1],[1,1]],[[0],[3,1]]]
    inverses=[[[1,P-1],5],[[3],1]]
    exps=[[[0,1],4],[[0],5]]
    recs=[[[0,1],[1,1],10],[[3],[2],0]]
    subsets=[[[1,2,3,4],[1,1,1,1]],[[2],[7]]]
    for _ in range(16):
        a=[rng.randrange(P) for _ in range(rng.randint(1,80))]
        b=[rng.randrange(1,P) for _ in range(rng.randint(1,48))]
        n=rng.randint(15,90)
        divisions.append([a,b]); inverses.append([b,n]); exps.append([[0]+a,n])
        recs.append([[rng.randrange(P) for _ in b],b,rng.randint(0,140)])
        size=1<<rng.randrange(1,7)
        subsets.append([[rng.randrange(P) for _ in range(size)],[rng.randrange(P) for _ in range(size)]])
    common="계수는 0 이상 998244353 미만의 정수이며 낮은 차수부터 저장합니다. 모든 연산은 소수 P=998244353을 법으로 합니다."
    m=2048
    # a_n=3^n을 만족시키는 밀집 점화식. c_2..c_k를 먼저 정하고
    # 1=sum c_i*3^(-i)에서 c_1을 구하므로 큰 항도 pow로 독립 검산된다.
    dense=[0]+[rng.randrange(P) for _ in range(m-1)]
    inverse_three=pow(3,P-2,P)
    dense[0]=3*(1-sum(c*pow(inverse_three,i,P) for i,c in enumerate(dense[1:],2)))%P
    side=8192
    triangle=list(range(1,side+1))+[side]+list(range(side-1,0,-1))
    add("다항식 나눗셈","NTT","a:int[] b:int[]","int[][]",
        "A=BQ+R이고 R=0 또는 deg R<deg B를 만족하는 [Q,R]의 계수 배열을 구하세요. 각 반환 배열 끝의 불필요한 0을 제거하고 영다항식은 [0]으로 나타냅니다.",
        common+" 1 ≤ 각 배열 길이 ≤ 16,384. 입력에도 불필요한 끝의 0은 없으며 B≠0입니다.","O(N log N), N=max(|a|,|b|)",quotient,divide_long,divisions,
        [([[P-1]+[0]*(2*m-1)+[1],[P-1]+[0]*(m-1)+[1]],[[1]+[0]*(m-1)+[1],[0]]),
         ([triangle,[1]*side],[[1]*(side+1),[0]])],(multiply,series_inverse))
    add("다항식 역원","Formal Power Series","coeffs:int[] n:int","int[]",
        "형식적 멱급수 A와 정밀도 n이 주어집니다. A·B ≡ 1 (mod xⁿ)을 만족하는 B의 0차부터 n−1차까지의 계수를 정확히 n개 반환하세요. 입력에서 생략된 계수는 0입니다.",
        common+" 1 ≤ n, 계수 배열 길이 ≤ 16,384. 상수항은 0이 아닙니다.","O(n log n)",inverse_answer,coeff_inverse,inverses,
        [([[1,P-1],m],[1]*m),([[pow(3,i,P) for i in range(16384)],16384],[1,P-3]+[0]*16382)],(multiply,series_inverse))
    add("다항식 지수 함수","FPS","coeffs:int[] n:int","int[]",
        "상수항이 0인 A에 대해 exp(A)=Σ Aʲ/j!을 xⁿ 미만에서 계산하세요. 0차부터 n−1차까지의 계수를 정확히 n개 반환합니다. 생략된 입력 계수는 0입니다.",
        common+" 1 ≤ n, 계수 배열 길이 ≤ 16,384, coeffs[0]=0.","O(n log n)",exponential,coeff_exp,exps,
        [([[0,1],m],coeff_exp([0,1],m)),
         ([[0]+[pow(i,P-2,P) for i in range(1,16384)],16384],[1]*16384)],(multiply,series_inverse))
    add("고속 선형 점화식","Bostan-Mori","initial:int[] coeffs:int[] n:int","int",
        "initial=[a₀,…,aₖ₋₁], coeffs=[c₁,…,cₖ]가 주어집니다. i≥k이면 aᵢ=Σ(cⱼ·aᵢ₋ⱼ), 1≤j≤k입니다. aₙ을 998244353으로 나눈 나머지를 반환하세요.",
        "1 ≤ k ≤ 2,048, 0 ≤ n ≤ 2,000,000,000. 두 배열의 길이는 k이고 원소는 0 이상 998244353 미만입니다.","O(k log(2k) log(n+2))",recurrence,rec_direct,recs,
        [([list(range(m)),[0]*(m-1)+[1],2_000_000_000],2_000_000_000%m),
         ([[pow(3,i,P) for i in range(m)],dense,2_000_000_000],pow(3,2_000_000_000,P))],(multiply,))
    add("부분집합 합성곱","Subset Convolution","a:int[] b:int[]","int[]",
        "길이가 2ᵏ인 배열 a,b는 k개 원소의 각 부분집합을 비트마스크로 인덱싱합니다. 각 S에 대해 Σ a[T]·b[S∖T] (T⊆S)를 998244353으로 나눈 배열을 마스크 증가 순서로 반환하세요.",
        "0 ≤ k ≤ 15. 모든 원소는 0 이상 998244353 미만입니다.","O((k+1)²·2ᵏ)",subset_product,sub_direct,subsets,
        [([[1]*(1<<15),[1]*(1<<15)],[1<<i.bit_count() for i in range(1<<15)])])
