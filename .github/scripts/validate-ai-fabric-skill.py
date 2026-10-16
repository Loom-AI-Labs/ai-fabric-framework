#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SKILL_ROOT = ROOT / "skills" / "ai-fabric"
EXPECTED = {
    "SKILL.md",
    "references/modules-and-providers.md",
    "references/indexing-and-retrieval.md",
    "references/actions-and-trust.md",
    "references/conversation-and-execution.md",
    "references/verification.md",
}
MARKDOWN_LINK = re.compile(r"(?<!!)\[[^]]+\]\(([^)]+)\)")
FORBIDDEN = (
    "/Users/",
    "TheBaseRepo",
    "PLATFORM_ADMIN_API_KEY",
    "COOLIFY_PRODUCTION_API_TOKEN",
    "OPENAI_API_KEY=",
)


def fail(message: str) -> None:
    print(f"AI Fabric skill validation failed: {message}", file=sys.stderr)
    raise SystemExit(1)


for relative in EXPECTED:
    if not (SKILL_ROOT / relative).is_file():
        fail(f"missing {relative}")

skill_text = (SKILL_ROOT / "SKILL.md").read_text(encoding="utf-8")
if not skill_text.startswith("---\n"):
    fail("SKILL.md must start with YAML frontmatter")
frontmatter = skill_text.split("---\n", 2)[1]
if not re.search(r"(?m)^name:\s+ai-fabric\s*$", frontmatter):
    fail("SKILL.md name must be ai-fabric")
if not re.search(r"(?m)^description:\s+\S", frontmatter):
    fail("SKILL.md must declare a description")

for document in sorted(SKILL_ROOT.rglob("*.md")):
    text = document.read_text(encoding="utf-8")
    for forbidden in FORBIDDEN:
        if forbidden in text:
            fail(f"{document.relative_to(ROOT)} contains private or secret-shaped text: {forbidden}")
    for target in MARKDOWN_LINK.findall(text):
        target = target.strip()
        if target.startswith(("http://", "https://", "mailto:", "#")):
            continue
        path_text = target.split("#", 1)[0]
        resolved = (document.parent / path_text).resolve()
        if not resolved.exists():
            fail(f"broken link in {document.relative_to(ROOT)}: {target}")
        try:
            resolved.relative_to(ROOT)
        except ValueError:
            fail(f"link escapes the public repository in {document.relative_to(ROOT)}: {target}")

pom = ET.parse(ROOT / "ai-infrastructure-module" / "pom.xml").getroot()
namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
version = pom.findtext("m:version", namespaces=namespace)
skill_version_match = re.search(
    r"validated for `(?P<version>\d+\.\d+\.\d+)`",
    skill_text,
)
if not skill_version_match:
    fail("SKILL.md must declare its validated immutable release")
skill_version = skill_version_match.group("version")
if skill_version != version:
    fail(f"skill supports {skill_version} but framework BOM is {version}")

managed_artifacts = {
    dependency.findtext("m:artifactId", namespaces=namespace)
    for dependency in pom.findall(".//m:dependencyManagement/m:dependencies/m:dependency", namespace)
}
referenced_artifacts = set(
    re.findall(
        r"`(ai-fabric-(?:starter|execution|provider-starter|provider-spring-ai|indexing|data-sync|rag|retrieval-connector|actions-connector|actions-registry|actions-registry-liquibase|chat-session|behavior|pii|governance|web|vector-[a-z-]+))`",
        "\n".join(path.read_text(encoding="utf-8") for path in sorted(SKILL_ROOT.rglob("*.md"))),
    )
)
missing_artifacts = sorted(referenced_artifacts - managed_artifacts)
if missing_artifacts:
    fail("referenced artifacts are absent from the BOM: " + ", ".join(missing_artifacts))

print(f"AI Fabric skill validation passed ({len(EXPECTED)} files, release {version}).")
