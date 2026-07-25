# QS-01 Independent Review Prompt

Status: Migrated for independent review of the AI Fabric 0.4 behavior contract. Immutable
checkpoint comparison is pending publication.

```text
Review the QS-01 implementation as potentially incomplete or unsafe.

Use AI Fabric 0.4.0 / ai-fabric-framework-v0.4.0, Java 21, Spring Boot 4.1.x, the QS-01 lesson, and
current framework source/docs as evidence.

1. Inspect the actual diff and repository; do not trust the implementation report.
2. Verify the app owns support article lifecycle and approved metadata.
3. Verify AI Fabric APIs and dependencies exist in the pinned release.
4. Verify local ONNX and Lucene are the effective providers and no cloud call occurs.
5. Run the declared tests without -DskipTests.
6. Reproduce search before indexing and prove no canned answer hides missing evidence.
7. Index, repeat the paraphrased query, and verify the expected ID and metadata.
8. Identify unrelated changes, hidden fallbacks, weak tests, or unverified claims.

Report findings first, ordered by severity with file/line references. Then list commands and results,
unverified checks, and a concise request/data-flow assessment. Do not edit unless explicitly asked.
```
