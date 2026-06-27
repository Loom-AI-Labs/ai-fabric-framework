#!/usr/bin/env bash

set -euo pipefail

REPORTS_DIR=""
SCORECARD_PATH=""
SUITE=""
LLM_PROVIDER=""
EMBEDDING_PROVIDER=""
VECTOR_DB_PROVIDER=""
MIN_SUCCESS_RATE="0.85"
MIN_CONSIDERED_TESTS="20"

usage() {
  cat <<'USAGE'
Usage: evaluate-failsafe-success-rate.sh
  --reports-dir DIR
  --scorecard-path FILE
  --suite NAME
  [--llm PROVIDER]
  [--embedding PROVIDER]
  [--vector-db PROVIDER]
  [--min-success-rate RATE]
  [--min-considered-tests COUNT]
USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --reports-dir)
      REPORTS_DIR="${2:-}"
      shift 2
      ;;
    --scorecard-path)
      SCORECARD_PATH="${2:-}"
      shift 2
      ;;
    --suite)
      SUITE="${2:-}"
      shift 2
      ;;
    --llm)
      LLM_PROVIDER="${2:-}"
      shift 2
      ;;
    --embedding)
      EMBEDDING_PROVIDER="${2:-}"
      shift 2
      ;;
    --vector-db)
      VECTOR_DB_PROVIDER="${2:-}"
      shift 2
      ;;
    --min-success-rate)
      MIN_SUCCESS_RATE="${2:-}"
      shift 2
      ;;
    --min-considered-tests)
      MIN_CONSIDERED_TESTS="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [ -z "$REPORTS_DIR" ] || [ -z "$SCORECARD_PATH" ] || [ -z "$SUITE" ]; then
  echo "Missing required arguments." >&2
  usage >&2
  exit 2
fi

python3 - "$REPORTS_DIR" "$SCORECARD_PATH" "$SUITE" "$LLM_PROVIDER" "$EMBEDDING_PROVIDER" "$VECTOR_DB_PROVIDER" "$MIN_SUCCESS_RATE" "$MIN_CONSIDERED_TESTS" <<'PY'
from __future__ import annotations

import json
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path


def as_int(value: str | None) -> int:
    try:
        return int(float(value or "0"))
    except Exception:
        return 0


def strip_namespace(tag: str) -> str:
    if "}" in tag:
        return tag.rsplit("}", 1)[1]
    return tag


def direct_children(element: ET.Element, name: str) -> list[ET.Element]:
    return [child for child in list(element) if strip_namespace(child.tag) == name]


reports_dir = Path(sys.argv[1])
scorecard_path = Path(sys.argv[2])
suite_name = sys.argv[3]
llm_provider = sys.argv[4]
embedding_provider = sys.argv[5]
vector_db_provider = sys.argv[6]
minimum_success_rate = float(sys.argv[7])
minimum_considered_tests = int(sys.argv[8])

report_files = sorted(reports_dir.glob("TEST-*.xml"))

total_tests = 0
total_failures = 0
total_errors = 0
total_skipped = 0
failing_cases: list[dict[str, str]] = []

for path in report_files:
    try:
        root = ET.parse(path).getroot()
    except Exception as exc:
        failing_cases.append({
            "className": path.name,
            "name": "parse",
            "type": "PARSE_ERROR",
            "message": str(exc),
        })
        total_errors += 1
        continue

    root_name = strip_namespace(root.tag)
    if root_name == "testsuite":
        suites = [root]
    elif root_name == "testsuites":
        suites = direct_children(root, "testsuite")
    else:
        continue

    for suite in suites:
        total_tests += as_int(suite.attrib.get("tests"))
        total_failures += as_int(suite.attrib.get("failures"))
        total_errors += as_int(suite.attrib.get("errors"))
        total_skipped += as_int(suite.attrib.get("skipped"))

        for testcase in direct_children(suite, "testcase"):
            classname = testcase.attrib.get("classname", "")
            name = testcase.attrib.get("name", "")
            failure = next((c for c in list(testcase) if strip_namespace(c.tag) == "failure"), None)
            error = next((c for c in list(testcase) if strip_namespace(c.tag) == "error"), None)
            if failure is not None or error is not None:
                issue = failure if failure is not None else error
                issue_type = "FAILURE" if failure is not None else "ERROR"
                message = (issue.attrib.get("message") or (issue.text or "")).strip()
                failing_cases.append({
                    "className": classname,
                    "name": name,
                    "type": issue_type,
                    "message": message[:1000],
                })

failed = total_failures + total_errors
passed = max(0, total_tests - failed - total_skipped)
considered = passed + failed
success_rate = 1.0 if considered == 0 else passed / considered

reasons: list[str] = []
if not report_files:
    reasons.append(f"No TEST-*.xml reports found in {reports_dir}")
if considered < minimum_considered_tests:
    reasons.append(f"consideredTests {considered} < minimumConsideredTests {minimum_considered_tests}")
if success_rate < minimum_success_rate:
    reasons.append(f"successRate {success_rate:.4f} < minimumSuccessRate {minimum_success_rate:.4f}")

passed_gate = not reasons

scorecard = {
    "suite": suite_name,
    "generatedAt": datetime.now(timezone.utc).isoformat(),
    "providers": {
        "llm": llm_provider or None,
        "embedding": embedding_provider or None,
        "vectorDb": vector_db_provider or None,
    },
    "thresholds": {
        "minimumSuccessRate": minimum_success_rate,
        "minimumConsideredTests": minimum_considered_tests,
    },
    "results": {
        "totalTests": total_tests,
        "passed": passed,
        "failed": failed,
        "failures": total_failures,
        "errors": total_errors,
        "skipped": total_skipped,
        "consideredTests": considered,
        "successRate": success_rate,
    },
    "decision": {
        "passed": passed_gate,
        "reason": "; ".join(reasons) if reasons else None,
    },
    "failingCases": failing_cases,
}

scorecard_path.parent.mkdir(parents=True, exist_ok=True)
scorecard_path.write_text(json.dumps(scorecard, indent=2) + "\n")

print(json.dumps(scorecard, indent=2))
sys.exit(0 if passed_gate else 1)
PY
