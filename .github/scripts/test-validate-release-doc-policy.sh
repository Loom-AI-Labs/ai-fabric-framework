#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
tmp_root="$(mktemp -d)"
trap 'rm -rf "$tmp_root"' EXIT

mkdir -p "$tmp_root/docs"

cat > "$tmp_root/docs/allowed.md" <<'DOC'
# Allowed Policy Statements

Do not replace the vector layer with Spring AI `VectorStore` for the full AI Fabric lifecycle/admin contract.
Do not promise arbitrary nested JSON metadata filtering across all providers.
The in-memory vector store is dev/test only and is not durable for production.

```bash
mvn -f ai-infrastructure-module/pom.xml test
```
DOC

"$script_dir/validate-release-doc-policy.sh" "$tmp_root" >/dev/null

mkdir -p "$tmp_root/docs/reviews"
cat > "$tmp_root/docs/reviews/review-note.md" <<'DOC'
Review notes can quote rejected commands:

```bash
mvn -DskipTests test
```
DOC
"$script_dir/validate-release-doc-policy.sh" "$tmp_root" >/dev/null
rm -rf "$tmp_root/docs/reviews"

cat > "$tmp_root/docs/vectorstore-overclaim.md" <<'DOC'
Spring AI VectorStore replaces the full AI Fabric vector lifecycle/admin API.
DOC
if "$script_dir/validate-release-doc-policy.sh" "$tmp_root" >"$tmp_root/vectorstore.out" 2>&1; then
  echo "Expected VectorStore lifecycle/admin overclaim to fail" >&2
  exit 1
fi
grep -q "Spring AI VectorStore" "$tmp_root/vectorstore.out"
rm "$tmp_root/docs/vectorstore-overclaim.md"

cat > "$tmp_root/docs/metadata-overclaim.md" <<'DOC'
AI Fabric supports arbitrary nested JSON metadata filtering across all providers.
DOC
if "$script_dir/validate-release-doc-policy.sh" "$tmp_root" >"$tmp_root/metadata.out" 2>&1; then
  echo "Expected arbitrary metadata filtering overclaim to fail" >&2
  exit 1
fi
grep -q "arbitrary nested JSON metadata filtering" "$tmp_root/metadata.out"
rm "$tmp_root/docs/metadata-overclaim.md"

cat > "$tmp_root/docs/memory-overclaim.md" <<'DOC'
The in-memory vector store is production durable and recommended for prod.
DOC
if "$script_dir/validate-release-doc-policy.sh" "$tmp_root" >"$tmp_root/memory.out" 2>&1; then
  echo "Expected in-memory production durability overclaim to fail" >&2
  exit 1
fi
grep -q "in-memory vector provider" "$tmp_root/memory.out"
rm "$tmp_root/docs/memory-overclaim.md"

echo "Release documentation policy self-test passed."
