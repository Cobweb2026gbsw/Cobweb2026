"""엄랭 원본 고정 및 채점 어댑터 계약 회귀. 실행 프로그램은 모두 고정된 테스트 픽스처입니다."""
import hashlib
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]
CLI = ROOT / "judge" / "umjunsik_cli.py"
VENDOR = ROOT / "judge" / "vendor" / "umjunsik"
SUM = "\n".join((
    "어떻게", "엄식?", "어엄식?", "동탄어어?준........", "엄어.",
    "어엄어어,", "준....", "식어!", "이 사람이름이냐ㅋㅋ",
))


class UmjunsikTest(unittest.TestCase):
    def run_source(self, source, input_text="", timeout=5):
        with tempfile.TemporaryDirectory(prefix="cobweb-umjunsik-test-") as folder:
            source_path = Path(folder) / "main.umm"
            source_path.write_bytes(source.encode("utf-8") if isinstance(source, str) else source)
            return subprocess.run(
                [sys.executable, "-I", "-B", str(CLI), str(source_path)], input=input_text,
                text=True, capture_output=True, timeout=timeout,
            )

    def assert_error(self, source, input_text=""):
        result = self.run_source(source, input_text)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("엄랭 오류:", result.stderr)

    def test_pinned_runtime_hash(self):
        self.assertEqual("520642d8dfcdd811172799a4a26fc5c8d809ba9a97da8ce7259d5fdc01c4fc53",
                         hashlib.sha256((VENDOR / "runtime.py").read_bytes()).hexdigest())

    def test_pinned_license_hash(self):
        self.assertEqual("e353712979d210ca3c549b807d836efeeeeb8091c00b4a380c9334a95378248b",
                         hashlib.sha256((VENDOR / "LICENSE").read_bytes()).hexdigest())

    def test_sum_real_whitespace_inputs(self):
        for input_text, expected in (("2 3\n", "5"), ("4\n3\n", "7"), ("\n 2\t3\n", "5"), ("2 0\n", "2")):
            with self.subTest(input=input_text):
                result = self.run_source(SUM, input_text)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(expected, result.stdout)

    def test_one_line_form_with_trailing_newline(self):
        result = self.run_source(SUM.replace("\n", "~") + "\n", "4 3\n")
        self.assertEqual((0, "7"), (result.returncode, result.stdout), result.stderr)

    def test_crlf(self):
        result = self.run_source(SUM.replace("\n", "\r\n") + "\r\n", "2 3\r\n")
        self.assertEqual((0, "5"), (result.returncode, result.stdout), result.stderr)

    def test_empty_program(self):
        result = self.run_source("어떻게\n이 사람이름이냐ㅋㅋ")
        self.assertEqual((0, ""), (result.returncode, result.stdout), result.stderr)

    def test_multiply_and_default_variable(self):
        result = self.run_source("어떻게\n식어!\n식.. ...!\n이 사람이름이냐ㅋㅋ")
        self.assertEqual((0, "06"), (result.returncode, result.stdout), result.stderr)

    def test_conditional_branch(self):
        result = self.run_source("어떻게\n동탄.?식..!\n동탄?식...!\n이 사람이름이냐ㅋㅋ")
        self.assertEqual((0, "3"), (result.returncode, result.stdout), result.stderr)

    def test_v2_character_and_newline_output(self):
        result = self.run_source("어떻게\n식........ ........ㅋ\n식ㅋ\n이 사람이름이냐ㅋㅋ")
        self.assertEqual((0, "@\n"), (result.returncode, result.stdout), result.stderr)

    def test_v2_exit_zero_does_not_print_exit_code(self):
        result = self.run_source("어떻게\n화이팅!\n식.!\n이 사람이름이냐ㅋㅋ")
        self.assertEqual((0, ""), (result.returncode, result.stdout), result.stderr)

    def test_v2_nonzero_exit(self):
        result = self.run_source("어떻게\n화이팅!..\n이 사람이름이냐ㅋㅋ")
        self.assertEqual((2, ""), (result.returncode, result.stdout), result.stderr)

    def test_invalid_marker(self):
        for source in ("", "엄식?", "어떻게\n식.!", "invalid\n이 사람이름이냐ㅋㅋ"):
            with self.subTest(source=source):
                self.assert_error(source)

    def test_unknown_statement_not_silently_ignored(self):
        self.assert_error("어떻게\n아무거나\n이 사람이름이냐ㅋㅋ")

    def test_python_source_cannot_be_executed(self):
        self.assert_error("어떻게\n__import__('os').system('echo unsafe')\n이 사람이름이냐ㅋㅋ")

    def test_unsupported_draft_v3_syntax(self):
        self.assert_error("어떻게\n엌..ㅋ.\n이 사람이름이냐ㅋㅋ")

    def test_invalid_input(self):
        self.assert_error(SUM, "2 bad\n")

    def test_missing_input(self):
        self.assert_error(SUM, "2\n")

    def test_invalid_utf8(self):
        self.assert_error(b"\xff\xfe")

    def test_invalid_line_jump(self):
        for jump in ("", ",", ".........."):  # 0, -1, 10 outside the three-line program
            with self.subTest(jump=jump):
                self.assert_error("어떻게\n준" + jump + "\n이 사람이름이냐ㅋㅋ")

    def test_variable_bounds(self):
        self.assert_error("어떻게\n" + "어" * 256 + "엄.\n이 사람이름이냐ㅋㅋ")

    def test_character_bounds(self):
        self.assert_error("어떻게\n식,ㅋ\n이 사람이름이냐ㅋㅋ")

    def test_no_arbitrary_upstream_instruction_limit(self):
        result = self.run_source(SUM, "0 100001\n", timeout=12)
        self.assertEqual((0, "100001"), (result.returncode, result.stdout), result.stderr)

    def test_infinite_loop_waits_for_external_timeout(self):
        with self.assertRaises(subprocess.TimeoutExpired):
            self.run_source("어떻게\n준..\n이 사람이름이냐ㅋㅋ", timeout=0.25)


if __name__ == "__main__":
    unittest.main()
