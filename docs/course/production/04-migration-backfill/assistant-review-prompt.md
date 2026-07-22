# Independent Review Prompt: PROD-04 Migration Backfill

Review `course-0.3.3-p03-prompt-overlays..course-0.3.3-p04-migration-backfill`.

Lead with findings. Verify explicit source-repository registration, stable IDs, bounded job control,
server-owned admin scope, private-field exclusion before queue persistence, legal state transitions,
filtered migration semantics, queue/dead-letter visibility, tenant-safe retrieval, and idempotent
rerun proof. Reject any claim that `COMPLETED` alone means retrievable or that AI Fabric 0.3.3
provides an exact skipped count. Require clean tests and packaged ONNX/Lucene evidence.
