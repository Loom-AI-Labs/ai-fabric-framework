# Coding Assistant Prompt: Implement PROD-05 Live Data Sync

Work from `course-0.3.3-p04-migration-backfill` in the learner repository.

Add the released Data Sync module and an application-owned knowledge article create/update/delete
boundary. Derive verified auth context, tenant, scopes, vector space, metadata, source version, and
trace server-side. Keep the raw framework DTO endpoint externally inaccessible and keep trusted
platform bypass disabled. Add a bounded reconciliation endpoint and tests for stable identity,
stale-content replacement, delete, access denial, invalid-content rollback, batch limit, and visible
partial failure. Extend readiness, release evidence, HTTP requests, and packaged ONNX/Lucene smoke.

Do not accept client auth context or vector space, put internal notes in evidence, claim a distributed
transaction, hide per-operation failures, or require a cloud key. Run `clean verify` and packaged
smoke.
