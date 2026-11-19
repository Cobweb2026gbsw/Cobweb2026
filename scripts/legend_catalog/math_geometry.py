"""정수 산술과 기하. 모든 입출력은 32비트 정수 ABI에서 정확히 표현된다."""

import itertools
import math
import random


def power(value, exponent, modulus):
    answer = 1 % modulus
    value %= modulus
    while exponent:
        if exponent & 1:
            answer = answer * value % modulus
        value = value * value % modulus
        exponent //= 2
    return answer


def bezout(a, b):
    old_r, r = a, b
    old_x, x = 1, 0
    old_y, y = 0, 1
    while r:
        q = old_r // r
        old_r, r = r, old_r - q * r
        old_x, x = x, old_x - q * x
        old_y, y = y, old_y - q * y
    return old_r, old_x, old_y


def inverse(value, modulus):
    g, x, _ = bezout(value, modulus)
    return x % modulus if g == 1 else -1


def extended(a, b):
    g, x, _ = bezout(a, b)
    x %= b // g
    return [g, x, (g - a * x) // b]


def binomial(n, k):
    if k > n:
        return 0
    prime = 1000000007
    k = min(k, n - k)
    numerator = denominator = 1
    for i in range(1, k + 1):
        numerator = numerator * (n - i + 1) % prime
        denominator = denominator * i % prime
    return numerator * pow(denominator, prime - 2, prime) % prime


def matrix_power(matrix, exponent):
    n = len(matrix)
    prime = 1000000007

    def multiply(a, b):
        product = [[0] * n for _ in range(n)]
        for i in range(n):
            for k in range(n):
                for j in range(n):
                    product[i][j] = (product[i][j] + a[i][k] * b[k][j]) % prime
        return product

    result = [[int(i == j) for j in range(n)] for i in range(n)]
    while exponent:
        if exponent & 1:
            result = multiply(result, matrix)
        matrix = multiply(matrix, matrix)
        exponent //= 2
    return result


def fibonacci(n):
    prime = 1000000007
    a, b = 0, 1
    for digit in bin(n)[2:]:
        even = a * (2 * b - a) % prime
        odd = (a * a + b * b) % prime
        if digit == "0":
            a, b = even, odd
        else:
            a, b = odd, (even + odd) % prime
    return a


def totient(n):
    ans = n
    divisor = 2
    while divisor * divisor <= n:
        if n % divisor == 0:
            ans -= ans // divisor
            while n % divisor == 0:
                n //= divisor
        divisor += 1
    if n > 1:
        ans -= ans // n
    return ans


def cross(a, b, c):
    return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])


def intersect(a, b, c, d):
    def sign(x):
        return int(x > 0) - int(x < 0)

    sides1 = sign(cross(a, b, c)) * sign(cross(a, b, d))
    sides2 = sign(cross(c, d, a)) * sign(cross(c, d, b))
    if sides1 > 0 or sides2 > 0:
        return False
    return all(max(min(a[i], b[i]), min(c[i], d[i])) <= min(max(a[i], b[i]), max(c[i], d[i])) for i in (0, 1))


def hull(points):
    ordered = sorted(set(map(tuple, points)))
    if len(ordered) < 2:
        return [list(p) for p in ordered]

    def chain(vertices):
        boundary = []
        for point in vertices:
            while len(boundary) >= 2 and cross(boundary[-2], boundary[-1], point) <= 0:
                boundary.pop()
            boundary.append(point)
        return boundary

    lower = chain(ordered)
    upper = chain(reversed(ordered))
    return [list(p) for p in lower[:-1] + upper[:-1]]


def nearest(points):
    from heapq import merge

    if len(points) < 2:
        return -1

    def squared(a, b):
        return (a[0] - b[0]) ** 2 + (a[1] - b[1]) ** 2

    def solve(by_x):
        n = len(by_x)
        if n <= 3:
            dist = min((squared(by_x[i], by_x[j]) for i in range(n) for j in range(i)), default=float("inf"))
            return dist, sorted(by_x, key=lambda p: p[1])
        mid = n // 2
        split_x = by_x[mid][0]
        left, a = solve(by_x[:mid])
        right, b = solve(by_x[mid:])
        best = min(left, right)
        by_y = list(merge(a, b, key=lambda p: p[1]))
        strip = [p for p in by_y if (p[0] - split_x) ** 2 < best]
        for i, p in enumerate(strip):
            j = i + 1
            while j < len(strip) and (strip[j][1] - p[1]) ** 2 < best:
                best = min(best, squared(p, strip[j]))
                j += 1
        return best, by_y

    return solve(sorted(points))[0]


def mat_oracle(a, k):
    n = len(a)
    result = [[int(i == j) for j in range(n)] for i in range(n)]
    for _ in range(k):
        result = [
            [sum(result[i][t] * a[t][j] for t in range(n)) % 1000000007 for j in range(n)] for i in range(n)
        ]
    return result


def fib_oracle(n):
    # 2×2 행렬의 네 항으로 F(2^k)를 분할 정복한다.
    def mult(a, b):
        p, q, r, s = a
        t, u, v, w = b
        return tuple(x % 1000000007 for x in (p * t + q * v, p * u + q * w, r * t + s * v, r * u + s * w))

    current = (1, 0, 0, 1)
    power = (1, 1, 1, 0)
    while n:
        if n & 1:
            current = mult(current, power)
        power = mult(power, power)
        n //= 2
    return current[1]


def segment_oracle(a, b, c, d):
    # 독립 검산에서는 교점의 선분 매개변수를 유리수로 직접 계산한다.
    from fractions import Fraction

    u, v = (b[0] - a[0], b[1] - a[1]), (d[0] - c[0], d[1] - c[1])
    determinant = u[0] * v[1] - u[1] * v[0]
    w = (c[0] - a[0], c[1] - a[1])
    if determinant:
        s = Fraction(w[0] * v[1] - w[1] * v[0], determinant)
        t = Fraction(w[0] * u[1] - w[1] * u[0], determinant)
        return 0 <= s <= 1 and 0 <= t <= 1

    def on(p, x, y):
        return cross(x, y, p) == 0 and all(min(x[i], y[i]) <= p[i] <= max(x[i], y[i]) for i in (0, 1))

    return on(a, c, d) or on(b, c, d) or on(c, a, b) or on(d, a, b)


def jarvis(points):
    pts = sorted(set(map(tuple, points)))
    if len(pts) < 2:
        return [list(p) for p in pts]
    p = pts[0]
    output = []
    while not output or p != pts[0]:
        output.append(list(p))
        q = next(v for v in pts if v != p)
        for r in pts:
            area = cross(p, q, r)
            if area < 0 or (area == 0 and math.dist(p, r) > math.dist(p, q)):
                q = r
        p = q
    return output


def populate(add):
    rng = random.Random(1386)
    powers = [
        [3, 5, 7],
        [99, 0, 1],
        [0, 0, 9],
        [-2, 3, 11],
        [-2147483648, 3, 2147483647],
        [2147483647, 2147483647, 2147483629],
    ] + [[rng.randint(-200, 200), rng.randrange(10000), rng.randint(1, 200)] for _ in range(10)]
    add(
        "빠른 거듭제곱",
        power,
        "int int int",
        "int",
        "value의 exponent제곱을 modulus로 나눈 나머지를 0 이상 modulus 미만으로 반환하세요. 지수가 0이면 밑이 0인 경우에도 거듭제곱은 1입니다.",
        "value는 32비트 정수, 0 ≤ exponent ≤ 2147483647, 1 ≤ modulus ≤ 2147483647.",
        "O(log(exponent+2))",
        powers,
        ("3의 5제곱 243을 7로 나눈 나머지는 5입니다.", "지수 0의 결과 1을 1로 나누면 나머지는 0입니다."),
        oracle=pow,
    )
    inverses = [[3, 7], [6, 9], [0, 11], [1, 2], [2147483646, 2147483647]] + [
        [i, 3 + i % 8] for i in range(2, 17)
    ]
    add(
        "모듈러 역원",
        inverse,
        "int int",
        "int",
        "value×x를 modulus로 나눈 나머지가 1이 되도록 하는 0 ≤ x < modulus를 반환하세요. 역원이 존재하지 않으면 −1입니다.",
        "0 ≤ value ≤ 2147483647, 2 ≤ modulus ≤ 2147483647.",
        "O(log(max(value,modulus)+2))",
        inverses,
        ("3×5=15의 나머지가 1이므로 역원은 5입니다.", "6과 9의 최대공약수는 3이므로 역원이 없습니다."),
        oracle=lambda a, m: pow(a, -1, m) if math.gcd(a, m) == 1 else -1,
        helpers=(bezout,),
    )
    eg = [[30, 18], [5, 10], [1, 1], [13, 1], [2, 13]] + [
        [rng.randint(1, 80), rng.randint(1, 80)] for _ in range(12)
    ]

    def check_eg(a, b):
        g = math.gcd(a, b)
        x = next(x for x in range(b // g) if (g - a * x) % b == 0)
        return [g, x, (g - a * x) // b]

    add(
        "확장 유클리드 알고리즘",
        extended,
        "int int",
        "int[]",
        "g=gcd(a,b)이고 ax+by=g를 만족하는 [g,x,y]를 반환하세요. 유일한 답을 위해 0 ≤ x < b/g가 되도록 x를 고릅니다. y는 음수일 수 있습니다.",
        "1 ≤ a,b ≤ 1000000000.",
        "O(log(max(a,b)+2))",
        eg,
        ("30×2+18×(−3)=6이며 0 ≤ 2 < 3입니다.", "5×1+10×0=5입니다."),
        oracle=check_eg,
        helpers=(bezout,),
        stress=[([1000000000, 999999999], [1, 1, -1])],
    )
    combos = [[5, 2], [2, 3], [0, 0], [100, 0], [10, 10], [100, 50]] + [[i * 4, i // 2] for i in range(2, 13)]
    add(
        "조합의 나머지",
        binomial,
        "int int",
        "int",
        "서로 다른 n개에서 순서 없이 k개를 고르는 조합 수를 소수 1000000007로 나눈 나머지를 반환하세요. k가 n보다 크면 0입니다.",
        "0 ≤ n,k ≤ 1000000.",
        "O(min(k,n−k)+log 1000000007), k>n이면 O(1)",
        combos,
        ("5개 중 2개를 고르는 방법은 10가지입니다.", "2개에서 서로 다른 3개를 선택할 수 없습니다."),
        oracle=lambda n, k: math.comb(n, k) % 1000000007,
        stress=[([1000000, 2], math.comb(1000000, 2) % 1000000007)],
    )
    mats = [[[[1, 1], [1, 0]], 3], [[[2, 3], [4, 5]], 0], [[[0]], 99]]
    for i in range(12):
        n = i % 3 + 1
        mats.append([[[rng.randint(0, 100) for _ in range(n)] for _ in range(n)], i % 9])
    add(
        "행렬의 거듭제곱",
        matrix_power,
        "int[][] int",
        "int[][]",
        "정사각 행렬 matrix의 exponent제곱을 계산하고 각 원소를 1000000007로 나눈 나머지를 행렬로 반환하세요. 0제곱은 같은 크기의 단위 행렬입니다.",
        "1 ≤ 행렬 크기 n ≤ 25, 0 ≤ exponent ≤ 2147483647, 각 원소는 0~1000000006입니다.",
        "O(n³ log(exponent+2))",
        mats,
        (
            "피보나치 행렬을 세 번 곱하면 행들이 [3,2], [2,1]입니다.",
            "대각선만 1이고 나머지는 0인 행렬입니다.",
        ),
        oracle=mat_oracle,
        stress=[([[[1, 1], [0, 1]], 2147483647], [[1, 2147483647 % 1000000007], [0, 1]])],
    )
    add(
        "피보나치 수열 4",
        fibonacci,
        "int",
        "int",
        "F(0)=0, F(1)=1이고 F(n)=F(n−1)+F(n−2)입니다. 빠른 두 배 공식으로 F(n)을 1000000007로 나눈 나머지를 반환하세요.",
        "0 ≤ n ≤ 2147483647.",
        "O(log(n+2))",
        [[n] for n in [10, 0, 1, 2, 3, 5, 42, 46, 47, 100, 100000, 999999999, 2147483647]],
        ("F(10)은 55입니다.", "정의에 따라 F(0)은 0입니다."),
        oracle=fib_oracle,
    )
    tot = [[n] for n in [12, 1, 2, 3, 4, 5, 6, 8, 9, 17, 21, 25, 49, 64, 97, 1001, 2310]]
    add(
        "오일러 피 함수",
        totient,
        "int",
        "int",
        "1부터 n까지 정수 중 n과 서로소인 수의 개수 φ(n)을 반환하세요. 두 수의 최대공약수가 1이면 서로소입니다.",
        "1 ≤ n ≤ 2147483647.",
        "O(√n)",
        tot,
        ("12와 서로소인 수는 1, 5, 7, 11 네 개입니다.", "gcd(1,1)=1이므로 φ(1)=1입니다."),
        oracle=lambda n: sum(math.gcd(x, n) == 1 for x in range(1, n + 1)),
        stress=[([2147483647], 2147483646), ([2**30], 2**29)],
    )
    segments = [
        [[0, 0], [3, 3], [0, 3], [3, 0]],
        [[0, 0], [1, 1], [2, 2], [3, 3]],
        [[0, 0], [2, 0], [2, 0], [3, 2]],
        [[0, 0], [0, 0], [1, 1], [1, 1]],
        [[1, 1], [1, 1], [0, 0], [3, 3]],
        [[1, 1], [1, 1], [1, 1], [1, 1]],
        [[0, 0], [0, 3], [0, 2], [0, 5]],
        [[-10000, -10000], [10000, 10000], [-10000, 10000], [10000, -10000]],
    ]
    for _ in range(15):
        segments.append([[rng.randrange(-8, 9), rng.randrange(-8, 9)] for _ in range(4)])
    add(
        "두 선분의 교차 판정",
        intersect,
        "int[] int[] int[] int[]",
        "bool",
        "끝점 a,b인 닫힌 선분과 끝점 c,d인 닫힌 선분에 공통점이 있으면 true를 반환하세요. 끝점에서 만나거나 한 직선 위에서 겹쳐도 교차입니다. 두 끝점이 같아 길이 0인 선분도 허용합니다.",
        "각 점은 [x,y]이며 |x|, |y| ≤ 10000입니다.",
        "O(1)",
        segments,
        ("두 대각선은 (1.5,1.5)에서 만납니다.", "같은 직선 위에 있지만 두 구간 사이에 틈이 있습니다."),
        oracle=segment_oracle,
        helpers=(cross,),
    )
    points = [
        [[0, 0], [1, 0], [2, 0], [2, 2], [0, 2], [1, 1]],
        [[1, 1], [1, 1]],
        [],
        [[0, 0]],
        [[3, 3], [1, 1], [0, 0]],
        [[0, 2], [0, 1], [0, 0]],
    ]
    for i in range(16):
        points.append([[rng.randrange(-9, 10), rng.randrange(-9, 10)] for _ in range(i + 2)])
    limits = "0 ≤ 점의 개수 n ≤ 100000, 각 점은 [x,y]이며 |x|, |y| ≤ 10000입니다."
    add(
        "볼록 껍질",
        hull,
        "int[][]",
        "int[][]",
        "모든 점을 포함하는 볼록 껍질의 꼭짓점을 반시계 방향으로 반환하세요. 첫 점은 (x,y) 사전순 최솟값이며 끝에 반복하지 않습니다. 중복 점과 변 중간의 점은 제외하고 모두 한 직선 위이면 양 끝점만 반환합니다.",
        limits,
        "O(n log(n+2))",
        [[p] for p in points],
        (
            "밑변 중간의 (1,0)과 내부의 (1,1)을 제외한 네 모서리를 반환합니다.",
            "중복을 제거하면 꼭짓점은 (1,1) 하나입니다.",
        ),
        oracle=jarvis,
        helpers=(cross,),
        stress=[([[[i, 0] for i in range(1500)]], [[0, 0], [1499, 0]])],
    )
    add(
        "가장 가까운 두 점",
        nearest,
        "int[][]",
        "int",
        "서로 다른 두 인덱스의 점 사이 유클리드 거리의 제곱 중 최솟값을 반환하세요. 좌표가 같은 서로 다른 점의 거리는 0입니다. 점이 둘 미만이면 −1입니다.",
        limits,
        "O(n log(n+2))",
        [[p] for p in points],
        (
            "예를 들어 (0,0)과 (1,0) 사이 거리의 제곱은 1입니다.",
            "좌표가 같은 서로 다른 두 점이 있어 답은 0입니다.",
        ),
        oracle=lambda p: min(
            ((a[0] - b[0]) ** 2 + (a[1] - b[1]) ** 2 for a, b in itertools.combinations(p, 2)), default=-1
        ),
        stress=[([[[i, 0] for i in range(3000)]], 1)],
    )
