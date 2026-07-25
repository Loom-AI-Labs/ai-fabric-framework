# Coding Assistant Prompt: Implement PROD-04 Migration Backfill

Work from `course-0.4.0-p03-prompt-overlays` in the learner repository.

Before editing, verify that the tag exists. If it does not, stop and report that the 0.4 learner
checkpoint is not published; never substitute `main` or an older 0.3 tag.

Add the released migration module, explicitly register required JPA entities/repositories, bind
`KnowledgeArticleRepository` through `@AICapable.migrationRepository`, and expose a fixed
knowledge-article admin API with bounded filters and pause/resume/cancel operations. Report source
progress, queue state, and vector readiness separately. Exclude private source fields before durable
projection and submit only the class-free `AIIndexDocument`. Add tests for authorization,
lifecycle transitions, filters, tenant-safe
retrieval, idempotent rerun, and failure visibility. Extend readiness, HTTP examples, and packaged
smoke.

Do not serialize the source entity, make vectors the source of truth, accept an arbitrary client
entity type, fabricate an exact skipped count, treat job completion as vector readiness, or require a
cloud key. Run `clean verify` and the packaged ONNX/Lucene smoke.
