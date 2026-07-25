# CORE-02 Coding-Assistant Implementation Prompt

Status: Validated against `course-0.3.3-00-starter` and the CORE-02 behavioral contract.

```text
You are implementing AI Fabric course lesson CORE-02: Model And Index Application Data.

Use the framework version and immutable starter ref declared in the lesson front matter, Java 21,
and Spring Boot 4.1.x. Work only from
`course-0.3.3-00-starter` in
https://github.com/Loom-AI-Labs/ai-fabric-course-support-assistant. Do not inspect or copy the
`course-0.3.3-01-first-search` solution checkpoint while implementing.

Read first:
- docs/course/core/02-model-and-index-data/lesson.md
- docs/getting-started/03-first-semantic-search.md
- docs/getting-started/09-vector-storage-lucene.md
- current AICapable, AIIdentity, AISearchable, AIContext, AIProcess,
  AIEntityIndexingGateway, AISearchRequest, and vector APIs

Goal:
Add an application-owned KnowledgeArticle model, an allowlisted AI-facing projection,
transaction-aware local indexing, and a lifecycle test that proves no-index, create, update,
metadata, rollback, provider-failure, and delete behavior.

Before editing:
1. Verify the exact starter ref, Java/Maven versions, tests, and worktree state.
2. Inspect current pinned APIs; do not invent annotations, properties, services, or result shapes.
3. Produce a field projection table that excludes internalNotes.
4. Explain application, AI Fabric, embedding-provider, and vector-provider ownership.
5. Give a concise implementation and test plan.

Required behavior:
1. Preserve a stable entity ID across create and update.
2. Mark only approved title/body prose searchable.
3. preserve category, tenantId, and status as structured metadata.
4. Return no evidence before indexing.
5. Retrieve the expected ID with paraphrased wording after indexing.
6. Reindex updates without retaining stale content.
7. Remove vectors on delete and prove absence.
8. Add a deliberate missing-tenant-metadata failure and recovery test.
9. Prove source rollback creates neither a queue row nor a vector mutation.
10. Keep local embedding and Lucene failures visible through retry/dead-letter state; do not
    fabricate success output.

Testing:
- configuration resolution test;
- focused request/result mapping tests;
- full create/update/search/delete lifecycle integration test;
- metadata completeness assertion;
- clean `./mvnw clean verify` from the starter project.

Do not:
- serialize the entire entity into the AI projection;
- embed internalNotes or trusted scope identifiers as prose;
- use removed string-valued annotation properties or `AISearchable.weight`;
- use raw embedding/vector calls as a substitute for `AIEntityIndexingGateway`;
- treat a committed database row as indexing proof;
- assert a universal similarity percentage;
- hide missing provider/index behavior behind a canned answer;
- add an LLM or cloud credential;
- use -DskipTests;
- commit, push, deploy, or discard unrelated changes.

Stop and report when the starter checkpoint is missing, current APIs contradict the lesson, local
provider assets are unavailable, or the required lifecycle cannot be observed.

Finish with changed files, exact command outcomes, before-index and after-index evidence, metadata
proof, update/delete proof, unexecuted checks, and the final data-flow explanation.
```
