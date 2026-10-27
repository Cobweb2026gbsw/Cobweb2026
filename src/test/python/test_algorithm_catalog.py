"""고정 저작 카탈로그 165문제의 명세·기준풀이·ABI 및 경계 조건 검증.

호스트에서 실행하는 것은 저장소에 검토되는 Python 기준 풀이뿐이며, 사용자
제출 코드/DB의 reference는 읽거나 실행하지 않는다. 실언어 격리 검증은 별도
Docker integration suite가 담당한다.
"""
import ast
import copy
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import random
import unittest

ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location("algorithm_catalog_builder", ROOT / "scripts/build-algorithm-catalog.py")
BUILDER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILDER)
BUILDER.level1()
BUILDER.level2()
CATALOGS = {level: json.loads((ROOT / f"src/main/resources/problems/level{level}.json").read_text(encoding="utf-8")) for level in (1, 2)}
PROBLEMS = {p["number"]: p for catalog in CATALOGS.values() for p in catalog["problems"]}
TITLES_1 = "약수의 개수|약수의 합|소수 판별|소수의 개수|최대공약수|최소공배수|팩토리얼 1|피보나치 수열|자릿수 합 1|숫자 뒤집기 1|완전제곱수|등차수열의 합|등비수열의 N번째 항|부족한 금액|소수 자릿수|배열 뒤집기 1|배열 회전 1|배열 중복 제거|배열 교집합|배열 합집합|배열의 최댓값 1|두 번째 최댓값|문자열 뒤집기 1|문자열 압축|문자열 회전|회문 검사|회문 검사 1|가장 긴 단어|문자 빈도표|문자열 정렬|오름차순 정렬|내림차순 정렬|문자열 길이 정렬|정렬 후 K번째|가장 가까운 수|순위 매기기|중복 숫자 세기|최빈값|선형 탐색|이진 탐색|두 배열 비교|정렬 확인|역순 정렬 확인|최소 차이|빠진 숫자 찾기|로봇 이동|로봇 이동 1|시계 계산|날짜 계산|달력의 요일|엘리베이터|엘리베이터 1|주사위 게임|가위바위보|가위바위보 1|좌표 이동 기록|자판기 계산|점수 누적|비밀번호 입력|간단한 주차장|행렬의 합|행렬의 차|행렬의 행 합|행렬의 열 합|행렬 전치|행렬의 대각선 합|행렬 테두리 합|가장 큰 행|가장 큰 열|행렬 뒤집기|두 수의 합 찾기|세 수의 합 찾기|연속 구간 합|직사각형 개수|최소 이동 횟수".split("|")
TITLES_2 = "소수의 개수 1|소인수분해|최대공약수 1|최소공배수 1|약수의 개수 1|피보나치 수열 1|조합의 개수|순열의 개수|이진수 변환|진법 변환|진법 변환 1|다음 소수|완전수 판별|나머지 연산|분수의 덧셈|문자열 압축 1|문자열 압축 2|회문 검사 2|가장 긴 회문|애너그램 판별|애너그램 그룹|문자열 빈도 비교|중복 없는 최장 부분 문자열|첫 번째 유일 문자|문자열 패턴 찾기|접두사 검사|공통 접두사|문자열 숫자 정렬|단어 빈도 분석|가장 많이 등장한 단어|이진 탐색 1|이진 탐색 2|Lower Bound|Upper Bound|특정 숫자의 등장 횟수|좌표 압축|K번째 수 1|가장 가까운 수 1|정렬된 배열 합치기|중복 원소 제거 1|순위 매기기 1|회의 정렬|구간 병합|구간 교집합|두 배열의 차집합|스택 구현|큐 구현|덱 구현|괄호 균형 검사|괄호 균형 검사 1|문자열 폭발|올바른 문자열 제거|후위 표기식 계산|다음 큰 수|탑 신호|카드 제거|작업 대기열|해시 기반 중복 검사|두 배열의 공통 원소 1|빈도 기반 정렬|두 수의 합 찾기 1|세 수의 합 찾기 1|연속 구간 합 1|연속 구간 합 2|구간 합 계산|2차원 구간 합|최대 연속 합|고정 길이 최대 합|고정 길이 최소 합|서로 다른 수의 개수|최대 평균 구간|연속된 0과 1|부분 배열의 곱|두 정렬 배열의 중복 제거|가장 가까운 합|순열 생성|조합 생성|부분집합 생성|숫자 야구|모의 시험 채점|체스판 다시 칠하기|최소 동전 개수|최대 회의 개수|구명보트|체육복 배분|로봇 이동 2|로봇 이동 3|행렬 회전|행렬 회전 1|지뢰찾기".split("|")
TYPES = {"int", "float", "bool", "string", "int[]", "string[]", "int[][]", "string[][]"}


def validate(value, typ):
    if typ.endswith("[]"):
        if type(value) is not list:
            return False
        return all(validate(item, typ[:-2]) for item in value)
    if typ == "int":
        return type(value) is int and -2147483648 <= value <= 2147483647
    if typ == "float":
        return type(value) in (int, float) and math.isfinite(value)
    if typ == "bool":
        return type(value) is bool
    return type(value) is str and len(value) <= 16384 and value.isascii()


def slots(value):
    return len(value) + sum(slots(item) for item in value) if type(value) is list else 0


def fingerprint(value):
    """대형 fixture를 문자열 diff로 풀지 않고 정규 JSON의 크기·SHA-256만 남긴다."""
    digest = hashlib.sha256()
    size = 0
    encoder = json.JSONEncoder(ensure_ascii=True, sort_keys=True, separators=(",", ":"), allow_nan=False)
    for chunk in encoder.iterencode(value):
        encoded = chunk.encode("ascii")
        size += len(encoded)
        digest.update(encoded)
    return size, digest.hexdigest()


def assert_same_catalog(testcase, level, expected, actual):
    # unittest.assertEqual(list/dict)는 실패 시 대형 ndiff를 생성하므로 사용하지 않는다.
    expected_size, expected_hash = fingerprint(expected)
    actual_size, actual_hash = fingerprint(actual)
    if (expected_size, expected_hash) != (actual_size, actual_hash):
        testcase.fail(
            f"catalog level={level} mismatch: expected bytes={expected_size} sha256={expected_hash}; "
            f"actual bytes={actual_size} sha256={actual_hash}")


def reference(number):
    source = PROBLEMS[number]["referenceSolution"]["sourceCode"]
    tree = ast.parse(source, filename=f"trusted_catalog_{number}.py")
    if not all(isinstance(node, ast.FunctionDef) for node in tree.body):
        raise AssertionError("고정 기준 풀이 최상위에는 함수 정의만 허용")
    for node in ast.walk(tree):
        if isinstance(node, (ast.Import, ast.ImportFrom)):
            modules = [alias.name for alias in node.names] if isinstance(node, ast.Import) else [node.module]
            if any(module not in {"math", "bisect", "collections", "itertools"} for module in modules):
                raise AssertionError("기준 풀이의 import 허용 목록 위반")
    namespace = {}
    exec(compile(tree, f"trusted_catalog_{number}.py", "exec"), namespace)
    return namespace["sol"]


class AlgorithmCatalogTest(unittest.TestCase):
    def test_exact_titles_numbering_and_existing_titles_are_unique(self):
        self.assertEqual(TITLES_1, [p["title"] for p in CATALOGS[1]["problems"]])
        self.assertEqual(TITLES_2, [p["title"] for p in CATALOGS[2]["problems"]])
        self.assertEqual(list(range(1101, 1266)), list(PROBLEMS))
        level0 = json.loads((ROOT / "src/main/resources/problems/level0.json").read_text(encoding="utf-8"))
        titles = [p["title"].strip().casefold() for p in level0["problems"] + list(PROBLEMS.values())] + ["체인 레이저"]
        self.assertEqual(len(titles), len(set(titles)))

    def test_reproducible_generator_and_ranks(self):
        for level in (1, 2):
            with self.subTest(level=level):
                assert_same_catalog(self, level, BUILDER.SPECS[level], CATALOGS[level]["problems"])
                for ordinal, problem in enumerate(CATALOGS[level]["problems"], 1):
                    chapter = (ordinal - 1) // 15
                    self.assertEqual(ordinal, problem["ordinal"])
                    self.assertEqual(f"Lv.{level}-{chapter + 1}", problem["chapter"])
                    self.assertEqual("SKILLED" if level == 1 else "ELITE", problem["rankType"])
                    self.assertEqual(([5, 4, 3, 2, 1] if level == 1 else [5, 4, 3, 3, 2, 1])[chapter], problem["rankInt"])
                    self.assertEqual(10000 if level == 2 else 2000, problem["timeLimitMs"])
                    self.assertEqual(512 if level == 2 else 256, problem["memoryLimitMb"])

    def test_large_catalog_mismatch_has_a_bounded_message_without_fixture_content(self):
        expected = [{"source": "private-sentinel-" + "x" * 100000, "args": list(range(10000))}]
        actual = copy.deepcopy(expected)
        actual[0]["source"] += "y"
        with self.assertRaises(self.failureException) as failure:
            assert_same_catalog(self, 99, expected, actual)
        message = str(failure.exception)
        self.assertLess(len(message), 512)
        self.assertIn("catalog level=99 mismatch", message)
        self.assertIn("sha256=", message)
        self.assertNotIn("private-sentinel", message)
        self.assertNotIn("xxxxx", message)

    def test_problem_statements_and_canonical_signature(self):
        for number, problem in PROBLEMS.items():
            with self.subTest(number=number):
                self.assertTrue(all(problem[key].strip() for key in ("title", "description", "inputDescription", "outputDescription", "constraints")))
                signature = problem["functionSignature"]
                self.assertEqual("sol", signature["name"])
                self.assertLessEqual(len(signature["parameters"]), 6)
                self.assertEqual(len(signature["parameters"]), len({p["name"] for p in signature["parameters"]}))
                self.assertIn(signature["returnType"], TYPES)
                for parameter in signature["parameters"]:
                    self.assertIn(parameter["type"], TYPES)
                    self.assertRegex(parameter["name"], r"^[A-Za-z][A-Za-z0-9_]*$")

    def test_all_cases_fit_shared_types_byte_and_slot_limits(self):
        for number, problem in PROBLEMS.items():
            signature = problem["functionSignature"]
            for index, case in enumerate(problem["tests"]):
                with self.subTest(number=number, case=index):
                    envelope = json.loads(case["input"])
                    self.assertEqual({"args"}, set(envelope))
                    args = envelope["args"]
                    self.assertEqual(len(signature["parameters"]), len(args))
                    for parameter, value in zip(signature["parameters"], args):
                        self.assertTrue(validate(value, parameter["type"]), parameter)
                    expected = json.loads(case["expected"])
                    self.assertTrue(validate(expected, signature["returnType"]))
                    self.assertLessEqual(sum(slots(arg) for arg in args), 1000000)
                    self.assertLessEqual(slots(expected), 1000000)
                    self.assertLessEqual(len(case["input"].encode()), 16 * 1024 * 1024)
                    self.assertLessEqual(len(case["expected"].encode()), 16 * 1024 * 1024)

    def test_distinct_examples_private_coverage_and_fixed_reference_oracle(self):
        count = 0
        for number, problem in PROBLEMS.items():
            solution = reference(number)
            oracle = BUILDER.ORACLES[number]
            cases = problem["tests"]
            with self.subTest(number=number):
                self.assertGreaterEqual(len(cases), 9 if number == 1154 else 10)
                self.assertEqual(len(cases), len({case["input"] for case in cases}))
                self.assertEqual(2, len(problem["examples"]))
                for example in problem["examples"]:
                    if {"input": example["input"], "expected": example["output"]} not in cases:
                        self.fail(f"problem {number}: public example is missing from stored tests")
                for index, case in enumerate(cases):
                    with self.subTest(number=number, case=index):
                        args = json.loads(case["input"])["args"]
                        expected = json.loads(case["expected"])
                        self.assertTrue(BUILDER.equivalent(oracle(*copy.deepcopy(args)), expected))
                        self.assertTrue(BUILDER.equivalent(solution(*copy.deepcopy(args)), expected))
                        count += 1
        self.assertGreaterEqual(count, 2000)

    def test_exhaustive_small_math_inputs_against_separate_oracles(self):
        rng = random.Random(165)
        for number in (1101, 1102, 1103, 1104):
            solution, oracle = reference(number), BUILDER.ORACLES[number]
            for value in range(1, 101):
                self.assertEqual(oracle(value), solution(value))
        for number in (1105, 1106):
            solution, oracle = reference(number), BUILDER.ORACLES[number]
            for _ in range(100):
                a, b = rng.randint(1, 30000), rng.randint(1, 30000)
                self.assertEqual(oracle(a, b), solution(a, b))

    def test_nested_shape_order_and_ties(self):
        self.assertEqual([[], [1], [2], [1, 2]], reference(1253)([2, 1]))
        self.assertEqual([[1, 2], [2, 1]], reference(1251)([2, 1]))
        self.assertEqual([[0], []], reference(1215)([]))
        self.assertEqual([["", ""], ["ab", "ba"]], reference(1196)(["ba", "", "ab", ""]))
        self.assertEqual([[-1, 0, 1]], reference(1237)([-1, 0, 1, -1]))
        self.assertEqual([1, 4], reference(1250)([1, 2, 3, 4], 5))

    def test_randomized_algorithms_against_independent_bruteforce(self):
        rng = random.Random(20261027)
        solutions = {number: reference(number) for number in PROBLEMS}
        def check(number, *args):
            expected = BUILDER.ORACLES[number](*copy.deepcopy(args))
            actual = solutions[number](*copy.deepcopy(args))
            self.assertTrue(BUILDER.equivalent(actual, expected), (number, args, expected, actual))
        for _ in range(100):
            array = [rng.randint(-5, 5) for _ in range(rng.randrange(9))]
            other = [rng.randint(-5, 5) for _ in range(rng.randrange(9))]
            target = rng.randint(-10, 10)
            for number in (1118, 1131, 1132, 1142, 1143, 1211, 1229, 1233, 1235, 1237):
                check(number, array)
            for number in (1119, 1120, 1141, 1220, 1234):
                check(number, array, other)
            for number in (1137, 1139, 1171, 1172, 1173):
                check(number, array, target)
            for number in (1206, 1207, 1208, 1209, 1210, 1236):
                check(number, sorted(array), target)
            if array:
                for number in (1121, 1122, 1138, 1242):
                    check(number, array)
                check(1135, array, target)
                check(1213, sorted(array), target)
                k = rng.randint(1, len(array))
                for number in (1243, 1244, 1245, 1246):
                    check(number, array, k)
                left = rng.randrange(len(array))
                right = rng.randrange(left, len(array))
                check(1116, array, left, right)
            if len(array) >= 2:
                check(1144, array)
                check(1250, array, target)
            positive = [abs(value) + 1 for value in array]
            for number in (1238, 1239, 1248):
                check(number, positive, rng.randint(1, 15))
            check(1259, positive, 12)
            check(1257, [rng.randint(1, 8) for _ in range(rng.randrange(5))], rng.randrange(30))
            n = rng.randint(1, 8)
            lost = [i for i in range(1, n + 1) if rng.randrange(2)]
            reserve = [i for i in range(1, n + 1) if rng.randrange(2)]
            check(1260, n, lost, reserve)
            text = ''.join(rng.choice('abc') for _ in range(rng.randrange(15)))
            check(1192, text, rng.randint(1, 6))
            check(1194, text)
            check(1198, text)
            check(1227, text)
            rows, cols = rng.randint(2, 5), rng.randint(2, 5)
            matrix = [[rng.randint(-10, 10) for _ in range(cols)] for _ in range(rows)]
            for number in (1163, 1164, 1165, 1167, 1168, 1169, 1170):
                check(number, matrix)
            top, bottom = sorted(rng.sample(range(rows), 2))
            left, right = sorted(rng.sample(range(cols), 2))
            check(1264, matrix, top, left, bottom, right)

    def test_explicit_large_input_ranges_are_preserved_and_executable(self):
        self.assertIn("100000", PROBLEMS[1178]["constraints"])
        self.assertIn("1000000", PROBLEMS[1233]["constraints"])
        self.assertIn("1000000", PROBLEMS[1181]["constraints"])
        self.assertEqual(6, reference(1178)([6 * (i + 1) for i in range(100000)]))
        self.assertEqual(918091266, reference(1181)(1000000))
        self.assertFalse(reference(1233)(list(range(1000000))))
        self.assertTrue(reference(1233)(list(range(999999)) + [0]))
        self.assertGreaterEqual(max(len(json.loads(case["input"])["args"][0]) for case in PROBLEMS[1178]["tests"]), 10000)
        self.assertGreaterEqual(max(len(json.loads(case["input"])["args"][0]) for case in PROBLEMS[1233]["tests"]), 10000)

    def test_undefined_mechanics_have_explicit_deterministic_rules(self):
        for number, expected in ((1153, "1000"), (1160, "30분"), (1194, "공백"), (1204, "십진수 문자열"), (1215, "유효한 길이"), (1248, "미만"), (1256, "전체"), (1262, "0:북")):
            with self.subTest(number=number):
                self.assertIn(expected, PROBLEMS[number]["description"])


if __name__ == "__main__":
    unittest.main()
