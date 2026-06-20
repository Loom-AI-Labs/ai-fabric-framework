#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd "$script_dir/../.." && pwd)"
workflow_dir="$project_root/.github/workflows"

if [ ! -d "$workflow_dir" ]; then
  echo "Workflow directory not found: $workflow_dir" >&2
  exit 1
fi

python3 - "$workflow_dir" <<'PY'
import re
import sys
from pathlib import Path

workflow_dir = Path(sys.argv[1])

patterns = [
    re.compile(r"-DskipTests(?:\b|=)", re.IGNORECASE),
    re.compile(r"-Dmaven\.test\.skip(?:\b|=)", re.IGNORECASE),
    re.compile(r"\bskipTests\b", re.IGNORECASE),
    re.compile(r"\bmaven\.test\.skip\b", re.IGNORECASE),
    re.compile(r"\bskip(?:ped|ping)?\s+(?:unit\s+)?tests?\b", re.IGNORECASE),
]

violations = []
for workflow in sorted(workflow_dir.glob("*.y*ml")):
    for line_number, line in enumerate(workflow.read_text(encoding="utf-8").splitlines(), start=1):
        if any(pattern.search(line) for pattern in patterns):
            violations.append((workflow, line_number, line.strip()))

if violations:
    print("Workflow test policy violation: workflow commands must not skip unit tests.", file=sys.stderr)
    for workflow, line_number, line in violations:
        print(f"{workflow}:{line_number}: {line}", file=sys.stderr)
    sys.exit(1)

print("Workflow test policy validation passed.")
PY
