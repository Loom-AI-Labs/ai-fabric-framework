#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd "$script_dir/../.." && pwd)"

python3 - "$project_root" <<'PY'
import re
import sys
from pathlib import Path

project_root = Path(sys.argv[1])

roots = [
    project_root / "ai-infrastructure-module",
    project_root / "examples",
]

patterns = [
    ("TODO marker", re.compile(r"\bTODO\b", re.IGNORECASE)),
    ("FIXME marker", re.compile(r"\bFIXME\b", re.IGNORECASE)),
    ("stub marker", re.compile(r"\bstub\b", re.IGNORECASE)),
    ("dummy marker", re.compile(r"\bdummy\b", re.IGNORECASE)),
    ("not implemented marker", re.compile(r"not implemented", re.IGNORECASE)),
    ("unsupported operation", re.compile(r"UnsupportedOperationException")),
]

allowed_unsupported_operations = {
    "ai-infrastructure-module/providers/ai-fabric-provider-spring-ai/src/main/java/ai/fabric/provider/springai/SpringAiChatProvider.java": [
        "does not support embeddings through Spring AI",
    ],
    "ai-infrastructure-module/providers/ai-fabric-provider-spring-ai/src/main/java/ai/fabric/provider/springai/SpringAiModelResolver.java": [
        "Spring AI ONNX is embedding-only.",
        "does not expose embeddings through Spring AI.",
        "Anthropic embeddings are not supported by Spring AI.",
    ],
}

production_suffixes = {".java", ".kt", ".groovy", ".scala", ".yml", ".yaml", ".properties", ".xml"}
violations = []

def is_allowed(relative: str, line: str, reason: str) -> bool:
    if reason != "unsupported operation":
        return False
    return any(fragment in line for fragment in allowed_unsupported_operations.get(relative, []))

for root in roots:
    if not root.exists():
        continue
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.suffix not in production_suffixes:
            continue
        relative = path.relative_to(project_root).as_posix()
        if "/target/" in relative or "/src/test/" in relative or "/src/it/" in relative:
            continue
        if "/src/main/" not in relative:
            continue

        try:
            lines = path.read_text(encoding="utf-8").splitlines()
        except UnicodeDecodeError:
            continue

        for line_number, line in enumerate(lines, start=1):
            for reason, pattern in patterns:
                if pattern.search(line) and not is_allowed(relative, line, reason):
                    violations.append((relative, line_number, reason, line.strip()))

if violations:
    print("Production stub/dummy marker validation failed.", file=sys.stderr)
    print("Production code must not ship TODO/FIXME/stub/dummy/not-implemented markers.", file=sys.stderr)
    print("UnsupportedOperationException is allowed only for explicitly documented capability boundaries.", file=sys.stderr)
    for relative, line_number, reason, line in violations:
        print(f"{relative}:{line_number}: {reason}: {line}", file=sys.stderr)
    sys.exit(1)

print("Production stub/dummy marker validation passed.")
PY
