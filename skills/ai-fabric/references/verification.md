# Verification

## Match Evidence To Risk

1. Run focused unit/contract tests for every changed framework-facing behavior.
2. Run the nearest maintained real-app tests for indexing, actions, sessions, or execution.
3. Package the application and test the packaged artifact, not only IDE/source-classpath execution.
4. Resolve released dependencies with a fresh Maven local repository to prove the consumer does not rely on locally installed framework snapshots.
5. Use deterministic no-key providers only for wiring and lifecycle checks, and label that evidence honestly.
6. Run a real-provider smoke when the change claims model or embedding quality and credentials are available.

Example empty-cache command for the minimal external consumer:

```bash
fresh_repo="$(mktemp -d)"
mvn -B -V --no-transfer-progress \
  -Dmaven.repo.local="$fresh_repo" \
  -f examples/minimal-spring-boot/pom.xml \
  clean test
```

Do not add `-DskipTests` or `-Dmaven.test.skip` to verification commands.

## Capability Checks

- **Indexing/retrieval:** create, update, delete, status reconciliation, source-version replacement, exact tenant filters, nonzero retrieval, citations, and provider failure.
- **Actions:** allow/deny, trusted target resolution, schema validation, confirmation, duplicate submission, execution failure, and safe result projection.
- **Conversation:** owner binding, bounded history, restart, stale/cross-owner denial, pending confirmation continuity, handover, and explicit return to AI.
- **Execution:** typed worker input/output, budget exhaustion, restart/replay, cancellation, definition drift, and effect authorization.
- **Providers:** configuration readiness, dimensions/model compatibility, unavailable credentials, and no hidden fallback.

## Framework Release Versus Consumer Change

For a consumer application, run the focused checks above; do not require the whole framework release matrix. For a framework binary change or release, follow the complete [Release Safety Checklist](../../../docs/Framework-Dev-Guides/testing-verification/RELEASE_SAFETY_CHECKLIST.md).

For a documentation/skill-only change, validate the package and links, resolve the declared release from a fresh Maven cache, and forward-test at least one realistic task. Escalate any discovered API defect separately with focused reproduction; do not change runtime code to make documentation appear correct.

## Completion Report

Report commands and outcomes for unit tests, maintained real apps, fresh-cache resolution, packaged startup, real-provider checks, and live checks. List anything not run. A green HTTP response without expected evidence/actions/session state is not a passing capability test.
