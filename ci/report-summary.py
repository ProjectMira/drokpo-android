#!/usr/bin/env python3
"""Prints a Markdown summary of the unit-test and lint results of a Gradle run,
for $GITHUB_STEP_SUMMARY:

    python3 ci/report-summary.py >> "$GITHUB_STEP_SUMMARY"

Reads app/build/test-results/testDebugUnitTest/*.xml (JUnit) and
app/build/reports/lint-results-debug.sarif. Missing reports are reported as
such rather than failing — this runs with `if: always()`, including after a
compile error, when neither exists.
"""

import collections
import glob
import json
import os
import xml.etree.ElementTree as ET

TEST_GLOB = "app/build/test-results/testDebugUnitTest/*.xml"
LINT_SARIF = "app/build/reports/lint-results-debug.sarif"


def tests_section():
    files = sorted(glob.glob(TEST_GLOB))
    if not files:
        return ["**Unit tests:** no results (no test sources yet, or the build failed before tests ran)."]
    total = failures = skipped = 0
    failed = []
    for path in files:
        root = ET.parse(path).getroot()
        for suite in ([root] if root.tag == "testsuite" else root.iter("testsuite")):
            total += int(suite.get("tests", 0))
            failures += int(suite.get("failures", 0)) + int(suite.get("errors", 0))
            skipped += int(suite.get("skipped", 0))
            for case in suite.iter("testcase"):
                if case.find("failure") is not None or case.find("error") is not None:
                    failed.append(f"{case.get('classname')}.{case.get('name')}")
    verdict = "FAILED —" if failures else "OK —"
    lines = [f"**Unit tests:** {verdict} {total - failures - skipped} passed, {failures} failed, {skipped} skipped."]
    lines += [f"- `{name}`" for name in failed[:20]]
    if len(failed) > 20:
        lines.append(f"- … and {len(failed) - 20} more (see the test report artifact)")
    return lines


def lint_section():
    if not os.path.isfile(LINT_SARIF):
        return ["**Lint:** no report (the build failed before lint ran)."]
    with open(LINT_SARIF, encoding="utf-8") as f:
        sarif = json.load(f)
    levels = collections.Counter()
    rules = collections.Counter()
    for run in sarif.get("runs", []):
        for result in run.get("results", []):
            # Lint leaves "level" out for warnings (the SARIF default).
            levels[result.get("level") or "warning"] += 1
            rules[result.get("ruleId", "?")] += 1
    if not rules:
        return ["**Lint:** OK — no issues."]
    errors = levels.get("error", 0)
    verdict = "FAILED —" if errors else "OK —"
    lines = [f"**Lint:** {verdict} {errors} errors, {levels.get('warning', 0)} warnings, "
             f"{sum(levels.values()) - errors - levels.get('warning', 0)} other.", "",
             "| Rule | Count |", "|---|---|"]
    lines += [f"| `{rule}` | {count} |" for rule, count in rules.most_common(15)]
    return lines


def main():
    print("\n".join(["### Android checks", ""] + tests_section() + [""] + lint_section() + [""]))


if __name__ == "__main__":
    main()
