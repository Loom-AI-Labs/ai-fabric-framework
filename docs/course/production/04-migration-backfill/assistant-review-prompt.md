# Independent Review Prompt: PROD-04 Migration Backfill

Review `course-0.4.0-p03-prompt-overlays..course-0.4.0-p04-migration-backfill`.

Before reviewing, verify that both refs exist. If either is missing, stop and report that the 0.4
checkpoint comparison is not published; never substitute `main` or an older 0.3 tag.

Lead with findings. Verify explicit source-repository registration, stable IDs, bounded job control,
server-owned admin scope, private-field exclusion before queue persistence, legal state transitions,
filtered migration semantics, queue/dead-letter visibility, tenant-safe retrieval, and idempotent
rerun proof. Reject any claim that `COMPLETED` alone means retrievable or that AI Fabric 0.4.0
provides an exact skipped count. Require clean tests and packaged ONNX/Lucene evidence.
