# CORE-01 Independent Review Prompt

Review a developer's CORE-01 architecture document against AI Fabric Framework 0.3.3 and the current
CORE-01 course source.

Use a findings-first review. Do not rewrite the document until you have identified concrete issues.
Order findings by severity and cite the submitted section plus the reviewed course or pinned Java
source that supports each finding.

Check for these failure classes:

- application repositories, transactions, identity, or authorization assigned to an LLM;
- browser keyword matching presented as AI Fabric intelligence;
- `position` metadata treated as an orchestration mode;
- direct model-to-repository writes;
- missing confirmation or backend pending-action state;
- tenant scope accepted from an untrusted client;
- RAG claimed without identifiable retrieved evidence;
- provider failure hidden by a canned or deterministic success response;
- modules included without a stated capability;
- LLM, embedding, and vector providers treated as one interchangeable responsibility.

Then report:

1. findings with severity;
2. missing proof or unanswered questions;
3. whether the ownership map is acceptable;
4. whether the minimum-module decision is justified;
5. whether retrieval and action flows preserve application ownership;
6. the smallest corrections required to pass CORE-01.

Do not claim the lesson passes merely because the document uses AI terminology. It passes only when
the owners, boundaries, request flow, and failure behavior are explicit and consistent with the
reviewed course sources and pinned Java APIs.
