#!/usr/bin/env python3
"""MYTHIC 35문제의 저장 산출물을 생성하거나 --check로 재현성을 검증한다."""
import argparse
import copy
import importlib.util
import inspect
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "src/main/resources/problems/level5.json"
CHAPTERS = ["그래프와 트리", "네트워크 유량과 매칭", "고급 문자열", "구간 자료구조와 오프라인 쿼리", "수학과 DP 최적화"]


def load(path):
    spec = importlib.util.spec_from_file_location(path.stem, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def build():
    folder = ROOT / "scripts/mythic_catalog"
    helpers = load(ROOT / "scripts/build-expert-catalog.py")
    abi = load(ROOT / "judge/generic_function.py")
    examples = load(folder / "examples.py")
    stress_case = load(folder / "stress.py").at_scale
    problems = []

    def add(title, signature, output_type, statement, limits, reference, inputs, oracle, *,
            dependencies=(), stress=()):
        ordinal = len(problems) + 1
        chapter = (ordinal - 1) // 7
        number = 1395 + ordinal
        params = [pair.split(":") for pair in signature.split()]
        assert [name for name, _ in params] == list(inspect.signature(reference).parameters), title
        contract = abi.signature({"name": "sol", "parameters": [{"name": n, "type": t} for n,t in params],
                                  "returnType": output_type})
        source = "".join(inspect.getsource(fn).rstrip() + "\n\n" for fn in dependencies)
        source += inspect.getsource(reference).replace("def " + reference.__name__ + "(", "def sol(", 1)
        solve = helpers.trusted_reference(source, number)
        tests, seen = [], set()
        cases = [(args, oracle(*copy.deepcopy(args))) for args in inputs] + list(stress) + stress_case(number)
        for args, expected in cases:
            envelope = {"args": args}
            packed = helpers.compact(envelope)
            if packed in seen:
                continue
            seen.add(packed)
            abi.payload(envelope, contract)
            encoded = " ".join(abi.encode(expected, output_type)).encode("ascii")
            actual = solve(*copy.deepcopy(args))
            if not helpers.equivalent(actual, expected) or not helpers.equivalent(abi.decode(encoded, output_type), expected):
                raise ValueError((number, title, str(args)[:300], str(expected)[:150], str(actual)[:150]))
            tests.append({"input": packed, "expected": helpers.compact(expected)})
        assert 10 <= len(tests[2:]) <= 50, (title,len(tests))
        chapter_tag = f"Lv.5-{chapter + 1}"
        problems.append({
            "ordinal": ordinal, "number": number, "chapter": chapter_tag, "title": title,
            "description": "## 문제\n\n" + statement,
            "inputDescription": "함수 인자는 " + ", ".join(f"`{name}: {typ}`" for name,typ in params) + " 순서입니다.",
            "outputDescription": "반환형은 `" + output_type + "`입니다. " + statement.splitlines()[-1],
            "constraints": limits + "\n\n목표 시간 복잡도는 " + examples.COMPLEXITIES[ordinal-1] + "." +
                           "\n- 정수 인자와 정수형 반환값의 범위는 부호 있는 32비트입니다. 합·곱의 중간 계산에는 필요한 정밀도를 확보하세요.",
            "rankType": "MYTHIC", "rankInt": 5-chapter, "timeLimitMs": 4000, "memoryLimitMb": 512,
            "functionSignature": contract,
            "tags": ["Lv.5", chapter_tag, CHAPTERS[chapter]],
            "examples": [{"input": t["input"], "output": t["expected"], "explanation": note}
                         for t,note in zip(tests[:2],examples.NOTES[number],strict=True)],
            "tests": tests[2:],
            "referenceSolution": {"language": "PYTHON", "fileName": "solution.py", "sourceCode": source},
        })
    for i,name in enumerate(("graphs","flows","strings","queries","math_dp"),1):
        load(folder / (name+".py")).populate(add)
        assert len(problems)==7*i, name
    assert len({p["title"] for p in problems}) == 35
    return {"version":1, "name":"OJ Level 5 — 고급 알고리즘 35문제", "problems":problems}


if __name__ == "__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("--check",action="store_true",help="저장 파일을 변경하지 않고 재생성 결과를 비교합니다.")
    args=parser.parse_args()
    result=build()
    content=json.dumps(result,ensure_ascii=False,indent=2)+"\n"
    if args.check:
        if TARGET.read_text(encoding="utf-8")!=content:
            raise SystemExit("level5.json differs from the verified catalog")
    else:
        TARGET.write_text(content,encoding="utf-8")
    print("MYTHIC",len(result["problems"]),"problems,",sum(len(p["tests"]) for p in result["problems"]),"private cases,",len(content.encode()),"bytes")
