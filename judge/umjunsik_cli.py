"""공식 엄랭 v2 Python 구현을 채점 컨테이너에 연결합니다.

원본 알고리즘은 vendor/umjunsik/runtime.py에 고정하여 보존합니다. 이 파일은
호스트에서 제출을 실행하지 않으며 Docker의 시간/메모리/출력 제한을 대체하지 않습니다.
엄랭에는 sol 함수가 없으므로 표준 입출력 문제에서만 사용합니다.
"""
import importlib.util
from pathlib import Path
import re
import sys


RUNTIME_PATH = Path(__file__).resolve().parent / "vendor" / "umjunsik" / "runtime.py"
spec = importlib.util.spec_from_file_location("cobweb_pinned_umjunsik", RUNTIME_PATH)
upstream = importlib.util.module_from_spec(spec)
spec.loader.exec_module(upstream)

HEADER = "어떻게"
FOOTER = "이 사람이름이냐ㅋㅋ"
EXPRESSION = re.compile(r"[어., ]*")
INTEGER = re.compile(r"[+-]?[0-9]+")


def expression(value):
    # 표현식은 원본 v2의 변수·정수·곱셈만 허용합니다. Python으로 평가하지 않습니다.
    if not EXPRESSION.fullmatch(value):
        raise SyntaxError("엄랭 v2 정수 표현식이 아닙니다.")


def statement(line):
    if not line:
        return
    if line.startswith("동탄"):
        condition, separator, command = line[2:].partition("?")
        if not separator or not command or command.startswith("동탄"):
            # 원본 compileLine은 '?' 하나만 분해하며 중첩 조건 문법은 제공하지 않습니다.
            raise SyntaxError("동탄 조건문 형식이 잘못되었습니다.")
        expression(condition)
        statement(command)
    elif re.fullmatch(r"(?:어*엄)?식\?", line):
        return
    elif line.startswith("화이팅!"):
        expression(line[len("화이팅!"):])
    elif line.startswith("준"):
        expression(line[1:])
    elif line.startswith("식") and line[-1:] in ("!", "ㅋ"):
        expression(line[1:-1])
    elif "엄" in line:
        variable, separator, value = line.partition("엄")
        if not separator or re.fullmatch(r"어*", variable) is None:
            raise SyntaxError("엄랭 변수 대입 형식이 잘못되었습니다.")
        expression(value)
    else:
        raise SyntaxError("엄랭 v2 명령이 아닙니다.")


def validated_source(source):
    # CRLF와 파일 끝 개행을 정리한 뒤 원본의 줄 번호를 그대로 유지합니다.
    source = source.replace("\r\n", "\n").rstrip()
    lines = source.split("\n" if "\n" in source else "~")
    if len(lines) < 2 or lines[0].strip() != HEADER or lines[-1].strip() != FOOTER:
        raise SyntaxError("어떻게 이게 엄랭이냐!")
    lines[0], lines[-1] = HEADER, FOOTER
    for line in lines[1:-1]:
        statement(line.strip())
    return "\n".join(lines), len(lines)


class JudgeUmjunsik(upstream.Umjunsik):
    def __init__(self, line_count):
        super().__init__()
        self.line_count = line_count
        self.input_tokens = iter(())

    def read_integer(self):
        # 일반 채점 입력처럼 공백/개행 모두 정수 구분자로 받습니다. 프롬프트는 없습니다.
        while True:
            value = next(self.input_tokens, None)
            if value is not None:
                if INTEGER.fullmatch(value) is None:
                    raise ValueError("입력이 정수가 아닙니다.")
                return int(value)
            line = sys.stdin.readline()
            if not line:
                raise EOFError("정수 입력이 부족합니다.")
            self.input_tokens = iter(line.split())

    def compileLine(self, code):
        kind = self.type(code)
        if kind == "INPUT":
            self.data[code.replace("식?", "").count("어")] = self.read_integer()
            return None
        if kind == "END":
            # upstream Python의 숫자 출력은 공식 v2 명세의 프로세스 종료값으로 보완합니다.
            raise SystemExit(self.toNumber(code[len("화이팅!"):]))
        result = super().compileLine(code)
        if kind == "MOVE" and not 1 <= result <= self.line_count:
            raise ValueError("이동할 줄 번호가 범위를 벗어났습니다.")
        return result


def main(argv):
    if len(argv) != 2:
        raise ValueError("엄랭 소스 파일 하나가 필요합니다.")
    source, line_count = validated_source(Path(argv[1]).read_text(encoding="utf-8"))
    # 원본의 임의 100,000단계 제한 대신 모든 언어와 동일한 컨테이너 시간 제한을 사용합니다.
    JudgeUmjunsik(line_count).compile(source, errors=0)


if __name__ == "__main__":
    try:
        main(sys.argv)
    except (SyntaxError, ValueError, IndexError, EOFError, OSError, UnicodeError) as failure:
        print(f"엄랭 오류: {failure}", file=sys.stderr)
        raise SystemExit(1)
