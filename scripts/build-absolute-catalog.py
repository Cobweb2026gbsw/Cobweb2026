#!/usr/bin/env python3
"""ABSOLUTE 20문제를 재생성한다. 독립 판정과 저장된 Python 풀이를 대조한다."""
import argparse
import copy
import inspect
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "judge"))
import generic_function as abi

from absolute_catalog import trees, flows, series, optimization

CHAPTERS = ("동적 그래프와 트리", "매칭과 절단", "다항식과 합성곱", "복합 최적화")
EXPLANATIONS = [
    ("0과 1을 이은 직후에는 2+3=5입니다. 정점 1의 값을 8로 바꾸면 0→1→2의 합은 2+8−1=9이고, 간선을 끊은 뒤 정점 2 자신만 세면 −1입니다.", "정점 0에서 자신까지의 경로에는 값 4 하나만 있습니다."),
    ("첫 간선만으로는 정점 2에 갈 수 없습니다. 둘째 간선까지의 트리는 4+7=11이며 마지막 간선으로 비용 7을 2로 교체하면 6입니다.", "정점 한 개의 신장 트리에는 간선이 필요 없어 자기 루프를 추가한 뒤에도 비용이 0입니다."),
    ("0–1만 있을 때 2는 고립되어 있습니다. 1–2를 이으면 연결되고, 같은 간선을 지우면 다시 끊깁니다.", "간선이 없어도 0에서 0으로 가는 길이 0의 경로가 있습니다."),
    ("경로 1→0→2의 값은 [1,5,4]입니다. 정렬한 둘째 값은 4이며 0에서 자신까지의 첫째 값은 5입니다.", "유일한 정점의 값 7이 첫째 값입니다."),
    ("정점 1에 이웃한 0, 2, 3 중 두 개를 고르는 세 쌍의 거리가 모두 2입니다.", "서로 다른 정점이 없으므로 거리 0인 쌍도 없습니다."),
    ("0–1과 2–3을 함께 고르면 네 정점을 모두 덮어 매칭 크기가 2입니다.", "간선이 없으므로 매칭 크기는 0입니다."),
    ("0→1에서 최소 2를 보내야 하므로 1→2와 2→0에도 각각 2가 필요합니다. 최소 비용은 2×(4+1+2)=14입니다.", "0→1로 유량 1을 보내야 하지만 1에서 돌아오는 간선이 없어 −1입니다."),
    ("0과 1은 정점 1만 떼어 내면 3+2=5로 분리됩니다. 0과 2는 정점 2만 떼어 내면 4+2=6이며 두 경우 모두 최소입니다.", "두 정점이 처음부터 분리되어 있으므로 절단 용량은 0입니다."),
    ("정점 1을 나머지 두 정점에서 떼면 0–1과 1–2를 제거해 3+2=5입니다. 다른 두 단일 정점 절단 값은 7과 6입니다.", "간선 제거 없이 두 정점을 분리할 수 있습니다."),
    ("S={0}일 때 간선 0,2가 나가며 S={0,1}일 때 간선 1,2가 나갑니다. 둘 다 용량 3인 최소 절단이므로 세 인덱스를 모두 반환합니다.", "S={0}의 절단 값은 0입니다. 유일한 간선 1→0은 어떤 최소 절단에서도 밖으로 나가지 않습니다."),
    ("1+2x+x²=(1+x)(1+x)입니다. 몫 계수는 [1,1]이고 나머지는 영다항식 [0]입니다.", "영다항식을 3+x로 나눈 몫과 나머지는 모두 [0]입니다."),
    ("A=1−x입니다. (1−x)(1+x+x²+x³+x⁴)=1−x⁵이므로 필요한 계수는 모두 1입니다.", "3의 모듈러 역수는 332748118이고, 3×332748118은 998244353으로 나누면 1이 남습니다."),
    ("exp(x)의 처음 네 계수는 1, 1, 1/2, 1/6입니다. 법 998244353에서 뒤의 두 값은 각각 499122177과 166374059입니다.", "exp(0)=1이므로 n=5에서 [1,0,0,0,0]입니다."),
    ("두 직전 항의 합으로 0,1,1,2,3,5,8,13,21,34,55를 얻습니다. 0부터 센 10번째 항은 55입니다.", "n=0이면 점화식 적용 전의 첫째 초기값 3입니다."),
    ("마스크 3의 부분집합은 0,1,2,3이고 b값은 모두 1입니다. 합은 1+2+3+4=10입니다. 마스크 0,1,2의 합은 각각 1,3,4입니다.", "공집합에는 부분집합이 하나이므로 결과는 [2×7]=[14]입니다."),
    ("처음 두 점은 같은 좌표라 상대방 한 점씩을 셉니다. 세 번째 점은 앞의 두 점을 모두 포함하고, 마지막 점의 y좌표 −1 이하인 다른 점은 없습니다.", "자기 자신을 제외하면 비교할 점이 없습니다."),
    ("x=3에서 첫 직선의 값은 2×3+1=7입니다. 두 번째 직선을 더하면 −3+6=3이 더 작습니다.", "수평선 y=0은 x=−10000에서도 0입니다."),
    ("두 직사각형의 넓이는 각각 6입니다. 겹친 영역 [1,3]×[1,2]의 넓이 2를 한 번 빼면 10입니다.", "직사각형이 없으므로 합집합 넓이는 0입니다."),
    ("'b' 뒤에는 일치가 없고 'ba' 뒤에는 'a'와 'ba'가 모두 접미사입니다. 'a'를 비활성화한 뒤 'baa'에는 활성 접미사가 없습니다.", "'z'를 붙여도 패턴 0을 활성화한 적이 없어 0입니다."),
    ("도시 1까지는 4×2=8입니다. 도시 2에는 루트에서 바로 가면 36, 도시 1을 거치면 8+1+3×5=24입니다. 도시 3까지는 1이 듭니다. 각 값을 십진 문자열로 반환합니다.", "도시 1까지는 100×1,000,000,000=100,000,000,000입니다. 도시 2까지는 루트 직송이 200×999,999,999+123=199,999,999,923으로, 도시 1을 거치는 200,000,000,023보다 저렴합니다. 문자열에는 자릿수 구분 기호를 쓰지 않습니다."),
]


def compact(value):
    return json.dumps(value, separators=(",", ":"), ensure_ascii=True, allow_nan=False)


def build():
    problems = []
    def add(title, tag, signature, result, description, constraints, complexity,
            solve, oracle, cases, stress=(), dependencies=()):
        index = len(problems)
        chapter = index // 5
        parameters = [dict(name=name, type=typ) for name, typ in
                      (field.split(":") for field in signature.split())]
        contract = abi.signature(dict(name="sol", parameters=parameters, returnType=result))
        assert list(inspect.signature(solve).parameters) == [p["name"] for p in parameters]
        source = "\n\n".join(inspect.getsource(fn).rstrip() for fn in (*dependencies, solve))
        source = source.replace(f"def {solve.__name__}(", "def sol(", 1) + "\n"
        scope = {}
        exec(compile(source, title, "exec"), scope)
        tests, seen = [], set()
        def check(args, expected):
            envelope = {"args": args}
            key = compact(envelope)
            if key in seen:
                return
            seen.add(key)
            abi.payload(envelope, contract)
            actual = scope["sol"](*copy.deepcopy(args))
            assert compact(actual) == compact(expected), (title, str(args)[:160], str(actual)[:80], str(expected)[:80])
            # 언어별 래퍼가 공유하는 출력 전송 형식도 왕복 검증한다.
            encoded = " ".join(abi.encode(expected, result)).encode("ascii")
            assert abi.decode(encoded, result) == expected
            tests.append(dict(input=key, expected=compact(expected)))
        for args in cases:
            check(args, oracle(*copy.deepcopy(args)))
        for args, expected in stress:
            check(args, expected)
        assert 10 <= len(tests[2:]) <= 50, (title, len(tests))
        label = f"Lv.6-{chapter + 1}"
        problems.append(dict(ordinal=index+1, number=1431+index, chapter=label, title=title,
            description=description, inputDescription="함수 인자는 "+", ".join(f"`{p['name']}: {p['type']}`" for p in parameters)+" 순서로 주어집니다. 인덱스는 0부터 시작합니다.",
            outputDescription=f"`{result}` 형식으로 본문에서 정한 결과를 반환하세요.",
            constraints=constraints+"\n\n목표 시간 복잡도는 "+complexity+"입니다.",
            rankType="ABSOLUTE",rankInt=1 if index==19 else 5-chapter,
            timeLimitMs=5000,memoryLimitMb=512,functionSignature=contract,
            tags=["Lv.6", label, CHAPTERS[chapter], tag],
            examples=[dict(input=t["input"],output=t["expected"],explanation=why)
                      for t,why in zip(tests[:2],EXPLANATIONS[index])],
            tests=tests[2:],referenceSolution=dict(language="PYTHON",fileName="solution.py",sourceCode=source)))
    for module in (trees,flows,series,optimization):
        module.populate(add)
    assert len(problems)==20
    return dict(version=1,name="OJ Level 6 — 최상위 알고리즘 20문제",problems=problems)


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--check", action="store_true", help="저장된 카탈로그와 재생성 결과를 비교합니다.")
    args = cli.parse_args()
    catalog = build()
    output = ROOT / "src/main/resources/problems/level6.json"
    document = (json.dumps(catalog,ensure_ascii=False,indent=2)+"\n").encode("utf-8")
    if args.check:
        if output.read_bytes() != document:
            raise SystemExit("level6.json이 생성 결과와 일치하지 않습니다.")
    else:
        output.write_bytes(document)
    print(f"{len(catalog['problems'])} problems, {sum(len(p['tests']) for p in catalog['problems'])} cases")
