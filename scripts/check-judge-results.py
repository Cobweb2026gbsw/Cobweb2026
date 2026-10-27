#!/usr/bin/env python3
"""Fail CI if Docker judge tests were omitted or any supported language was skipped."""

import sys
import re
import json
import xml.etree.ElementTree as ET
from pathlib import Path


LANGUAGES = (
    "PYTHON", "C", "CPP", "JAVA", "JAVASCRIPT", "TYPESCRIPT", "CSHARP",
    "GO", "RUST", "KOTLIN", "SQL", "RUBY", "LLVM_IR", "PASCAL", "PHP", "DART", "UMJUNSIK",
)
FUNCTION_LANGUAGES = tuple(language for language in LANGUAGES if language not in {"SQL", "UMJUNSIK"})
GENERIC_TYPES = ("int", "float", "bool", "string", "int[]", "string[]")
SUITES = (
    "com.drewdrew0414.domain.submission.DockerJudgeTest",
    "com.drewdrew0414.domain.submission.MultiLanguageJudgeTest",
    "com.drewdrew0414.MvpIntegrationTest",
    "com.drewdrew0414.domain.submission.GenericFunctionJudgeTest",
    "com.drewdrew0414.domain.submission.GenericLevel0CatalogJudgeTest",
    "com.drewdrew0414.Level0GenericWorkerIntegrationTest",
    "com.drewdrew0414.domain.submission.JudgeDiagnosticDockerTest",
    "com.drewdrew0414.TestRunIntegrationTest",
    "com.drewdrew0414.domain.submission.GenericNestedFunctionJudgeTest",
    "com.drewdrew0414.domain.submission.GenericAlgorithmCatalogJudgeTest",
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
    case_counts = {}
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
        if not tests or any(test.find(status) is not None for test in tests
                            for status in ("skipped", "failure", "error")):
            raise ValueError(f"Empty, skipped, or failed judge test suite: {report}")
        if len(tests) != int(root.get("tests", "0")):
            raise ValueError(f"Incomplete judge test report: {report}")
        cases[suite] = {test.get("name") for test in tests}
        case_counts[suite] = len(tests)

    docker = cases[SUITES[0]]
    multi = cases[SUITES[1]]
    http = cases[SUITES[2]]
    generic = cases[SUITES[3]]
    catalog = cases[SUITES[4]]
    generic_worker = cases[SUITES[5]]
    diagnostics = cases[SUITES[6]]
    test_runs = cases[SUITES[7]]
    nested = cases[SUITES[8]]
    algorithms = cases[SUITES[9]]
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
    required_generic = {
        f"{language}: generic {kind} identity and isolated reuse"
        for language in FUNCTION_LANGUAGES for kind in GENERIC_TYPES
    } | {
        f"{language}: actual frontend generic {kind} starter"
        for language in FUNCTION_LANGUAGES for kind in GENERIC_TYPES
    } | {
        f"{language}: solution name and empty parameters" for language in FUNCTION_LANGUAGES
    } | {
        "rejectsMalformedArgumentsAndWrongDynamicReturnTypes()",
        "fourArgumentsHaveDistinctNativeTypes()",
    }
    catalog_source = (
        Path(__file__).resolve().parents[1] / "src/main/resources/problems/level0.json"
    )
    authored = json.loads(catalog_source.read_text(encoding="utf-8"))["problems"]
    if (len(authored) != 100 or [problem["ordinal"] for problem in authored] != list(range(1, 101))
            or sum(len(problem["tests"]) for problem in authored) != 812):
        raise ValueError("Lv.0 catalog changed; update the 100-problem/812-case Docker CI contract")
    required_catalog = {
        f"Lv.0 {problem['ordinal']}: {problem['title']} ({len(problem['tests'])} Docker cases)"
        for problem in authored
    }
    required_generic_worker = {
        "genericHttpSubmissionRunsStoredSignaturesThroughWorkerAndPreservesReuseScope()",
    }
    # 컴파일/런타임 진단과 사용자 테스트 실행도 실제 Docker 경로가 빠지면 실패합니다.
    required_diagnostics = {
        "actualCCompilerFailureReportsCategoryAndOwnSourceLocation()",
        "pythonValueErrorEchoingHiddenInputDoesNotLeakItsArgumentsOrSource()",
        "javascriptArbitraryErrorAndSqlSchemaErrorsHaveOnlySafeFixedReasons()",
    }
    required_test_runs = {
        "httpQueueIsOwnerOnlyAndNeverChangesSubmissionOrSolvedStatistics()",
        "validationRejectsBadCaseCountsUtf8SizesTypesLanguagesAndRevokedProblemAccess()",
        "perAccountPendingAndPerMinuteLimitsAreEnforcedWithoutCreatingRejectedRows()",
        "globalQueueLimitIsCheckedAcrossAccounts()",
        "escapedCaseEnvelopeHasItsOwnByteLimitWithoutReturningDatabaseErrors()",
        "claimTokensAndExpiredLeasesFenceOffOldCompletionsAndNeverRetryTimedOutJobs()",
        "dockerFunctionUsesOnlyUserInputsReturnsActualForCorrectAndWrongExpectedAndLeavesFormalStatsUntouched()",
        "dockerStdinReturnsUserOutputAndCompileAndRuntimeFailuresHaveOnlySafeDiagnostics()",
        "invalidUtf8OutputCannotExpandPastThePublicDisplayByteLimit()",
    }
    required_nested = {
        f"{language}: actual nested {kind} starter"
        for language in FUNCTION_LANGUAGES for kind in ("int[][]", "string[][]")
    } | {f"{language}: six mixed native arguments" for language in FUNCTION_LANGUAGES} | {
        "millionIntegerInputAndLargeJsonResultPassThroughActualJavaDockerComparison()",
        "manualTestOutputStaysBoundedEvenThoughFormalGenericOutputIsLarger()",
        "nestedComparisonPreservesRowBoundariesEmptyRowsAndOrder()",
    }
    algorithm_entries = []
    for level, count, first, rank in ((1, 75, 1101, "SKILLED"), (2, 90, 1176, "ELITE")):
        entries = json.loads((catalog_source.parent / f"level{level}.json").read_text(encoding="utf-8"))["problems"]
        if (len(entries) != count or [p["ordinal"] for p in entries] != list(range(1, count + 1))
                or [p["number"] for p in entries] != list(range(first, first + count))
                or any(p["rankType"] != rank or len(p["tests"]) < 9 for p in entries)):
            raise ValueError(f"Invalid algorithm catalog level {level}")
        algorithm_entries.extend(entries)
    required_algorithms = {
        f"{p['rankType']} {p['ordinal']}: {p['title']} ({len(p['tests'])} Docker cases)"
        for p in algorithm_entries
    }
    if not docker:
        raise ValueError("DockerJudgeTest did not execute")
    if missing := required_multi - multi:
        raise ValueError(f"Missing {len(LANGUAGES)}-language judge cases: {sorted(missing)}")
    if missing := required_http - http:
        raise ValueError(f"Missing Docker-backed HTTP/worker cases: {sorted(missing)}")
    for suite, actual, required, expected in (
        (SUITES[3], generic, required_generic, 197),
        (SUITES[4], catalog, required_catalog, 100),
        (SUITES[5], generic_worker, required_generic_worker, 1),
        (SUITES[6], diagnostics, required_diagnostics, 3),
        (SUITES[7], test_runs, required_test_runs, 9),
        (SUITES[8], nested, required_nested, 48),
        (SUITES[9], algorithms, required_algorithms, 165),
    ):
        if case_counts[suite] != expected or len(actual) != expected or actual != required:
            raise ValueError(
                f"Incomplete {suite.rsplit('.', 1)[-1]}: expected {expected} distinct cases; "
                f"missing={sorted(required - actual)}, unexpected={sorted(actual - required)}"
            )
    print(f"Docker judge gate: {len(LANGUAGES)} languages, {len(FUNCTION_LANGUAGES)} FUNCTION adapters, "
          "HTTP/worker cases, 197 generic ABI cases, all 100 Lv.0 references/812 stored cases, "
          "generic HTTP/worker reuse integration, 3 safe-diagnostic Docker cases, "
          "all 9 custom-test-run cases (including 3 Docker cases), 48 nested/large-input ABI cases, "
          f"and all 165 algorithm references/{sum(len(p['tests']) for p in algorithm_entries)} stored cases ran")


if __name__ == "__main__":
    try:
        main(Path(sys.argv[1]) if len(sys.argv) > 1 else Path("build/test-results/test"))
    except (OSError, ET.ParseError, ValueError, KeyError, TypeError) as error:
        print(f"Docker judge gate failed: {error}", file=sys.stderr)
        sys.exit(1)
