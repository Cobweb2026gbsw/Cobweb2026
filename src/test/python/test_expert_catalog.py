"""EXPERT 80문제의 원문 주제·결정적 생성·ABI·참고 풀이를 검증한다.

호스트에서 실행하는 코드는 저장소의 고정 저작 참고 풀이뿐이다. 사용자
제출/실제 DB의 코드는 실행하지 않으며 실제 컴파일은 선택 Docker 회귀가 담당한다.
대형 결과의 실패 메시지는 내용 대신 크기와 SHA-256만 표시한다.
"""
import ast
import copy
import hashlib
import importlib.util
import itertools
import json
import math
from pathlib import Path
import random
import unittest

ROOT = Path(__file__).resolve().parents[3]


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


BUILDER = load_module("expert_catalog_builder_test", ROOT / "scripts/build-expert-catalog.py")
GENERIC = load_module("expert_catalog_abi_test", ROOT / "judge/generic_function.py")
TITLES = (
    "재귀 팩토리얼|피보나치 수열 2|재귀로 배열 뒤집기|재귀로 자릿수 합|하노이의 탑|순열 생성 1|조합 생성 1|중복 순열|중복 조합|부분집합의 합|N-Queen|N-Queen 1|암호 만들기|연산자 끼워 넣기|숫자 조합 소수|스도쿠 해결|"
    "그래프 DFS|그래프 BFS|연결 요소의 개수|연결 요소의 크기|미로 최단 경로|미로 최단 경로 1|단지 번호 붙이기|섬의 개수|섬의 개수 1|바이러스 전파|최단 거리 배열|숨바꼭질|숨바꼭질 1|토마토 익히기|토마토 익히기 1|연쇄 레이저 1|"
    "계단 오르기|계단 오르기 1|피보나치 수열 3|1로 만들기|1로 만들기 1|최소 동전 개수 1|동전 조합|타일 채우기|타일 채우기 1|최대 연속 합 1|가장 긴 증가 부분 수열|가장 긴 감소 부분 수열|정수 삼각형|격자 경로의 수|격자 경로의 수 1|배낭 문제|"
    "트리의 부모 찾기|트리의 깊이|트리의 높이|트리의 리프 개수|이진 트리 전위 순회|이진 트리 중위 순회|이진 트리 후위 순회|이진 트리 레벨 순회|이진 탐색 트리 검사|트리의 지름|이분 그래프 판별|사이클 판별|사이클 판별 1|위상 정렬|선수 과목|도시 연결|"
    "최대 회의 개수 1|회의실 배정|최소 점프 횟수|최소 점프 횟수 1|구간 덮기|구간 병합 1|물류 상자 배치|랜선 자르기|나무 자르기|공유기 설치|예산 배분|숫자 카드 합치기|작업 스케줄러|로봇 이동 4|미로 최단 경로 2|연구소 안전 구역"
).split("|")
EARLIER_SHA256 = {
    0: "37cafd013c9222cbcaf8885a052afc549174790228d20f2fa8c3cfb634c1082f",
    1: "90eaea58e0a9714c70dfb40ca9e7a51ff37dcc88e7fb651d3d74e75deec3cecf",
    2: "d771d1a7533b3305da91d46ab716fc274c2263b6c8d30ce2c5db9268dbc1d73c",
}
# 요약문을 그대로 반복할 필요는 없지만, 사용자 원문의 핵심 과제가 본문에서
# 빠져 범용 sol 안내만 남는 회귀는 문제별 의미 표지로 차단한다.
STATEMENT_MARKERS = (
    ("재귀", "n!"), ("메모이제이션", "F(n)"), ("재귀", "배열"), ("재귀", "자릿수"),
    ("원판", "이동"), ("순열", "r"), ("조합", "백트래킹"), ("순열", "여러 번"),
    ("중복", "조합"), ("부분집합", "target"), ("퀸", "공격"), ("퀸", "열"),
    ("암호", "사전순"), ("연산자", "최댓값", "최솟값"), ("카드", "소수"), ("스도쿠", "9×9"),
    ("DFS", "방문"), ("BFS", "방문"), ("연결 요소", "개수"), ("연결 요소", "오름차순"),
    ("벽", "최소 이동"), ("최단 경로", "좌표"), ("단지", "상하좌우"), ("육지", "상하좌우"),
    ("대각선", "8방향"), ("감염", "start"), ("그래프", "간선 수"), ("위치", "최소 이동"),
    ("최단 경로", "위치"), ("토마토", "하루"), ("3차원", "6방향"), ("레이저", "연쇄", "서로 다른 칸"),
    ("계단", "1칸", "2칸"), ("연속된 세 계단", "최대"), ("행렬 거듭제곱", "1,000,000,007"), ("1 빼기", "최소"),
    ("최소", "경로"), ("동전", "DP"), ("동전", "조합"), ("2×n", "도미노"),
    ("2×2", "타일"), ("연속 구간", "삭제"), ("엄격히 증가", "부분 수열"), ("엄격히 감소", "부분 수열"),
    ("삼각형", "최댓값"), ("오른쪽", "아래쪽", "경로 수"), ("장애물", "경로 수"), ("무게", "가치", "최대 한 번"),
    ("루트", "부모"), ("루트", "깊이"), ("리프", "간선 수"), ("리프", "개수"),
    ("전위", "왼쪽", "오른쪽"), ("중위", "왼쪽", "오른쪽"), ("후위", "왼쪽", "오른쪽"), ("깊이별", "왼쪽"),
    ("이진 탐색 트리", "모든 값"), ("지름", "최댓값"), ("두 집합", "간선"), ("무방향", "사이클"),
    ("방향", "사이클"), ("위상 정렬", "작은 번호"), ("과목", "먼저"), ("모든 도시", "이동"),
    ("회의", "번호"), ("회의", "최소"), ("점프", "최소"), ("점프", "경로"),
    ("구간", "덮", "최소"), ("구간", "병합", "point"), ("상자", "두"), ("랜선", "최대"),
    ("나무", "높이"), ("공유기", "거리"), ("요청", "상한액"), ("카드", "비용"),
    ("작업", "평균"), ("전진", "회전"), ("벽", "최대 한"), ("벽", "3", "바이러스"),
)


def fingerprint(value):
    digest, size = hashlib.sha256(), 0
    encoder = json.JSONEncoder(ensure_ascii=True, sort_keys=True, separators=(",", ":"), allow_nan=False)
    for chunk in encoder.iterencode(value):
        encoded = chunk.encode("ascii")
        size += len(encoded)
        digest.update(encoded)
    return size, digest.hexdigest()


def assert_same(testcase, label, expected, actual):
    left, right = fingerprint(expected), fingerprint(actual)
    if left != right:
        testcase.fail(f"{label}: expected bytes={left[0]} sha256={left[1]}; actual bytes={right[0]} sha256={right[1]}")


def reference(problem):
    source = problem["referenceSolution"]["sourceCode"]
    tree = ast.parse(source, filename=f"trusted_expert_{problem['number']}.py")
    if not all(isinstance(node, ast.FunctionDef) for node in tree.body):
        raise AssertionError("참고 풀이 최상위에는 함수 정의만 허용합니다")
    for node in ast.walk(tree):
        if isinstance(node, (ast.Import, ast.ImportFrom)):
            modules = [alias.name for alias in node.names] if isinstance(node, ast.Import) else [node.module]
            if any(name not in {"math", "bisect", "collections", "itertools", "functools", "heapq"} for name in modules):
                raise AssertionError("참고 풀이 import 허용 목록 위반")
    namespace = {}
    exec(compile(tree, f"trusted_expert_{problem['number']}.py", "exec"), namespace)
    return namespace["sol"]


class ExpertCatalogTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.generated = copy.deepcopy(BUILDER.build())
        cls.catalog = json.loads((ROOT / "src/main/resources/problems/level3.json").read_text(encoding="utf-8"))
        cls.problems = {problem["number"]: problem for problem in cls.catalog["problems"]}
        cls.solutions = {number: reference(problem) for number, problem in cls.problems.items()}

    def solution(self, ordinal):
        return self.solutions[1265 + ordinal]

    def test_generated_document_is_canonical_and_reproducible(self):
        assert_same(self, "generated EXPERT document", self.generated, self.catalog)
        assert_same(self, "repeat generated EXPERT document", self.generated, BUILDER.build())
        self.assertEqual(1, self.catalog["version"])
        self.assertTrue(self.catalog["name"].strip())

    def test_exact_user_title_order_numbering_and_rank_subdivisions(self):
        self.assertEqual(80, len(TITLES))
        self.assertEqual(TITLES, [problem["title"] for problem in self.catalog["problems"]])
        self.assertEqual(list(range(1266, 1346)), list(self.problems))
        for ordinal, problem in enumerate(self.catalog["problems"], 1):
            with self.subTest(ordinal=ordinal):
                self.assertEqual(ordinal, problem["ordinal"])
                self.assertEqual("EXPERT", problem["rankType"])
                self.assertEqual(f"Lv.3-{(ordinal - 1) // 16 + 1}", problem["chapter"])
                self.assertEqual(5 - (ordinal - 1) // 16, problem["rankInt"])
                self.assertTrue(100 <= problem["timeLimitMs"] <= 10000)
                self.assertTrue(64 <= problem["memoryLimitMb"] <= 512)

    def test_every_original_topic_has_a_real_statement_and_complete_signature(self):
        self.assertEqual(80, len(STATEMENT_MARKERS))
        for ordinal, problem in enumerate(self.catalog["problems"], 1):
            with self.subTest(ordinal=ordinal):
                for key in ("description", "inputDescription", "outputDescription", "constraints"):
                    self.assertTrue(problem[key].strip(), key)
                body = problem["description"].split("`sol` 함수를 구현", 1)[0]
                self.assertGreaterEqual(len(body), 30)
                for marker in STATEMENT_MARKERS[ordinal - 1]:
                    self.assertIn(marker, body)
                signature = GENERIC.signature(problem["functionSignature"])
                self.assertEqual("sol", signature["name"])
                self.assertEqual(len(signature["parameters"]), len({p["name"].casefold() for p in signature["parameters"]}))
                self.assertLessEqual(len(signature["parameters"]), 6)
                self.assertTrue(problem["tags"])
                self.assertLessEqual(len(problem["tags"]), 10)
                ref = problem["referenceSolution"]
                self.assertEqual("PYTHON", ref["language"])
                self.assertRegex(ref["fileName"], r"^[A-Za-z0-9_.-]{1,120}$")
                self.assertLessEqual(len(ref["sourceCode"].encode("utf-8")), 65536)

    def test_existing_catalogs_and_global_title_namespace_remain_unchanged(self):
        titles = ["체인 레이저"]
        for level, expected in EARLIER_SHA256.items():
            path = ROOT / f"src/main/resources/problems/level{level}.json"
            self.assertEqual(expected, hashlib.sha256(path.read_bytes()).hexdigest())
            titles.extend(p["title"] for p in json.loads(path.read_text(encoding="utf-8"))["problems"])
        titles.extend(TITLES)
        normalized = [title.strip().casefold() for title in titles]
        self.assertEqual(len(normalized), len(set(normalized)))

    def test_two_examples_and_ten_to_fifty_distinct_private_cases_are_valid_in_shared_abi(self):
        self.assertEqual({"PYTHON", "C", "CPP", "JAVA", "JAVASCRIPT", "TYPESCRIPT", "CSHARP", "GO", "RUST", "KOTLIN", "RUBY", "LLVM_IR", "PASCAL", "PHP", "DART"}, set(GENERIC.LANGUAGES))
        self.assertEqual(15, len(GENERIC.LANGUAGES))
        total = 0
        for number, problem in self.problems.items():
            signature = problem["functionSignature"]
            cases = problem["tests"]
            with self.subTest(number=number):
                self.assertEqual(2, len(problem["examples"]))
                self.assertTrue(10 <= len(cases) <= 50)
                self.assertEqual(len(cases), len({case["input"] for case in cases}))
                stored = {(case["input"], case["expected"]) for case in cases}
                for example in problem["examples"]:
                    self.assertIn((example["input"], example["output"]), stored)
                self.assertEqual(2, len({example["input"] for example in problem["examples"]}))
                for index, case in enumerate(cases):
                    with self.subTest(number=number, case=index):
                        envelope, expected = json.loads(case["input"]), json.loads(case["expected"])
                        self.assertEqual({"args"}, set(envelope))
                        self.assertEqual(len(signature["parameters"]), len(envelope["args"]))
                        GENERIC.payload(envelope, signature)
                        wire = " ".join(GENERIC.encode(expected, signature["returnType"])).encode("ascii")
                        assert_same(self, f"ABI return roundtrip {number}/{index}", expected, GENERIC.decode(wire, signature["returnType"]))
                        self.assertLessEqual(len(case["input"].encode("utf-8")), 16 * 1024 * 1024)
                        self.assertLessEqual(len(case["expected"].encode("utf-8")), 16 * 1024 * 1024)
                        total += 1
        self.assertGreaterEqual(total, 800)

    def test_all_stored_answers_match_both_reference_and_separate_builder_oracle(self):
        count = 0
        for number, problem in self.problems.items():
            for index, case in enumerate(problem["tests"]):
                with self.subTest(number=number, case=index):
                    args, expected = json.loads(case["input"])["args"], json.loads(case["expected"])
                    for label, function in (("reference", self.solutions[number]), ("oracle", BUILDER.ORACLES[number])):
                        actual = function(*copy.deepcopy(args))
                        if not BUILDER.equivalent(actual, expected):
                            assert_same(self, f"{label} {number}/{index}", expected, actual)
                    count += 1
        self.assertGreaterEqual(count, 800)

    def test_large_mismatch_output_is_bounded_and_does_not_disclose_case_contents(self):
        expected = {"private": "private-sentinel-" + "x" * 100000, "args": list(range(10000))}
        actual = copy.deepcopy(expected)
        actual["args"][-1] += 1
        with self.assertRaises(self.failureException) as failure:
            assert_same(self, "EXPERT mismatch", expected, actual)
        message = str(failure.exception)
        self.assertLess(len(message), 512)
        self.assertIn("sha256=", message)
        self.assertNotIn("private-sentinel", message)
        self.assertNotIn("xxxxx", message)

    def test_small_combinatorics_against_independent_enumeration(self):
        rng = random.Random(2026102803)
        for n in range(13):
            self.assertEqual(math.factorial(n), self.solution(1)(n))
        for _ in range(40):
            values = rng.sample(range(-5, 6), rng.randint(0, 6))
            size = rng.randint(0, len(values))
            ordered = sorted(values)
            assert_same(self, "permutations", [list(row) for row in itertools.permutations(ordered, size)], self.solution(6)(values, size))
            assert_same(self, "combinations", [list(row) for row in itertools.combinations(ordered, size)], self.solution(7)(values, size))
            array = [rng.randint(-3, 3) for _ in range(rng.randrange(9))]
            target = rng.randint(-5, 5)
            expected = sum(sum(value for index, value in enumerate(array) if mask >> index & 1) == target for mask in range(1, 1 << len(array)))
            self.assertEqual(expected, self.solution(10)(array, target))
        known = (1, 0, 0, 2, 10, 4, 40, 92)
        for n, count in enumerate(known, 1):
            self.assertEqual(count, self.solution(11)(n))
            placements = self.solution(12)(n)
            self.assertEqual(count, len(placements))
            self.assertTrue(placements == sorted(placements))
            for placement in placements:
                self.assertEqual(n, len(set(placement)))
                self.assertTrue(all(abs(placement[a] - placement[b]) != b - a for a in range(n) for b in range(a + 1, n)))

    def test_hanoi_moves_are_minimal_legal_and_reach_the_target(self):
        for n in range(10):
            moves = self.solution(5)(n)
            self.assertEqual((1 << n) - 1, len(moves))
            pegs = [list(range(n, 0, -1)), [], []]
            for source, target in moves:
                self.assertIn(source, (1, 2, 3)); self.assertIn(target, (1, 2, 3)); self.assertNotEqual(source, target)
                self.assertTrue(pegs[source - 1])
                disk = pegs[source - 1].pop()
                self.assertTrue(not pegs[target - 1] or disk < pegs[target - 1][-1])
                pegs[target - 1].append(disk)
            assert_same(self, f"Hanoi n={n}", [[], [], list(range(n, 0, -1))], pegs)

    def test_small_graphs_against_floyd_warshall_and_exhaustive_coloring(self):
        rng = random.Random(2026102804)
        for _ in range(60):
            n = rng.randint(1, 6)
            edges = [[a, b] for a in range(1, n + 1) for b in range(a + 1, n + 1) if rng.randrange(3) == 0]
            start = rng.randint(1, n)
            distance = [[0 if a == b else n + 1 for b in range(n)] for a in range(n)]
            for a, b in edges:
                distance[a - 1][b - 1] = distance[b - 1][a - 1] = 1
            for middle in range(n):
                for a in range(n):
                    for b in range(n):
                        distance[a][b] = min(distance[a][b], distance[a][middle] + distance[middle][b])
            expected = [value if value <= n else -1 for value in distance[start - 1]]
            assert_same(self, "graph shortest paths", expected, self.solution(27)(n, edges, start))
            components = {tuple(b for b, value in enumerate(row) if value <= n) for row in distance}
            sizes = sorted(map(len, components))
            self.assertEqual(len(sizes), self.solution(19)(n, edges))
            assert_same(self, "component sizes", sizes, self.solution(20)(n, edges))
            bipartite = any(all((mask >> (a - 1) & 1) != (mask >> (b - 1) & 1) for a, b in edges) for mask in range(1 << n))
            self.assertEqual(bipartite, self.solution(59)(n, edges))
            self.assertEqual(len(components) == 1, self.solution(64)(n, edges))

    def test_dp_against_subsequence_subset_and_deleted_interval_enumeration(self):
        rng = random.Random(2026102805)
        for _ in range(60):
            numbers = [rng.randint(-8, 8) for _ in range(rng.randrange(9))]
            subsequences = [[value for index, value in enumerate(numbers) if mask >> index & 1] for mask in range(1 << len(numbers))]
            for ordinal, compare in ((43, lambda a, b: a < b), (44, lambda a, b: a > b)):
                expected = max(len(row) for row in subsequences if all(compare(a, b) for a, b in zip(row, row[1:])))
                self.assertEqual(expected, self.solution(ordinal)(numbers))
            if numbers:
                sums = []
                for left in range(len(numbers)):
                    for right in range(left + 1, len(numbers) + 1):
                        segment = numbers[left:right]
                        sums.append(sum(segment))
                        if len(segment) > 1:
                            sums.extend(sum(segment) - value for value in segment)
                self.assertEqual(max(sums), self.solution(42)(numbers))
            weights = [rng.randint(1, 7) for _ in range(rng.randrange(9))]
            values = [rng.randint(0, 20) for _ in weights]
            capacity = rng.randint(0, 15)
            expected = max(sum(value for i, value in enumerate(values) if mask >> i & 1) for mask in range(1 << len(weights)) if sum(weight for i, weight in enumerate(weights) if mask >> i & 1) <= capacity)
            self.assertEqual(expected, self.solution(48)(weights, values, capacity))

    def test_lexicographic_topological_order_against_all_permutations(self):
        rng = random.Random(2026102806)
        for _ in range(25):
            n = rng.randint(1, 6)
            order = rng.sample(range(1, n + 1), n)
            edges = [[order[a], order[b]] for a in range(n) for b in range(a + 1, n) if rng.randrange(3) == 0]
            candidates = []
            for permutation in itertools.permutations(range(1, n + 1)):
                index = {vertex: i for i, vertex in enumerate(permutation)}
                if all(index[a] < index[b] for a, b in edges):
                    candidates.append(list(permutation))
            assert_same(self, "topological order", min(candidates), self.solution(62)(n, edges))

    def test_laser_closure_and_three_dimensional_tomatoes_against_direct_simulation(self):
        rng = random.Random(2026102807)
        for _ in range(60):
            rows, cols = rng.randint(1, 5), rng.randint(1, 5)
            matrix = [[rng.randrange(2) for _ in range(cols)] for _ in range(rows)]
            start_row, start_col = rng.randrange(rows), rng.randrange(cols)
            fired = {(start_row, start_col)}
            while True:
                reached = {(r, c) for r in range(rows) for c in range(cols) if any(r == a or c == b for a, b in fired)}
                next_fired = fired | {(r, c) for r, c in reached if matrix[r][c] == 1}
                if fired == next_fired:
                    break
                fired = next_fired
            self.assertEqual(len(reached), self.solution(32)(matrix, start_row, start_col))
            depth, height, width = rng.randint(1, 3), rng.randint(1, 3), rng.randint(1, 3)
            layers = [[rng.choice((-1, 0, 0, 1)) for _ in range(height * width)] for _ in range(depth)]
            state, days = copy.deepcopy(layers), 0
            while any(0 in layer for layer in state):
                changes = []
                for z in range(depth):
                    for r in range(height):
                        for c in range(width):
                            if state[z][r * width + c] != 0:
                                continue
                            neighbors = ((z - 1, r, c), (z + 1, r, c), (z, r - 1, c), (z, r + 1, c), (z, r, c - 1), (z, r, c + 1))
                            if any(0 <= a < depth and 0 <= b < height and 0 <= d < width and state[a][b * width + d] == 1 for a, b, d in neighbors):
                                changes.append((z, r * width + c))
                if not changes:
                    days = -1
                    break
                for z, index in changes:
                    state[z][index] = 1
                days += 1
            self.assertEqual(days, self.solution(31)(layers, height, width))


if __name__ == "__main__":
    unittest.main()
