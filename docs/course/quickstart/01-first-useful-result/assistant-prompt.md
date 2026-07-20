# QS-01 Assistant Implementation Prompt

Status: Planned. Do not use this prompt as a validated implementation handoff until the published
starter checkpoint replaces `planned`.

```text
You are implementing AI Fabric course lesson QS-01: First Useful Result.

Use AI Fabric 0.3.3 / ai-fabric-framework-v0.3.3, Java 21, and Spring Boot 4.1.x.
Work only from the published QS-01 starter checkpoint when it becomes available. Do not copy the
solution checkpoint.

Read first:
- docs/getting-started/02-installation.md
- docs/getting-started/03-first-semantic-search.md
- docs/getting-started/08-local-onnx-embeddings.md
- docs/getting-started/09-vector-storage-lucene.md
- this lesson and its intentional-failure contract

Goal:
Create a no-cloud-key semantic-search flow that seeds five application-owned support articles,
returns no evidence before indexing, indexes them through AI Fabric, and retrieves the expected
article by a paraphrased query with entity ID, score, content, and metadata.

Before editing:
1. Verify the exact starter ref, clean prerequisites, Java/Maven versions, and worktree state.
2. Inspect current AI Fabric APIs and dependencies; do not invent annotations, properties, or
   service methods.
3. Explain application, AI Fabric, ONNX provider, Lucene, and browser ownership.
4. Give a concise implementation and test plan.

Required behavior:
1. Resolve AI Fabric from Maven Central.
2. Use local ONNX embeddings and Lucene vector storage.
3. Keep support article lifecycle and approved metadata in the application.
4. Make reset, seed, index, readiness, and search explicit.
5. Reproduce search-before-index and preserve the honest no-evidence response.
6. Add focused tests for no-index and indexed retrieval behavior.

Do not:
- create browser or backend keyword shortcuts;
- return a canned support answer when retrieval is empty;
- enable a cloud provider or use credentials;
- expose unrelated domain fields;
- use -DskipTests;
- commit, push, deploy, or discard unrelated changes.

Stop and report when the starter checkpoint is missing, current APIs contradict the lesson, ONNX
assets are unavailable, or required source state is incompatible.

Finish with changed files, commands and exact outcomes, failure/recovery evidence, unexecuted checks,
and the final request/data-flow explanation.
```

