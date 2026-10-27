#!/usr/bin/env python3
"""Fail CI if Docker judge tests were omitted or any supported language was skipped."""

import sys
import re
import xml.etree.ElementTree as ET
from pathlib import Path


LANGUAGES = (
    "PYTHON", "C", "CPP", "JAVA", "JAVASCRIPT", "TYPESCRIPT", "CSHARP",
    "GO", "RUST", "KOTLIN", "SQL", "RUBY", "LLVM_IR", "PASCAL", "PHP", "DART", "UMJUNSIK",
)
FUNCTION_LANGUAGES = tuple(language for language in LANGUAGES if language not in {"SQL", "UMJUNSIK"})
SUITES = (
    "com.drewdrew0414.domain.submission.DockerJudgeTest",
    "com.drewdrew0414.domain.submission.MultiLanguageJudgeTest",
    "com.drewdrew0414.MvpIntegrationTest",
)


def main(result_dir: Path) -> None:
    language_source = (
        Path(__file__).resolve().parents[1]
        / "src/main/java/com/drewdrew0414/domain/submission/Language.java"
    ).read_text(encoding="utf-8")
    enum_body = re.search(r"public\s+enum\s+Language\s*\{(.*?);", language_source, re.S)
    if enum_body is None:
        raise ValueError("Could not read the supported Language enum")
    declared = set(re.findall(r"\b([A-Z][A-Z0-9_]*)\s*\(", enum_body.group(1)))
    if declared != set(LANGUAGES):
        raise ValueError(f"Supported language list changed; update the CI gate: {sorted(declared)}")

    cases = {}
    for suite in SUITES:
        report = result_dir / f"TEST-{suite}.xml"
        if not report.is_file():
            raise ValueError(f"Missing judge test report: {report}")
        root = ET.parse(report).getroot()
        if root.tag != "testsuite" or root.get("name") != suite:
            raise ValueError(f"Unexpected test suite in {report}")
        if any(int(root.get(key, "0")) for key in ("skipped", "failures", "errors")):
            raise ValueError(f"Skipped or failed judge tests in {report}")
        tests = root.findall("testcase")
        if not tests or any(test.find("skipped") is not None for test in tests):
            raise ValueError(f"Empty or skipped judge test suite: {report}")
        if len(tests) != int(root.get("tests", "0")):
            raise ValueError(f"Incomplete judge test report: {report}")
        cases[suite] = {test.get("name") for test in tests}

    docker = cases[SUITES[0]]
    multi = cases[SUITES[1]]
    http = cases[SUITES[2]]
    required_multi = {
        f"{language}: compile once, run isolated cases" for language in LANGUAGES
    } | {
        f"{language}: FUNCTION adapter" for language in FUNCTION_LANGUAGES
    }
    required_http = {
        "chainLaserHttpSubmissionRunsAllPrivateCases()",
        "realWorkerHandlesCompiledSqlAndCompilationFailure()",
        "newLanguagesRunThroughHttpAndWorker()",
    }
    if not docker:
        raise ValueError("DockerJudgeTest did not execute")
    if missing := required_multi - multi:
        raise ValueError(f"Missing {len(LANGUAGES)}-language judge cases: {sorted(missing)}")
    if missing := required_http - http:
        raise ValueError(f"Missing Docker-backed HTTP/worker cases: {sorted(missing)}")
    print(f"Docker judge gate: {len(LANGUAGES)} languages, {len(FUNCTION_LANGUAGES)} FUNCTION adapters, and HTTP/worker cases ran")


if __name__ == "__main__":
    try:
        main(Path(sys.argv[1]) if len(sys.argv) > 1 else Path("build/test-results/test"))
    except (OSError, ET.ParseError, ValueError) as error:
        print(f"Docker judge gate failed: {error}", file=sys.stderr)
        sys.exit(1)
