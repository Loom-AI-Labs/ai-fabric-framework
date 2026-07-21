# CORE-02 Coding-Assistant Implementation Prompt

Status: Planned. Do not use this prompt as a validated implementation handoff until the published
starter checkpoint replaces `planned`.

```text
You are implementing AI Fabric course lesson CORE-02: Model And Index Application Data.

Use AI Fabric 0.3.3 / ai-fabric-framework-v0.3.3, Java 21, and Spring Boot 4.1.x. Work only from the
published CORE-02 starter checkpoint when it becomes available. Do not copy a solution checkpoint.

Read first:
- docs/course/core/02-model-and-index-data/lesson.md
- docs/getting-started/03-first-semantic-search.md
- docs/getting-started/09-vector-storage-lucene.md
- current AICapable, AISearchable, AIContext, AICapabilityService, AISearchRequest, and vector APIs

Goal:
Add an application-owned KnowledgeArticle model, an allowlisted AI-facing projection, explicit local
indexing, and a lifecycle test that proves no-index, create, update, metadata, and delete behavior.

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
9. Keep local embedding and Lucene failures visible; do not fabricate success output.

Testing:
- configuration resolution test;
- focused request/result mapping tests;
- full create/update/search/delete lifecycle integration test;
- metadata completeness assertion;
- clean `./mvnw clean verify` from the starter project.

Do not:
- serialize the entire entity into the AI projection;
- embed internalNotes or trusted scope identifiers as prose;
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
