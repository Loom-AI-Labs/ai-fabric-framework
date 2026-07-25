# Coding Assistant Prompt: PROD-08

Implement the operations and release-readiness checkpoint in the continuing Support Knowledge
Assistant.

Constraints:

- start from `course-0.4.0-p07-qdrant`;
- verify that the starter tag exists before editing; if it is missing, stop and report that the 0.4
  checkpoint is unpublished, and never substitute `main` or an older 0.3 tag;
- preserve every existing Lucene/Qdrant, tenant, privacy, action, memory, migration, Data Sync, and
  quality contract;
- add a source-labelled, multi-stage, non-root Docker image whose build runs tests normally;
- exclude `.git` and inject source commit, branch, and build time explicitly;
- add a durable production-like profile and compose stack for the application database and pinned
  Qdrant;
- expose safe independent readiness for build, database, vector, sessions, indexing, migration,
  and generation-provider posture;
- mark disabled optional generation as optional, while making an explicitly selected provider with
  missing credentials fail visibly;
- add admin-scoped, explicitly enabled release-probe and retention operations;
- label the persistence probe truthfully as non-LLM work;
- prove application restart preserves source, vector, chat, migration, and indexing state;
- prove cleanup removes only eligible operational state and preserves source/vector ownership;
- retain keyless and optional OpenAI evidence in separate JSON artifacts;
- add CI, HTTP requests, release documentation, tests, and cleanup for every Docker resource;
- never expose credentials, fake provider success, reseed after restart, or use `-DskipTests`.

Before editing, map current state ownership, health metadata, profiles, security scopes, repositories,
smokes, and CI. Run the complete exact-commit release gate and report optional OpenAI as `NOT_RUN`
when no protected key is supplied.
