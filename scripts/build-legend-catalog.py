"""LEGEND의 공개 예제, 비공개 테스트 및 Python 기준 풀이를 검증한 뒤 JSON을 생성한다."""

import argparse
import copy
import importlib.util
import inspect
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "src/main/resources/problems/level4.json"
CHAPTERS = ("최단 경로와 그래프", "고급 자료구조", "문자열 알고리즘", "동적 계획법 심화", "수학과 기하")


def load(path):
    spec = importlib.util.spec_from_file_location(path.stem, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def build():
    expert = load(ROOT / "scripts/build-expert-catalog.py")
    abi = load(ROOT / "judge/generic_function.py")
    limit_case = load(ROOT / "scripts/legend_catalog/boundaries.py").limit_case
    problems = []

    def add(
        title,
        reference,
        types,
        result,
        description,
        limits,
        complexity,
        cases,
        explanations,
        *,
        oracle=None,
        helpers=(),
        stress=(),
    ):
        ordinal = len(problems) + 1
        number = 1345 + ordinal
        chapter_index = (ordinal - 1) // 10
        chapter = f"Lv.4-{chapter_index + 1}"
        params = [
            {"name": name, "type": typ}
            for name, typ in zip(inspect.signature(reference).parameters, types.split(), strict=True)
        ]
        signature = abi.signature({"name": "sol", "parameters": params, "returnType": result})
        source = "\n".join(inspect.getsource(helper) for helper in helpers)
        source += "\n" + inspect.getsource(reference).replace(f"def {reference.__name__}(", "def sol(", 1)
        source = source.strip() + "\n"
        solve = expert.trusted_reference(source, number)
        regular = [(args, oracle(*copy.deepcopy(args))) for args in cases] if oracle else list(cases)
        tests = []
        seen = set()
        for args, expected in regular + list(stress) + [limit_case(ordinal)]:
            envelope = {"args": list(args)}
            raw = expert.compact(envelope)
            if raw in seen:
                continue
            seen.add(raw)
            abi.payload(envelope, signature)
            wire = " ".join(abi.encode(expected, result)).encode("ascii")
            actual = solve(*copy.deepcopy(args))
            if not expert.equivalent(abi.decode(wire, result), expected) or not expert.equivalent(
                actual, expected
            ):
                raise ValueError(
                    f"{number} {title}: reference or ABI disagrees with expected for case {len(tests) + 1}"
                )
            tests.append({"input": raw, "expected": expert.compact(expected)})
        if not 10 <= len(tests) - 2 <= 50 or len(explanations) != 2:
            raise ValueError(f"{number} {title}: example/test count {len(tests)}")
        examples = [
            {"input": test["input"], "output": test["expected"], "explanation": why}
            for test, why in zip(tests[:2], explanations)
        ]
        problems.append(
            {
                "ordinal": ordinal,
                "number": number,
                "chapter": chapter,
                "title": title,
                "description": "## 문제\n\n" + description,
                "inputDescription": "함수 `sol`에 다음 인자가 순서대로 주어집니다.\n\n"
                + "\n".join(f"- `{p['name']}` · `{p['type']}`" for p in params),
                "outputDescription": f"`{result}` 형식으로 반환하세요. " + description.split(". ")[-1],
                "constraints": limits
                + f"\n\n목표 시간 복잡도는 {complexity}입니다. 인덱스는 0부터 시작하며, 모든 정수 인자와 반환값은 부호 있는 32비트 정수 범위입니다. 중간 계산에는 필요한 경우 64비트 정수를 사용하세요.",
                "rankType": "LEGEND",
                "rankInt": 5 - chapter_index,
                "timeLimitMs": 4000,
                "memoryLimitMb": 512,
                "functionSignature": signature,
                "tags": ["Lv.4", chapter, CHAPTERS[chapter_index]],
                "examples": examples,
                "tests": tests[2:],
                "referenceSolution": {"language": "PYTHON", "fileName": "solution.py", "sourceCode": source},
            }
        )

    for index, module in enumerate(("graph", "structures", "strings", "dp", "math_geometry"), 1):
        load(ROOT / f"scripts/legend_catalog/{module}.py").populate(add)
        if len(problems) != index * 10:
            raise ValueError(f"Expected ten problems in chapter {index}")
    earlier_titles = {"체인 레이저"}
    for level in range(4):
        catalog = json.loads((TARGET.parent / f"level{level}.json").read_text(encoding="utf-8"))
        earlier_titles.update(p["title"].casefold() for p in catalog["problems"])
    new_titles = {p["title"].strip().casefold() for p in problems}
    if len(new_titles) != 50 or earlier_titles & new_titles:
        raise ValueError("Duplicate problem title in LEGEND catalog")
    return {"version": 1, "name": "OJ Level 4 — 심화 알고리즘 50문제", "problems": problems}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="기준 풀이를 검증하고 기존 파일과 비교합니다.")
    args = parser.parse_args()
    content = json.dumps(build(), ensure_ascii=False, indent=2) + "\n"
    if args.check:
        if TARGET.read_text(encoding="utf-8") != content:
            raise SystemExit("level4.json differs from the verified catalog")
    else:
        TARGET.write_text(content, encoding="utf-8")
    print(f"LEGEND verified: {TARGET.name}, 50 problems, {len(content.encode('utf-8'))} bytes")
