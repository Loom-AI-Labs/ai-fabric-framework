#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_root="${1:-$(cd "$script_dir/../.." && pwd)}"

python3 - "$project_root" <<'PY'
import re
import sys
from pathlib import Path

project_root = Path(sys.argv[1])

doc_paths = []
for relative in ["README.md", "docs"]:
    path = project_root / relative
    if path.is_file():
        doc_paths.append(path)
    elif path.is_dir():
        doc_paths.extend(sorted(path.rglob("*.md")))

skip_flags = re.compile(r"(?:-DskipTests(?:\b|=)|-Dmaven\.test\.skip(?:\b|=)|\bskipTests\b|\bmaven\.test\.skip\b)", re.IGNORECASE)
stale_boot = [
    re.compile(r"\bSpring Boot\s+3\.(?:2|4)(?:\.x|\.0)?\b", re.IGNORECASE),
    re.compile(r"\bBoot\s+3\.(?:2|4)\b", re.IGNORECASE),
    re.compile(r"\bspring-boot-starter-parent:3\.2\.0\b", re.IGNORECASE),
]

guarded_claim = re.compile(
    r"\b("
    r"do not|don't|must not|cannot|can't|does not|is not|are not|not|without|rejected|"
    r"guard|guarding|guard against|fail when|dev/test|dev only|test only|false|"
    r"acknowledg(?:e|ement|ed|required)?|required|disabled|unsupported|limitation"
    r")\b",
    re.IGNORECASE,
)
replacement_terms = re.compile(r"\b(replace|replaces|replacement|covers|supports|implements|provides)\b", re.IGNORECASE)

def normalized(text):
    return text.replace("`", "").strip()

def is_guarded_claim(text):
    return guarded_claim.search(normalized(text)) is not None

def overclaims_vectorstore_lifecycle(text):
    line = normalized(text).lower()
    if "vectorstore" not in line:
        return False
    lifecycle = "lifecycle/admin" in line or ("lifecycle" in line and "admin" in line)
    ai_fabric = "ai fabric" in line or "native vector" in line or "vector layer" in line
    return lifecycle and ai_fabric and replacement_terms.search(line) is not None and not is_guarded_claim(text)

def overclaims_arbitrary_metadata_filtering(text):
    line = normalized(text).lower()
    arbitrary_nested = "arbitrary" in line and ("nested json" in line or "nested metadata" in line)
    all_providers = "all providers" in line or "across providers" in line or "across all providers" in line
    metadata_filtering = "metadata filter" in line or "metadata filtering" in line
    return arbitrary_nested and all_providers and metadata_filtering and not is_guarded_claim(text)

def overclaims_memory_provider_durability(text):
    line = normalized(text).lower()
    memory_provider = "in-memory vector" in line or "memory provider" in line or "ai-fabric-vector-memory" in line
    production = "production" in line or "prod" in line
    durability = (
        "durable" in line
        or "persistent" in line
        or "production-ready" in line
        or "production ready" in line
        or "recommended" in line
        or "safe" in line
    )
    return memory_provider and production and durability and not is_guarded_claim(text)

violations = []
for document in doc_paths:
    relative = document.relative_to(project_root)
    in_fence = False
    fence_language = ""
    for line_number, line in enumerate(document.read_text(encoding="utf-8").splitlines(), start=1):
        stripped = line.strip()
        if stripped.startswith("```"):
            if not in_fence:
                in_fence = True
                fence_language = stripped[3:].strip().lower()
            else:
                in_fence = False
                fence_language = ""
            continue

        command_line = re.match(r"^\s*(?:\$ )?(?:mvn|./mvnw|mvnw)\b", line)
        shell_code = in_fence and fence_language in {"", "bash", "sh", "shell", "zsh"}
        if skip_flags.search(line) and (command_line or shell_code):
            violations.append((relative, line_number, "Maven examples must not skip tests", stripped))

        for pattern in stale_boot:
            if pattern.search(line):
                violations.append((relative, line_number, "Release docs must reference Spring Boot 4.1.x", stripped))

        if overclaims_vectorstore_lifecycle(line):
            violations.append((
                relative,
                line_number,
                "Docs must not claim Spring AI VectorStore replaces AI Fabric's full vector lifecycle/admin contract",
                stripped,
            ))

        if overclaims_arbitrary_metadata_filtering(line):
            violations.append((
                relative,
                line_number,
                "Docs must not claim arbitrary nested JSON metadata filtering across all vector providers",
                stripped,
            ))

        if overclaims_memory_provider_durability(line):
            violations.append((
                relative,
                line_number,
                "Docs must not present the in-memory vector provider as production durable",
                stripped,
            ))

if violations:
    print("Release documentation policy violation.", file=sys.stderr)
    for document, line_number, reason, line in violations:
        print(f"{document}:{line_number}: {reason}: {line}", file=sys.stderr)
    sys.exit(1)

print("Release documentation policy validation passed.")
PY
