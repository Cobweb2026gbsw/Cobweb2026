"""문제별 상한에서 정답을 손으로 유도할 수 있는 큰 입력. 마지막 비공개 케이스로 저장한다."""


def limit_case(ordinal):
    if ordinal in (1, 2, 7, 8, 9):
        n = 20000
        edges = [[i, i + 1, 1] for i in range(n - 1)]
        if ordinal == 1:
            return [n, edges, 0], list(range(n))
        if ordinal == 2:
            return [n, edges, 0, n // 2, n - 1], n - 1
        if ordinal == 7:
            return [n, edges, 0, n - 1], list(range(n))
        return [n, edges], n - 1
    if ordinal == 3:
        n, k = 1000, 20
        edges = [[i, i + 1, 1] for i in range(n - 1)] + [[0, 0, 1]]
        return [n, edges, 0, k], [i + k - 1 for i in range(n)]
    if ordinal == 4:
        n = 500
        edges = [[i, i + 1, -1] for i in reversed(range(n - 1))]
        return [n, edges, 0], [-i for i in range(n)]
    if ordinal == 5:
        return [500, [[i, i + 1, 1] for i in range(498)] + [[499, 499, -1]]], True
    if ordinal == 6:
        n = 150
        return [n, [[i, i + 1, 1] for i in range(n - 1)]], [
            [v - u if v >= u else -1 for v in range(n)] for u in range(n)
        ]
    if ordinal == 10:
        n = 150
        return [[[abs(i - j) for j in range(n)] for i in range(n)]], n - 1
    if ordinal == 11:
        n = 100000
        return [n, [[0, i, i + 1] for i in range(n - 1)] + [[1, 0, n - 1]]], [1]
    if ordinal == 12:
        # 중복된 관계가 최대 횟수만큼 들어와도 새 인원으로 세지 않는다.
        return [[["a", "b"]] * 50000], [2] * 50000
    if ordinal == 13:
        return [[[0, 4]] * 50000 + [[1, 0], [2, 0]] * 25000], [4] * 50000
    if ordinal == 14:
        return [[9, -9] * 50000], [9, -9] * 50000
    if ordinal == 15:
        n = 65536
        tree = []
        width = 1
        while width <= n:
            tree.extend([n // width] * width)
            width *= 2
        return [[1] * n], tree
    if ordinal in (16, 17, 18, 19):
        n = 30000
        if ordinal == 16:
            return [[1] * n, [[0, 0, 0], [1, 0, n - 1]] * 15000], [n - 1] * 15000
        if ordinal == 17:
            return [[0] * (n - 1) + [-3], [[0, n - 1], [0, n - 2]] * 15000], [-3, 0] * 15000
        if ordinal == 18:
            return [[0] * n, [[0, 0, 2], [1, n - 1, 0]] * 15000], [2 * i for i in range(1, 15001)]
        return [[0] * n, [[0, 0, n - 1, 1], [1, 0, n - 1, 0]] * 15000], [n * i for i in range(1, 15001)]
    if ordinal == 20:
        return [[[0, 3], [0, 1], [2, 2], [1, 1], [1, 3]] * 20000], [3] * 20000
    if ordinal == 21:
        return ["a" * 16384, "a" * 16000], list(range(385))
    if ordinal in (22, 23):
        n = 16384
        return ["z" * n], list(range(n)) if ordinal == 22 else [0] + list(range(n - 1, 0, -1))
    if ordinal == 24:
        return ["z" * 16384, [[0, 0], [16382, 16383]] * 25000], [26, 26 * 32] * 25000
    if ordinal == 25:
        return ["a" * 16384, ["aa", "ab"] * 5000], [16383, 0] * 5000
    if ordinal == 26:
        # 사전에 포함되는 문자는 모두 소문자이므로 사전순으로 고정 길이 번호를 부호화한다.
        def word(i):
            return "".join(chr(97 + (i // (26**j)) % 26) for j in (3, 2, 1, 0))

        words = [word(i) for i in range(20000)]
        return [words, ["z", "aaa"] * 500, 2], [[], words[:2]] * 500
    if ordinal in (27, 28, 29):
        same = 1500
        return ["ab" * 750, "ba" * 750], 2 if ordinal == 29 else same - 1
    if ordinal == 30:
        return ["ab" * 8192], 16383
    if ordinal in (31, 32):
        n = 100000
        return [[10] * n], 1 if ordinal == 31 else [10]
    if ordinal == 33:
        return [[100] * 101], 99 * 100**3
    if ordinal == 34:
        # 균등 가중치 500개의 최적 이진 병합 트리는 깊이 8에 12개, 깊이 9에 488개가 있다.
        return [[1000] * 500], (12 * 8 + 488 * 9) * 1000
    if ordinal == 35:
        return [[[0 if i == j else 1 for j in range(14)] for i in range(14)]], 14
    if ordinal == 36:
        return [5000, [[1, 1, 64]] * 100], 5000
    if ordinal in (37, 38):
        n = 100000
        edges = [[0, i] for i in range(1, n)]
        return ([n, edges], n - 1) if ordinal == 37 else ([[1000] + [1] * (n - 1), edges], n - 1)
    if ordinal == 39:
        return [15, [[1 << i, 2] for i in range(15)] + [[0, 1]] * 55], 30
    if ordinal == 40:
        return [100000, list(range(1, 51))], 100000 % 51 != 0
    if ordinal == 41:
        return [2147483646, 2147483647, 2147483647], 2147483646
    if ordinal == 42:
        return [2147483645, 2147483647], 1073741823
    if ordinal == 43:
        return [999999999, 1000000000], [1, 999999999, -999999998]
    if ordinal == 44:
        # 전수 팩토리얼 대신 Python 다중 정밀도 정수로 이항계수 자체를 계산한 뒤 나눈다.
        import math

        return [1000000, 20000], math.comb(1000000, 20000) % 1000000007
    if ordinal == 45:
        n = 25
        return [[[1000000006 if i == j else 0 for j in range(n)] for i in range(n)], 2147483646], [
            [int(i == j) for j in range(n)] for i in range(n)
        ]
    if ordinal == 46:
        # 독립적인 2×2 행렬 거듭제곱으로 계산한 F(2^31-2).
        return [2147483646], 267816090
    if ordinal == 47:
        return [2147483646], 534600000  # 서로 다른 소인수 [2, 3, 7, 11, 31, 151, 331]
    if ordinal == 48:
        return [[-10000, 10000], [10000, -10000], [-10000, 9999], [9999, -10000]], False
    if ordinal == 49:
        corners = [[-10000, -10000], [10000, -10000], [10000, 10000], [-10000, 10000]]
        return [corners + [[0, 0]] * 99996], corners
    if ordinal == 50:
        return [[[i % 125, i // 125] for i in range(100000)]], 1
    raise ValueError(ordinal)
