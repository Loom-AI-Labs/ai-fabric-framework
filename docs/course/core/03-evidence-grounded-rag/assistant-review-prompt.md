# CORE-03 Independent Review Prompt

Status: Published for independently reviewing the CORE-03 checkpoint behavior.

Review a developer's CORE-03 implementation against AI Fabric Framework 0.3.3, the current CORE-03
course source, and pinned RAG and generation APIs.

Use a findings-first review. Order findings by severity and cite changed files/tests plus the course
or pinned source. Review retrieval, generation, and public projection as separate boundaries.

Check for these failure classes:

- `RAGService` or `performRAGQuery` incorrectly described as generating an answer;
- generation invoked after empty or failed retrieval;
- database rows presented as evidence without vector retrieval;
- answer returned without stable evidence IDs;
- model-proposed vector space treated as authorization;
- unbounded or unapproved context sent to the model;
- raw embeddings, prompts, secrets, paths, or internal metadata returned publicly;
- similarity score presented as answer correctness;
- exact generated wording used as the only quality assertion;
- retrieval or generation failure hidden behind a canned success response;
- missing `RAGProvider` or empty index reported as healthy RAG readiness;
- policy records containing prompt-control instructions instead of user-facing policy text.

Then report:

1. findings with severity;
2. missing proof or unexecuted checks;
3. whether retrieval and generation remain independently observable;
4. whether no-evidence and failure paths prevent generation;
5. whether every answered response exposes approved evidence identity;
6. whether the golden test fails when an expected source is absent;
7. the smallest corrections required to pass CORE-03.

The lesson passes only when the answer is downstream of approved retrieved evidence and callers can
inspect that evidence without receiving internal provider or prompt data.
