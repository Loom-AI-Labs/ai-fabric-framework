# Independent Review Prompt: PROD-05 Live Data Sync

Review `course-0.4.0-p04-migration-backfill..course-0.4.0-p05-live-data-sync`.

Before reviewing, verify that both refs exist. If either is missing, stop and report that the 0.4
checkpoint comparison is not published; never substitute `main` or an older 0.3 tag.

Lead with findings. Verify the public boundary derives identity, tenant, scope, vector space, and
projection from trusted backend state; internal bypass is false; the low-level endpoint is not
public; private fields are absent; IDs remain stable; source rollback and external-store limits are
described honestly; stale update/delete evidence is tested; and batch denial, limits, and partial
failure have no hidden success. Require clean tests and packaged ONNX/Lucene proof.
