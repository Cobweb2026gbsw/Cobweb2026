"""문자열 알고리즘의 짧은 입력은 직접 비교와 전수 탐색으로 검산한다."""

import random


def prefix(pattern):
    border = [0] * len(pattern)
    length = 0
    for i in range(1, len(pattern)):
        while length and pattern[i] != pattern[length]:
            length = border[length - 1]
        if pattern[i] == pattern[length]:
            length += 1
        border[i] = length
    return border


def kmp(text, pattern):
    border = prefix(pattern)
    length = 0
    hits = []
    for i, c in enumerate(text):
        while length and c != pattern[length]:
            length = border[length - 1]
        if c == pattern[length]:
            length += 1
        if length == len(pattern):
            hits.append(i + 1 - length)
            length = border[length - 1]
    return hits


def z_values(text):
    z = [0] * len(text)
    left = right = 0
    for i in range(1, len(text)):
        if i < right:
            z[i] = min(right - i, z[i - left])
        while i + z[i] < len(text) and text[z[i]] == text[i + z[i]]:
            z[i] += 1
        if i + z[i] > right:
            left, right = i, i + z[i]
    return z


def hashes(text, queries):
    mod = 1000000007
    powers, prefixes = [1], [0]
    for c in text:
        powers.append(powers[-1] * 31 % mod)
        prefixes.append((31 * prefixes[-1] + ord(c) - 96) % mod)
    return [(prefixes[r + 1] - prefixes[l] * powers[r + 1 - l]) % mod for l, r in queries]


def multiple(text, patterns):
    from collections import deque

    children = [[-1] * 26]
    failure = [0]
    ends = []
    for pattern in patterns:
        node = 0
        for c in pattern:
            col = ord(c) - 97
            if children[node][col] == -1:
                children[node][col] = len(children)
                children.append([-1] * 26)
                failure.append(0)
            node = children[node][col]
        ends.append(node)
    queue = deque()
    order = []
    for col, child in enumerate(children[0]):
        if child == -1:
            children[0][col] = 0
        else:
            queue.append(child)
    while queue:
        u = queue.popleft()
        order.append(u)
        for col, v in enumerate(children[u]):
            link = children[failure[u]][col]
            if v < 0:
                children[u][col] = link
            else:
                failure[v] = link
                queue.append(v)
    seen = [0] * len(children)
    state = 0
    for c in text:
        state = children[state][ord(c) - 97]
        seen[state] += 1
    for u in reversed(order):
        seen[failure[u]] += seen[u]
    return [seen[v] for v in ends]


def autocomplete(words, prefixes, k):
    from bisect import bisect_left

    ordered = sorted(words)
    answer = []
    for p in prefixes:
        pos = bisect_left(ordered, p)
        answer.append([word for word in ordered[pos : pos + k] if word.startswith(p)])
    return answer


def common_seq(first, second):
    prev = [0] * (len(second) + 1)
    for a in first:
        row = [0]
        for j, b in enumerate(second, 1):
            row.append(prev[j - 1] + 1 if a == b else max(prev[j], row[-1]))
        prev = row
    return prev[-1]


def common_sub(first, second):
    row = [0] * (len(second) + 1)
    best = 0
    for a in first:
        for j in range(len(second), 0, -1):
            row[j] = row[j - 1] + 1 if a == second[j - 1] else 0
            best = max(best, row[j])
    return best


def edit(first, second):
    prev = list(range(len(second) + 1))
    for i, a in enumerate(first, 1):
        row = [i]
        for j, b in enumerate(second, 1):
            row.append(min(prev[j] + 1, row[-1] + 1, prev[j - 1] + (a != b)))
        prev = row
    return prev[-1]


def manacher(text):
    transformed = "#" + "#".join(text) + "#"
    radius = [0] * len(transformed)
    center = right = 0
    for i in range(len(transformed)):
        if i < right:
            radius[i] = min(right - i, radius[2 * center - i])
        d = radius[i] + 1
        while i - d >= 0 and i + d < len(transformed) and transformed[i - d] == transformed[i + d]:
            d += 1
        radius[i] = d - 1
        if i + radius[i] > right:
            center, right = i, i + radius[i]
    return max(radius, default=0) if text else 0


def lcs_oracle(a, b):
    for length in range(min(len(a), len(b)), -1, -1):
        from itertools import combinations

        for part in combinations(a, length):
            it = iter(b)
            if all(c in it for c in part):
                return length


def lev_oracle(a, b):
    from functools import lru_cache

    @lru_cache(None)
    def cost(i, j):
        if i == len(a) or j == len(b):
            return len(a) - i + len(b) - j
        return min(1 + cost(i + 1, j), 1 + cost(i, j + 1), (a[i] != b[j]) + cost(i + 1, j + 1))

    return cost(0, 0)


def populate(add):
    rng = random.Random(1366)
    texts = [
        "ababa",
        "aaaa",
        "",
        "a",
        "abc",
        "abacaba",
        "abcdef",
        "aabbaa",
        "abababab",
        "bbaaa",
        "mississippi",
        "xyzzzy",
    ]
    pairs = [
        ["abcde", "ace"],
        ["", "abc"],
        ["abab", "baba"],
        ["aaa", "aa"],
        ["ab", "ba"],
        ["a", "a"],
        ["abc", "def"],
    ]
    for _ in range(11):
        pairs.append(["".join(rng.choices("abc", k=rng.randrange(1, 9))) for _ in range(2)])
    search = [["ababa", "aba"], ["aaaa", "aa"], ["", "a"]] + [p for p in pairs if p[1]]
    text_limit = "문자열은 영문 소문자로 구성되며 각 길이는 0~16384입니다."
    add(
        "KMP 문자열 검색",
        kmp,
        "string string",
        "int[]",
        "text에서 pattern이 시작하는 모든 인덱스를 오름차순으로 반환하세요. 겹치는 일치도 모두 포함합니다. 패턴은 비어 있지 않습니다.",
        text_limit,
        "O(|text|+|pattern|)",
        search,
        ("aba는 0번째와 2번째에서 시작합니다.", "aa는 서로 겹치면서 0, 1, 2번째에 등장합니다."),
        oracle=lambda s, p: [i for i in range(len(s)) if s.startswith(p, i)],
        helpers=(prefix,),
        stress=[(["a" * 16000, "a" * 8000 + "b"], [])],
    )
    add(
        "접두사 함수 계산",
        prefix,
        "string",
        "int[]",
        "pattern의 각 인덱스 i에 대해 pattern[0..i]의 진접두사이면서 접미사인 가장 긴 문자열의 길이를 반환하세요. 진접두사는 전체 문자열보다 짧고, 빈 접두사의 길이는 0입니다.",
        text_limit,
        "O(n)",
        [[s] for s in texts],
        (
            "ababa의 다섯 접두 구간에 대해 0, 0, 1, 2, 3입니다.",
            "같은 문자만 있어 각 위치까지의 길이보다 1 작은 값입니다.",
        ),
        oracle=lambda s: [
            max(k for k in range(i + 1) if s[:k] == s[i + 1 - k : i + 1]) for i in range(len(s))
        ],
    )
    add(
        "Z 알고리즘",
        z_values,
        "string",
        "int[]",
        "각 i에 대해 text[i..]와 전체 text의 접두부가 앞에서부터 연속으로 일치하는 길이를 반환하세요. 첫 원소 Z[0]은 0으로 정합니다. 빈 문자열의 결과는 빈 배열입니다.",
        text_limit,
        "O(n)",
        [[s] for s in texts],
        ("2번째에서 aba가, 4번째에서 a가 일치합니다.", "1, 2, 3번째에 남은 모든 문자가 접두부와 일치합니다."),
        oracle=lambda s: [
            0 if i == 0 else next((j for j in range(len(s) - i) if s[j] != s[i + j]), len(s) - i)
            for i in range(len(s))
        ],
    )
    intervals = [["abc", [[0, 2], [1, 2]]], ["z", [[0, 0]]], ["", []]]
    for s in texts:
        if s:
            intervals.append([s, [sorted(rng.choices(range(len(s)), k=2)) for _ in range(8)]])
    add(
        "문자열 해싱",
        hashes,
        "string int[][]",
        "int[]",
        "각 [l,r]에 대해 text[l..r]의 해시를 순서대로 구하세요. 왼쪽부터 h=0에서 시작하여 글자마다 h=(31h+글자값) mod 1000000007을 적용합니다. a의 값은 1, z는 26입니다.",
        text_limit + " 쿼리 수 q ≤ 50000, 0 ≤ l ≤ r < n.",
        "O(n+q)",
        intervals,
        ("abc의 해시는 (1×31+2)×31+3=1026이고 bc는 65입니다.", "한 글자 z의 해시는 26입니다."),
        oracle=lambda s, q: [
            sum((ord(s[j]) - 96) * 31 ** (r - j) for j in range(l, r + 1)) % 1000000007 for l, r in q
        ],
    )
    patterns = [
        ["ababa", ["aba", "ba", "aba", "z"]],
        ["aaaa", ["a", "aa", "aaa"]],
        ["", ["abc"]],
        ["abc", []],
    ] + [[s, ["a", s[:2], "ab", s[-1]]] for s in texts if s]
    add(
        "다중 패턴 검색",
        multiple,
        "string string[]",
        "int[]",
        "트라이를 활용하여 text에서 각 패턴이 등장하는 횟수를 입력 패턴 순서대로 반환하세요. 겹치는 일치와 중복된 패턴 모두 허용합니다.",
        "text 길이 ≤ 16384. 패턴 수 ≤ 10000, 각 길이는 1~100, 패턴 길이 합 L ≤ 50000입니다. 문자는 영문 소문자입니다.",
        "O(|text|+26L+패턴 수)",
        patterns,
        (
            "aba와 ba가 두 번씩 일치하고 z는 등장하지 않습니다.",
            "한 글자씩 시작점을 옮기면 a, aa, aaa가 각각 4, 3, 2번입니다.",
        ),
        oracle=lambda s, p: [sum(s.startswith(w, i) for i in range(len(s))) for w in p],
    )
    suggestions = [
        [["apply", "apple", "apt", "bee"], ["ap", "x"], 2],
        [["a"], ["", "a", "ab"], 3],
        [[], [""], 1],
    ]
    for i in range(10):
        words = sorted({"".join(rng.choices("abc", k=rng.randint(1, 5))) for _ in range(i + 5)})
        suggestions.append([words, ["", "a", "ba", words[-1]], i % 4 + 1])
    add(
        "자동 완성 시스템",
        autocomplete,
        "string[] string[] int",
        "string[][]",
        "사전 words의 서로 다른 단어 중 각 접두사로 시작하는 단어를 사전순으로 최대 k개씩 반환하세요. 결과의 행 순서는 prefixes의 순서입니다. 빈 접두사는 모든 단어와 일치합니다.",
        "단어와 접두사는 길이 32 이하의 영문 소문자입니다. 사전 단어는 비어 있지 않습니다. 단어 수 n, 접두사 수 q ≤ 20000, 1 ≤ k ≤ 5.",
        "O((n log(n+2)+q(log(n+2)+k))×최대 단어 길이)",
        suggestions,
        (
            "ap로 시작하는 첫 두 단어는 apple, apply이고 x로 시작하는 단어는 없습니다.",
            "빈 접두사와 a에는 a가 일치하고 ab에는 일치하지 않습니다.",
        ),
        oracle=lambda words, ps, k: [sorted(w for w in words if w.startswith(p))[:k] for p in ps],
    )
    for title, ref, desc, oracle, notes in [
        (
            "최장 공통 부분 수열",
            common_seq,
            "두 문자열에서 글자 순서를 유지하며 일부 글자를 건너뛸 수 있습니다. 두 문자열에 모두 나타나는 가장 긴 부분 수열의 길이를 반환하세요.",
            lcs_oracle,
            ("a, c, e를 순서대로 고르면 길이 3입니다.", "한 문자열이 비어 있어 공통 길이는 0입니다."),
        ),
        (
            "최장 공통 부분 문자열",
            common_sub,
            "first와 second에서 각각 연속으로 등장하는 공통 문자열 중 최대 길이를 반환하세요. 공통 문자가 없으면 0입니다.",
            lambda a, b: max(
                [0] + [j - i for i in range(len(a)) for j in range(i + 1, len(a) + 1) if a[i:j] in b]
            ),
            (
                "ace의 문자 사이에 다른 문자가 끼어 있어 공통 연속 길이는 1입니다.",
                "빈 문자열과의 최대 길이는 0입니다.",
            ),
        ),
        (
            "최소 편집 거리",
            edit,
            "first를 second로 바꾸기 위한 최소 연산 수를 반환하세요. 글자 한 개의 삽입·삭제·교체가 각각 비용 1이며 이웃 글자 교환 연산은 없습니다.",
            lev_oracle,
            ("abcde에서 b와 d를 삭제하면 ace가 됩니다.", "빈 문자열에 a, b, c를 하나씩 삽입합니다."),
        ),
    ]:
        add(
            title,
            ref,
            "string string",
            "int",
            desc,
            "두 문자열은 길이 0~1500의 영문 소문자입니다.",
            "O((|first|+1)(|second|+1))",
            pairs,
            notes,
            oracle=oracle,
            stress=[(["a" * 500, "b" * 500], 500 if ref == edit else 0)],
        )
    add(
        "팰린드롬 부분 문자열",
        manacher,
        "string",
        "int",
        "마나커(Manacher) 알고리즘을 사용해 text에서 가장 긴 연속 팰린드롬의 길이를 반환하세요. 팰린드롬은 양방향으로 읽어도 같은 문자열입니다. 빈 문자열의 답은 0입니다.",
        text_limit,
        "O(n)",
        [[s] for s in texts],
        ("ababa 전체가 길이 5의 팰린드롬입니다.", "aaaa 전체가 짝수 길이 팰린드롬입니다."),
        oracle=lambda s: max(
            [0] + [j - i for i in range(len(s)) for j in range(i + 1, len(s) + 1) if s[i:j] == s[i:j][::-1]]
        ),
        stress=[(["a" * 16384], 16384)],
    )
