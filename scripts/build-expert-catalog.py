#!/usr/bin/env python3
"""EXPERT 80문제를 결정적으로 생성한다. 기존 Lv.0~2/SQL은 수정하지 않는다.

실행하는 코드는 이 저장소의 고정 저작 기준 풀이뿐이다. 각 저장 케이스는
별도 오라클과 대조하고, JSON 타입·ABI 크기·번호·제목을 검증한 뒤 저장한다.
"""
from __future__ import annotations

import ast
import copy
import importlib.util
import json
import math
from pathlib import Path
import textwrap

ROOT = Path(__file__).resolve().parents[1]
CHAPTERS = ("재귀·백트래킹", "DFS·BFS", "동적 계획법", "그래프·트리", "그리디·탐색·복합 구현")
TYPES = {"int", "float", "bool", "string", "int[]", "string[]", "int[][]", "string[][]"}
ALLOWED_IMPORTS = {"math", "bisect", "collections", "itertools", "functools", "heapq"}
SPECS, ORACLES, CASES = [], {}, {}


def compact(value):
    return json.dumps(value, ensure_ascii=True, separators=(",", ":"), allow_nan=False)


def valid(value, typ):
    if typ.endswith("[]"):
        return type(value) is list and all(valid(item, typ[:-2]) for item in value)
    if typ == "int":
        return type(value) is int and -2147483648 <= value <= 2147483647
    if typ == "float":
        return type(value) in (int, float) and math.isfinite(value)
    if typ == "bool":
        return type(value) is bool
    return type(value) is str and value.isascii() and len(value) <= 16384


def slots(value):
    return len(value) + sum(slots(item) for item in value) if type(value) is list else 0


def equivalent(actual, expected):
    if type(expected) is float:
        return type(actual) in (int, float) and math.isfinite(actual) and math.isclose(actual, expected, rel_tol=1e-9, abs_tol=1e-9)
    if type(expected) is list:
        return type(actual) is list and len(actual) == len(expected) and all(equivalent(a, b) for a, b in zip(actual, expected))
    return type(actual) is type(expected) and actual == expected


def trusted_reference(source, number):
    tree = ast.parse(source, filename=f"expert_reference_{number}.py")
    if not all(isinstance(node, ast.FunctionDef) for node in tree.body):
        raise AssertionError((number, "reference top level must contain only function definitions"))
    for node in ast.walk(tree):
        if isinstance(node, (ast.Import, ast.ImportFrom)):
            modules = [alias.name for alias in node.names] if isinstance(node, ast.Import) else [node.module]
            if any(module not in ALLOWED_IMPORTS for module in modules):
                raise AssertionError((number, "reference import is not allowlisted"))
    namespace = {}
    exec(compile(tree, f"expert_reference_{number}.py", "exec"), namespace)
    return namespace["sol"]


def stress_cases(ordinal):
    """큰 입력은 기준 풀이를 복제하지 않고 항등식으로 정답이 확정되는 형태를 쓴다."""
    if ordinal == 3:
        return [([list(range(500))], list(range(499, -1, -1)))]
    if ordinal == 17:
        return [([500, [[i, i + 1] for i in range(1, 500)], 1], list(range(1, 501)))]
    if ordinal == 31:
        layers = [[0] * 900 for _ in range(30)]
        layers[0][0] = 1
        return [([layers, 30, 30], 29 + 29 + 29)]
    if ordinal == 32:
        return [([[[1] * 100 for _ in range(100)], 0, 0], 10000)]
    if ordinal == 43:
        return [([list(range(100000))], 100000)]
    if ordinal == 44:
        return [([list(range(100000, 0, -1))], 100000)]
    if ordinal == 49:
        return [([100000, [[i, i + 1] for i in range(1, 100000)]], list(range(100000)))]
    if ordinal == 65:
        return [([[[i, i + 1] for i in range(100000)]], [[100000], list(range(100000))])]
    if ordinal == 67:
        return [([[1] * 100000], 99999)]
    if ordinal == 68:
        return [([[1] * 100000], [[1], list(range(100000))])]
    if ordinal == 79:
        return [([[[0] * 300 for _ in range(300)], [0, 0], [299, 299]], 598)]
    if ordinal == 80:
        grid = [[0] * 8 for _ in range(8)]
        grid[0][0] = 2
        # 바이러스 옆 두 칸을 막고 남은 벽 하나를 놓으면 나머지 60칸이 안전하다.
        return [([grid], 60)]
    return []


def add(title, params, result, description, constraints, body, cases, oracle):
    ordinal = len(SPECS) + 1
    number, chapter_index = 1265 + ordinal, (ordinal - 1) // 16
    if result not in TYPES or not 0 <= len(params) <= 6 or any(typ not in TYPES for _, typ in params):
        raise AssertionError((number, "unsupported signature"))
    names = [name for name, _ in params]
    if len(names) != len(set(names)) or any(not name.isascii() or not name.isidentifier() for name in names):
        raise AssertionError((number, "invalid parameter names"))
    source = "def sol(" + ", ".join(names) + "):\n" + textwrap.indent(textwrap.dedent(body).strip(), "    ") + "\n"
    reference = trusted_reference(source, number)
    specials = stress_cases(ordinal)
    analytic = {compact(args): expected for args, expected in specials}
    def checked_oracle(*args):
        key = compact(list(args))
        return copy.deepcopy(analytic[key]) if key in analytic else oracle(*args)
    seen, tests, original = set(), [], []
    for args in list(cases) + [args for args, _ in specials]:
        key = compact(args)
        if key in seen:
            continue
        if type(args) is not list or len(args) != len(params) or any(not valid(v, typ) for v, (_, typ) in zip(args, params)):
            raise AssertionError((number, "argument type violates ABI"))
        if sum(slots(value) for value in args) > 1000000:
            raise AssertionError((number, "argument slot limit"))
        expected = checked_oracle(*copy.deepcopy(args))
        actual = reference(*copy.deepcopy(args))
        if not valid(expected, result) or slots(expected) > 1000000:
            raise AssertionError((number, "return type or slot limit violates ABI"))
        if not equivalent(actual, expected):
            raise AssertionError((number, title, "reference/oracle disagreement", compact(args)[:250], compact(expected)[:250], compact(actual)[:250]))
        item = {"input": compact({"args": args}), "expected": compact(expected)}
        if max(len(value.encode("utf-8")) for value in item.values()) > 16 * 1024 * 1024:
            raise AssertionError((number, "serialized case size limit"))
        tests.append(item)
        original.append(copy.deepcopy(args))
        seen.add(key)
    if not 10 <= len(tests) <= 50:
        raise AssertionError((number, title, "expected 10..50 distinct stored cases", len(tests)))
    chapter = f"Lv.3-{chapter_index + 1}"
    SPECS.append({
        "ordinal": ordinal, "number": number, "chapter": chapter, "title": title,
        "description": "## 문제\n\n" + description + "\n\n`sol` 함수를 구현해 반환하세요. 표준 입출력을 직접 처리하지 않습니다. 인덱스는 별도 설명이 없으면 0부터 시작합니다. 풀이 방법을 지정한 문제도 소스 형태가 아닌 반환 결과를 채점합니다.",
        "inputDescription": "\n\n".join(f"`{name}` ({typ}): 본문과 제한 사항을 만족하는 인자입니다." for name, typ in params),
        "outputDescription": f"`{result}` 형식으로 반환합니다. " + ("절대 또는 상대 오차 1e-9 이내를 허용합니다." if result == "float" else "순서·동률·도달 불가 처리와 나머지 연산 규칙은 문제 본문을 따릅니다."),
        "constraints": constraints + "\n- 모든 정수 인자와 정수 반환값은 부호 있는 32비트 범위입니다. 중간 계산은 필요한 경우 64비트 정수를 사용하세요.\n- 문자열은 ASCII 문자만 포함합니다. 입력은 명시한 모양·범위·유효성 조건을 만족합니다.",
        "rankType": "EXPERT", "rankInt": 5 - chapter_index,
        "timeLimitMs": 10000, "memoryLimitMb": 512,
        "functionSignature": {"name": "sol", "parameters": [{"name": name, "type": typ} for name, typ in params], "returnType": result},
        "tags": ["Lv.3", chapter, CHAPTERS[chapter_index]],
        "examples": [{"input": case["input"], "output": case["expected"], "explanation": "본문의 규칙과 반환 순서를 적용한 결과입니다. 입력 예제는 함수에 전달할 인자를 나타냅니다."} for case in tests[:2]],
        "tests": tests,
        "referenceSolution": {"language": "PYTHON", "fileName": "solution.py", "sourceCode": source},
    })
    ORACLES[number], CASES[number] = checked_oracle, original


def build():
    SPECS.clear()
    ORACLES.clear()
    CASES.clear()
    for index in (1, 2, 3):
        path = ROOT / f"scripts/expert_catalog/part{index}.py"
        spec = importlib.util.spec_from_file_location(f"expert_catalog_part{index}", path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        module.populate(add)
        if len(SPECS) != (32, 64, 80)[index - 1]:
            raise AssertionError((path.name, "incorrect chapter length", len(SPECS)))
    previous = ["체인 레이저"]
    for level in (0, 1, 2):
        catalog = json.loads((ROOT / f"src/main/resources/problems/level{level}.json").read_text(encoding="utf-8"))
        previous.extend(p["title"] for p in catalog["problems"])
    titles = [title.strip().casefold() for title in previous + [p["title"] for p in SPECS]]
    if len(titles) != len(set(titles)):
        raise AssertionError("duplicate title across existing and new catalogs")
    return {"version": 1, "name": "OJ Level 3 — 재귀·그래프·동적 계획법 80문제", "problems": SPECS}


def main():
    document = build()
    path = ROOT / "src/main/resources/problems/level3.json"
    path.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{path}: {len(SPECS)} problems, {sum(len(p['tests']) for p in SPECS)} cases, {path.stat().st_size} bytes")


if __name__ == "__main__":
    main()
