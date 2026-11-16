#!/usr/bin/env python3
"""저장소에 검토되는 기초 알고리즘 문제집을 결정적으로 생성한다.

사용자 코드를 실행하지 않는다. 각 기준 풀이와 별도로 작성한 명세 오라클로
기대값을 만들고, 생성 시 두 구현을 대조한다. level0.json은 수정하지 않는다.
"""
from __future__ import annotations

import bisect
import calendar
import collections
import copy
import itertools
import json
import math
from pathlib import Path
import random
import re
import textwrap

ROOT = Path(__file__).resolve().parents[1]
RNG = random.Random(20261027)
SPECS: dict[int, list[dict]] = {1: [], 2: []}
ORACLES = {}
CASES = {}


def compact(value):
    return json.dumps(value, ensure_ascii=True, separators=(",", ":"), allow_nan=False)


def add(level, title, params, result, description, constraints, body, cases, oracle):
    ordinal = len(SPECS[level]) + 1
    number = (1100 if level == 1 else 1175) + ordinal
    chapter_index = (ordinal - 1) // 15
    chapter = f"Lv.{level}-{chapter_index + 1}"
    # base는 C# 예약어이므로 모든 언어에서 쓸 수 있는 인자 이름으로 통일한다.
    if any(name == "base" for name, _ in params):
        replacement = "radix" if title.startswith("진법 변환") else "baseValue"
        params = [(replacement if name == "base" else name, typ) for name, typ in params]
        body = re.sub(r"\bbase\b", replacement, body)
        description = re.sub(r"\bbase\b", replacement, description)
        constraints = re.sub(r"\bbase\b", replacement, constraints)
    # 배열은 대용량을 허용하되 단일 문자열은 공통 ASCII ABI 한도 안에 둔다.
    # 사용자 목록이 명시한 배열 10만/100만 크기는 축소하지 않는다.
    for before, after in (
        ("0 ≤ 길이 ≤ 100000", "0 ≤ 길이 ≤ 16384"),
        ("각 문자열 길이 0 이상 100000 이하", "각 문자열 길이 0 이상 16384 이하"),
        ("0 ≤ text 길이 ≤ 100000", "0 ≤ text 길이 ≤ 16384"),
    ):
        constraints = constraints.replace(before, after)
    if title == "문자열 압축 2":
        # k=1, 모든 인접 문자가 다른 경우에도 반환 문자열이 16384 이하여야 한다.
        constraints = constraints.replace("0 ≤ 길이 ≤ 16384", "0 ≤ 길이 ≤ 8192")
    cases = list(cases)
    if level == 2 and title == "최대공약수 1":
        cases += [[[6 * (i + 1) for i in range(10000)]], [[6] * 9999 + [7]]]
    if level == 2 and title == "해시 기반 중복 검사":
        cases += [[list(range(10000))], [list(range(10000)) + [9999]]]
    parameters = [{"name": name, "type": typ} for name, typ in params]
    source = f"def sol({', '.join(name for name, _ in params)}):\n" + textwrap.indent(textwrap.dedent(body).strip(), "    ") + "\n"
    namespace = {}
    exec(compile(source, f"catalog_reference_{number}.py", "exec"), namespace)
    unique = []
    seen = set()
    for args in cases:
        key = compact(args)
        if key in seen:
            continue
        seen.add(key)
        expected = oracle(*copy.deepcopy(args))
        actual = namespace["sol"](*copy.deepcopy(args))
        if not equivalent(actual, expected):
            raise AssertionError((number, title, args, expected, actual))
        unique.append({"input": compact({"args": args}), "expected": compact(expected)})
    if len(unique) < DOMAIN_EXHAUSTIVE.get(number, 10):
        raise AssertionError((number, title, "ten distinct cases required", len(unique)))
    explanation = "입력 인자에 문제의 규칙을 적용한 함수 반환값입니다. 배열의 순서와 문자열의 대소문자도 결과의 일부입니다."
    problem = {
        "ordinal": ordinal, "number": number, "chapter": chapter, "title": title,
        "description": "## 문제\n\n" + description + "\n\n`sol` 함수를 구현하여 결과를 반환하세요. 표준 출력으로 출력하는 문제가 아닙니다. 인덱스는 별도 설명이 없으면 0부터 시작합니다.",
        "inputDescription": "\n\n".join(f"`{name}` ({typ}): 아래 제한 사항과 문제의 규칙을 만족하는 인자입니다." for name, typ in params),
        "outputDescription": f"`{result}` 형식의 함수 반환값입니다. " + ("실수는 절대 또는 상대 오차 1e-9 이내를 허용합니다." if result == "float" else "배열의 원소 순서와 중복 여부는 문제에 명시된 규칙을 따릅니다."),
        "constraints": constraints + "\n- 모든 정수 인자·정수 결과는 부호 있는 32비트 범위입니다. 중간 곱셈·합산은 필요한 경우 64비트로 계산하세요.\n- 문자열은 ASCII 문자만 포함합니다. 배열 인덱스와 날짜 등은 별도 설명이 없으면 유효한 값만 주어집니다.",
        "rankType": "SKILLED" if level == 1 else "ELITE",
        "rankInt": ([5, 4, 3, 2, 1] if level == 1 else [5, 4, 3, 3, 2, 1])[chapter_index],
        "timeLimitMs": 10000 if level == 2 else 2000, "memoryLimitMb": 512 if level == 2 else 256,
        "functionSignature": {"name": "sol", "parameters": parameters, "returnType": result},
        "tags": [f"Lv.{level}", chapter, ("수학", "배열·문자열", "정렬·탐색", "구현", "2차원 배열")[chapter_index] if level == 1 else ("정수론", "문자열·해시", "정렬·이진 탐색", "자료구조", "투 포인터·누적 합", "완전탐색·그리디")[chapter_index]],
        "examples": [{"input": case["input"], "output": case["expected"], "explanation": explanation} for case in unique[:2]],
        "tests": unique,
        "referenceSolution": {"language": "PYTHON", "fileName": "solution.py", "sourceCode": source},
    }
    SPECS[level].append(problem)
    ORACLES[number], CASES[number] = oracle, copy.deepcopy(cases)


def equivalent(actual, expected):
    if type(expected) is float:
        return type(actual) in (int, float) and math.isfinite(actual) and math.isclose(actual, expected, rel_tol=1e-9, abs_tol=1e-9)
    if type(expected) is list:
        return type(actual) is list and len(actual) == len(expected) and all(equivalent(a, b) for a, b in zip(actual, expected))
    return type(actual) is type(expected) and actual == expected


def ints(values):
    return [[value] for value in values]


def prime(n):
    return n >= 2 and all(n % d for d in range(2, math.isqrt(n) + 1))


def divisor_count(n):
    return sum(n % d == 0 for d in range(1, n + 1))


def moving(commands, start=(0, 0), bounds=None, blocks=()):
    x, y = start
    history = []
    for command in commands:
        dx, dy = {"U": (0, 1), "D": (0, -1), "L": (-1, 0), "R": (1, 0)}[command]
        nx, ny = x + dx, y + dy
        if (bounds is None or 0 <= nx < bounds[0] and 0 <= ny < bounds[1]) and (nx, ny) not in blocks:
            x, y = nx, ny
        history.append([x, y])
    return [x, y], history


def rle(text, block=1):
    chunks = [text[i:i + block] for i in range(0, len(text), block)]
    return "".join(chunk + str(len(list(group))) for chunk, group in itertools.groupby(chunks))


SMALL_POS = [1, 2, 3, 4, 6, 12, 17, 25, 36, 97, 100, 360]
SIGNED = [-100, -7, -1, 0, 1, 2, 3, 9, 10, 37, 100, 999]
ARRAYS = [[], [1], [-1], [0, 0], [3, 1, 2], [-5, -1, -3], [4, 4, 2, 4], [1, 2, 1, 3, 2], [9, 8, 7, 6], [0, -1, 1, -1], list(range(15)), [10, -10, 5, 0, 5, 2]]
NONEMPTY = [a for a in ARRAYS if a]
TEXTS = ["", "a", "aa", "ab", "abba", "abcba", "aabbccc", "abcdef", "zzzzy", "mississippi", "a a", "AbaA"]
COMMANDS = ["", "U", "D", "L", "R", "UDLR", "RRRUU", "DDDLLL", "URURDDLL", "LLLLRRRR", "UUUURDDD", "RDLURDLU"]
MATRICES = [[[1]], [[0]], [[-3]], [[1, 2]], [[1], [2]], [[1, 2], [3, 4]], [[0, 0], [0, 0]], [[-1, -2, 3], [4, 5, -6]], [[1, 2, 3], [4, 5, 6], [7, 8, 9]], [[2, 2], [2, 2], [2, 2]], [[7, -7, 0]], [[9], [-8], [2]]]


def level1():
    add(1, "약수의 개수", [("n", "int")], "int", "양의 정수 `n`의 양의 약수 개수를 반환하세요.", "- 1 ≤ n ≤ 100000", "return sum(n % d == 0 for d in range(1, n + 1))", ints(SMALL_POS), divisor_count)
    add(1, "약수의 합", [("n", "int")], "int", "양의 정수 `n`의 모든 양의 약수의 합을 반환하세요.", "- 1 ≤ n ≤ 100000", "total = 0\nfor d in range(1, n + 1):\n    if n % d == 0:\n        total += d\nreturn total", ints(SMALL_POS), lambda n: sum(d for d in range(1, n + 1) if n % d == 0))
    add(1, "소수 판별", [("n", "int")], "bool", "정수 `n`이 소수인지 반환하세요. 소수는 1보다 크고 양의 약수가 1과 자기 자신뿐인 정수입니다.", "- -100 ≤ n ≤ 1000000", "if n < 2:\n    return False\nd = 2\nwhile d * d <= n:\n    if n % d == 0:\n        return False\n    d += 1\nreturn True", ints([-7, 0, 1, 2, 3, 4, 9, 17, 25, 97, 101, 999983]), prime)
    add(1, "소수의 개수", [("n", "int")], "int", "1부터 `n`까지의 정수 중 소수의 개수를 반환하세요.", "- 1 ≤ n ≤ 2000", "answer = 0\nfor value in range(2, n + 1):\n    if all(value % d for d in range(2, int(value ** 0.5) + 1)):\n        answer += 1\nreturn answer", ints(SMALL_POS), lambda n: sum(prime(i) for i in range(1, n + 1)))
    pairs = [[a, b] for a, b in [(12, 18), (7, 13), (1, 1), (1, 9), (9, 1), (8, 8), (100, 10), (21, 14), (99, 66), (48, 180), (17, 51), (36, 60)]]
    add(1, "최대공약수", [("a", "int"), ("b", "int")], "int", "두 양의 정수 `a`, `b`의 최대공약수를 반환하세요.", "- 1 ≤ a, b ≤ 1000000", "while b:\n    a, b = b, a % b\nreturn a", pairs, math.gcd)
    add(1, "최소공배수", [("a", "int"), ("b", "int")], "int", "두 양의 정수 `a`, `b`의 최소공배수를 반환하세요.", "- 1 ≤ a, b ≤ 30000", "x, y = a, b\nwhile y:\n    x, y = y, x % y\nreturn a // x * b", pairs, math.lcm)
    add(1, "팩토리얼 1", [("n", "int")], "int", "`n!`의 십진수 표현 끝에 연속해서 등장하는 0의 개수를 반환하세요. `0! = 1`입니다.", "- 0 ≤ n ≤ 1000000000", "answer = 0\nwhile n:\n    n //= 5\n    answer += n\nreturn answer", ints([0, 1, 4, 5, 6, 9, 10, 24, 25, 49, 100, 125]), lambda n: sum(n // 5 ** e for e in range(1, 15)))
    add(1, "피보나치 수열", [("n", "int")], "int", "`F(0)=0`, `F(1)=1`, `F(n)=F(n-1)+F(n-2)`일 때 `F(n)`을 반환하세요.", "- 0 ≤ n ≤ 46", "a, b = 0, 1\nfor _ in range(n):\n    a, b = b, a + b\nreturn a", ints([0, 1, 2, 3, 4, 5, 8, 10, 20, 30, 45, 46]), lambda n: fibonacci(n))
    add(1, "자릿수 합 1", [("n", "int")], "int", "0 이상 정수 `n`의 각 자릿수를 더하는 일을 결과가 한 자리 수가 될 때까지 반복하고 그 값을 반환하세요.", "- 0 ≤ n ≤ 2147483647", "while n >= 10:\n    n = sum(int(c) for c in str(n))\nreturn n", ints([0, 1, 9, 10, 19, 99, 100, 12345, 999999, 987654321, 2147483647, 777]), lambda n: 0 if n == 0 else 1 + (n - 1) % 9)
    add(1, "숫자 뒤집기 1", [("n", "int")], "int", "0 이상 정수 `n`의 십진수 자릿수를 뒤집어 만든 정수와 원래 정수의 차이의 절댓값을 반환하세요. 뒤집은 수의 앞쪽 0은 무시합니다.", "- 0 ≤ n ≤ 100000000", "return abs(n - int(str(n)[::-1]))", ints([0, 1, 12, 21, 100, 120, 121, 12345, 909, 12300, 100000000, 98765]), lambda n: abs(n - sum(int(c) * 10 ** i for i, c in enumerate(str(n)))))
    add(1, "완전제곱수", [("n", "int")], "bool", "정수 `n`이 어떤 정수의 제곱인지 반환하세요. 0도 완전제곱수이고 음수는 아닙니다.", "- -100 ≤ n ≤ 2147395600", "if n < 0:\n    return False\nimport math\nr = math.isqrt(n)\nreturn r * r == n", ints([-9, -1, 0, 1, 2, 4, 8, 9, 25, 26, 100, 2147395600]), lambda n: n >= 0 and math.isqrt(n) ** 2 == n)
    arith = [[a, d, n] for a, d, n in [(2, 3, 4), (1, 1, 10), (0, 0, 1), (-5, 2, 4), (9, -3, 5), (1, 0, 10), (5, 5, 1), (-3, -2, 6), (100, 1, 100), (0, 7, 12), (5, -5, 2), (-1, 1, 3)]]
    add(1, "등차수열의 합", [("a", "int"), ("d", "int"), ("n", "int")], "int", "초항 `a`, 공차 `d`인 등차수열의 처음 `n`개 항의 합을 반환하세요.", "- -1000 ≤ a, d ≤ 1000\n- 1 ≤ n ≤ 1000", "return n * (2 * a + (n - 1) * d) // 2", arith, lambda a, d, n: sum(a + i * d for i in range(n)))
    geo = [[a, r, n] for a, r, n in [(2, 3, 4), (1, 2, 5), (0, 5, 5), (5, 0, 1), (5, 0, 2), (-2, 2, 4), (2, -2, 4), (3, 1, 9), (3, -1, 8), (1, 5, 10), (5, 3, 8), (2, 2, 1)]]
    add(1, "등비수열의 N번째 항", [("a", "int"), ("r", "int"), ("n", "int")], "int", "초항 `a`, 공비 `r`인 등비수열의 `n`번째 항을 반환하세요. 첫 항은 `n=1`입니다.", "- -5 ≤ a, r ≤ 5\n- 1 ≤ n ≤ 10", "value = a\nfor _ in range(n - 1):\n    value *= r\nreturn value", geo, lambda a, r, n: a * r ** (n - 1))
    purchase = [[p, m, n] for p, m, n in [(3, 20, 4), (5, 100, 2), (1, 0, 1), (1, 1, 1), (2, 11, 3), (10, 59, 3), (10, 60, 3), (7, 10, 5), (100, 0, 10), (2, 100, 10), (3, 5, 2), (1, 55, 10)]]
    add(1, "부족한 금액", [("price", "int"), ("money", "int"), ("count", "int")], "int", "물건을 `i`번째 구매할 때 가격은 `price × i`입니다. `count`번 구매하려고 할 때 가진 돈 `money`에서 부족한 금액을 반환하세요. 돈이 충분하면 0입니다.", "- 1 ≤ price, count ≤ 1000\n- 0 ≤ money ≤ 1000000000", "return max(0, price * count * (count + 1) // 2 - money)", purchase, lambda price, money, count: max(sum(price * i for i in range(1, count + 1)) - money, 0))
    decimal = [[a, b, k] for a, b, k in [(1, 3, 1), (1, 8, 3), (1, 2, 2), (0, 7, 5), (22, 7, 6), (7, 2, 1), (1, 7, 6), (10, 3, 4), (5, 5, 1), (17, 16, 4), (3, 20, 2), (1, 6, 5)]]
    add(1, "소수 자릿수", [("numerator", "int"), ("denominator", "int"), ("k", "int")], "int", "음이 아닌 분수 `numerator / denominator`의 소수점 아래 `k`번째 숫자 하나를 반환하세요. 유한소수가 끝난 뒤 자리는 0입니다. 부동소수점 반올림 없이 나눗셈의 나머지를 이용하세요.", "- 0 ≤ numerator ≤ 1000000\n- 1 ≤ denominator ≤ 1000000\n- 1 ≤ k ≤ 1000", "remainder = numerator % denominator\nfor _ in range(k):\n    digit, remainder = divmod(remainder * 10, denominator)\nreturn digit", decimal, lambda a, b, k: (a * 10 ** k // b) % 10)
    ranges = [[a, 0, len(a) - 1] for a in NONEMPTY] + [[[1, 2, 3, 4], 1, 2], [[1, 2, 3], 1, 1]]
    add(1, "배열 뒤집기 1", [("numbers", "int[]"), ("left", "int"), ("right", "int")], "int[]", "배열 `numbers`에서 양 끝을 포함한 인덱스 구간 `[left, right]`만 역순으로 뒤집은 배열을 반환하세요. 나머지 원소는 유지합니다.", "- 1 ≤ 배열 길이 ≤ 1000\n- 0 ≤ left ≤ right < 배열 길이\n- 각 원소의 절댓값 ≤ 1000000", "return numbers[:left] + numbers[left:right + 1][::-1] + numbers[right + 1:]", ranges, lambda a, l, r: [a[l + r - i] if l <= i <= r else a[i] for i in range(len(a))])
    rotations = [[a, i * 2] for i, a in enumerate(ARRAYS)] + [[[1, 2, 3], 1], [[1, 2, 3], 3]]
    add(1, "배열 회전 1", [("numbers", "int[]"), ("k", "int")], "int[]", "배열을 오른쪽으로 `k`칸 순환 이동한 배열을 반환하세요. 마지막 원소 다음은 첫 위치이고, 빈 배열은 그대로 반환합니다.", "- 0 ≤ 배열 길이 ≤ 1000\n- 0 ≤ k ≤ 1000000\n- 각 원소의 절댓값 ≤ 1000000", "if not numbers:\n    return []\nk %= len(numbers)\nreturn numbers[-k:] + numbers[:-k] if k else numbers[:]", rotations, lambda a, k: [a[(i - k) % len(a)] for i in range(len(a))])
    add(1, "배열 중복 제거", [("numbers", "int[]")], "int[]", "중복된 값을 제거하되 각 값이 처음 등장한 순서를 유지한 배열을 반환하세요.", "- 0 ≤ 배열 길이 ≤ 1000\n- 각 원소의 절댓값 ≤ 1000000", "result = []\nseen = set()\nfor value in numbers:\n    if value not in seen:\n        seen.add(value)\n        result.append(value)\nreturn result", [[a] for a in ARRAYS], lambda a: [x for i, x in enumerate(a) if x not in a[:i]])
    array_pairs = [[a, ARRAYS[-i - 1]] for i, a in enumerate(ARRAYS)]
    add(1, "배열 교집합", [("first", "int[]"), ("second", "int[]")], "int[]", "두 배열에 공통으로 포함된 서로 다른 값을 오름차순 배열로 반환하세요.", "- 각 배열 길이 0 이상 1000 이하\n- 각 원소의 절댓값 ≤ 1000000", "return sorted(set(first) & set(second))", array_pairs, lambda a, b: sorted({x for x in a if x in b}))
    add(1, "배열 합집합", [("first", "int[]"), ("second", "int[]")], "int[]", "두 배열 중 하나 이상에 포함된 서로 다른 모든 값을 오름차순 배열로 반환하세요.", "- 각 배열 길이 0 이상 1000 이하\n- 각 원소의 절댓값 ≤ 1000000", "return sorted(set(first) | set(second))", array_pairs, lambda a, b: sorted(dict.fromkeys(a + b)))
    add(1, "배열의 최댓값 1", [("numbers", "int[]")], "int[]", "배열의 최댓값과 그 값이 처음 등장하는 인덱스를 `[최댓값, 인덱스]`로 반환하세요.", "- 1 ≤ 배열 길이 ≤ 1000\n- 각 원소의 절댓값 ≤ 1000000", "value = max(numbers)\nreturn [value, numbers.index(value)]", [[a] for a in NONEMPTY], lambda a: list(max(enumerate(a), key=lambda item: (item[1], -item[0]))[::-1]))
    add(1, "두 번째 최댓값", [("numbers", "int[]")], "int", "배열의 서로 다른 값 중 두 번째로 큰 값을 반환하세요. 서로 다른 값이 두 개 미만이면 -1을 반환하세요. -1은 실제 원소 값일 수도 있습니다.", "- 0 ≤ 배열 길이 ≤ 1000\n- 각 원소의 절댓값 ≤ 1000000", "values = sorted(set(numbers))\nreturn values[-2] if len(values) >= 2 else -1", [[a] for a in ARRAYS], lambda a: max([x for x in a if x < max(a)], default=-1) if a else -1)
    string_ranges = [[s, 0, len(s) - 1] for s in TEXTS if s] + [["abcdef", 1, 4], ["abc", 1, 1]]
    add(1, "문자열 뒤집기 1", [("text", "string"), ("left", "int"), ("right", "int")], "string", "문자열에서 양 끝을 포함한 인덱스 구간 `[left, right]`만 뒤집은 문자열을 반환하세요.", "- 1 ≤ 문자열 길이 ≤ 1000\n- 0 ≤ left ≤ right < 문자열 길이", "return text[:left] + text[left:right + 1][::-1] + text[right + 1:]", string_ranges, lambda s, l, r: "".join(s[l + r - i] if l <= i <= r else s[i] for i in range(len(s))))
    add(1, "문자열 압축", [("text", "string")], "string", "연속된 같은 문자를 `문자 + 반복 횟수`로 바꾸어 연결하세요. 한 번 등장해도 횟수 1을 붙입니다. 예: `aaabbc` → `a3b2c1`. 빈 문자열의 결과는 빈 문자열입니다.", "- 영문 소문자로만 구성\n- 0 ≤ 길이 ≤ 1000", "answer = ''\ni = 0\nwhile i < len(text):\n    j = i + 1\n    while j < len(text) and text[j] == text[i]:\n        j += 1\n    answer += text[i] + str(j - i)\n    i = j\nreturn answer", [[s.lower().replace(" ", "")] for s in TEXTS] + [["x" * 12], ["abccba"]], rle)
    add(1, "문자열 회전", [("text", "string"), ("k", "int")], "string", "문자열을 왼쪽으로 `k`칸 순환 이동하여 반환하세요. 빈 문자열은 그대로 반환합니다.", "- 0 ≤ 문자열 길이 ≤ 1000\n- 0 ≤ k ≤ 1000000", "if not text:\n    return text\nk %= len(text)\nreturn text[k:] + text[:k]", [[s, i * 3] for i, s in enumerate(TEXTS)], lambda s, k: "".join(s[(i + k) % len(s)] for i in range(len(s))))
    add(1, "회문 검사", [("text", "string")], "bool", "문자열을 뒤집어도 원래와 같으면 true, 아니면 false를 반환하세요. 대소문자·공백을 구분하며 빈 문자열도 회문입니다.", "- 0 ≤ 문자열 길이 ≤ 1000", "return text == text[::-1]", [[s] for s in TEXTS], lambda s: all(s[i] == s[len(s) - 1 - i] for i in range(len(s) // 2)))
    add(1, "회문 검사 1", [("text", "string")], "bool", "영문 대소문자를 구분하지 않고 ASCII 공백(U+0020)을 모두 무시하여 회문인지 반환하세요. 다른 문자는 유지하며 빈 결과도 회문입니다.", "- 0 ≤ 문자열 길이 ≤ 1000", "clean = text.replace(' ', '').lower()\nreturn clean == clean[::-1]", [[s] for s in TEXTS] + [["Never odd or even"], ["A man a plan a canal Panama"]], lambda s: all(a == b for a, b in zip(s.lower().replace(" ", ""), reversed(s.lower().replace(" ", "")))))
    sentences = ["hello world", "a bb cc", "same size", "", "   ", "one", "long tiny longest", "first FIRST", "a  bbb c", "  leading trailing  ", "x yy zzz", "equal words"]
    add(1, "가장 긴 단어", [("sentence", "string")], "string", "문장을 하나 이상의 ASCII 공백으로 나누어 가장 긴 단어를 반환하세요. 길이가 같으면 먼저 등장한 단어입니다. 앞뒤·연속 공백은 무시하고 단어가 없으면 빈 문자열을 반환합니다.", "- 영문과 ASCII 공백만 포함\n- 0 ≤ 길이 ≤ 1000", "words = sentence.split()\nreturn max(words, key=len) if words else ''", [[s] for s in sentences], lambda s: sorted(enumerate(s.split()), key=lambda item: (-len(item[1]), item[0]))[0][1] if s.split() else "")
    lower_texts = ["", "a", "z", "abc", "aaa", "zzz", "abacaba", "abcdefghijklmnopqrstuvwxyz", "zzzza", "mississippi", "banana", "aabbcc"]
    add(1, "문자 빈도표", [("text", "string")], "int[]", "영문 소문자 문자열에서 a부터 z까지 각 알파벳의 등장 횟수를 길이 26인 배열로 반환하세요.", "- 영문 소문자만 포함\n- 0 ≤ 길이 ≤ 1000", "counts = [0] * 26\nfor c in text:\n    counts[ord(c) - ord('a')] += 1\nreturn counts", [[s] for s in lower_texts], lambda s: [s.count(chr(97 + i)) for i in range(26)])
    add(1, "문자열 정렬", [("text", "string")], "string", "영문 소문자 문자열의 문자들을 알파벳 오름차순으로 정렬하여 반환하세요. 중복 문자는 그대로 유지합니다.", "- 영문 소문자만 포함\n- 0 ≤ 길이 ≤ 1000", "return ''.join(sorted(text))", [[s] for s in lower_texts], lambda s: "".join(chr(i) * s.count(chr(i)) for i in range(97, 123)))
    add(1, "오름차순 정렬", [("numbers", "int[]")], "int[]", "정수 배열을 오름차순으로 정렬한 배열을 반환하세요. 중복 값도 모두 유지합니다.", "- 0 ≤ 배열 길이 ≤ 1000\n- 각 원소의 절댓값 ≤ 1000000", "return sorted(numbers)", [[a] for a in ARRAYS], lambda a: sorted(a, reverse=True)[::-1])
    add(1, "내림차순 정렬", [("numbers", "int[]")], "int[]", "정수 배열을 내림차순으로 정렬한 배열을 반환하세요. 중복 값도 모두 유지합니다.", "- 0 ≤ 배열 길이 ≤ 1000\n- 각 원소의 절댓값 ≤ 1000000", "return sorted(numbers, reverse=True)", [[a] for a in ARRAYS], lambda a: sorted(a)[::-1])
    word_arrays = [[], ["a"], ["bb", "a", "cc"], ["b", "a", "c"], ["", "a", ""], ["pear", "apple", "fig"], ["same", "same"], ["A", "a", "B"], ["zz", "aa", "ab"], ["long", "x", "yy", "z"], ["cat", "dog", "ant"], ["10", "2", "001"]]
    add(1, "문자열 길이 정렬", [("words", "string[]")], "string[]", "문자열 배열을 길이가 짧은 순으로, 길이가 같으면 ASCII 사전순으로 정렬하세요. 같은 문자열의 중복은 유지합니다.", "- 0 ≤ 배열 길이 ≤ 1000\n- 각 문자열 길이 0 이상 100 이하", "return sorted(words, key=lambda s: (len(s), s))", [[a] for a in word_arrays], lambda a: [s for length in sorted(set(map(len, a))) for s in sorted(x for x in a if len(x) == length)])
    kth = [[a, 1 if i % 2 == 0 else len(a)] for i, a in enumerate(NONEMPTY)] + [[[3, 1, 2], 2]]
    add(1, "정렬 후 K번째", [("numbers", "int[]"), ("k", "int")], "int", "배열을 오름차순으로 정렬했을 때 1부터 세는 `k`번째 값을 반환하세요. 중복 값도 각각 한 자리를 차지합니다.", "- 1 ≤ 배열 길이 ≤ 1000\n- 1 ≤ k ≤ 배열 길이\n- 각 원소의 절댓값 ≤ 1000000", "return sorted(numbers)[k - 1]", kth, lambda a, k: sorted(a, reverse=True)[len(a) - k])
    nearest = [[a, i - 4] for i, a in enumerate(NONEMPTY)] + [[[1, 3], 2]]
    add(1, "가장 가까운 수", [("numbers", "int[]"), ("target", "int")], "int", "배열에서 `target`과 차이의 절댓값이 가장 작은 값을 반환하세요. 동률이면 더 작은 값을 선택하세요.", "- 1 ≤ 배열 길이 ≤ 1000\n- 원소·target의 절댓값 ≤ 1000000", "return min(numbers, key=lambda x: (abs(x - target), x))", nearest, lambda a, target: sorted(a, key=lambda x: (abs(target - x), x))[0])
    add(1, "순위 매기기", [("scores", "int[]")], "int[]", "각 점수의 경쟁 순위를 원래 순서대로 반환하세요. 순위는 `자신보다 높은 점수의 개수 + 1`입니다. 예: [90,90,80] → [1,1,3].", "- 0 ≤ 점수 개수 ≤ 1000\n- 0 ≤ 각 점수 ≤ 1000000", "return [1 + sum(other > score for other in scores) for score in scores]", [[[abs(x) for x in a]] for a in ARRAYS], lambda a: [sorted(a, reverse=True).index(x) + 1 for x in a])
    search_cases = [[a, i % 5 - 1] for i, a in enumerate(ARRAYS)]
    add(1, "중복 숫자 세기", [("numbers", "int[]"), ("target", "int")], "int", "배열에서 `target`과 같은 원소의 개수를 반환하세요.", "- 0 ≤ 배열 길이 ≤ 1000\n- 원소·target의 절댓값 ≤ 1000000", "return numbers.count(target)", search_cases, lambda a, x: sum(1 for v in a if v == x))
    add(1, "최빈값", [("numbers", "int[]")], "int", "배열에서 가장 많이 등장하는 값을 반환하세요. 등장 횟수가 같으면 더 작은 값을 선택하세요.", "- 1 ≤ 배열 길이 ≤ 1000\n- 각 원소의 절댓값 ≤ 1000000", "from collections import Counter\ncounts = Counter(numbers)\nreturn min(counts, key=lambda x: (-counts[x], x))", [[a] for a in NONEMPTY], lambda a: min(set(a), key=lambda x: (-a.count(x), x)))
    add(1, "선형 탐색", [("numbers", "int[]"), ("target", "int")], "int", "배열을 앞에서부터 살펴 `target`이 처음 등장하는 인덱스를 반환하세요. 없으면 -1입니다.", "- 0 ≤ 배열 길이 ≤ 1000\n- 원소·target의 절댓값 ≤ 1000000", "for i, value in enumerate(numbers):\n    if value == target:\n        return i\nreturn -1", search_cases, lambda a, x: next((i for i in range(len(a)) if a[i] == x), -1))
    add(1, "이진 탐색", [("numbers", "int[]"), ("target", "int")], "bool", "비내림차순으로 정렬된 배열에서 이진 탐색을 사용하여 `target`의 존재 여부를 반환하세요.", "- 0 ≤ 배열 길이 ≤ 10000\n- 원소·target의 절댓값 ≤ 1000000", "left, right = 0, len(numbers) - 1\nwhile left <= right:\n    mid = (left + right) // 2\n    if numbers[mid] == target:\n        return True\n    if numbers[mid] < target:\n        left = mid + 1\n    else:\n        right = mid - 1\nreturn False", [[sorted(a), x] for a, x in search_cases], lambda a, x: x in a)
    add(1, "두 배열 비교", [("first", "int[]"), ("second", "int[]")], "bool", "두 배열의 길이가 같고 모든 위치의 원소가 같으면 true, 아니면 false를 반환하세요.", "- 각 배열 길이 0 이상 1000 이하\n- 각 원소의 절댓값 ≤ 1000000", "return first == second", array_pairs + [[[1, 2], [1, 2]], [[], []]], lambda a, b: len(a) == len(b) and all(a[i] == b[i] for i in range(len(a))))
    add(1, "정렬 확인", [("numbers", "int[]")], "bool", "배열이 비내림차순인지 반환하세요. 즉 앞 원소가 다음 원소보다 크면 안 됩니다. 빈 배열과 원소 하나의 배열도 정렬되어 있습니다.", "- 0 ≤ 배열 길이 ≤ 1000\n- 각 원소의 절댓값 ≤ 1000000", "return all(numbers[i] <= numbers[i + 1] for i in range(len(numbers) - 1))", [[a] for a in ARRAYS], lambda a: a == sorted(a))
    add(1, "역순 정렬 확인", [("numbers", "int[]")], "bool", "배열이 비오름차순인지 반환하세요. 즉 앞 원소가 다음 원소보다 작으면 안 됩니다. 같은 값은 허용합니다.", "- 0 ≤ 배열 길이 ≤ 1000\n- 각 원소의 절댓값 ≤ 1000000", "return all(numbers[i] >= numbers[i + 1] for i in range(len(numbers) - 1))", [[a] for a in ARRAYS], lambda a: a == sorted(a, reverse=True))
    diffs = [a for a in ARRAYS if len(a) >= 2] + [[1, 5], [-1, 1], [100, 1000]]
    add(1, "최소 차이", [("numbers", "int[]")], "int", "서로 다른 두 인덱스의 원소 차이의 절댓값 중 최솟값을 반환하세요. 값이 같은 두 원소를 고르면 차이는 0입니다.", "- 2 ≤ 배열 길이 ≤ 1000\n- 각 원소의 절댓값 ≤ 1000000", "a = sorted(numbers)\nreturn min(a[i + 1] - a[i] for i in range(len(a) - 1))", [[a] for a in diffs], lambda a: min(abs(a[i] - a[j]) for i in range(len(a)) for j in range(i)))
    missing = [[n, [x for x in range(n, 0, -1) if x != lost]] for n, lost in [(5, 3), (1, 1), (2, 1), (2, 2), (10, 10), (10, 1), (10, 7), (20, 11), (100, 50), (7, 4), (8, 8), (9, 2)]]
    add(1, "빠진 숫자 찾기", [("n", "int"), ("numbers", "int[]")], "int", "1부터 `n`까지의 서로 다른 정수 중 정확히 한 수만 빠진 길이 `n-1` 배열이 주어집니다. 빠진 수를 반환하세요. 배열은 정렬되어 있지 않을 수 있습니다.", "- 1 ≤ n ≤ 1000", "return n * (n + 1) // 2 - sum(numbers)", missing, lambda n, a: next(x for x in range(1, n + 1) if x not in a))
    add(1, "로봇 이동", [("commands", "string")], "int[]", "좌표 (0,0)에서 U: y+1, D: y-1, L: x-1, R: x+1 명령을 순서대로 수행한 최종 좌표 `[x,y]`를 반환하세요.", "- U,D,L,R만 포함\n- 0 ≤ 명령 길이 ≤ 1000", "x = y = 0\nfor c in commands:\n    if c == 'U': y += 1\n    elif c == 'D': y -= 1\n    elif c == 'L': x -= 1\n    else: x += 1\nreturn [x, y]", [[s] for s in COMMANDS], lambda s: [s.count("R") - s.count("L"), s.count("U") - s.count("D")])
    add(1, "로봇 이동 1", [("width", "int"), ("height", "int"), ("commands", "string")], "int[]", "로봇은 (0,0)에서 시작합니다. U,D,L,R는 각각 y+1,y-1,x-1,x+1입니다. `0≤x<width`, `0≤y<height`를 벗어나는 명령은 무시하고 최종 `[x,y]`를 반환하세요.", "- 1 ≤ width,height ≤ 1000\n- 명령 길이 0 이상 1000 이하", "x = y = 0\nfor c in commands:\n    dx, dy = {'U': (0, 1), 'D': (0, -1), 'L': (-1, 0), 'R': (1, 0)}[c]\n    nx, ny = x + dx, y + dy\n    if 0 <= nx < width and 0 <= ny < height:\n        x, y = nx, ny\nreturn [x, y]", [[1 + i % 4, 1 + i % 3, s] for i, s in enumerate(COMMANDS)], lambda w, h, s: moving(s, bounds=(w, h))[0])
    clock_cases = [[h, m, n] for h, m, n in [(23, 59, 1), (0, 0, 0), (12, 30, 30), (1, 59, 2), (23, 0, 120), (0, 1, 1439), (10, 10, 1440), (5, 5, 3000), (20, 20, 100), (7, 0, 60), (0, 0, 100000), (22, 58, 3)]]
    add(1, "시계 계산", [("hour", "int"), ("minute", "int"), ("added", "int")], "int[]", "현재 시각에 `added`분을 더한 뒤 24시간제 시각을 `[시,분]`으로 반환하세요. 날짜가 바뀌면 0시부터 다시 셉니다.", "- 0 ≤ hour ≤ 23, 0 ≤ minute ≤ 59\n- 0 ≤ added ≤ 1000000", "total = (hour * 60 + minute + added) % 1440\nreturn [total // 60, total % 60]", clock_cases, lambda h, m, a: list(divmod((h * 60 + m + a) % (24 * 60), 60)))
    dates = [[1, 1], [1, 31], [2, 1], [2, 28], [3, 1], [4, 30], [6, 15], [7, 1], [8, 31], [10, 10], [11, 30], [12, 31]]
    add(1, "날짜 계산", [("month", "int"), ("day", "int")], "int", "평년의 유효한 월·일을 받아 1월 1일을 1일째로 셌을 때 해당 연도의 몇 번째 날인지 반환하세요. 2월은 28일까지입니다.", "- 1 ≤ month ≤ 12\n- day는 해당 월의 유효한 일", "days = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]\nreturn sum(days[:month - 1]) + day", dates, lambda m, d: sum(calendar.monthrange(2023, i)[1] for i in range(1, m)) + d)
    add(1, "달력의 요일", [("month", "int"), ("day", "int")], "string", "1월 1일이 월요일인 평년에서 해당 날짜의 요일을 MON,TUE,WED,THU,FRI,SAT,SUN 중 하나로 반환하세요.", "- 1 ≤ month ≤ 12\n- day는 해당 월의 유효한 일\n- 2월은 28일", "days = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]\nreturn ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'][(sum(days[:month - 1]) + day - 1) % 7]", dates, lambda m, d: ("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")[(sum(calendar.monthrange(2018, i)[1] for i in range(1, m)) + d - 1) % 7])
    elevators = [[i, [x % 7 - 3 for x in a]] for i, a in enumerate(ARRAYS)]
    add(1, "엘리베이터", [("start", "int"), ("moves", "int[]")], "int", "시작 층 `start`에서 각 명령의 정수만큼 이동하고 최종 층을 반환하세요. 양수는 위로, 음수는 아래로 이동하며 0층과 음수 층도 있습니다.", "- -1000 ≤ start ≤ 1000\n- 0 ≤ 명령 수 ≤ 1000\n- 각 이동량의 절댓값 ≤ 1000", "floor = start\nfor move in moves:\n    floor += move\nreturn floor", elevators, lambda start, moves: start + sum(moves))
    bounded_elevators = [[0, -2, 5, moves] for _, moves in elevators]
    add(1, "엘리베이터 1", [("start", "int"), ("minimum", "int"), ("maximum", "int"), ("moves", "int[]")], "int", "각 정수 이동 명령을 처리하되 이동 결과가 `[minimum,maximum]` 밖이면 명령 전체를 무시하세요. 최종 층을 반환합니다.", "- -1000 ≤ minimum ≤ start ≤ maximum ≤ 1000\n- 명령 수 0 이상 1000 이하\n- 이동량 절댓값 ≤ 1000", "floor = start\nfor move in moves:\n    candidate = floor + move\n    if minimum <= candidate <= maximum:\n        floor = candidate\nreturn floor", bounded_elevators, elevator_oracle)
    dice = [[a, b] for a, b in [(1, 1), (1, 2), (2, 2), (3, 4), (6, 6), (6, 1), (2, 5), (4, 4), (5, 6), (3, 3), (1, 6), (5, 5)]]
    add(1, "주사위 게임", [("first", "int"), ("second", "int")], "int", "두 주사위 눈의 합에 따라 점수를 반환하세요. 합이 7이면 1000점, 합이 10 이상이면 500점, 나머지는 합의 10배입니다. 같은 눈인지와 관계없이 이 점수표만 적용합니다.", "- 1 ≤ first,second ≤ 6", "total = first + second\nif total == 7:\n    return 1000\nif total >= 10:\n    return 500\nreturn total * 10", dice, lambda a, b: {2: 20, 3: 30, 4: 40, 5: 50, 6: 60, 7: 1000, 8: 80, 9: 90, 10: 500, 11: 500, 12: 500}[a + b])
    rps = [[a, b] for a in ("rock", "paper", "scissors") for b in ("rock", "paper", "scissors")] + [["rock", "rock"]]
    # 3×3의 입력 공간이 9개뿐인 문제는 전수 검사에 보조 빈도/동률 케이스를 사용하지 않는다.
    # 함수에 경기 수를 넣지 않기 위해 이 문제만 모든 9개 입력을 허용한다.
    rps.append(["rock", "scissors"])
    add_small_domain(1, "가위바위보", [("first", "string"), ("second", "string")], "int", "두 플레이어의 선택 rock(바위),paper(보),scissors(가위)가 주어집니다. 비기면 0, 첫 플레이어가 이기면 1, 두 번째가 이기면 2를 반환하세요.", "- 각 인자는 rock,paper,scissors 중 하나", "if first == second:\n    return 0\nreturn 1 if (first, second) in [('rock', 'scissors'), ('scissors', 'paper'), ('paper', 'rock')] else 2", rps[:9], lambda a, b: 0 if a == b else 1 if (a, b) in {("rock", "scissors"), ("paper", "rock"), ("scissors", "paper")} else 2)
    round_cases = [[[], []], [["rock"], ["rock"]], [["rock"], ["scissors"]], [["rock"], ["paper"]]] + [[["rock", "paper", "scissors"] * i, ["scissors", "rock", "paper"] * i] for i in range(1, 9)]
    add(1, "가위바위보 1", [("first", "string[]"), ("second", "string[]")], "int[]", "같은 길이의 두 배열에 라운드별 선택 rock,paper,scissors가 담겨 있습니다. 무승부는 제외하고 두 플레이어의 승리 횟수를 `[첫 승수,둘째 승수]`로 반환하세요.", "- 두 배열 길이가 같으며 0 이상 1000 이하\n- 각 원소는 rock,paper,scissors 중 하나", "wins = [0, 0]\nfor a, b in zip(first, second):\n    if a == b:\n        continue\n    winner = 0 if (a, b) in [('rock', 'scissors'), ('scissors', 'paper'), ('paper', 'rock')] else 1\n    wins[winner] += 1\nreturn wins", round_cases, lambda a, b: [sum((x, y) in {("rock", "scissors"), ("paper", "rock"), ("scissors", "paper")} for x, y in zip(a, b)), sum((y, x) in {("rock", "scissors"), ("paper", "rock"), ("scissors", "paper")} for x, y in zip(a, b))])
    add(1, "좌표 이동 기록", [("commands", "string")], "int[][]", "(0,0)에서 U:y+1,D:y-1,L:x-1,R:x+1 명령을 처리하고 각 명령 직후의 `[x,y]`를 순서대로 담은 2차원 배열을 반환하세요. 시작 좌표는 기록에 포함하지 않습니다.", "- 명령 길이 0 이상 1000 이하", "x = y = 0\nresult = []\nfor command in commands:\n    dx, dy = {'U': (0, 1), 'D': (0, -1), 'L': (-1, 0), 'R': (1, 0)}[command]\n    x += dx\n    y += dy\n    result.append([x, y])\nreturn result", [[s] for s in COMMANDS], lambda s: [[s[:i].count("R") - s[:i].count("L"), s[:i].count("U") - s[:i].count("D")] for i in range(1, len(s) + 1)])
    change_cases = [[p, p + change] for p, change in [(100, 760), (0, 0), (100, 10), (50, 50), (0, 100), (30, 500), (100, 990), (10, 40), (1000, 10000), (50, 330), (100, 120), (300, 570)]]
    add(1, "자판기 계산", [("price", "int"), ("paid", "int")], "int[]", "거스름돈을 500,100,50,10원 동전으로 최소 개수로 만들고 각 동전 개수를 그 순서의 배열로 반환하세요. 투입액은 가격 이상입니다.", "- 0 ≤ price ≤ paid ≤ 1000000\n- price,paid는 10의 배수", "change = paid - price\nresult = []\nfor coin in [500, 100, 50, 10]:\n    count, change = divmod(change, coin)\n    result.append(count)\nreturn result", change_cases, lambda p, m: [(m - p) // 500, (m - p) % 500 // 100, (m - p) % 100 // 50, (m - p) % 50 // 10])
    add(1, "점수 누적", [("start", "int"), ("changes", "int[]")], "int", "시작 점수에서 획득 점수(양수)와 차감 점수(음수) 명령을 순서대로 더한 최종 점수를 반환하세요. 중간 점수와 최종 점수는 음수여도 됩니다.", "- -1000 ≤ start ≤ 1000\n- 0 ≤ 명령 수 ≤ 1000\n- 각 변화량 절댓값 ≤ 1000", "score = start\nfor change in changes:\n    score += change\nreturn score", elevators, lambda s, a: sum([s] + a))
    passwords = [["secret", a] for a in [[], ["secret"], ["x"], ["x", "secret"], ["x", "x", "secret"], ["x", "x", "x", "secret"], ["Secret"], ["secret", "x"], ["", "secret"], ["x", "y", "z"], ["x", "y", "secret", "x"], ["secret", "secret"]]]
    add(1, "비밀번호 입력", [("password", "string"), ("attempts", "string[]")], "bool", "기록의 처음 최대 3회 시도 중 비밀번호와 정확히 일치한 문자열이 있는지 반환하세요. 4번째 이후의 성공은 인정하지 않으며 대소문자를 구분합니다.", "- 비밀번호·각 시도 길이 1 이상 100 이하(실패 시도에는 빈 문자열도 가능)\n- 0 ≤ 시도 수 ≤ 100", "return password in attempts[:3]", passwords, lambda p, a: any(p == a[i] for i in range(min(3, len(a)))))
    parking = [[entry, entry + minutes] for entry, minutes in [(0, 1), (0, 30), (10, 31), (20, 40), (30, 41), (100, 60), (0, 0), (60, 29), (100, 100), (0, 1000), (1440, 2880), (10, 32)]]
    add(1, "간단한 주차장", [("entered", "int"), ("left", "int")], "int", "입차·출차 시각은 같은 기준에서 센 누적 분입니다. 기본 30분까지 1000원, 초과한 매 10분(일부도 올림)마다 500원입니다. 체류 시간이 0분이어도 기본 요금을 부과합니다. 총 요금을 반환하세요.", "- 0 ≤ entered ≤ left ≤ 1000000", "duration = left - entered\nreturn 1000 + max(0, (duration - 30 + 9) // 10) * 500", parking, lambda a, b: 1000 + 500 * math.ceil(max(0, b - a - 30) / 10))
    matrix_pairs = [[m, [[v % 5 - 2 for v in row] for row in m]] for m in MATRICES]
    add(1, "행렬의 합", [("first", "int[][]"), ("second", "int[][]")], "int[][]", "같은 크기의 두 행렬에서 같은 위치의 원소를 더한 행렬을 반환하세요.", "- 1 ≤ 행·열 수 ≤ 50\n- 각 원소의 절댓값 ≤ 10000\n- 각 행의 길이가 같은 직사각형 행렬", "return [[a + b for a, b in zip(row_a, row_b)] for row_a, row_b in zip(first, second)]", matrix_pairs, lambda a, b: [[a[r][c] + b[r][c] for c in range(len(a[0]))] for r in range(len(a))])
    add(1, "행렬의 차", [("first", "int[][]"), ("second", "int[][]")], "int[][]", "같은 크기의 두 행렬에서 첫 행렬 원소에서 둘째 행렬의 같은 위치 원소를 뺀 행렬을 반환하세요.", "- 1 ≤ 행·열 수 ≤ 50\n- 각 원소의 절댓값 ≤ 10000\n- 각 행의 길이가 같은 직사각형 행렬", "return [[first[r][c] - second[r][c] for c in range(len(first[0]))] for r in range(len(first))]", matrix_pairs, lambda a, b: [[x + -y for x, y in zip(ar, br)] for ar, br in zip(a, b)])
    add(1, "행렬의 행 합", [("matrix", "int[][]")], "int[]", "각 행의 원소 합을 위쪽 행부터 순서대로 배열로 반환하세요.", matrix_limits(), "return [sum(row) for row in matrix]", [[m] for m in MATRICES], lambda m: [sum(m[r][c] for c in range(len(m[0]))) for r in range(len(m))])
    add(1, "행렬의 열 합", [("matrix", "int[][]")], "int[]", "각 열의 원소 합을 왼쪽 열부터 순서대로 배열로 반환하세요.", matrix_limits(), "return [sum(row[c] for row in matrix) for c in range(len(matrix[0]))]", [[m] for m in MATRICES], lambda m: list(map(sum, zip(*m))))
    add(1, "행렬 전치", [("matrix", "int[][]")], "int[][]", "n×m 행렬의 행과 열을 교환하여 m×n 전치 행렬을 반환하세요. 원래 (r,c)의 값은 결과 (c,r)에 놓입니다.", matrix_limits(), "return [list(column) for column in zip(*matrix)]", [[m] for m in MATRICES], lambda m: [[m[r][c] for r in range(len(m))] for c in range(len(m[0]))])
    squares = [[[i + r * n for i in range(n)] for r in range(n)] for n in range(1, 11)] + [[[1, 2], [3, -4]], [[-1]]]
    add(1, "행렬의 대각선 합", [("matrix", "int[][]")], "int", "정사각 행렬의 왼쪽 위에서 오른쪽 아래로 이어지는 주대각선 원소 합을 반환하세요.", "- 1 ≤ 행·열 수 ≤ 50\n- 각 원소의 절댓값 ≤ 10000", "return sum(matrix[i][i] for i in range(len(matrix)))", [[m] for m in squares], lambda m: sum(v for r, row in enumerate(m) for c, v in enumerate(row) if r == c))
    add(1, "행렬 테두리 합", [("matrix", "int[][]")], "int", "첫 행·마지막 행·첫 열·마지막 열에 있는 원소의 합을 반환하세요. 모서리나 행·열 수가 1인 경우에도 각 칸을 정확히 한 번만 더합니다.", matrix_limits(), "rows, cols = len(matrix), len(matrix[0])\nreturn sum(matrix[r][c] for r in range(rows) for c in range(cols) if r in (0, rows - 1) or c in (0, cols - 1))", [[m] for m in MATRICES], lambda m: sum(map(sum, m)) - sum(sum(row[1:-1]) for row in m[1:-1]))
    add(1, "가장 큰 행", [("matrix", "int[][]")], "int", "원소 합이 가장 큰 행의 인덱스를 반환하세요. 합이 같으면 인덱스가 가장 작은 행을 선택하세요.", matrix_limits(), "sums = [sum(row) for row in matrix]\nreturn sums.index(max(sums))", [[m] for m in MATRICES], lambda m: min(range(len(m)), key=lambda r: (-sum(m[r]), r)))
    add(1, "가장 큰 열", [("matrix", "int[][]")], "int", "원소 합이 가장 큰 열의 인덱스를 반환하세요. 합이 같으면 인덱스가 가장 작은 열을 선택하세요.", matrix_limits(), "sums = [sum(row[c] for row in matrix) for c in range(len(matrix[0]))]\nreturn sums.index(max(sums))", [[m] for m in MATRICES], lambda m: min(range(len(m[0])), key=lambda c: (-sum(row[c] for row in m), c)))
    add(1, "행렬 뒤집기", [("matrix", "int[][]")], "int[][]", "행렬을 상하 반전하여 반환하세요. 행의 순서만 역순으로 바꾸고 각 행 내부 원소 순서는 유지합니다.", matrix_limits(), "return matrix[::-1]", [[m] for m in MATRICES], lambda m: [m[len(m) - r - 1][:] for r in range(len(m))])
    targets = [[a, i % 7 - 2] for i, a in enumerate(ARRAYS)] + [[[1, 1], 2], [[1], 2], [[0, 0, 0], 0]]
    add(1, "두 수의 합 찾기", [("numbers", "int[]"), ("target", "int")], "bool", "서로 다른 두 인덱스의 원소 합이 target인 경우가 존재하는지 반환하세요. 값은 같아도 인덱스가 다르면 됩니다.", "- 0 ≤ 배열 길이 ≤ 1000\n- 각 원소·target의 절댓값 ≤ 1000000", "for i in range(len(numbers)):\n    for j in range(i + 1, len(numbers)):\n        if numbers[i] + numbers[j] == target:\n            return True\nreturn False", targets, lambda a, t: any(sum(pair) == t for pair in itertools.combinations(a, 2)))
    add(1, "세 수의 합 찾기", [("numbers", "int[]"), ("target", "int")], "bool", "서로 다른 세 인덱스의 원소 합이 target인 경우가 존재하는지 반환하세요.", "- 0 ≤ 배열 길이 ≤ 100\n- 각 원소·target의 절댓값 ≤ 1000000", "for i in range(len(numbers)):\n    for j in range(i + 1, len(numbers)):\n        for k in range(j + 1, len(numbers)):\n            if numbers[i] + numbers[j] + numbers[k] == target:\n                return True\nreturn False", targets, lambda a, t: any(sum(triple) == t for triple in itertools.combinations(a, 3)))
    add(1, "연속 구간 합", [("numbers", "int[]"), ("target", "int")], "bool", "길이 1 이상인 연속 부분 배열의 합이 target인 경우가 존재하는지 반환하세요. 음수·0도 들어 있으므로 빈 구간은 답에 포함하지 않습니다.", "- 0 ≤ 배열 길이 ≤ 1000\n- 각 원소·target의 절댓값 ≤ 1000000", "for left in range(len(numbers)):\n    total = 0\n    for right in range(left, len(numbers)):\n        total += numbers[right]\n        if total == target:\n            return True\nreturn False", targets, lambda a, t: any(sum(a[i:j]) == t for i in range(len(a)) for j in range(i + 1, len(a) + 1)))
    grids = [[w, h] for w, h in [(1, 1), (1, 2), (2, 2), (3, 2), (0, 5), (5, 0), (0, 0), (3, 3), (10, 10), (1, 10), (10, 1), (100, 1)]]
    add(1, "직사각형 개수", [("width", "int"), ("height", "int")], "int", "정수 격자점 `0≤x≤width`, `0≤y≤height` 중 네 꼭짓점을 골라 만들 수 있는 축에 평행한 직사각형의 개수를 반환하세요. 폭·높이가 0인 도형은 제외합니다.", "- 0 ≤ width,height ≤ 200", "return width * (width + 1) // 2 * (height * (height + 1) // 2)", grids, lambda w, h: math.comb(w + 1, 2) * math.comb(h + 1, 2))
    add(1, "최소 이동 횟수", [("x", "int"), ("y", "int")], "int", "좌표 (0,0)에서 (x,y)까지 상하좌우로 한 칸씩 이동할 때 필요한 최소 이동 횟수를 반환하세요. 장애물은 없습니다.", "- -1000000 ≤ x,y ≤ 1000000", "return abs(x) + abs(y)", [[x, y] for x, y in [(3, 4), (0, 0), (-3, 4), (3, -4), (-3, -4), (1, 0), (0, -1), (10, 1), (-100, 100), (1000000, 1000000), (7, -2), (0, 999)]], lambda x, y: max(x, -x) + max(y, -y))


def fibonacci(n):
    values = [0, 1]
    for _ in range(n):
        values.append(sum(values[-2:]))
    return values[n]


def elevator_oracle(start, minimum, maximum, moves):
    for move in moves:
        start += move if minimum <= start + move <= maximum else 0
    return start


def matrix_limits():
    return "- 1 ≤ 행·열 수 ≤ 50\n- 각 행의 길이가 같은 직사각형 행렬\n- 각 원소의 절댓값 ≤ 10000"


def add_small_domain(*args):
    # 단판 가위바위보는 유효한 입력 공간 전체가 9개이다.
    return add(*args)


DOMAIN_EXHAUSTIVE = {1154: 9}


def factor_oracle(n):
    factors = []
    for p in range(2, math.isqrt(n) + 1):
        if not prime(p):
            continue
        while n % p == 0:
            factors.append(p)
            n //= p
    if n > 1:
        factors.append(n)
    return factors


def longest_palindrome(text):
    return max((j - i for i in range(len(text) + 1) for j in range(i, len(text) + 1) if text[i:j] == text[i:j][::-1]), default=0)


def group_anagrams(words):
    groups = {}
    for word in words:
        groups.setdefault("".join(sorted(word)), []).append(word)
    return sorted([sorted(group) for group in groups.values()])


def level2():
    add(2, "소수의 개수 1", [("n", "int")], "int", "에라토스테네스의 체를 사용하여 1부터 n까지의 소수 개수를 반환하세요.", "- 1 ≤ n ≤ 1000000\n- 반복적인 개별 소수 판별보다 체를 이용한 O(n log log n) 풀이를 권장합니다.", "if n < 2:\n    return 0\nsieve = bytearray(b'\\x01') * (n + 1)\nsieve[0] = sieve[1] = 0\nfor p in range(2, int(n ** 0.5) + 1):\n    if sieve[p]:\n        sieve[p * p:n + 1:p] = b'\\x00' * ((n - p * p) // p + 1)\nreturn sum(sieve)", ints([1, 2, 3, 4, 10, 25, 49, 100, 199, 1000, 2000, 10000]), lambda n: sum(prime(i) for i in range(2, n + 1)))
    add(2, "소인수분해", [("n", "int")], "int[]", "양의 정수 n을 소인수분해한 소인수를 중복을 포함하여 오름차순으로 반환하세요. n=1이면 빈 배열입니다.", "- 1 ≤ n ≤ 1000000000", "factors = []\nd = 2\nwhile d * d <= n:\n    while n % d == 0:\n        factors.append(d)\n        n //= d\n    d += 1\nif n > 1:\n    factors.append(n)\nreturn factors", ints([12, 97, 1, 2, 4, 18, 49, 64, 360, 99991, 1000000000, 1001]), factor_oracle)
    positive_arrays = [[1], [12, 18], [7, 13], [6, 6, 6], [12, 18, 24], [1, 99, 100], [100, 50, 25], [17, 34, 51], [9, 27, 81], [2, 3, 5, 7], [144, 96, 48, 240], [1000000, 500000]]
    add(2, "최대공약수 1", [("numbers", "int[]")], "int", "최대 10만 개 양의 정수 전체의 최대공약수를 반환하세요.", "- 1 ≤ 배열 길이 ≤ 100000\n- 1 ≤ 각 원소 ≤ 1000000000", "import math\nanswer = 0\nfor value in numbers:\n    answer = math.gcd(answer, value)\nreturn answer", [[a] for a in positive_arrays], lambda a: math.gcd(*a))
    lcm_arrays = [[1], [2, 3], [4, 6], [6, 6, 6], [12, 18, 24], [1, 9, 10], [100, 50, 25], [17, 34, 51], [9, 27, 81], [2, 3, 5, 7], [16, 24, 40], [7, 11, 13]]
    add(2, "최소공배수 1", [("numbers", "int[]")], "int", "양의 정수 배열 전체의 최소공배수를 반환하세요. 모든 원소를 나누어떨어지게 하는 가장 작은 양의 정수입니다.", "- 1 ≤ 배열 길이 ≤ 100000\n- 1 ≤ 각 원소 ≤ 1000000\n- 전체 최소공배수는 2147483647 이하임이 보장됩니다.", "import math\nanswer = 1\nfor value in numbers:\n    answer = answer // math.gcd(answer, value) * value\nreturn answer", [[a] for a in lcm_arrays], lambda a: math.lcm(*a))
    add(2, "약수의 개수 1", [("n", "int")], "int[]", "1부터 n까지 각 정수의 양의 약수 개수를 배열로 반환하세요. 결과의 i번째 원소는 i+1의 약수 개수입니다.", "- 1 ≤ n ≤ 100000", "counts = [0] * (n + 1)\nfor divisor in range(1, n + 1):\n    for multiple in range(divisor, n + 1, divisor):\n        counts[multiple] += 1\nreturn counts[1:]", ints([1, 2, 3, 4, 6, 10, 16, 25, 36, 64, 100, 200]), lambda n: [divisor_count(i) for i in range(1, n + 1)])
    add(2, "피보나치 수열 1", [("n", "int")], "int", "F(0)=0,F(1)=1,F(n)=F(n-1)+F(n-2)일 때 F(n)을 1000000007로 나눈 나머지를 반환하세요.", "- 0 ≤ n ≤ 1000000\n- 전체 피보나치 수를 저장하지 말고 각 단계에서 나머지를 계산할 수 있습니다.", "a, b = 0, 1\nfor _ in range(n):\n    a, b = b, (a + b) % 1000000007\nreturn a", ints([0, 1, 2, 3, 10, 46, 50, 100, 1000, 10000, 100000, 1000000]), fib_mod_oracle)
    choose_cases = [[n, r] for n, r in [(5, 2), (0, 0), (1, 0), (1, 1), (10, 3), (10, 10), (30, 15), (12, 6), (20, 1), (20, 19), (7, 3), (8, 4)]]
    add(2, "조합의 개수", [("n", "int"), ("r", "int")], "int", "서로 다른 n개에서 순서 없이 r개를 고르는 경우의 수를 반환하세요. 0개를 고르는 경우는 1가지입니다.", "- 0 ≤ r ≤ n ≤ 30", "r = min(r, n - r)\nanswer = 1\nfor i in range(1, r + 1):\n    answer = answer * (n - r + i) // i\nreturn answer", choose_cases, math.comb)
    perm_cases = [[n, r] for n, r in [(5, 2), (0, 0), (1, 0), (1, 1), (10, 3), (10, 10), (12, 12), (12, 6), (8, 1), (8, 7), (7, 3), (8, 4)]]
    add(2, "순열의 개수", [("n", "int"), ("r", "int")], "int", "서로 다른 n개에서 r개를 골라 순서 있게 나열하는 경우의 수를 반환하세요. 아무것도 고르지 않는 경우는 1가지입니다.", "- 0 ≤ r ≤ n ≤ 12", "answer = 1\nfor value in range(n - r + 1, n + 1):\n    answer *= value\nreturn answer", perm_cases, math.perm)
    add(2, "이진수 변환", [("n", "int")], "string", "0 이상 정수를 접두사 0b와 불필요한 앞쪽 0 없이 이진수 문자열로 변환하세요. 0은 문자열 0으로 반환합니다.", "- 0 ≤ n ≤ 2147483647", "return bin(n)[2:]", ints([0, 1, 2, 3, 7, 8, 15, 16, 31, 1024, 1000000, 2147483647]), lambda n: convert_base(n, 2))
    bases = [["101", 2], ["FF", 16], ["0", 2], ["0010", 10], ["77", 8], ["123", 4], ["f", 16], ["ZZ".replace("Z", "a"), 16], ["12345", 10], ["11111111", 2], ["21", 3], ["7fffffff", 16]]
    add(2, "진법 변환", [("text", "string"), ("base", "int")], "int", "2~16진수 문자열을 10진수 정수로 변환하세요. A~F와 a~f는 같은 값이며 앞쪽 0은 허용합니다. 부호나 진법 접두사는 없습니다.", "- 2 ≤ base ≤ 16\n- text는 해당 진법의 유효한 1~31글자 숫자\n- 변환한 값은 0 이상 2147483647 이하", "return int(text, base)", bases, parse_base_oracle)
    add(2, "진법 변환 1", [("n", "int"), ("base", "int")], "string", "10진수 정수를 2~16진수 문자열로 변환하세요. 영문 숫자는 대문자 A~F를 쓰고 접두사와 불필요한 앞쪽 0은 붙이지 않습니다. 0은 문자열 0입니다.", "- 0 ≤ n ≤ 2147483647\n- 2 ≤ base ≤ 16", "if n == 0:\n    return '0'\ndigits = '0123456789ABCDEF'\nresult = ''\nwhile n:\n    n, remainder = divmod(n, base)\n    result = digits[remainder] + result\nreturn result", [[n, b] for n, b in [(5, 2), (255, 16), (0, 2), (10, 10), (63, 8), (27, 3), (15, 16), (1000, 7), (31, 2), (2147483647, 16), (100, 5), (16, 16)]], convert_base)
    add(2, "다음 소수", [("n", "int")], "int", "n보다 큰 정수 중 가장 작은 소수를 반환하세요. n이 소수여도 그 다음 소수를 찾아야 합니다.", "- 0 ≤ n ≤ 1000000", "candidate = max(2, n + 1)\nwhile True:\n    if all(candidate % d for d in range(2, int(candidate ** 0.5) + 1)):\n        return candidate\n    candidate += 1", ints([0, 1, 2, 3, 4, 8, 13, 24, 97, 100, 1000, 999983]), lambda n: next(x for x in range(n + 1, max(100, 2 * n + 10)) if prime(x)))
    add(2, "완전수 판별", [("n", "int")], "bool", "자기 자신을 제외한 양의 약수의 합이 n과 같으면 true를 반환하세요. 1은 완전수가 아닙니다.", "- 1 ≤ n ≤ 1000000", "if n == 1:\n    return False\ntotal = 1\nd = 2\nwhile d * d <= n:\n    if n % d == 0:\n        total += d\n        if d * d != n:\n            total += n // d\n    d += 1\nreturn total == n", ints([1, 2, 6, 12, 28, 100, 496, 8128, 36, 97, 999, 10000]), lambda n: sum(d for d in range(1, n) if n % d == 0) == n)
    pow_cases = [[a, b, m] for a, b, m in [(2, 10, 1000), (5, 0, 7), (0, 0, 5), (0, 5, 7), (2, 30, 1000000007), (10, 10, 9), (123, 456, 1009), (7, 1000000000, 13), (1, 99999, 1), (999999937, 999999, 1000000007), (3, 15, 11), (10, 1, 3)]]
    add(2, "나머지 연산", [("base", "int"), ("exponent", "int"), ("modulus", "int")], "int", "base의 exponent제곱을 modulus로 나눈 나머지를 반환하세요. exponent=0이면 0의 0제곱도 1로 정의한 후 나머지를 취합니다. 반복 제곱법으로 큰 지수를 처리하세요.", "- 0 ≤ base ≤ 1000000000\n- 0 ≤ exponent ≤ 1000000000\n- 1 ≤ modulus ≤ 1000000007\n- 중간 곱셈에는 64비트 정수를 사용하세요.", "answer = 1 % modulus\nbase %= modulus\nwhile exponent:\n    if exponent & 1:\n        answer = answer * base % modulus\n    base = base * base % modulus\n    exponent //= 2\nreturn answer", pow_cases, pow)
    fractions = [[a, b, c, d] for a, b, c, d in [(1, 2, 1, 3), (1, 2, 1, 2), (0, 1, 0, 5), (-1, 2, 1, 2), (-1, 3, -2, 3), (2, 4, 3, 6), (1, 7, 2, 3), (5, 1, 3, 1), (1, 100, 1, 1000), (100, 99, -1, 99), (3, 10, 7, 10), (999, 1000, 1, 1000)]]
    add(2, "분수의 덧셈", [("firstNumerator", "int"), ("firstDenominator", "int"), ("secondNumerator", "int"), ("secondDenominator", "int")], "int[]", "두 분수를 더해 기약분수 `[분자,분모]`로 반환하세요. 분모는 양수이며 결과가 0이면 [0,1]입니다.", "- 각 분자의 절댓값 ≤ 1000\n- 1 ≤ 각 분모 ≤ 1000", "import math\nnumerator = firstNumerator * secondDenominator + secondNumerator * firstDenominator\ndenominator = firstDenominator * secondDenominator\ng = math.gcd(abs(numerator), denominator)\nreturn [numerator // g, denominator // g]", fractions, fraction_oracle)
    compression_texts = ["", "a", "aa", "aaa", "ab", "aabb", "aaaaaaaaaa", "aabbbcccc", "abcabc", "zzzzx", "abababab", "aabbaa"]
    add(2, "문자열 압축 1", [("text", "string")], "string", "연속된 같은 문자를 문자와 반복 횟수(1도 표시)로 바꾸어 연결하세요. 압축 결과의 길이가 원본보다 엄격히 짧을 때만 압축 결과를 반환하고, 같거나 더 길면 원본을 반환하세요.", "- 영문 소문자 문자열\n- 0 ≤ 길이 ≤ 100000", "result = ''\ni = 0\nwhile i < len(text):\n    j = i + 1\n    while j < len(text) and text[j] == text[i]:\n        j += 1\n    result += text[i] + str(j - i)\n    i = j\nreturn result if len(result) < len(text) else text", [[s] for s in compression_texts], lambda s: min((s, rle(s)), key=lambda x: len(x)))
    block_cases = [["abababc", 2], ["abcabcabc", 3], ["", 1], ["a", 2], ["aaaa", 1], ["aaaa", 2], ["abcde", 2], ["abcabcab", 3], ["zzzzzz", 4], ["abc", 10], ["abababab", 2], ["aabbaabb", 4]]
    add(2, "문자열 압축 2", [("text", "string"), ("k", "int")], "string", "문자열을 앞에서부터 길이 k 블록으로 나눕니다. 마지막 블록은 k보다 짧아도 됩니다. 연속된 같은 블록을 `블록 + 반복 횟수`로 바꾸어 반환하세요. 횟수가 1이어도 표시하며 원본과 길이를 비교하지 않습니다. 예: abababc,k=2 → ab3c1.", "- 영문 소문자 문자열\n- 0 ≤ 길이 ≤ 100000\n- 1 ≤ k ≤ 1000", "blocks = [text[i:i + k] for i in range(0, len(text), k)]\nresult = ''\ni = 0\nwhile i < len(blocks):\n    j = i + 1\n    while j < len(blocks) and blocks[j] == blocks[i]:\n        j += 1\n    result += blocks[i] + str(j - i)\n    i = j\nreturn result", block_cases, rle)
    palindrome_cases = ["A man, a plan, a canal: Panama", "race a car", "", "!!!", "0P", "a.", "No 'x' in Nixon", "12321", "12 3 21", "ab_ba", "A-b!a", "abc"]
    add(2, "회문 검사 2", [("text", "string")], "bool", "ASCII 영문자와 숫자가 아닌 문자를 모두 무시하고 영문 대소문자를 구분하지 않아 회문인지 판정하세요. 남은 문자열이 비어 있어도 회문입니다.", "- 0 ≤ 길이 ≤ 100000", "clean = ''.join(c.lower() for c in text if c.isalnum())\nreturn clean == clean[::-1]", [[s] for s in palindrome_cases], lambda s: (lambda c: all(c[i] == c[-i - 1] for i in range(len(c) // 2)))("".join(x.lower() for x in s if x in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789')))
    add(2, "가장 긴 회문", [("text", "string")], "int", "문자열에 포함된 가장 긴 연속 회문 부분 문자열의 길이를 반환하세요. 빈 문자열의 답은 0이고 대소문자를 구분합니다. 공백도 다른 문자와 동일하게 회문 비교에 포함합니다.", "- ASCII 문자로 구성하며 공백을 포함할 수 있습니다.\n- 0 ≤ 길이 ≤ 2000", "answer = 0\nfor center in range(len(text)):\n    for left, right in [(center, center), (center, center + 1)]:\n        while left >= 0 and right < len(text) and text[left] == text[right]:\n            answer = max(answer, right - left + 1)\n            left -= 1\n            right += 1\nreturn answer", [[s.lower()] for s in TEXTS] + [["babad"], ["cbbd"]], longest_palindrome)
    anagram_pairs = [["listen", "silent"], ["a", "a"], ["", ""], ["a", "b"], ["aab", "aba"], ["aab", "abb"], ["abc", "ab"], ["aA", "Aa"], ["a", "A"], ["123", "321"], ["ab c", "c ba"], ["zz", "z"]]
    add(2, "애너그램 판별", [("first", "string"), ("second", "string")], "bool", "두 문자열이 같은 문자들을 같은 횟수만큼 포함하는지 반환하세요. 대소문자와 공백도 서로 다른 문자로 구분합니다.", "- 각 문자열 길이 0 이상 100000 이하", "from collections import Counter\nreturn Counter(first) == Counter(second)", anagram_pairs, lambda a, b: sorted(a) == sorted(b))
    anagram_groups = [[], ["a"], ["eat", "tea", "tan", "ate", "nat", "bat"], ["", ""], ["a", "a", "b"], ["ab", "ba", "aa"], ["abc", "cab", "bca"], ["a", "A"], ["12", "21", "1"], ["zzz", "zz", "z"], ["same", "mesa", "other"], ["ab", "cd", "ef"]]
    add(2, "애너그램 그룹", [("words", "string[]")], "string[][]", "애너그램인 문자열을 같은 그룹에 묶으세요. 각 그룹 내부를 ASCII 사전순으로 정렬하고, 그룹 목록도 문자열 배열의 사전순으로 정렬하여 반환하세요(앞 원소부터 비교하며 접두 배열이 짧으면 먼저). 중복 문자열도 유지합니다.", "- 0 ≤ 문자열 수 ≤ 10000\n- 각 문자열 길이 0 이상 100 이하", "groups = {}\nfor word in words:\n    key = ''.join(sorted(word))\n    groups.setdefault(key, []).append(word)\nreturn sorted(sorted(group) for group in groups.values())", [[a] for a in anagram_groups], group_anagrams)
    freq_pairs = [[a.lower().replace(" ", ""), b.lower().replace(" ", "")] for a, b in anagram_pairs if a.isalpha() or not a] + [["abc", ""], ["", "xyz"], ["banana", "bandana"]]
    add(2, "문자열 빈도 비교", [("first", "string"), ("second", "string")], "int[]", "영문 소문자 a부터 z까지 `첫 문자열 등장 횟수 - 둘째 문자열 등장 횟수`를 길이 26 배열로 반환하세요. 음수도 허용합니다.", "- 두 문자열은 영문 소문자만 포함\n- 각 길이 0 이상 100000 이하", "counts = [0] * 26\nfor c in first:\n    counts[ord(c) - 97] += 1\nfor c in second:\n    counts[ord(c) - 97] -= 1\nreturn counts", freq_pairs, lambda a, b: [a.count(chr(97 + i)) - b.count(chr(97 + i)) for i in range(26)])
    add(2, "중복 없는 최장 부분 문자열", [("text", "string")], "int", "같은 문자가 두 번 이상 등장하지 않는 가장 긴 연속 부분 문자열의 길이를 반환하세요. 빈 문자열의 답은 0입니다.", "- 0 ≤ 길이 ≤ 100000", "seen = {}\nleft = answer = 0\nfor right, c in enumerate(text):\n    left = max(left, seen.get(c, -1) + 1)\n    seen[c] = right\n    answer = max(answer, right - left + 1)\nreturn answer", [[s] for s in TEXTS] + [["abcabcbb"], ["pwwkew"]], lambda s: max((j - i for i in range(len(s) + 1) for j in range(i, len(s) + 1) if len(set(s[i:j])) == j - i), default=0))
    add(2, "첫 번째 유일 문자", [("text", "string")], "int", "문자열 전체에서 정확히 한 번 등장하는 문자 중 가장 앞선 인덱스를 반환하세요. 그런 문자가 없으면 -1입니다.", "- 0 ≤ 길이 ≤ 100000", "from collections import Counter\ncounts = Counter(text)\nfor i, c in enumerate(text):\n    if counts[c] == 1:\n        return i\nreturn -1", [[s] for s in TEXTS], lambda s: next((i for i, c in enumerate(s) if s.count(c) == 1), -1))
    pattern_cases = [["aaaa", "aa"], ["abcabc", "abc"], ["", "a"], ["a", "a"], ["a", "aa"], ["abc", "x"], ["banana", "ana"], ["abababa", "aba"], ["aaa", "a"], ["abc", "bc"], ["ababc", "ab"], ["xyzxyz", "z"]]
    add(2, "문자열 패턴 찾기", [("text", "string"), ("pattern", "string")], "int[]", "text에서 pattern이 시작되는 모든 인덱스를 오름차순으로 반환하세요. 겹치는 등장도 포함합니다. 없으면 빈 배열입니다.", "- 0 ≤ text 길이 ≤ 100000\n- 1 ≤ pattern 길이 ≤ 1000", "return [i for i in range(len(text) - len(pattern) + 1) if text.startswith(pattern, i)]", pattern_cases, lambda s, p: [i for i in range(len(s)) if s[i:i + len(p)] == p])
    prefix_arrays = [[], ["a"], ["a", "ab"], ["ab", "cd"], ["", "a"], ["abc", "abc"], ["car", "cat", "dog"], ["foo", "foobar", "fo"], ["123", "12", "1"], ["dog", "dove"], ["a", "b", "c"], ["", ""]]
    add(2, "접두사 검사", [("words", "string[]")], "bool", "서로 다른 두 인덱스에서 한 문자열이 다른 문자열의 접두사인 경우가 존재하는지 반환하세요. 같은 문자열 두 개도 접두사 관계이며 빈 문자열은 모든 문자열의 접두사입니다.", "- 0 ≤ 문자열 수 ≤ 10000\n- 각 문자열 길이 0 이상 100 이하", "ordered = sorted(words)\nreturn any(ordered[i + 1].startswith(ordered[i]) for i in range(len(ordered) - 1))", [[a] for a in prefix_arrays], lambda a: any(i != j and a[j].startswith(a[i]) for i in range(len(a)) for j in range(len(a))))
    add(2, "공통 접두사", [("words", "string[]")], "string", "모든 문자열이 공유하는 가장 긴 접두사를 반환하세요. 배열이 비었거나 공통 접두사가 없으면 빈 문자열을 반환하세요.", "- 0 ≤ 문자열 수 ≤ 10000\n- 각 문자열 길이 0 이상 100 이하", "if not words:\n    return ''\nprefix = words[0]\nfor word in words[1:]:\n    while not word.startswith(prefix):\n        prefix = prefix[:-1]\nreturn prefix", [[a] for a in prefix_arrays], common_prefix_oracle)
    numeric_arrays = [[], ["1"], ["10", "2", "1"], ["-1", "0", "1"], ["01", "1", "+1"], ["-2", "-10", "3"], ["0", "00", "-0"], ["123", "9"], ["2147483647", "-2147483648"], ["5", "5", "05"], ["+2", "-2", "2"], ["100", "20", "3"]]
    add(2, "문자열 숫자 정렬", [("numbers", "string[]")], "string[]", "정수 형태의 문자열을 실제 정수값의 오름차순으로 정렬하되 원문을 그대로 반환하세요. 값이 같으면 입력에서의 상대 순서를 유지합니다. 선택적 +,- 부호와 앞쪽 0이 가능합니다.", "- 0 ≤ 문자열 수 ≤ 100000\n- 각 문자열은 부호 있는 32비트 정수의 유효한 표현(길이 1~20)", "return sorted(numbers, key=int)", [[a] for a in numeric_arrays], lambda a: [s for _, s in sorted(enumerate(a), key=lambda item: (int(item[1]), item[0]))])
    word_sentences = ["Hello hello WORLD", "", "   ", "a", "a b a", "Cat dog cat DOG", "one two three", "Z a z A", "same same same", "beta alpha", "  Leading  trailing ", "a aa aaa a"]
    add(2, "단어 빈도 분석", [("sentence", "string")], "string[][]", "영문자와 ASCII 공백으로 된 문장을 공백 기준으로 단어로 나누고 대소문자를 구분하지 않아 빈도를 계산하세요. 단어를 소문자 사전순으로 정렬한 뒤 `[단어, 횟수의 십진수 문자열]` 쌍의 배열로 반환하세요. 단어가 없으면 빈 배열입니다.", "- 영문자와 ASCII 공백만 포함\n- 0 ≤ 길이 ≤ 100000", "from collections import Counter\ncounts = Counter(sentence.lower().split())\nreturn [[word, str(counts[word])] for word in sorted(counts)]", [[s] for s in word_sentences], lambda s: [[w, str(s.lower().split().count(w))] for w in sorted(set(s.lower().split()))])
    add(2, "가장 많이 등장한 단어", [("sentence", "string")], "string", "영문자와 ASCII 공백 문장을 공백 기준으로 나누어 가장 자주 등장한 단어를 소문자로 반환하세요. 대소문자는 구분하지 않으며 동률이면 소문자 사전순으로 작은 단어입니다. 단어가 없으면 빈 문자열입니다.", "- 영문자와 ASCII 공백만 포함\n- 0 ≤ 길이 ≤ 100000", "from collections import Counter\ncounts = Counter(sentence.lower().split())\nreturn min(counts, key=lambda w: (-counts[w], w)) if counts else ''", [[s] for s in word_sentences], lambda s: min(set(s.lower().split()), key=lambda w: (-s.lower().split().count(w), w)) if s.split() else "")
    level2_search()
    level2_structures()
    level2_windows()
    level2_final()


def fib_mod_oracle(n):
    # 기준 풀이의 선형 순회와 독립적인 분할 정복 fast doubling.
    def pair(k):
        if k == 0:
            return 0, 1
        a, b = pair(k // 2)
        c = a * (2 * b - a) % 1000000007
        d = (a * a + b * b) % 1000000007
        return (d, (c + d) % 1000000007) if k % 2 else (c, d)
    return pair(n)[0]


def convert_base(n, base):
    if n == 0:
        return "0"
    power = 1
    while power * base <= n:
        power *= base
    result = ""
    while power:
        digit = n // power
        result += "0123456789ABCDEF"[digit]
        n -= digit * power
        power //= base
    return result


def parse_base_oracle(text, base):
    return sum("0123456789ABCDEF".index(c.upper()) * base ** i for i, c in enumerate(reversed(text)))


def fraction_oracle(a, b, c, d):
    from fractions import Fraction
    result = Fraction(a, b) + Fraction(c, d)
    return [result.numerator, result.denominator]


def common_prefix_oracle(words):
    if not words:
        return ""
    shortest = min(words, key=len)
    for length in range(len(shortest), -1, -1):
        if all(word[:length] == shortest[:length] for word in words):
            return shortest[:length]


def level2_search():
    search_cases = [[sorted(a), i % 5 - 1] for i, a in enumerate(ARRAYS)] + [[[1, 1, 2, 2, 2, 3], 2], [[1, 2, 3], 4]]
    add(2, "이진 탐색 1", [("numbers", "int[]"), ("target", "int")], "int", "비내림차순 배열에서 target이 처음 등장하는 인덱스를 이진 탐색으로 반환하세요. 없으면 -1입니다.", "- 0 ≤ 배열 길이 ≤ 100000\n- 원소·target의 절댓값 ≤ 1000000000", "import bisect\ni = bisect.bisect_left(numbers, target)\nreturn i if i < len(numbers) and numbers[i] == target else -1", search_cases, lambda a, t: a.index(t) if t in a else -1)
    add(2, "이진 탐색 2", [("numbers", "int[]"), ("target", "int")], "int", "비내림차순 배열에서 target이 마지막으로 등장하는 인덱스를 이진 탐색으로 반환하세요. 없으면 -1입니다.", "- 0 ≤ 배열 길이 ≤ 100000\n- 원소·target의 절댓값 ≤ 1000000000", "import bisect\ni = bisect.bisect_right(numbers, target) - 1\nreturn i if i >= 0 and numbers[i] == target else -1", search_cases, lambda a, t: len(a) - 1 - a[::-1].index(t) if t in a else -1)
    add(2, "Lower Bound", [("numbers", "int[]"), ("target", "int")], "int", "비내림차순 배열에서 target 이상인 값이 처음 등장하는 인덱스를 반환하세요. 그런 값이 없으면 배열 길이를 반환합니다.", "- 0 ≤ 배열 길이 ≤ 100000\n- 원소·target의 절댓값 ≤ 1000000000", "left, right = 0, len(numbers)\nwhile left < right:\n    mid = (left + right) // 2\n    if numbers[mid] < target:\n        left = mid + 1\n    else:\n        right = mid\nreturn left", search_cases, lambda a, t: sum(x < t for x in a))
    add(2, "Upper Bound", [("numbers", "int[]"), ("target", "int")], "int", "비내림차순 배열에서 target보다 큰 값이 처음 등장하는 인덱스를 반환하세요. 그런 값이 없으면 배열 길이를 반환합니다.", "- 0 ≤ 배열 길이 ≤ 100000\n- 원소·target의 절댓값 ≤ 1000000000", "left, right = 0, len(numbers)\nwhile left < right:\n    mid = (left + right) // 2\n    if numbers[mid] <= target:\n        left = mid + 1\n    else:\n        right = mid\nreturn left", search_cases, lambda a, t: sum(x <= t for x in a))
    add(2, "특정 숫자의 등장 횟수", [("numbers", "int[]"), ("target", "int")], "int", "비내림차순 배열에서 target의 등장 횟수를 두 번의 이진 탐색으로 반환하세요.", "- 0 ≤ 배열 길이 ≤ 100000\n- 원소·target의 절댓값 ≤ 1000000000", "import bisect\nreturn bisect.bisect_right(numbers, target) - bisect.bisect_left(numbers, target)", search_cases, lambda a, t: a.count(t))
    add(2, "좌표 압축", [("numbers", "int[]")], "int[]", "서로 다른 값들을 오름차순으로 나열한 순서를 0부터 부여합니다. 각 원소를 자신보다 작은 서로 다른 값의 개수로 치환한 배열을 원래 순서대로 반환하세요.", "- 0 ≤ 배열 길이 ≤ 100000\n- 각 원소의 절댓값 ≤ 1000000000", "ranks = {value: i for i, value in enumerate(sorted(set(numbers)))}\nreturn [ranks[value] for value in numbers]", [[a] for a in ARRAYS], lambda a: [sum(y < x for y in set(a)) for x in a])
    query_cases = []
    for a in NONEMPTY:
        queries = [[0, len(a) - 1, 1], [0, len(a) - 1, len(a)], [0, 0, 1]]
        query_cases.append([a, queries])
    add(2, "K번째 수 1", [("numbers", "int[]"), ("queries", "int[][]")], "int[]", "각 쿼리 `[left,right,k]`에 대해 양 끝을 포함한 0-based 구간을 오름차순 정렬했을 때 1-based k번째 값을 반환하세요. 답은 쿼리 순서의 배열입니다. 원본 배열은 쿼리 사이에 바뀌지 않습니다.", "- 1 ≤ 배열 길이 ≤ 1000\n- 0 ≤ 쿼리 수 ≤ 1000\n- 0 ≤ left ≤ right < 배열 길이\n- 1 ≤ k ≤ right-left+1\n- 각 원소의 절댓값 ≤ 1000000", "return [sorted(numbers[left:right + 1])[k - 1] for left, right, k in queries]", query_cases + [[[3, 1, 2], []]], lambda a, q: [sorted(a[l:r + 1], reverse=True)[r - l + 1 - k] for l, r, k in q])
    nearest = [[sorted(a), i - 4] for i, a in enumerate(NONEMPTY)] + [[[1, 3], 2]]
    add(2, "가장 가까운 수 1", [("numbers", "int[]"), ("target", "int")], "int", "비내림차순 배열에서 target과 차이의 절댓값이 가장 작은 값을 이진 탐색으로 찾으세요. 동률이면 작은 값을 반환합니다.", "- 1 ≤ 배열 길이 ≤ 100000\n- 각 원소·target의 절댓값 ≤ 1000000000", "import bisect\ni = bisect.bisect_left(numbers, target)\nif i == 0:\n    return numbers[0]\nif i == len(numbers):\n    return numbers[-1]\nreturn numbers[i - 1] if target - numbers[i - 1] <= numbers[i] - target else numbers[i]", nearest, lambda a, t: min(a, key=lambda x: (abs(x - t), x)))
    array_pairs = [[sorted(a), sorted(ARRAYS[-i - 1])] for i, a in enumerate(ARRAYS)]
    add(2, "정렬된 배열 합치기", [("first", "int[]"), ("second", "int[]")], "int[]", "비내림차순으로 정렬된 두 배열을 원소 누락 없이 하나의 비내림차순 배열로 병합하세요. 중복도 유지합니다.", "- 각 배열 길이 0 이상 100000 이하\n- 각 원소의 절댓값 ≤ 1000000000\n- 두 포인터를 사용한 O(n+m) 병합을 권장합니다.", "i = j = 0\nresult = []\nwhile i < len(first) and j < len(second):\n    if first[i] <= second[j]:\n        result.append(first[i])\n        i += 1\n    else:\n        result.append(second[j])\n        j += 1\nreturn result + first[i:] + second[j:]", array_pairs, lambda a, b: sorted(a + b))
    add(2, "중복 원소 제거 1", [("numbers", "int[]")], "int[][]", "비내림차순 배열의 앞부분을 제자리 덮어쓰기로 중복 제거하세요. 반환값은 `[[유효한 길이], [중복 제거된 유효 원소들]]`입니다. 뒤쪽의 무효한 원소는 반환하지 않습니다. 빈 배열은 [[0],[]]입니다.", "- 0 ≤ 배열 길이 ≤ 100000\n- 각 원소의 절댓값 ≤ 1000000000\n- 원소 이동은 입력 배열에 제자리로 구현할 수 있습니다. 채점은 반환 구조로 판정합니다.", "length = 0\nfor value in numbers:\n    if length == 0 or numbers[length - 1] != value:\n        numbers[length] = value\n        length += 1\nreturn [[length], numbers[:length]]", [[sorted(a)] for a in ARRAYS], lambda a: [[len(set(a))], sorted(set(a))])
    rankings = [[[], []], [["a"], [1]], [["bob", "amy"], [10, 10]], [["z", "a", "m"], [2, 3, 1]], [["A", "a"], [0, 0]], [["one", "two", "three"], [10, 20, 10]], [["x", "y"], [100, 0]], [["p", "q", "r"], [7, 7, 7]], [["beta", "alpha", "gamma"], [3, 3, 2]], [["a", "b", "c", "d"], [1, 2, 3, 4]], [["aa", "ab", "a"], [5, 5, 5]], [["a", "z"], [999, 1000]]]
    add(2, "순위 매기기 1", [("names", "string[]"), ("scores", "int[]")], "string[]", "같은 인덱스의 이름과 점수가 한 사람을 나타냅니다. 점수 내림차순, 동점이면 이름 ASCII 사전순으로 정렬한 이름 배열을 반환하세요.", "- 두 배열의 길이가 같으며 0 이상 100000 이하\n- 이름은 서로 다르며 영문자 1~30글자\n- 0 ≤ 점수 ≤ 1000000000", "return [name for name, score in sorted(zip(names, scores), key=lambda item: (-item[1], item[0]))]", rankings, lambda names, scores: sorted(names, key=lambda name: (-scores[names.index(name)], name)))
    interval_arrays = [[], [[1, 2]], [[1, 3], [2, 4]], [[5, 7], [1, 2]], [[1, 2], [2, 3]], [[0, 10], [2, 3], [4, 5]], [[1, 1], [1, 1]], [[-3, -1], [-1, 2]], [[3, 5], [1, 5], [2, 4]], [[1, 2], [4, 5], [7, 8]], [[10, 20], [0, 10], [20, 30]], [[0, 0], [2, 2], [1, 1]]]
    add(2, "회의 정렬", [("meetings", "int[][]")], "int[][]", "각 회의는 [시작 시각,종료 시각]입니다. 종료 시각 오름차순, 같으면 시작 시각 오름차순으로 정렬한 회의 배열을 반환하세요. 중복 회의도 유지합니다.", "- 0 ≤ 회의 수 ≤ 100000\n- 0 ≤ 시작 ≤ 종료 ≤ 1000000000", "return sorted(meetings, key=lambda item: (item[1], item[0]))", [[[list(map(abs, pair)) if pair[0] >= 0 else [0, pair[1] + 5] for pair in a]] for a in interval_arrays], lambda a: [list(x[::-1]) for x in sorted(tuple(pair[::-1]) for pair in a)])
    add(2, "구간 병합", [("intervals", "int[][]")], "int[][]", "닫힌 구간 [시작,끝]들이 겹치거나 끝점에서 맞닿으면 합치세요. 더 이상 합칠 수 없는 구간들을 시작점 오름차순으로 반환하세요. 점 하나인 구간도 유효합니다.", "- 0 ≤ 구간 수 ≤ 100000\n- -1000000000 ≤ 시작 ≤ 끝 ≤ 1000000000", "result = []\nfor start, end in sorted(intervals):\n    if result and start <= result[-1][1]:\n        result[-1][1] = max(result[-1][1], end)\n    else:\n        result.append([start, end])\nreturn result", [[a] for a in interval_arrays], merge_oracle)
    disjoint_pairs = [[[], []], [[[1, 2]], [[2, 3]]], [[[1, 3], [5, 8]], [[2, 6], [9, 10]]], [[[0, 1]], [[2, 3]]], [[], [[1, 2]]], [[[1, 2]], []], [[[0, 10]], [[1, 2], [4, 5], [7, 8]]], [[[1, 1]], [[1, 1]]], [[[-5, -2]], [[-3, 0]]], [[[1, 3], [7, 9]], [[0, 2], [5, 8]]], [[[0, 0], [2, 2]], [[0, 2]]], [[[10, 20]], [[0, 10], [20, 30]]]]
    add(2, "구간 교집합", [("first", "int[][]"), ("second", "int[][]")], "int[][]", "각 배열 내부는 시작순으로 정렬되어 있고 서로 겹치거나 맞닿지 않는 닫힌 구간들입니다. 두 배열 사이의 모든 교집합 구간을 시작순으로 반환하세요. 한 점에서 만나는 교집합도 [x,x]로 포함합니다.", "- 각 배열 길이 0 이상 100000 이하\n- 구간 끝점 절댓값 ≤ 1000000000\n- 각 구간의 시작 ≤ 끝", "i = j = 0\nresult = []\nwhile i < len(first) and j < len(second):\n    left = max(first[i][0], second[j][0])\n    right = min(first[i][1], second[j][1])\n    if left <= right:\n        result.append([left, right])\n    if first[i][1] < second[j][1]:\n        i += 1\n    else:\n        j += 1\nreturn result", disjoint_pairs, lambda a, b: sorted([[max(x[0], y[0]), min(x[1], y[1])] for x in a for y in b if max(x[0], y[0]) <= min(x[1], y[1])]))
    add(2, "두 배열의 차집합", [("first", "int[]"), ("second", "int[]")], "int[][]", "첫 배열에만 있는 값들과 둘째 배열에만 있는 값들을 각각 중복 없이 오름차순으로 반환하세요. 반환 구조는 `[첫째의 차집합,둘째의 차집합]`입니다.", "- 각 배열 길이 0 이상 100000 이하\n- 각 원소의 절댓값 ≤ 1000000000", "a, b = set(first), set(second)\nreturn [sorted(a - b), sorted(b - a)]", array_pairs, lambda a, b: [sorted(x for x in set(a) if x not in b), sorted(x for x in set(b) if x not in a)])


def merge_oracle(intervals):
    # 닫힌 구간들의 겹침 그래프 연결 성분으로 정의한다.
    unseen = set(range(len(intervals)))
    result = []
    while unseen:
        component = {unseen.pop()}
        while True:
            neighbours = {j for j in unseen if any(max(intervals[i][0], intervals[j][0]) <= min(intervals[i][1], intervals[j][1]) for i in component)}
            if not neighbours:
                break
            unseen -= neighbours
            component |= neighbours
        result.append([min(intervals[i][0] for i in component), max(intervals[i][1] for i in component)])
    return sorted(result)


def structure_oracle(commands, kind):
    values, outputs = [], []
    for command in commands:
        parts = command.split()
        op = parts[0]
        if op in ("push", "push_back"):
            values += [int(parts[1])]
        elif op == "push_front":
            values = [int(parts[1])] + values
        elif op in ("pop", "pop_front", "pop_back"):
            index = -1 if op == "pop_back" or op == "pop" and kind == "stack" else 0
            outputs.append(values.pop(index) if values else -1)
        elif op == "size":
            outputs.append(len(values))
        elif op == "empty":
            outputs.append(int(not values))
        else:
            outputs.append(values[-1 if op in ("top", "back") else 0] if values else -1)
    return outputs


def balanced_oracle(text):
    # 인접한 올바른 괄호쌍을 반복 제거하는 독립적인 명세 구현.
    while True:
        clean = text.replace("()", "").replace("[]", "").replace("{}", "")
        if clean == text:
            return not clean
        text = clean


def explode_oracle(text, pattern):
    while pattern in text:
        i = text.index(pattern)
        text = text[:i] + text[i + len(pattern):]
    return text


def adjacent_oracle(text):
    for i in range(len(text) - 1):
        if text[i] == text[i + 1]:
            return adjacent_oracle(text[:i] + text[i + 2:])
    return text


def postfix_oracle(tokens):
    def expression(index):
        token = tokens[index]
        if token not in {"+", "-", "*", "/"}:
            return int(token), index - 1
        b, index = expression(index - 1)
        a, index = expression(index)
        if token == "+":
            value = a + b
        elif token == "-":
            value = a - b
        elif token == "*":
            value = a * b
        else:
            value = abs(a) // abs(b) * (-1 if (a < 0) != (b < 0) else 1)
        return value, index
    return expression(len(tokens) - 1)[0]


def printer_oracle(priorities, location):
    queue = list(enumerate(priorities))
    count = 0
    while queue:
        index, priority = queue.pop(0)
        if any(other > priority for _, other in queue):
            queue.append((index, priority))
        else:
            count += 1
            if index == location:
                return count


def level2_structures():
    for kind, title in [("stack", "스택 구현"), ("queue", "큐 구현"), ("deque", "덱 구현")]:
        if kind == "stack":
            commands = [[], ["top"], ["pop"], ["empty", "size"], ["push 1", "top", "pop", "empty"], ["push 1", "push 2", "pop", "top"], ["push -1", "top", "size"], ["push 0", "push 0", "size", "pop"], ["push 5", "pop", "pop", "top"], ["push 3", "push 7", "top", "size", "pop", "pop"], ["empty", "push 4", "empty"], ["push 10", "push -5", "push 20", "pop", "pop", "top"]]
            allowed = "push x,pop,top,size,empty"
        elif kind == "queue":
            commands = [[], ["front", "back"], ["pop"], ["empty", "size"], ["push 1", "front", "pop", "empty"], ["push 1", "push 2", "pop", "front", "back"], ["push -1", "front", "size"], ["push 0", "push 0", "size", "pop"], ["push 5", "pop", "pop", "front"], ["push 3", "push 7", "back", "size", "pop", "pop"], ["empty", "push 4", "empty"], ["push 10", "push -5", "push 20", "pop", "pop", "front"]]
            allowed = "push x,pop,front,back,size,empty"
        else:
            commands = [[], ["front", "back"], ["pop_front", "pop_back"], ["empty", "size"], ["push_front 1", "front", "pop_back", "empty"], ["push_front 1", "push_back 2", "front", "back"], ["push_back -1", "front", "size"], ["push_front 0", "push_back 0", "size", "pop_front"], ["push_back 5", "pop_front", "pop_back", "front"], ["push_front 3", "push_back 7", "back", "size", "pop_front", "pop_back"], ["empty", "push_back 4", "empty"], ["push_front 10", "push_back -5", "push_front 20", "pop_back", "pop_front", "front"]]
            allowed = "push_front x,push_back x,pop_front,pop_back,front,back,size,empty"
        body = """from collections import deque
        values = deque()
        result = []
        for command in commands:
            parts = command.split()
            op = parts[0]
            if op == 'push_front': values.appendleft(int(parts[1]))
            elif op in ('push', 'push_back'): values.append(int(parts[1]))
            elif op == 'size': result.append(len(values))
            elif op == 'empty': result.append(1 if not values else 0)
            elif op in ('top', 'back'): result.append(values[-1] if values else -1)
            elif op == 'front': result.append(values[0] if values else -1)
            elif op == 'pop_back' or op == 'pop' and KIND == 'stack': result.append(values.pop() if values else -1)
            else: result.append(values.popleft() if values else -1)
        return result""".replace("KIND", repr(kind))
        # 첫 줄도 다른 줄과 같은 들여쓰기 수준으로 정규화한다.
        body = "\n".join(line[8:] if line.startswith("        ") else line for line in body.splitlines())
        add(2, title, [("commands", "string[]")], "int[]", f"{allowed} 명령을 순서대로 처리하세요. 삽입은 반환값이 없고, 삭제는 삭제한 값, 조회는 해당 끝의 값, size는 길이, empty는 비었으면 1 아니면 0입니다. 비어 있을 때 삭제·원소 조회 결과는 -1입니다. 결과가 있는 명령의 값만 배열로 반환하세요." + (" 스택의 pop은 마지막 삽입 값을 삭제합니다." if kind == "stack" else " 큐의 pop은 먼저 삽입한 값을 삭제합니다." if kind == "queue" else " front는 앞, back은 뒤를 뜻합니다."), "- 0 ≤ 명령 수 ≤ 100000\n- 명령은 정확한 형식으로 주어집니다.\n- 삽입 값의 절댓값 ≤ 1000000000", body, [[a] for a in commands], lambda a, k=kind: structure_oracle(a, k))
    parens = ["", "()", "(())", "()()", "(()", "())", ")(", "((()))", "(()())", "())(()", "((((", "))))"]
    add(2, "괄호 균형 검사", [("text", "string")], "bool", "소괄호만으로 된 문자열이 올바르게 닫혀 있는지 반환하세요. 모든 접두 문자열에서 닫는 괄호가 더 많으면 안 되고 전체 개수가 같아야 합니다. 빈 문자열은 올바릅니다.", "- (, )만 포함\n- 0 ≤ 길이 ≤ 100000", "balance = 0\nfor c in text:\n    balance += 1 if c == '(' else -1\n    if balance < 0:\n        return False\nreturn balance == 0", [[s] for s in parens], balanced_oracle)
    brackets = ["", "()[]{}", "([{}])", "([)]", "[(])", "{[()]}", "((", ")", "[]{}[]", "{[]}", "{[}", "([]{})"]
    add(2, "괄호 균형 검사 1", [("text", "string")], "bool", "(),[],{} 세 종류 괄호가 종류와 중첩 순서를 지키며 모두 닫혔는지 반환하세요. 빈 문자열은 올바릅니다.", "- 여섯 괄호 문자만 포함\n- 0 ≤ 길이 ≤ 100000", "stack = []\nmatching = {')': '(', ']': '[', '}': '{'}\nfor c in text:\n    if c in '([{':\n        stack.append(c)\n    elif not stack or stack.pop() != matching[c]:\n        return False\nreturn not stack", [[s] for s in brackets], balanced_oracle)
    explosions = [["mirkovC4nizCC44", "C4"], ["aaaa", "aa"], ["", "a"], ["a", "a"], ["abc", "x"], ["ababa", "aba"], ["aababa", "aba"], ["abcabc", "abc"], ["ababcc", "abc"], ["zzz", "zz"], ["axxxb", "x"], ["a", "aa"]]
    add(2, "문자열 폭발", [("text", "string"), ("pattern", "string")], "string", "문자열을 왼쪽부터 처리하며 지금까지 남은 문자열의 끝에 pattern이 나타나면 즉시 제거하세요. 제거로 새로 생기는 일치도 같은 방식으로 처리합니다. 최종 남은 문자열을 반환하고 비면 빈 문자열입니다.", "- 0 ≤ text 길이 ≤ 100000\n- 1 ≤ pattern 길이 ≤ 100", "stack = []\nfor c in text:\n    stack.append(c)\n    if len(stack) >= len(pattern) and ''.join(stack[-len(pattern):]) == pattern:\n        del stack[-len(pattern):]\nreturn ''.join(stack)", explosions, explode_oracle)
    add(2, "올바른 문자열 제거", [("text", "string")], "string", "인접한 같은 문자 두 개를 반복해서 제거하고 남은 문자열을 반환하세요. 왼쪽부터 처리하여 생기는 쌍도 제거합니다. 다 사라지면 빈 문자열입니다.", "- 영문 소문자만 포함\n- 0 ≤ 길이 ≤ 100000", "stack = []\nfor c in text:\n    if stack and stack[-1] == c:\n        stack.pop()\n    else:\n        stack.append(c)\nreturn ''.join(stack)", [[s.lower().replace(" ", "")] for s in TEXTS] + [["abbaac"], ["abbaca"]], adjacent_oracle)
    postfix = [["2", "3", "+"], ["5"], ["0"], ["2", "3", "4", "*", "+"], ["8", "3", "/"], ["-8", "3", "/"], ["8", "-3", "/"], ["-8", "-3", "/"], ["5", "7", "-"], ["2", "3", "+", "4", "*"], ["10", "2", "/", "3", "-"], ["4", "2", "*", "3", "1", "+", "+"]]
    add(2, "후위 표기식 계산", [("tokens", "string[]")], "int", "유효한 후위 표기식의 계산 결과를 반환하세요. 토큰은 정수 또는 +,-,*,/이고, 이항 연산에서 먼저 꺼낸 값은 오른쪽 피연산자입니다. 나눗셈은 0 방향으로 버림합니다. 0으로 나누지 않습니다.", "- 1 ≤ 토큰 수 ≤ 10000\n- 입력 정수·모든 중간 결과는 부호 있는 32비트 범위\n- 최종 결과가 정확히 하나인 유효 수식", "stack = []\nfor token in tokens:\n    if token not in ('+', '-', '*', '/'):\n        stack.append(int(token))\n        continue\n    b, a = stack.pop(), stack.pop()\n    if token == '+': value = a + b\n    elif token == '-': value = a - b\n    elif token == '*': value = a * b\n    else: value = (abs(a) // abs(b)) * (-1 if (a < 0) != (b < 0) else 1)\n    stack.append(value)\nreturn stack[0]", [[a] for a in postfix], postfix_oracle)
    add(2, "다음 큰 수", [("numbers", "int[]")], "int[]", "각 원소의 오른쪽에서 처음 만나는 자신보다 엄격히 큰 값을 반환하세요. 없으면 -1입니다. 원래 순서에 맞춘 배열을 반환합니다.", "- 0 ≤ 배열 길이 ≤ 100000\n- 각 원소의 절댓값 ≤ 1000000000", "result = [-1] * len(numbers)\nstack = []\nfor i, value in enumerate(numbers):\n    while stack and numbers[stack[-1]] < value:\n        result[stack.pop()] = value\n    stack.append(i)\nreturn result", [[a] for a in ARRAYS], lambda a: [next((x for x in a[i + 1:] if x > a[i]), -1) for i in range(len(a))])
    heights = [[abs(x) + 1 for x in a] for a in ARRAYS]
    add(2, "탑 신호", [("heights", "int[]")], "int[]", "각 탑은 왼쪽으로 신호를 보내며 처음 만나는 자신보다 엄격히 높은 탑이 받습니다. 수신 탑의 1-based 위치를 배열로 반환하고 없으면 0입니다. 같은 높이는 수신하지 않습니다.", "- 0 ≤ 탑 수 ≤ 100000\n- 1 ≤ 높이 ≤ 1000000000", "stack = []\nresult = []\nfor i, height in enumerate(heights):\n    while stack and heights[stack[-1]] <= height:\n        stack.pop()\n    result.append(stack[-1] + 1 if stack else 0)\n    stack.append(i)\nreturn result", [[a] for a in heights], lambda a: [next((j + 1 for j in range(i - 1, -1, -1) if a[j] > a[i]), 0) for i in range(len(a))])
    add(2, "카드 제거", [("n", "int")], "int", "1부터 n까지 카드가 앞에서부터 놓여 있습니다. 카드가 하나 남을 때까지 맨 앞 카드를 버리고, 다음 맨 앞 카드를 맨 뒤로 옮기는 두 동작을 반복하세요. 마지막 카드 번호를 반환하세요.", "- 1 ≤ n ≤ 1000000", "from collections import deque\nqueue = deque(range(1, n + 1))\nwhile len(queue) > 1:\n    queue.popleft()\n    queue.append(queue.popleft())\nreturn queue[0]", ints([1, 2, 3, 4, 5, 6, 7, 8, 9, 16, 25, 100]), lambda n: n if n & (n - 1) == 0 else 2 * (n - 2 ** (n.bit_length() - 1)))
    jobs = [[[2, 1, 3, 2], 2], [[1], 0], [[1, 1], 1], [[3, 1], 0], [[1, 3], 0], [[1, 1, 9, 1, 1, 1], 0], [[9, 8, 7], 2], [[1, 2, 3], 0], [[2, 2, 2], 2], [[4, 5, 1, 5], 1], [[9, 1, 9], 2], [[1, 2], 1]]
    add(2, "작업 대기열", [("priorities", "int[]"), ("location", "int")], "int", "맨 앞 작업을 꺼냈을 때 대기 중 더 높은 중요도가 있으면 뒤로 보내고, 없으면 처리합니다. 같은 중요도는 대기 순서를 따릅니다. 처음 location 인덱스의 작업이 몇 번째로 처리되는지 1부터 세어 반환하세요.", "- 1 ≤ 작업 수 ≤ 10000\n- 1 ≤ 중요도 ≤ 9\n- 0 ≤ location < 작업 수", "from collections import deque\nqueue = deque(enumerate(priorities))\ncounts = [0] * 10\nfor priority in priorities: counts[priority] += 1\nrank = 0\nwhile queue:\n    index, priority = queue.popleft()\n    if any(counts[p] for p in range(priority + 1, 10)):\n        queue.append((index, priority))\n    else:\n        counts[priority] -= 1\n        rank += 1\n        if index == location: return rank", jobs, printer_oracle)
    add(2, "해시 기반 중복 검사", [("numbers", "int[]")], "bool", "최대 100만 개 정수 배열에 같은 값이 두 번 이상 등장하는지 해시를 사용하여 효율적으로 반환하세요. 빈 배열은 false입니다.", "- 0 ≤ 배열 길이 ≤ 1000000\n- 각 원소는 부호 있는 32비트 정수\n- 평균 O(n) 시간 풀이를 권장합니다.", "seen = set()\nfor value in numbers:\n    if value in seen:\n        return True\n    seen.add(value)\nreturn False", [[a] for a in ARRAYS], lambda a: any(x == y for x, y in zip(sorted(a), sorted(a)[1:])))
    array_pairs = [[a, ARRAYS[-i - 1]] for i, a in enumerate(ARRAYS)]
    add(2, "두 배열의 공통 원소 1", [("first", "int[]"), ("second", "int[]")], "int[]", "해시를 사용해 두 배열의 공통 원소를 중복 없이 구하고 결과를 오름차순으로 반환하세요.", "- 각 배열 길이 0 이상 100000 이하\n- 원소의 절댓값 ≤ 1000000000", "return sorted(set(first) & set(second))", array_pairs, lambda a, b: sorted({x for x in a if x in b}))
    add(2, "빈도 기반 정렬", [("numbers", "int[]")], "int[]", "등장 빈도가 높은 값부터 정렬하고 빈도가 같으면 값이 작은 순서로 배치하세요. 각 값은 원래 등장 횟수만큼 모두 포함해야 합니다.", "- 0 ≤ 배열 길이 ≤ 100000\n- 각 원소의 절댓값 ≤ 1000000000", "from collections import Counter\ncounts = Counter(numbers)\nreturn sorted(numbers, key=lambda x: (-counts[x], x))", [[a] for a in ARRAYS], lambda a: [x for x in sorted(set(a), key=lambda x: (-a.count(x), x)) for _ in range(a.count(x))])


def level2_windows():
    targets = [[sorted(a), i % 7 - 2] for i, a in enumerate(ARRAYS)] + [[[1, 1], 2], [[1], 2], [[0, 0, 0], 0]]
    add(2, "두 수의 합 찾기 1", [("numbers", "int[]"), ("target", "int")], "bool", "비내림차순 배열에서 서로 다른 두 인덱스의 원소 합이 target인 경우가 존재하는지 투 포인터로 반환하세요.", "- 0 ≤ 배열 길이 ≤ 100000\n- 각 원소·target의 절댓값 ≤ 1000000000", "left, right = 0, len(numbers) - 1\nwhile left < right:\n    total = numbers[left] + numbers[right]\n    if total == target: return True\n    if total < target: left += 1\n    else: right -= 1\nreturn False", targets, lambda a, t: any(sum(pair) == t for pair in itertools.combinations(a, 2)))
    triplets = [[a] for a in ARRAYS] + [[[-1, 0, 1, 2, -1, -4]], [[0, 0, 0, 0]], [[-2, 0, 0, 2, 2]]]
    add(2, "세 수의 합 찾기 1", [("numbers", "int[]")], "int[][]", "서로 다른 세 인덱스의 합이 0인 모든 값의 조합을 중복 없이 반환하세요. 각 세 수를 오름차순으로 정렬하고 결과 목록도 세 수 배열의 사전순으로 정렬합니다. 같은 값이 여러 번 필요하면 입력에 그만큼 있어야 합니다.", "- 0 ≤ 배열 길이 ≤ 300\n- 각 원소의 절댓값 ≤ 1000000", "a = sorted(numbers)\nresult = []\nfor i in range(len(a) - 2):\n    if i and a[i] == a[i - 1]: continue\n    left, right = i + 1, len(a) - 1\n    while left < right:\n        total = a[i] + a[left] + a[right]\n        if total == 0:\n            result.append([a[i], a[left], a[right]])\n            left += 1\n            right -= 1\n            while left < right and a[left] == a[left - 1]: left += 1\n            while left < right and a[right] == a[right + 1]: right -= 1\n        elif total < 0: left += 1\n        else: right -= 1\nreturn result", triplets, lambda a: [list(t) for t in sorted({tuple(sorted(c)) for c in itertools.combinations(a, 3) if sum(c) == 0})])
    positive = [[abs(x) + 1 for x in a] for a in ARRAYS]
    pos_targets = [[a, i + 1] for i, a in enumerate(positive)] + [[[1, 1, 1], 2], [[2, 3, 1, 2, 4, 3], 7]]
    add(2, "연속 구간 합 1", [("numbers", "int[]"), ("target", "int")], "int", "양의 정수 배열에서 합이 정확히 target인 길이 1 이상의 연속 부분 배열 개수를 반환하세요. 원소의 위치가 다르면 다른 구간입니다.", "- 0 ≤ 배열 길이 ≤ 100000\n- 1 ≤ 원소 ≤ 10000\n- 1 ≤ target ≤ 1000000000", "left = total = count = 0\nfor right, value in enumerate(numbers):\n    total += value\n    while left <= right and total > target:\n        total -= numbers[left]\n        left += 1\n    if total == target: count += 1\nreturn count", pos_targets, lambda a, t: sum(sum(a[i:j]) == t for i in range(len(a)) for j in range(i + 1, len(a) + 1)))
    add(2, "연속 구간 합 2", [("numbers", "int[]"), ("target", "int")], "int", "양의 정수 배열에서 합이 target 이상인 가장 짧은 연속 부분 배열의 길이를 반환하세요. 그런 구간이 없으면 0입니다.", "- 0 ≤ 배열 길이 ≤ 100000\n- 1 ≤ 원소 ≤ 10000\n- 1 ≤ target ≤ 1000000000", "left = total = 0\nanswer = len(numbers) + 1\nfor right, value in enumerate(numbers):\n    total += value\n    while left <= right and total >= target:\n        answer = min(answer, right - left + 1)\n        total -= numbers[left]\n        left += 1\nreturn answer if answer <= len(numbers) else 0", pos_targets, lambda a, t: min((j - i for i in range(len(a)) for j in range(i + 1, len(a) + 1) if sum(a[i:j]) >= t), default=0))
    queries = [[a, [[0, len(a) - 1], [0, 0], [len(a) - 1, len(a) - 1]]] for a in NONEMPTY] + [[[1, 2, 3], []]]
    add(2, "구간 합 계산", [("numbers", "int[]"), ("queries", "int[][]")], "int[]", "각 [left,right] 쿼리에 대해 양 끝을 포함한 0-based 구간의 원소 합을 반환하세요. 답은 쿼리 순서의 배열입니다. 누적 합으로 각 쿼리를 O(1)에 처리할 수 있습니다.", "- 1 ≤ 배열 길이 ≤ 100000\n- 0 ≤ 쿼리 수 ≤ 100000\n- 각 원소의 절댓값 ≤ 10000\n- 0 ≤ left ≤ right < 배열 길이", "prefix = [0]\nfor value in numbers: prefix.append(prefix[-1] + value)\nreturn [prefix[right + 1] - prefix[left] for left, right in queries]", queries, lambda a, q: [sum(a[l:r + 1]) for l, r in q])
    matrix_queries = [[m, [[0, 0, len(m) - 1, len(m[0]) - 1], [0, 0, 0, 0], [len(m) - 1, len(m[0]) - 1, len(m) - 1, len(m[0]) - 1]]] for m in MATRICES]
    add(2, "2차원 구간 합", [("matrix", "int[][]"), ("queries", "int[][]")], "int[]", "각 쿼리 [top,left,bottom,right]가 지정하는 양 끝 포함 직사각형의 원소 합을 반환하세요. 좌표는 0-based 행·열이며 답은 쿼리 순서입니다. 2차원 누적 합을 사용하세요.", "- 1 ≤ 행·열 수 ≤ 300\n- 각 원소의 절댓값 ≤ 10000\n- 0 ≤ 쿼리 수 ≤ 100000\n- 각 쿼리 좌표는 유효하며 top≤bottom,left≤right", "rows, cols = len(matrix), len(matrix[0])\nprefix = [[0] * (cols + 1) for _ in range(rows + 1)]\nfor r in range(rows):\n    for c in range(cols):\n        prefix[r + 1][c + 1] = matrix[r][c] + prefix[r][c + 1] + prefix[r + 1][c] - prefix[r][c]\nreturn [prefix[b + 1][r + 1] - prefix[t][r + 1] - prefix[b + 1][l] + prefix[t][l] for t, l, b, r in queries]", matrix_queries, lambda m, q: [sum(m[row][col] for row in range(t, b + 1) for col in range(l, r + 1)) for t, l, b, r in q])
    add(2, "최대 연속 합", [("numbers", "int[]")], "int", "길이 1 이상인 연속 부분 배열의 합 중 최댓값을 반환하세요. 모든 원소가 음수여도 빈 구간 0을 선택할 수 없습니다.", "- 1 ≤ 배열 길이 ≤ 100000\n- 각 원소의 절댓값 ≤ 10000", "ending = answer = numbers[0]\nfor value in numbers[1:]:\n    ending = max(value, ending + value)\n    answer = max(answer, ending)\nreturn answer", [[a] for a in NONEMPTY], lambda a: max(sum(a[i:j]) for i in range(len(a)) for j in range(i + 1, len(a) + 1)))
    windows = [[a, 1 if i % 2 == 0 else len(a)] for i, a in enumerate(NONEMPTY)] + [[[1, -2, 3, 4, -1], 2], [[2, 2, 2], 2]]
    add(2, "고정 길이 최대 합", [("numbers", "int[]"), ("k", "int")], "int", "길이가 정확히 k인 연속 부분 배열들의 합 중 최댓값을 반환하세요.", "- 1 ≤ k ≤ 배열 길이 ≤ 100000\n- 각 원소의 절댓값 ≤ 10000", "total = sum(numbers[:k])\nanswer = total\nfor i in range(k, len(numbers)):\n    total += numbers[i] - numbers[i - k]\n    answer = max(answer, total)\nreturn answer", windows, lambda a, k: max(sum(a[i:i + k]) for i in range(len(a) - k + 1)))
    add(2, "고정 길이 최소 합", [("numbers", "int[]"), ("k", "int")], "int", "길이가 정확히 k인 연속 부분 배열들의 합 중 최솟값을 반환하세요.", "- 1 ≤ k ≤ 배열 길이 ≤ 100000\n- 각 원소의 절댓값 ≤ 10000", "total = sum(numbers[:k])\nanswer = total\nfor i in range(k, len(numbers)):\n    total += numbers[i] - numbers[i - k]\n    answer = min(answer, total)\nreturn answer", windows, lambda a, k: min(sum(a[i:i + k]) for i in range(len(a) - k + 1)))
    add(2, "서로 다른 수의 개수", [("numbers", "int[]"), ("k", "int")], "int[]", "길이 k인 각 연속 구간에 서로 다른 값이 몇 개 있는지 왼쪽 구간부터 순서대로 배열로 반환하세요.", "- 1 ≤ k ≤ 배열 길이 ≤ 100000\n- 각 원소의 절댓값 ≤ 1000000000", "from collections import Counter\ncounts = Counter(numbers[:k])\nresult = [len(counts)]\nfor i in range(k, len(numbers)):\n    counts[numbers[i - k]] -= 1\n    if counts[numbers[i - k]] == 0: del counts[numbers[i - k]]\n    counts[numbers[i]] += 1\n    result.append(len(counts))\nreturn result", windows, lambda a, k: [len(set(a[i:i + k])) for i in range(len(a) - k + 1)])
    add(2, "최대 평균 구간", [("numbers", "int[]"), ("k", "int")], "float", "길이 k인 연속 구간 중 평균이 가장 큰 값을 실수로 반환하세요. 구간 위치가 아니라 평균을 반환합니다.", "- 1 ≤ k ≤ 배열 길이 ≤ 100000\n- 각 원소의 절댓값 ≤ 10000", "total = sum(numbers[:k])\nanswer = total\nfor i in range(k, len(numbers)):\n    total += numbers[i] - numbers[i - k]\n    answer = max(answer, total)\nreturn answer / k", windows, lambda a, k: max(sum(a[i:i + k]) / k for i in range(len(a) - k + 1)))
    binaries = [[[v % 2 for v in a], i % 4] for i, a in enumerate(ARRAYS)] + [[[1, 1, 0, 0, 1, 1, 1, 0], 2], [[0, 0, 0], 0]]
    add(2, "연속된 0과 1", [("numbers", "int[]"), ("k", "int")], "int", "0과 1 배열에서 최대 k개의 0을 1로 바꿀 수 있습니다. 만들 수 있는 가장 긴 연속 1의 길이를 반환하세요. 변경은 하지 않아도 됩니다.", "- 0 ≤ 배열 길이 ≤ 100000\n- 각 원소는 0 또는 1\n- 0 ≤ k ≤ 100000", "left = zeros = answer = 0\nfor right, value in enumerate(numbers):\n    zeros += value == 0\n    while zeros > k:\n        zeros -= numbers[left] == 0\n        left += 1\n    answer = max(answer, right - left + 1)\nreturn answer", binaries, lambda a, k: max((j - i for i in range(len(a) + 1) for j in range(i, len(a) + 1) if a[i:j].count(0) <= k), default=0))
    products = [[[abs(v) + 1 for v in a], i * 3] for i, a in enumerate(ARRAYS)] + [[[10, 5, 2, 6], 100], [[1, 1, 1], 2]]
    add(2, "부분 배열의 곱", [("numbers", "int[]"), ("k", "int")], "int", "양의 정수 배열에서 원소 곱이 k 미만인 길이 1 이상 연속 부분 배열의 개수를 반환하세요. k 이하가 아니라 엄격히 미만입니다.", "- 0 ≤ 배열 길이 ≤ 50000\n- 1 ≤ 원소 ≤ 1000\n- 0 ≤ k ≤ 1000000000\n- 곱을 갱신할 때 64비트 정수를 사용하세요.", "if k <= 1: return 0\nproduct = 1\nleft = answer = 0\nfor right, value in enumerate(numbers):\n    product *= value\n    while left <= right and product >= k:\n        product //= numbers[left]\n        left += 1\n    answer += right - left + 1\nreturn answer", products, lambda a, k: sum(math.prod(a[i:j]) < k for i in range(len(a)) for j in range(i + 1, len(a) + 1)))
    array_pairs = [[sorted(a), sorted(ARRAYS[-i - 1])] for i, a in enumerate(ARRAYS)]
    add(2, "두 정렬 배열의 중복 제거", [("first", "int[]"), ("second", "int[]")], "int[]", "비내림차순으로 정렬된 두 배열을 병합하면서 중복을 제거한 하나의 오름차순 배열을 반환하세요.", "- 각 배열 길이 0 이상 100000 이하\n- 각 원소의 절댓값 ≤ 1000000000", "i = j = 0\nresult = []\nwhile i < len(first) or j < len(second):\n    if j == len(second) or i < len(first) and first[i] <= second[j]:\n        value = first[i]\n        i += 1\n    else:\n        value = second[j]\n        j += 1\n    if not result or result[-1] != value: result.append(value)\nreturn result", array_pairs, lambda a, b: sorted(set(a + b)))
    near_pairs = [[a, i - 5] for i, a in enumerate(ARRAYS) if len(a) >= 2] + [[[1, 2], 10], [[1, 2, 3, 4], 5], [[-2, -1, 1, 2], 0]]
    add(2, "가장 가까운 합", [("numbers", "int[]"), ("target", "int")], "int[]", "서로 다른 두 인덱스의 원소 합이 target에 가장 가까운 값의 조합을 반환하세요. 두 값을 오름차순 [작은 값,큰 값]으로 반환하고, 차이의 절댓값이 동률이면 이 두 값 배열의 사전순으로 작은 조합을 선택하세요.", "- 2 ≤ 배열 길이 ≤ 100000\n- 각 원소·target의 절댓값 ≤ 1000000000", "a = sorted(numbers)\nleft, right = 0, len(a) - 1\nbest = (abs(a[left] + a[right] - target), a[left], a[right])\nwhile left < right:\n    total = a[left] + a[right]\n    best = min(best, (abs(total - target), a[left], a[right]))\n    if total < target: left += 1\n    else: right -= 1\nreturn [best[1], best[2]]", near_pairs, lambda a, t: list(min((tuple(sorted(pair)) for pair in itertools.combinations(a, 2)), key=lambda p: (abs(sum(p) - t), p))))


def permutations_oracle(numbers):
    if not numbers:
        return [[]]
    return sorted([[value] + rest for i, value in enumerate(numbers) for rest in permutations_oracle(numbers[:i] + numbers[i + 1:])])


def baseball_oracle(hints):
    answer = 0
    for candidate in range(123, 988):
        text = str(candidate)
        if "0" in text or len(set(text)) != 3:
            continue
        valid = True
        for guessed, strikes, balls in hints:
            guess = str(guessed)
            if sum(a == b for a, b in zip(text, guess)) != strikes or len(set(text) & set(guess)) - strikes != balls:
                valid = False
                break
        answer += valid
    return answer


def coin_oracle(coins, amount):
    # 금액을 정점으로 하는 최단 경로의 BFS. 기준 풀이의 DP와 독립적이다.
    queue = collections.deque([(0, 0)])
    seen = {0}
    while queue:
        value, count = queue.popleft()
        if value == amount:
            return count
        for coin in coins:
            new = value + coin
            if new <= amount and new not in seen:
                seen.add(new)
                queue.append((new, count + 1))
    return -1


def meetings_oracle(meetings):
    answer = 0
    for size in range(len(meetings) + 1):
        for subset in itertools.combinations(meetings, size):
            ordered = sorted(subset)
            if all(a[1] <= b[0] for a, b in zip(ordered, ordered[1:])):
                answer = max(answer, size)
    return answer


def boats_oracle(weights, limit):
    if not weights:
        return 0
    first, rest = weights[0], weights[1:]
    candidates = [1 + boats_oracle(rest, limit)]
    for i, value in enumerate(rest):
        if first + value <= limit:
            candidates.append(1 + boats_oracle(rest[:i] + rest[i + 1:], limit))
    return min(candidates)


def uniform_oracle(n, lost, reserve):
    missing, surplus = set(lost) - set(reserve), set(reserve) - set(lost)
    def matches(students, available):
        if not students:
            return 0
        student, *rest = students
        best = matches(rest, available)
        for neighbour in (student - 1, student + 1):
            if neighbour in available:
                best = max(best, 1 + matches(rest, available - {neighbour}))
        return best
    return n - len(missing) + matches(sorted(missing), surplus)


def grid_robot_oracle(grid, start, commands):
    row, col = start
    for command in commands:
        dr, dc = {"U": (-1, 0), "D": (1, 0), "L": (0, -1), "R": (0, 1)}[command]
        if 0 <= row + dr < len(grid) and 0 <= col + dc < len(grid[0]) and grid[row + dr][col + dc] == 0:
            row, col = row + dr, col + dc
    return [row, col]


def turning_robot_oracle(grid, start, direction, commands):
    # 방향 벡터를 직접 회전한다. 기준 풀이의 방향 인덱스 산술과 독립적이다.
    row, col = start
    dr, dc = [(-1, 0), (0, 1), (1, 0), (0, -1)][direction]
    for command in commands:
        if command == "L":
            dr, dc = -dc, dr
        elif command == "R":
            dr, dc = dc, -dr
        else:
            r, c = row + dr, col + dc
            if 0 <= r < len(grid) and 0 <= c < len(grid[0]) and grid[r][c] == 0:
                row, col = r, c
    return [row, col, [(-1, 0), (0, 1), (1, 0), (0, -1)].index((dr, dc))]


def border_oracle(matrix, top, left, bottom, right):
    result = copy.deepcopy(matrix)
    for row in range(top, bottom + 1):
        for col in range(left, right + 1):
            if row == top and col < right:
                nr, nc = row, col + 1
            elif col == right and row < bottom:
                nr, nc = row + 1, col
            elif row == bottom and col > left:
                nr, nc = row, col - 1
            elif col == left and row > top:
                nr, nc = row - 1, col
            else:
                continue
            result[nr][nc] = matrix[row][col]
    return result


def level2_final():
    distinct = [[], [1], [2, 1], [3, 1, 2], [-1, 0, 1], [5, 3], [4, 2, 1, 3], [0, -2], [10, 5, 0], [-3, -1, -2], [1, 3, 5, 7], [9, 8, 7]]
    add(2, "순열 생성", [("numbers", "int[]")], "int[][]", "서로 다른 원소의 모든 순열을 사전순으로 반환하세요. 각 순열은 입력 원소를 한 번씩 모두 사용합니다. 빈 배열의 순열은 빈 순열 하나이므로 [[]]를 반환합니다.", "- 0 ≤ 배열 길이 ≤ 8\n- 원소는 서로 다르고 절댓값 ≤ 1000000\n- 배열 사전순은 첫 다른 위치의 값을 비교합니다.", "from itertools import permutations\nreturn [list(p) for p in permutations(sorted(numbers))]", [[a] for a in distinct], permutations_oracle)
    combinations_cases = [[a, i % (len(a) + 1)] for i, a in enumerate(distinct)] + [[[3, 1, 2], 2], [[1, 2, 3], 0], [[1, 2, 3], 3]]
    add(2, "조합 생성", [("numbers", "int[]"), ("r", "int")], "int[][]", "서로 다른 입력 원소 중 r개를 고르는 모든 조합을 반환하세요. 각 조합 내부를 오름차순으로 정렬하고 결과 목록을 사전순으로 정렬합니다. r=0이면 [[]]입니다.", "- 0 ≤ r ≤ 배열 길이 ≤ 16\n- 원소는 서로 다르고 절댓값 ≤ 1000000", "from itertools import combinations\nreturn [list(c) for c in combinations(sorted(numbers), r)]", combinations_cases, lambda a, r: sorted([sorted(a[i] for i in range(len(a)) if mask & (1 << i)) for mask in range(1 << len(a)) if mask.bit_count() == r]))
    add(2, "부분집합 생성", [("numbers", "int[]")], "int[][]", "서로 다른 원소의 모든 부분집합을 반환하세요. 부분집합 내부는 오름차순, 결과 목록은 원소 개수가 적은 순서로 정렬하되 크기가 같으면 사전순입니다. 빈 부분집합도 포함합니다.", "- 0 ≤ 배열 길이 ≤ 16\n- 원소는 서로 다르고 절댓값 ≤ 1000000", "from itertools import combinations\na = sorted(numbers)\nreturn [list(c) for size in range(len(a) + 1) for c in combinations(a, size)]", [[a] for a in distinct], lambda a: sorted([sorted(a[i] for i in range(len(a)) if mask & (1 << i)) for mask in range(1 << len(a))], key=lambda x: (len(x), x)))
    hints = [[], [[123, 3, 0]], [[123, 0, 0]], [[123, 0, 3]], [[123, 1, 1]], [[123, 2, 0]], [[123, 1, 2]], [[123, 3, 0], [456, 0, 0]], [[123, 3, 0], [123, 0, 0]], [[987, 3, 0]], [[123, 0, 0], [456, 0, 0]], [[123, 1, 1], [356, 1, 0], [327, 2, 0], [489, 0, 1]]]
    add(2, "숫자 야구", [("hints", "int[][]")], "int", "정답은 1~9의 서로 다른 세 숫자로 만든 세 자리 정수입니다. 각 힌트 [추측값,strike,ball]에서 strike는 숫자와 위치가 같은 개수, ball은 숫자는 있지만 위치가 다른 개수입니다. 모든 힌트를 만족하는 정답 후보의 개수를 반환하세요. 힌트가 모순되면 0입니다.", "- 0 ≤ 힌트 수 ≤ 100\n- 추측값도 1~9의 서로 다른 세 숫자로 구성\n- 0 ≤ strike,ball ≤ 3, strike+ball≤3", "from itertools import permutations\nanswer = 0\nfor digits in permutations('123456789', 3):\n    valid = True\n    for guessed, strikes, balls in hints:\n        guess = str(guessed)\n        same = sum(a == b for a, b in zip(digits, guess))\n        common = sum(c in guess for c in digits)\n        if same != strikes or common - same != balls:\n            valid = False\n            break\n    answer += valid\nreturn answer", [[a] for a in hints], baseball_oracle)
    exams = [[[], [[1], [2]]], [[1, 2, 3], [[1, 2, 3], [1]]], [[1], [[1], [1]]], [[1], [[2], [3]]], [[1, 1, 1], [[1], [2]]], [[1, 2, 1, 2], [[1, 2], [2, 1]]], [[5, 4, 3, 2, 1], [[1, 2, 3, 4, 5], [5, 4]]], [[3, 3, 3], [[3], [3], [2]]], [[1, 2], [[2, 1], [1, 2]]], [[1, 2, 3, 4, 5], [[1, 2, 3, 4, 5], [2, 1, 2, 3, 2, 4, 2, 5], [3, 3, 1, 1, 2, 2, 4, 4, 5, 5]]], [[4], [[4]]], [[2, 2], [[1], [2], [3]]]]
    add(2, "모의 시험 채점", [("answers", "int[]"), ("patterns", "int[][]")], "int[]", "각 사람은 자신의 답안 패턴을 처음부터 반복하여 제출합니다. 정답 배열과 비교하여 최고 점수인 사람들의 번호를 1부터 세어 오름차순으로 반환하세요. 정답이 비어 있으면 모든 사람이 0점으로 공동 최고입니다.", "- 0 ≤ 정답 수 ≤ 100000\n- 1 ≤ 사람 수 ≤ 10\n- 각 패턴 길이 1 이상 100 이하\n- 모든 정답·답안 값은 1~5", "scores = [sum(answer == pattern[i % len(pattern)] for i, answer in enumerate(answers)) for pattern in patterns]\nbest = max(scores)\nreturn [i + 1 for i, score in enumerate(scores) if score == best]", exams, lambda a, p: [i + 1 for i in range(len(p)) if sum(a[j] == p[i][j % len(p[i])] for j in range(len(a))) == max(sum(a[j] == pattern[j % len(pattern)] for j in range(len(a))) for pattern in p)])
    boards = [["W"], ["B"], ["WB", "BW"], ["WW", "WW"], ["BB", "BB"], ["WBW"], ["W", "B", "W"], ["WBWB", "BWBW", "WBWB"], ["BWW", "BBW"], ["WWWW", "BBBB"], ["BWB", "WBW", "BWB"], ["BWBW", "WBWB", "BWWW"]]
    add(2, "체스판 다시 칠하기", [("board", "string[]")], "int", "W와 B로 된 직사각형 보드 전체를 상하좌우로 인접한 두 칸의 색이 항상 다른 체스판으로 만들려고 합니다. 왼쪽 위 색은 W 또는 B 중 자유롭게 골라 칠할 칸 수를 최소화하세요. 부분 8×8 선택이 아니라 주어진 전체 보드를 바꿉니다.", "- 1 ≤ 행·열 수 ≤ 300\n- 모든 행 문자열 길이가 같으며 W,B만 포함", "rows, cols = len(board), len(board[0])\nwrong = sum(board[r][c] != ('W' if (r + c) % 2 == 0 else 'B') for r in range(rows) for c in range(cols))\nreturn min(wrong, rows * cols - wrong)", [[b] for b in boards], lambda b: min(sum(b[r][c] != ("WB" if first == "W" else "BW")[(r + c) % 2] for r in range(len(b)) for c in range(len(b[0]))) for first in "WB"))
    coins = [[[1, 3, 4], 6], [[2], 3], [[1], 0], [[], 0], [[], 5], [[5, 10], 20], [[2, 5], 7], [[3, 7], 10], [[4, 6], 7], [[1, 1, 2], 4], [[7, 3, 1], 12], [[5, 9], 18]]
    add(2, "최소 동전 개수", [("coins", "int[]"), ("amount", "int")], "int", "각 동전을 제한 없이 사용하여 amount를 정확히 만드는 최소 동전 개수를 반환하세요. 만들 수 없으면 -1이고 amount=0이면 0입니다. 동전은 일반적인 양의 정수 단위이므로 큰 동전부터 고르는 방법이 항상 맞지는 않습니다.", "- 0 ≤ 동전 종류 수 ≤ 100\n- 1 ≤ 동전 단위 ≤ 10000(중복 허용)\n- 0 ≤ amount ≤ 10000", "dp = [amount + 1] * (amount + 1)\ndp[0] = 0\nfor value in range(1, amount + 1):\n    for coin in coins:\n        if coin <= value:\n            dp[value] = min(dp[value], dp[value - coin] + 1)\nreturn dp[amount] if dp[amount] <= amount else -1", coins, coin_oracle)
    meetings = [[], [[1, 2]], [[1, 3], [2, 4]], [[1, 2], [2, 3]], [[0, 10], [1, 2], [2, 3], [3, 4]], [[5, 7], [1, 2], [3, 5]], [[1, 2], [1, 2]], [[1, 5], [2, 3], [3, 4]], [[0, 1], [1, 2], [2, 3]], [[4, 8], [0, 4], [2, 6]], [[3, 4], [1, 3], [2, 5]], [[10, 20], [20, 30], [0, 10]]]
    add(2, "최대 회의 개수", [("meetings", "int[][]")], "int", "각 회의 [start,end]를 겹치지 않게 최대한 많이 선택하세요. 회의 종료 시각과 다음 시작 시각이 같으면 연속으로 선택할 수 있습니다. 선택 가능한 최대 개수를 반환하세요.", "- 0 ≤ 회의 수 ≤ 100000\n- 0 ≤ start < end ≤ 1000000000", "last_end = -1\ncount = 0\nfor start, end in sorted(meetings, key=lambda x: (x[1], x[0])):\n    if start >= last_end:\n        count += 1\n        last_end = end\nreturn count", [[a] for a in meetings], meetings_oracle)
    boats = [[[], 100], [[50], 100], [[50, 50], 100], [[60, 60], 100], [[70, 50, 80, 50], 100], [[20, 30, 40], 100], [[100, 1], 100], [[40, 60, 40, 60], 100], [[10, 10, 10], 20], [[30, 70, 80, 20], 100], [[90, 90, 10, 10], 100], [[1, 2, 3, 4, 5], 5]]
    add(2, "구명보트", [("weights", "int[]"), ("limit", "int")], "int", "보트 한 척에는 최대 두 명이 타고 몸무게 합은 limit 이하여야 합니다. 모든 사람을 옮기는 데 필요한 최소 보트 수를 반환하세요.", "- 0 ≤ 사람 수 ≤ 100000\n- 1 ≤ 각 몸무게 ≤ limit ≤ 1000000", "a = sorted(weights)\nleft, right = 0, len(a) - 1\nboats = 0\nwhile left <= right:\n    if left < right and a[left] + a[right] <= limit:\n        left += 1\n    right -= 1\n    boats += 1\nreturn boats", boats, boats_oracle)
    uniforms = [[5, [2, 4], [1, 3, 5]], [5, [2, 4], [3]], [3, [], []], [3, [1], []], [3, [1], [1]], [5, [2, 3], [3, 4]], [4, [1, 4], [2, 3]], [1, [1], []], [1, [1], [1]], [6, [2, 4, 6], [1, 3, 5]], [5, [1, 2], [2, 3]], [5, [3], [1, 5]]]
    add(2, "체육복 배분", [("n", "int"), ("lost", "int[]"), ("reserve", "int[]")], "int", "학생은 1~n번입니다. lost 학생은 체육복 한 벌을 잃었고 reserve 학생은 원래 한 벌 외 여분 한 벌이 있습니다. 두 목록에 모두 있으면 자기 옷만 남아 빌려줄 수 없습니다. 여분은 바로 앞·뒤 번호 학생 한 명에게만 빌려줄 수 있습니다. 최대 참여 인원수를 반환하세요.", "- 1 ≤ n ≤ 100000\n- 두 배열 각각 중복 없이 1~n의 번호만 포함", "missing = set(lost) - set(reserve)\nsurplus = set(reserve) - set(lost)\nfor student in sorted(missing):\n    if student - 1 in surplus:\n        surplus.remove(student - 1)\n    elif student + 1 in surplus:\n        surplus.remove(student + 1)\n    else:\n        n -= 1\nreturn n", uniforms, uniform_oracle)
    grid_cases = [
        [[[0]], [0, 0], "UDLR"], [[[0, 0], [0, 0]], [0, 0], "RDDL"], [[[0, 1], [0, 0]], [0, 0], "RDR"],
        [[[0, 0, 0]], [0, 1], "LLRRR"], [[[0], [1], [0]], [0, 0], "DD"], [[[0, 0], [1, 0]], [1, 1], "LU"],
        [[[0, 1, 0], [0, 0, 0]], [0, 0], "DRRU"], [[[0, 0], [0, 0]], [1, 1], ""], [[[0, 0, 0], [0, 1, 0], [0, 0, 0]], [1, 0], "RURRDD"],
        [[[0, 1], [1, 0]], [0, 0], "RDLU"], [[[0, 0, 0], [0, 0, 0]], [1, 2], "LLUUR"], [[[0, 0], [0, 0]], [0, 1], "DLURDLUR"]]
    add(2, "로봇 이동 2", [("grid", "int[][]"), ("start", "int[]"), ("commands", "string")], "int[]", "격자는 0이 빈칸,1이 장애물입니다. start=[행,열]에서 시작해 U:행-1,D:행+1,L:열-1,R:열+1을 수행하세요. 경계를 벗어나거나 장애물인 칸으로 가는 명령은 무시하고 최종 [행,열]을 반환하세요.", "- 1 ≤ 행·열 수 ≤ 300\n- start는 격자 내부의 빈칸\n- 명령은 U,D,L,R로만 이루어지며 길이 0 이상 16384 이하", "row, col = start\nfor command in commands:\n    dr, dc = {'U': (-1, 0), 'D': (1, 0), 'L': (0, -1), 'R': (0, 1)}[command]\n    nr, nc = row + dr, col + dc\n    if 0 <= nr < len(grid) and 0 <= nc < len(grid[0]) and grid[nr][nc] == 0:\n        row, col = nr, nc\nreturn [row, col]", grid_cases, grid_robot_oracle)
    turning_cases = [[grid, start, i % 4, commands] for i, (grid, start, _) in enumerate(grid_cases) for commands in [["F", "FFRFF", "LFFRFF", "RRFFL", "RFLF", "", "FRFRFRF", "LLFF", "RFFLFF", "FFFFFF", "LRLRF", "RRRR"][i]]]
    add(2, "로봇 이동 3", [("grid", "int[][]"), ("start", "int[]"), ("direction", "int"), ("commands", "string")], "int[]", "0은 빈칸,1은 장애물인 격자에서 start=[행,열]과 초기 방향이 주어집니다. 방향 번호는 0:북,1:동,2:남,3:서입니다. L은 제자리 왼쪽 90도 회전,R은 오른쪽 90도 회전,F는 바라보는 방향으로 한 칸 전진입니다. 경계·장애물 전진은 무시하며 [최종 행,최종 열,최종 방향]을 반환하세요.", "- 1 ≤ 행·열 수 ≤ 300\n- start는 격자 내부의 빈칸\n- 0 ≤ direction ≤ 3\n- 명령은 L,R,F이며 길이 0 이상 16384 이하", "row, col = start\nsteps = [(-1, 0), (0, 1), (1, 0), (0, -1)]\nfor command in commands:\n    if command == 'L': direction = (direction + 3) % 4\n    elif command == 'R': direction = (direction + 1) % 4\n    else:\n        dr, dc = steps[direction]\n        nr, nc = row + dr, col + dc\n        if 0 <= nr < len(grid) and 0 <= nc < len(grid[0]) and grid[nr][nc] == 0:\n            row, col = nr, nc\nreturn [row, col, direction]", turning_cases, turning_robot_oracle)
    squares = [[[r * n + c for c in range(n)] for r in range(n)] for n in range(1, 11)] + [[[1, -2], [3, 4]], [[-1]]]
    add(2, "행렬 회전", [("matrix", "int[][]")], "int[][]", "정사각 행렬을 시계 방향으로 90도 회전하여 반환하세요. 원래 (r,c)는 결과 (c,n-1-r)에 놓입니다.", "- 1 ≤ 행·열 수 ≤ 100\n- 각 원소의 절댓값 ≤ 1000000", "return [list(row) for row in zip(*matrix[::-1])]", [[m] for m in squares], lambda m: [[m[len(m) - c - 1][r] for c in range(len(m))] for r in range(len(m))])
    border_cases = []
    for n in range(2, 6):
        m = [[r * (n + 1) + c + 1 for c in range(n + 1)] for r in range(n)]
        border_cases.extend([[m, 0, 0, n - 1, n], [m, 0, 0, 1, 1], [m, 0, 1, n - 1, n]])
    add(2, "행렬 회전 1", [("matrix", "int[][]"), ("top", "int"), ("left", "int"), ("bottom", "int"), ("right", "int")], "int[][]", "양 끝을 포함한 직사각형 [top,left,bottom,right]의 테두리 값만 시계 방향으로 한 칸 이동하세요. 윗변은 오른쪽,오른변은 아래,아랫변은 왼쪽,왼변은 위로 이동합니다. 내부와 영역 밖 값은 그대로 둔 전체 행렬을 반환하세요.", "- 2 ≤ 행·열 수 ≤ 100\n- 0 ≤ top < bottom < 행 수\n- 0 ≤ left < right < 열 수\n- 각 원소의 절댓값 ≤ 1000000", "result = [row[:] for row in matrix]\npositions = ([(top, c) for c in range(left, right + 1)] + [(r, right) for r in range(top + 1, bottom + 1)] + [(bottom, c) for c in range(right - 1, left - 1, -1)] + [(r, left) for r in range(bottom - 1, top, -1)])\nfor i, (r, c) in enumerate(positions):\n    nr, nc = positions[(i + 1) % len(positions)]\n    result[nr][nc] = matrix[r][c]\nreturn result", border_cases, border_oracle)
    mines = [[[0]], [[1]], [[0, 0]], [[1, 0]], [[1, 1]], [[0], [1], [0]], [[1, 0], [0, 1]], [[0, 0], [0, 0]], [[1, 1], [1, 1]], [[0, 0, 0], [0, 1, 0], [0, 0, 0]], [[1, 0, 1], [0, 0, 0]], [[0, 1, 0], [1, 0, 1], [0, 1, 0]]]
    add(2, "지뢰찾기", [("grid", "int[][]")], "int[][]", "1이 지뢰,0이 빈칸인 격자가 주어집니다. 지뢰 칸은 -1로,각 빈칸은 상하좌우·대각선의 최대 8칸에 있는 지뢰 수로 바꾼 행렬을 반환하세요. 격자 밖 칸은 세지 않습니다.", "- 1 ≤ 행·열 수 ≤ 300\n- 각 원소는 0 또는 1", "rows, cols = len(grid), len(grid[0])\nresult = [[0] * cols for _ in range(rows)]\nfor r in range(rows):\n    for c in range(cols):\n        if grid[r][c] == 1:\n            result[r][c] = -1\n        else:\n            for dr in (-1, 0, 1):\n                for dc in (-1, 0, 1):\n                    nr, nc = r + dr, c + dc\n                    if (dr or dc) and 0 <= nr < rows and 0 <= nc < cols:\n                        result[r][c] += grid[nr][nc]\nreturn result", [[m] for m in mines], lambda m: [[-1 if m[r][c] else sum(m[y][x] for y in range(max(0, r - 1), min(len(m), r + 2)) for x in range(max(0, c - 1), min(len(m[0]), c + 2))) for c in range(len(m[0]))] for r in range(len(m))])


def main():
    level1()
    level2()
    for level, expected in ((1, 75), (2, 90)):
        if len(SPECS[level]) != expected:
            raise AssertionError((level, len(SPECS[level])))
        document = {"version": 1, "name": f"OJ Level {level} — {'기초 알고리즘 75문제' if level == 1 else '자료구조와 알고리즘 응용 90문제'}", "problems": SPECS[level]}
        path = ROOT / f"src/main/resources/problems/level{level}.json"
        path.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"{path}: {len(SPECS[level])} problems, {sum(len(p['tests']) for p in SPECS[level])} cases")


if __name__ == "__main__":
    main()
