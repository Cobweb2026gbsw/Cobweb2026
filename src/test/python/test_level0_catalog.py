"""Level 0 저작 카탈로그 검증. 사용자 제출을 호스트에서 실행하지 않는다.

실행 대상은 저장소에 함께 검토되는 고정 Python 기준 풀이뿐이다. 기대값은
기준 풀이를 호출해 만들지 않고 아래의 별도 명세 오라클로 계산한다.
"""
import ast
import copy
from fractions import Fraction
from functools import lru_cache
import inspect
import json
import math
from pathlib import Path
import random
import unittest


ROOT = Path(__file__).resolve().parents[3]
CATALOG_PATH = ROOT / "src/main/resources/problems/level0.json"
CATALOG = json.loads(CATALOG_PATH.read_text(encoding="utf-8"))
PROBLEMS = CATALOG["problems"]
TITLES = (
    "Hello World!|여러 줄 출력|숫자 반환|문자열 반환|실수 반환|문자 반환|두 정수 반환|"
    "변수 값 바꾸기|변수 교환|문자열 연결|공백 추가|문자열 세 번 반복|정수를 문자열로|"
    "문자열을 정수로|정수와 실수|문자열 길이|첫 번째 문자|마지막 문자|배열 길이|배열 원소 접근|"
    "두 수의 합|두 수의 차|두 수의 곱|나눗셈|정수 나눗셈|나머지 구하기|제곱 계산|세제곱 계산|"
    "숫자 두 배|숫자 절반|복합 연산|연산 순서|괄호 연산|평균 계산|절댓값|부호 반전|"
    "일의 자리|십의 자리|초 변환|문자열 숫자 더하기|양수 판별|음수 판별|0인지 확인|"
    "홀수와 짝수|두 수 비교|작은 수 찾기|같은 숫자인가|다른 숫자인가|범위 확인|합격 판정|"
    "성적 등급|성인 판별|배수 확인|두 조건 만족|하나라도 만족|범위 밖인가|세 수 비교|"
    "중간 숫자|윤년 판별|간단한 계산기|1부터 N까지|N부터 1까지|자연수의 합|짝수의 합|"
    "홀수의 합|구구단|N번 반복|숫자 세기|배수 배열|팩토리얼|거듭제곱|자릿수 개수|"
    "자릿수 합|자릿수 곱|숫자 뒤집기|별 한 줄|별 계단|숫자 계단|반복되는 덧셈|목표 합 도달|"
    "배열의 합|배열의 평균|배열의 최댓값|배열의 최솟값|짝수 원소 개수|홀수만 추출|"
    "배열 뒤집기|배열 원소 두 배|특정 원소 찾기|배열 원소 교환|문자열 뒤집기|대문자로 변환|"
    "소문자로 변환|문자 개수 세기|공백 제거|문자열 포함 확인|문자열 자르기|함수로 제곱하기|"
    "함수로 최댓값 찾기|종합 계산 함수"
).split("|")
TYPES = {"int", "float", "bool", "string", "int[]", "string[]"}
SAFE_BUILTINS = {
    "abs": abs, "int": int, "len": len, "range": range,
    "sorted": sorted, "str": str,
}


def swap(numbers, first, second):
    """인덱스별 선택으로 정의하여 기준 풀이의 대입 구현과 독립시킨다."""
    return [numbers[second] if i == first else numbers[first] if i == second else value
            for i, value in enumerate(numbers)]


def grade(score):
    for lower, letter in ((90, "A"), (80, "B"), (70, "C"), (60, "D")):
        if score >= lower:
            return letter
    return "F"


def target_count(target):
    candidate = (math.isqrt(8 * target + 1) - 1) // 2
    return candidate if candidate * (candidate + 1) // 2 >= target else candidate + 1


# 모든 항목은 카탈로그의 sourceCode를 읽거나 호출하지 않는 독립 명세이다.
ORACLES = {
    1: lambda: "Hello World!",
    2: lambda first, second: "\n".join((first, second)),
    3: lambda num: num,
    4: lambda text: text,
    5: lambda num: float(num),
    6: lambda character: character,
    7: lambda a, b: [a, b],
    8: lambda num: sum((num, 10)),
    9: lambda a, b: [b, a],
    10: lambda first, second: "".join((first, second)),
    11: lambda first, second: " ".join((first, second)),
    12: lambda text: "".join([text, text, text]),
    13: lambda num: format(num, "d"),
    14: lambda text: int(text, 10),
    15: lambda num, value: float(Fraction(num) + Fraction(value)),
    16: lambda text: sum(1 for _ in text),
    17: lambda text: next(iter(text)),
    18: lambda text: next(reversed(text)),
    19: lambda numbers: sum(1 for _ in numbers),
    20: lambda numbers: next(iter(numbers)),
    21: lambda a, b: sum((a, b)),
    22: lambda a, b: sum((a, -b)),
    23: lambda a, b: math.prod((a, b)),
    24: lambda a, b: float(Fraction(a) / Fraction(b)),
    25: lambda a, b: divmod(a, b)[0],
    26: lambda a, b: divmod(a, b)[1],
    27: lambda num: pow(num, 2),
    28: lambda num: pow(num, 3),
    29: lambda num: sum((num, num)),
    30: lambda num: float(Fraction(num, 2)),
    31: lambda num: num * 3 + 15,
    32: lambda a, b, c: sum((a, math.prod((b, c)))),
    33: lambda a, b, c: a * c + b * c,
    34: lambda a, b: float(Fraction(a + b, 2)),
    35: lambda num: max(num, -num),
    36: lambda num: 0 - num,
    37: lambda num: int(str(num)[-1]),
    38: lambda num: int(str(num)[-2]),
    39: lambda seconds: list(divmod(seconds, 60)),
    40: lambda first, second: sum(map(int, (first, second))),
    41: lambda num: num >= 1,
    42: lambda num: num <= -1,
    43: lambda num: not num,
    44: lambda num: ("even", "odd")[num % 2],
    45: lambda a, b: sorted((a, b))[-1],
    46: lambda a, b: sorted((a, b))[0],
    47: lambda a, b: not (a - b),
    48: lambda a, b: bool(a - b),
    49: lambda num: num in range(1, 101),
    50: lambda score: ("FAIL", "PASS")[score >= 60],
    51: grade,
    52: lambda age: age > 18,
    53: lambda num: divmod(num, 3)[1] == 0,
    54: lambda num: num % 10 == 0,
    55: lambda num: any(num % divisor == 0 for divisor in (3, 7)),
    56: lambda num: num not in range(101),
    57: lambda a, b, c: sorted((a, b, c))[-1],
    58: lambda a, b, c: a + b + c - min(a, b, c) - max(a, b, c),
    59: lambda year: year % (400 if year % 100 == 0 else 4) == 0,
    60: lambda a, b, op: {"+": a + b, "-": a - b, "*": a * b}[op],
    61: lambda n: list(range(1, n + 1)),
    62: lambda n: list(reversed(range(1, n + 1))),
    63: lambda n: n * (n + 1) // 2,
    64: lambda n: (n // 2) * (n // 2 + 1),
    65: lambda n: ((n + 1) // 2) ** 2,
    66: lambda n: [n * multiplier for multiplier in range(1, 10)],
    67: lambda text, n: "".join(text for _ in range(n)),
    68: lambda n: n // 3,
    69: lambda n: list(range(5, n + 1, 5)),
    70: math.factorial,
    71: pow,
    72: lambda n: len(str(n)),
    73: lambda n: sum(map(int, str(n))),
    74: lambda n: math.prod(map(int, str(n))),
    75: lambda n: int("".join(reversed(str(n)))),
    76: lambda n: "".join("*" for _ in range(n)),
    77: lambda n: ["*" * height for height in range(1, n + 1)],
    78: lambda n: ["123456789"[:height] for height in range(1, n + 1)],
    79: lambda a, n: a * n,
    80: target_count,
    81: sum,
    82: lambda numbers: float(Fraction(sum(numbers), len(numbers))),
    83: max,
    84: min,
    85: lambda numbers: sum(number % 2 == 0 for number in numbers),
    86: lambda numbers: list(filter(lambda number: number % 2, numbers)),
    87: lambda numbers: list(reversed(numbers)),
    88: lambda numbers: [number + number for number in numbers],
    89: lambda numbers, x: any(number == x for number in numbers),
    90: swap,
    91: lambda text: "".join(reversed(text)),
    92: lambda text: "".join(chr(ord(c) - 32) if "a" <= c <= "z" else c for c in text),
    93: lambda text: "".join(chr(ord(c) + 32) if "A" <= c <= "Z" else c for c in text),
    94: lambda text, ch: len([c for c in text if c == ch]),
    95: lambda text: "".join(c for c in text if ord(c) != 32),
    96: lambda a, b: a.find(b) >= 0,
    97: lambda text, start, length: "".join(text[index] for index in range(start, start + length)),
    98: lambda num: pow(num, 2),
    99: lambda a, b: sorted((a, b))[-1],
    100: lambda numbers, op: sum(numbers) if op == "+" else math.prod(numbers),
}


def equivalent(actual, expected):
    if isinstance(expected, bool):
        return type(actual) is bool and actual == expected
    if type(expected) is int:
        return type(actual) is int and actual == expected
    if isinstance(expected, float):
        return type(actual) in (int, float) and math.isfinite(actual) and math.isclose(
            actual, expected, rel_tol=1e-9, abs_tol=1e-9)
    if isinstance(expected, list):
        return isinstance(actual, list) and len(actual) == len(expected) and all(
            equivalent(a, b) for a, b in zip(actual, expected))
    return type(actual) is type(expected) and actual == expected


@lru_cache(maxsize=100)
def reference(ordinal):
    problem = PROBLEMS[ordinal - 1]
    source = problem["referenceSolution"]["sourceCode"]
    tree = ast.parse(source, filename=f"level0_{ordinal:03d}.py")
    # 저장소의 고정 기준 풀이만 실행한다. 사용자 코드 경로와 연결하지 않는다.
    if not all(isinstance(node, ast.FunctionDef) for node in tree.body):
        raise AssertionError("기준 풀이의 최상위 코드는 함수 정의만 허용합니다.")
    if any(isinstance(node, (ast.Import, ast.ImportFrom)) for node in ast.walk(tree)):
        raise AssertionError("기준 풀이에는 외부 모듈이 필요하지 않습니다.")
    namespace = {"__builtins__": SAFE_BUILTINS}
    exec(compile(tree, f"level0_{ordinal:03d}.py", "exec"), namespace)
    return namespace[problem["functionSignature"]["name"]]


def random_args(ordinal, rng):
    alphabet = "abAZ09 !\"'\\\t\n"
    text = lambda: "".join(rng.choice(alphabet) for _ in range(rng.randint(0, 30)))
    num = lambda: rng.randint(-1000000, 1000000)
    if ordinal == 1:
        return []
    if ordinal in {2, 10, 11}:
        return [text(), text()]
    if ordinal in {3, 8, 13, 29, 30, 31, 35, 36, 41, 42, 43, 44, 49, 53, 54, 55, 56}:
        return [num()]
    if ordinal in {4, 12, 16, 91, 92, 93, 95}:
        return [text()]
    if ordinal == 5:
        return [num() / 8]
    if ordinal == 6:
        return [rng.choice(alphabet)]
    if ordinal in {7, 9, 21, 22, 34, 45, 46, 47, 48, 99}:
        return [num(), num()]
    if ordinal == 14:
        value = rng.randint(0, 1000000)
        return [rng.choice(("", "+", "-")) + "0" * rng.randint(0, 4) + str(value)]
    if ordinal == 15:
        return [num(), num() / 8]
    if ordinal in {17, 18}:
        return [text() or "x"]
    if ordinal in {19, 20}:
        return [[rng.randint(-1000, 1000) for _ in range(rng.randint(ordinal == 20, 100))]]
    if ordinal == 23:
        return [rng.randint(-10000, 10000), rng.randint(-10000, 10000)]
    if ordinal == 24:
        return [num() / 8, (rng.randint(1, 1000000) / 8) * rng.choice((-1, 1))]
    if ordinal in {25, 26}:
        return [rng.randint(1, 1000000), rng.randint(1, 1000000)]
    if ordinal in {27, 98}:
        return [rng.randint(-10000, 10000)]
    if ordinal == 28:
        return [rng.randint(-1000, 1000)]
    if ordinal in {32, 33}:
        return [rng.randint(-1000, 1000) for _ in range(3)]
    if ordinal in {37, 38, 39}:
        return [rng.randint(10 if ordinal == 38 else 0, 1000000)]
    if ordinal == 40:
        return [str(num()), str(num())]
    if ordinal in {50, 51}:
        return [rng.randint(0, 100)]
    if ordinal == 52:
        return [rng.randint(0, 150)]
    if ordinal in {57, 58}:
        return [num(), num(), num()]
    if ordinal == 59:
        return [rng.randint(1, 9999)]
    if ordinal == 60:
        return [rng.randint(-10000, 10000), rng.randint(-10000, 10000), rng.choice("+-*")]
    if ordinal in {61, 62, 76}:
        return [rng.randint(0, 100)]
    if ordinal in {63, 64, 65, 68}:
        return [rng.randint(0, 10000)]
    if ordinal == 66:
        return [rng.randint(1, 9)]
    if ordinal == 67:
        return [text(), rng.randint(0, 100)]
    if ordinal == 69:
        return [rng.randint(0, 1000)]
    if ordinal == 70:
        return [rng.randint(0, 12)]
    if ordinal == 71:
        return [rng.randint(-10, 10), rng.randint(0, 9)]
    if ordinal in {72, 73, 74}:
        return [rng.randint(0, 1000000000)]
    if ordinal == 75:
        return [rng.randint(1, 100000000)]
    if ordinal == 77:
        return [rng.randint(0, 50)]
    if ordinal == 78:
        return [rng.randint(0, 9)]
    if ordinal == 79:
        return [num(), rng.randint(0, 1000)]
    if ordinal == 80:
        return [rng.randint(1, 1000000)]
    if ordinal in set(range(81, 89)) | {89, 90}:
        minimum = 1 if ordinal in {82, 83, 84, 90} else 0
        numbers = [num() for _ in range(rng.randint(minimum, 100))]
        if ordinal == 89:
            return [numbers, rng.choice(numbers) if numbers and rng.choice((True, False)) else num()]
        if ordinal == 90:
            return [numbers, rng.randrange(len(numbers)), rng.randrange(len(numbers))]
        return [numbers]
    if ordinal == 94:
        return [text(), rng.choice(alphabet)]
    if ordinal == 96:
        first = text()
        return [first, first[:rng.randint(0, len(first))] if rng.choice((True, False)) else text()]
    if ordinal == 97:
        value = text()
        start = rng.randint(0, len(value))
        return [value, start, rng.randint(0, len(value) - start)]
    if ordinal == 100:
        return [[rng.randint(-5, 5) for _ in range(rng.randint(0, 8))], rng.choice("+*")]
    raise AssertionError(f"무작위 인자 정의 누락: {ordinal}")


class Level0CatalogTest(unittest.TestCase):
    def assert_typed(self, value, kind):
        if kind.endswith("[]"):
            self.assertIs(type(value), list)
            for item in value:
                self.assert_typed(item, kind[:-2])
        elif kind == "int":
            self.assertIs(type(value), int)
            self.assertGreaterEqual(value, -(2 ** 31))
            self.assertLess(value, 2 ** 31)
        elif kind == "float":
            self.assertIn(type(value), (int, float))
            self.assertTrue(math.isfinite(value))
        elif kind == "bool":
            self.assertIs(type(value), bool)
        elif kind == "string":
            self.assertIs(type(value), str)
            self.assertTrue(value.isascii())
        else:
            self.fail(f"지원하지 않는 타입: {kind}")

    def test_exact_curriculum_order_names_numbers_and_ranks(self):
        self.assertEqual(1, CATALOG["version"])
        self.assertEqual("OJ Level 0 — 기초 문법 100문제", CATALOG["name"])
        self.assertEqual(100, len(PROBLEMS))
        self.assertEqual(list(range(1, 101)), [p["ordinal"] for p in PROBLEMS])
        self.assertEqual(list(range(1000, 1100)), [p["number"] for p in PROBLEMS])
        self.assertEqual(TITLES, [p["title"] for p in PROBLEMS])
        self.assertEqual(100, len({p["title"] for p in PROBLEMS}))
        self.assertEqual(set(range(1, 101)), set(ORACLES))
        for index, problem in enumerate(PROBLEMS):
            with self.subTest(ordinal=index + 1):
                chapter = f"Lv.0-{index // 20 + 1}"
                self.assertEqual(chapter, problem["chapter"])
                self.assertEqual("INITIATE", problem["rankType"])
                self.assertEqual(5 - index // 20, problem["rankInt"])
                self.assertEqual(2000, problem["timeLimitMs"])
                self.assertEqual(256, problem["memoryLimitMb"])
                self.assertTrue({"Lv.0", chapter}.issubset(problem["tags"]))
                self.assertGreaterEqual(len(problem["tags"]), 3)
                for field in ("description", "inputDescription", "outputDescription", "constraints"):
                    self.assertTrue(problem[field].strip(), field)
                self.assertIn("ASCII", problem["constraints"])

    def test_signature_types_identifiers_and_reference_sources(self):
        for problem in PROBLEMS:
            ordinal = problem["ordinal"]
            with self.subTest(ordinal=ordinal):
                signature = problem["functionSignature"]
                self.assertEqual("solution" if ordinal in (98, 99) else "sol", signature["name"])
                self.assertIn(signature["returnType"], TYPES)
                parameters = signature["parameters"]
                names = [p["name"] for p in parameters]
                self.assertEqual(len(names), len(set(names)))
                for parameter in parameters:
                    self.assertRegex(parameter["name"], r"^[a-z][A-Za-z0-9_]{0,31}$")
                    self.assertNotIn(parameter["name"], {"str", "int", "float", "bool", "class", "for", "return"})
                    self.assertIn(parameter["type"], TYPES)
                self.assertEqual("PYTHON", problem["referenceSolution"]["language"])
                self.assertEqual("solution.py", problem["referenceSolution"]["fileName"])
                self.assertEqual(names, list(inspect.signature(reference(ordinal)).parameters))

    def test_all_examples_are_public_copies_of_real_tests(self):
        for problem in PROBLEMS:
            ordinal = problem["ordinal"]
            with self.subTest(ordinal=ordinal):
                tests = problem["tests"]
                self.assertGreaterEqual(len(tests), 1 if ordinal == 1 else 8)
                self.assertEqual(len(tests), len({t["input"] for t in tests}))
                self.assertGreaterEqual(len(problem["examples"]), 1 if ordinal == 1 else 2)
                for example in problem["examples"]:
                    self.assertTrue(example["explanation"].strip())
                    self.assertIn({"input": example["input"], "expected": example["output"]}, tests)
                for case in tests:
                    payload = json.loads(case["input"])
                    self.assertEqual({"args"}, set(payload))
                    self.assertIs(type(payload["args"]), list)

    def test_all_authored_expected_values_against_independent_oracles(self):
        for problem in PROBLEMS:
            ordinal = problem["ordinal"]
            signature = problem["functionSignature"]
            for index, case in enumerate(problem["tests"]):
                with self.subTest(ordinal=ordinal, case=index):
                    args = json.loads(case["input"])["args"]
                    expected = json.loads(case["expected"])
                    self.assertEqual(len(signature["parameters"]), len(args))
                    for arg, parameter in zip(args, signature["parameters"]):
                        self.assert_typed(arg, parameter["type"])
                    self.assert_typed(expected, signature["returnType"])
                    independently_expected = ORACLES[ordinal](*copy.deepcopy(args))
                    self.assertTrue(equivalent(expected, independently_expected), (expected, independently_expected))
                    actual = reference(ordinal)(*copy.deepcopy(args))
                    self.assert_typed(actual, signature["returnType"])
                    self.assertTrue(equivalent(actual, independently_expected), (actual, independently_expected))

    def test_seeded_random_inputs_against_independent_oracles(self):
        rng = random.Random(20261027)
        for ordinal in range(1, 101):
            problem = PROBLEMS[ordinal - 1]
            for case_index in range(30):
                with self.subTest(ordinal=ordinal, random_case=case_index):
                    args = random_args(ordinal, rng)
                    for arg, parameter in zip(args, problem["functionSignature"]["parameters"]):
                        self.assert_typed(arg, parameter["type"])
                    expected = ORACLES[ordinal](*copy.deepcopy(args))
                    actual = reference(ordinal)(*copy.deepcopy(args))
                    self.assert_typed(actual, problem["functionSignature"]["returnType"])
                    self.assertTrue(equivalent(actual, expected), (args, actual, expected))

    def test_authored_cases_kill_three_or_more_common_errors_per_chapter(self):
        mutants = {
            1: [
                (2, lambda a, b: a + b),
                (9, lambda a, b: [a, b]),
                (11, lambda a, b: a + b),
                (20, lambda numbers: numbers[-1]),
            ],
            2: [
                (24, lambda a, b: int(a / b)),
                (31, lambda num: num + 5 * 3),
                (32, lambda a, b, c: (a + b) * c),
                (33, lambda a, b, c: a + b * c),
                (38, lambda num: num // 10),
            ],
            3: [
                (41, lambda num: num >= 0),
                (49, lambda num: 1 < num < 100),
                (51, lambda score: "A" if score > 90 else grade(score - 1)),
                (54, lambda num: num % 2 == 0 or num % 5 == 0),
                (55, lambda num: num % 3 == 0 and num % 7 == 0),
                (59, lambda year: year % 4 == 0),
            ],
            4: [
                (61, lambda n: list(range(1, n))),
                (70, lambda n: 0 if n == 0 else math.factorial(n)),
                (74, lambda n: 1 if n == 0 else math.prod(map(int, str(n)))),
                (75, lambda n: str(n)[::-1]),
                (80, lambda target: target_count(target + 1)),
            ],
            5: [
                (82, lambda numbers: sum(numbers) // len(numbers)),
                (83, lambda numbers: max([0] + numbers)),
                (86, lambda numbers: sorted(n for n in numbers if n % 2)),
                (95, lambda text: "".join(text.split())),
                (97, lambda text, start, length: text[start:length]),
                (100, lambda numbers, op: sum(numbers) if op == "+" else (math.prod(numbers) if numbers else 0)),
            ],
        }
        for chapter, entries in mutants.items():
            self.assertGreaterEqual(len(entries), 3)
            for ordinal, mutant in entries:
                with self.subTest(chapter=chapter, ordinal=ordinal):
                    killed = False
                    for case in PROBLEMS[ordinal - 1]["tests"]:
                        args = json.loads(case["input"])["args"]
                        try:
                            actual = mutant(*copy.deepcopy(args))
                            killed |= not equivalent(actual, json.loads(case["expected"]))
                        except (IndexError, ValueError, ZeroDivisionError):
                            killed = True
                    self.assertTrue(killed, "흔한 오답을 검출할 테스트가 없습니다.")

    def test_boundary_rules_and_whitespace_are_explicit(self):
        self.assertEqual(1, ORACLES[72](0))
        self.assertEqual(0, ORACLES[74](0))
        self.assertEqual(21, ORACLES[75](1200))
        self.assertEqual(1, ORACLES[70](0))
        self.assertEqual(479001600, ORACLES[70](12))
        self.assertEqual(1, ORACLES[71](0, 0))
        self.assertEqual([1, 2, 3], [ORACLES[80](n) for n in (1, 3, 6)])
        self.assertEqual(["F", "D", "D", "C", "C", "B", "B", "A"],
                         [ORACLES[51](n) for n in (59, 60, 69, 70, 79, 80, 89, 90)])
        self.assertEqual("a\tb\n", ORACLES[95](" a \t b \n "))
        self.assertIn("U+0020", PROBLEMS[94]["description"])
        self.assertEqual(0, ORACLES[100]([], "+"))
        self.assertEqual(1, ORACLES[100]([], "*"))
        for ordinal in range(61, 81):
            self.assertIn("반환 결과만 검사", PROBLEMS[ordinal - 1]["description"])
        for ordinal in (98, 99):
            self.assertIn("문법적으로 강제하지", PROBLEMS[ordinal - 1]["description"])


if __name__ == "__main__":
    unittest.main()
