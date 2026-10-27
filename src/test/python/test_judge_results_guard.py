"""격리 XML fixture로 Docker 결과 누락 가드를 검사하며 Docker/DB는 사용하지 않습니다."""

import contextlib
import importlib.util
import io
import json
import re
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location("judge_results_guard", ROOT / "scripts/check-judge-results.py")
GUARD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GUARD)


def java_test_names(relative):
    """신규 필수 이름이 실제 Java @Test 선언과도 일치하는지 양성 fixture에서 확인합니다."""
    source = (ROOT / relative).read_text(encoding="utf-8")
    return {
        name + "()"
        for name in re.findall(r"@Test\b(?:(?!@Test\b).)*?\bvoid\s+(\w+)\s*\(", source, re.S)
    }


class JudgeResultsGuardTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="cobweb-judge-gate-")
        self.addCleanup(self.temporary.cleanup)
        self.result_dir = Path(self.temporary.name)
        languages = GUARD.FUNCTION_LANGUAGES
        kinds = GUARD.GENERIC_TYPES
        catalog = json.loads((ROOT / "src/main/resources/problems/level0.json").read_text(encoding="utf-8"))["problems"]
        self.cases = {
            GUARD.SUITES[0]: {"dockerSmoke()"},
            GUARD.SUITES[1]: {
                f"{language}: compile once, run isolated cases" for language in GUARD.LANGUAGES
            } | {f"{language}: FUNCTION adapter" for language in languages},
            GUARD.SUITES[2]: {
                "chainLaserHttpSubmissionRunsAllPrivateCases()",
                "realWorkerHandlesCompiledSqlAndCompilationFailure()",
                "newLanguagesRunThroughHttpAndWorker()",
            },
            GUARD.SUITES[3]: {
                f"{language}: generic {kind} identity and isolated reuse" for language in languages for kind in kinds
            } | {
                f"{language}: actual frontend generic {kind} starter" for language in languages for kind in kinds
            } | {f"{language}: solution name and empty parameters" for language in languages} | {
                "rejectsMalformedArgumentsAndWrongDynamicReturnTypes()",
                "fourArgumentsHaveDistinctNativeTypes()",
            },
            GUARD.SUITES[4]: {
                f"Lv.0 {problem['ordinal']}: {problem['title']} ({len(problem['tests'])} Docker cases)"
                for problem in catalog
            },
            GUARD.SUITES[5]: {
                "genericHttpSubmissionRunsStoredSignaturesThroughWorkerAndPreservesReuseScope()",
            },
            GUARD.SUITES[6]: java_test_names(
                "src/test/java/com/drewdrew0414/domain/submission/JudgeDiagnosticDockerTest.java"
            ),
            GUARD.SUITES[7]: java_test_names("src/test/java/com/drewdrew0414/TestRunIntegrationTest.java"),
            GUARD.SUITES[8]: {
                f"{language}: actual nested {kind} starter" for language in languages for kind in ("int[][]", "string[][]")
            } | {f"{language}: six mixed native arguments" for language in languages}
              | java_test_names("src/test/java/com/drewdrew0414/domain/submission/GenericNestedFunctionJudgeTest.java"),
            GUARD.SUITES[9]: {
                f"{p['rankType']} {p['ordinal']}: {p['title']} ({len(p['tests'])} Docker cases)"
                for level in (1, 2)
                for p in json.loads((ROOT / f"src/main/resources/problems/level{level}.json").read_text(encoding="utf-8"))["problems"]
            },
        }
        self.assertEqual(len(self.cases[GUARD.SUITES[6]]), 3)
        self.assertEqual(len(self.cases[GUARD.SUITES[7]]), 9)
        for suite, names in self.cases.items():
            self.write_report(suite, names)

    def path(self, suite):
        return self.result_dir / f"TEST-{suite}.xml"

    def write_report(self, suite, names):
        root = ET.Element("testsuite", name=suite, tests=str(len(names)), skipped="0", failures="0", errors="0")
        for name in sorted(names):
            ET.SubElement(root, "testcase", name=name, classname=suite)
        ET.ElementTree(root).write(self.path(suite), encoding="utf-8", xml_declaration=True)

    def check(self):
        with contextlib.redirect_stdout(io.StringIO()) as output:
            GUARD.main(self.result_dir)
        return output.getvalue()

    def assert_rejected(self):
        with self.assertRaises(ValueError):
            self.check()

    def test_complete_reports_match_java_names_and_preserve_existing_contracts(self):
        result = self.check()
        for expected in ("197 generic ABI", "100 Lv.0 references/812", "3 safe-diagnostic Docker", "9 custom-test-run"):
            self.assertIn(expected, result)

    def test_each_new_suite_report_is_required(self):
        for suite in GUARD.SUITES[6:]:
            with self.subTest(suite=suite):
                self.path(suite).unlink()
                self.assert_rejected()
                self.write_report(suite, self.cases[suite])

    def test_every_new_case_is_required_even_when_xml_counts_are_consistent(self):
        for suite in GUARD.SUITES[6:]:
            for name in self.cases[suite]:
                with self.subTest(suite=suite, name=name):
                    self.write_report(suite, self.cases[suite] - {name})
                    self.assert_rejected()
            self.write_report(suite, self.cases[suite])

    def test_every_new_case_skip_is_rejected_even_if_suite_counters_claim_zero(self):
        for suite in GUARD.SUITES[6:]:
            for name in self.cases[suite]:
                with self.subTest(suite=suite, name=name):
                    tree = ET.parse(self.path(suite))
                    test = next(case for case in tree.getroot().findall("testcase") if case.get("name") == name)
                    ET.SubElement(test, "skipped")
                    tree.write(self.path(suite))
                    self.assert_rejected()
                    self.write_report(suite, self.cases[suite])

    def test_suite_level_skips_fail(self):
        for suite in GUARD.SUITES[6:]:
            with self.subTest(suite=suite):
                tree = ET.parse(self.path(suite))
                tree.getroot().set("skipped", "1")
                tree.write(self.path(suite))
                self.assert_rejected()
                self.write_report(suite, self.cases[suite])

    def test_duplicate_names_cannot_replace_any_required_new_case(self):
        for suite in GUARD.SUITES[6:]:
            for name in self.cases[suite]:
                with self.subTest(suite=suite, name=name):
                    names = list(self.cases[suite] - {name})
                    self.write_report(suite, names + [names[0]])
                    self.assert_rejected()
            self.write_report(suite, self.cases[suite])

    def test_unexpected_new_cases_require_an_explicit_gate_update(self):
        for suite in GUARD.SUITES[6:]:
            with self.subTest(suite=suite):
                self.write_report(suite, self.cases[suite] | {"unexpectedCase()"})
                self.assert_rejected()
                self.write_report(suite, self.cases[suite])

    def test_failure_and_error_elements_cannot_hide_behind_zero_counters(self):
        for suite in GUARD.SUITES[6:]:
            for status in ("failure", "error"):
                with self.subTest(suite=suite, status=status):
                    tree = ET.parse(self.path(suite))
                    ET.SubElement(tree.getroot().find("testcase"), status)
                    tree.write(self.path(suite))
                    self.assert_rejected()
                    self.write_report(suite, self.cases[suite])

    def test_existing_generic_197_case_contract_remains_required(self):
        suite = GUARD.SUITES[3]
        self.write_report(suite, sorted(self.cases[suite])[1:])
        self.assert_rejected()

    def test_existing_catalog_100_problem_contract_remains_required(self):
        suite = GUARD.SUITES[4]
        self.write_report(suite, sorted(self.cases[suite])[1:])
        self.assert_rejected()

    def test_existing_generic_http_worker_case_remains_required(self):
        suite = GUARD.SUITES[5]
        self.write_report(suite, [])
        self.assert_rejected()

    def test_inconsistent_new_report_counts_fail(self):
        for suite in GUARD.SUITES[6:]:
            with self.subTest(suite=suite):
                tree = ET.parse(self.path(suite))
                tree.getroot().set("tests", "999")
                tree.write(self.path(suite))
                self.assert_rejected()
                self.write_report(suite, self.cases[suite])


if __name__ == "__main__":
    unittest.main()
