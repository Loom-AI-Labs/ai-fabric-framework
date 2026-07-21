# CORE-02 Independent Review Prompt

Review a developer's CORE-02 implementation against AI Fabric Framework 0.3.3, the current CORE-02
course source, and the pinned Java APIs.

Use a findings-first review. Order findings by severity and cite the changed file or test plus the
course or pinned framework source that supports each finding. Do not accept method-call verification
as vector-lifecycle proof.

Check for these failure classes:

- the full domain entity, internal notes, or sensitive data entering the AI projection;
- unstable entity IDs producing duplicate or stale vectors;
- missing tenant, category, or publication metadata;
- a database commit or indexing invocation presented as completed indexing;
- search-before-index returning canned evidence or an invented answer;
- updates that leave old content retrievable;
- deletes that remove only the database row;
- fixed similarity-score assertions with no provider/corpus justification;
- embedding model or dimension changes using an incompatible existing index;
- provider failures swallowed while readiness still reports success;
- client-provided tenant metadata treated as trusted scope.

Then report:

1. findings with severity;
2. missing proof or unexecuted checks;
3. whether the projection is an explicit allowlist;
4. whether identity and required metadata survive retrieval;
5. whether create, update, search, and delete are proven against vector state;
6. whether the intentional metadata failure fails for the correct reason;
7. the smallest corrections required to pass CORE-02.

The lesson passes only when stored and retrieved evidence, not logs or controller status, proves the
complete lifecycle.
