# CORE-03 Coding-Assistant Implementation Prompt

Status: Migrated to the AI Fabric 0.4 CORE-03 behavioral contract. Immutable checkpoint validation
is pending publication of `course-0.4.0-01-first-search`.

```text
You are implementing AI Fabric course lesson CORE-03: Evidence-Grounded RAG.

Use AI Fabric 0.4.0 / ai-fabric-framework-v0.4.0, Java 21, and Spring Boot 4.1.x. Before editing,
verify that the declared starter ref exists. If it does not, stop and report that the 0.4 learner
checkpoint is not published; never substitute `main` or an older 0.3 tag. Once published, work only
from
`course-0.4.0-01-first-search` in
https://github.com/Loom-AI-Labs/ai-fabric-course-support-assistant. Do not inspect or copy the
`course-0.4.0-02-rag` solution checkpoint while implementing.

Read first:
- docs/course/core/03-evidence-grounded-rag/lesson.md
- docs/getting-started/04-first-rag-chat.md
- current RAGProvider, RAGRequest, RAGResponse, RAGAutoConfiguration, and RAGService APIs
- the starter's existing CORE-02 indexing lifecycle tests

Goal:
Add evidence-grounded support answers that retrieve approved knowledge before generation, expose
stable evidence IDs, skip generation when evidence is absent, and keep retrieval and provider
failures visible.

Before editing:
1. Verify the exact starter ref, tests, provider profile, environment-variable names, and worktree.
2. Inspect pinned APIs and current application configuration; do not invent methods or properties.
3. Explain the retrieval-only RAGProvider boundary and the separate generation boundary.
4. Define the public response allowlist for answer, evidence, mode, and diagnostics.
5. Give a concise implementation and test plan.

Required behavior:
1. Add ai-fabric-rag and prove a usable RAGProvider exists at runtime.
2. Index the approved account-lockout policy through the CORE-02 lifecycle.
3. Query only the allowed knowledge-article evidence space.
4. Return RETRIEVAL_FAILED for a failed RAGResponse and do not call generation.
5. Return NO_EVIDENCE for an empty document list and do not call generation.
6. Generate only from bounded retrieved context when evidence exists.
7. Return stable evidence IDs, approved snippets, and safe diagnostics.
8. Expose generation failure; do not substitute a canned answer.
9. Add a golden evidence set that fails when an expected source ID is absent.

Testing:
- application-context RAGProvider test;
- request and public projection tests;
- no-evidence and retrieval-failure tests verifying no generation call;
- controlled grounded-generation test;
- golden expected-source test;
- empty-index then reindex integration proof;
- clean `./mvnw clean verify`.

Do not:
- claim performRAGQuery generates an answer;
- call an LLM when no evidence was retrieved;
- treat an LLM-proposed vector space as authorization;
- return raw embeddings, prompts, secrets, paths, or unrestricted metadata;
- assert a fixed universal score or exact generated paragraph;
- hide a provider failure behind deterministic success wording;
- commit API keys or use -DskipTests;
- commit, push, deploy, or discard unrelated changes.

Stop and report when the starter checkpoint is missing, the live provider key is unavailable for a
requested live run, pinned APIs contradict the lesson, or retrieval readiness cannot be proven.

Finish with changed files, exact command outcomes, RAGProvider readiness, retrieved source IDs,
no-evidence proof, generation proof, failure evidence, unexecuted checks, and the final request flow.
```
