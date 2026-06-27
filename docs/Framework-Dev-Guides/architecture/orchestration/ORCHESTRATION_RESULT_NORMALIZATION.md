# Orchestration Result Normalization

## Purpose

AI Fabric normalizes orchestration outcomes so callers see a stable framework contract even when
LLM providers, prompts, or compound intent shapes vary.

The runtime implementation is `ai.fabric.intent.orchestration.OrchestrationResultNormalizer`, applied
by `OrchestrationResultNormalizationStep` after intent handling and before metadata, suggestions,
sanitization, and history persistence.

## Public Contract

The normalized `OrchestrationResult` must expose stable values for:

- `type`: the user-visible outcome category.
- `success`: whether the top-level request should be treated as successful.
- `errorCode`: canonical error code for deterministic framework failures.
- `children`: preserved when a compound request was handled, so diagnostics and auditing keep the
  full child-result evidence.
- `metadata`: safe provider-agnostic diagnostics, including compound aggregation and soft child error
  evidence when applicable.

Normalization is enabled by default:

```yaml
ai:
  orchestration:
    result-normalization:
      enabled: true
      debug-snapshot-enabled: false
```

## Rules

### Compound Pending Results

When a `COMPOUND_HANDLED` result contains pending children:

- all pending children promote the next pending item to top level;
- confirmation beats clarification when both exist;
- pending plus successful children stays `COMPOUND_HANDLED` and `success=true`;
- pending plus hard failures stays `COMPOUND_HANDLED` and `success=false`;
- pending plus soft child errors keeps the compound wrapper visible.

This avoids returning misleading messages such as "some intents failed" when the real state is "the
user must confirm or clarify".

### Compound Primary Promotion

When a compound result has a successful primary child and no pending child:

- primary child priority is `ACTION_EXECUTED`, `ACTION_DENIED`, `INFORMATION_PROVIDED`,
  `CLARIFICATION_REQUIRED`, `OUT_OF_SCOPE`, then nested `COMPOUND_HANDLED`;
- the primary child type, success flag, message, and data become the top-level result;
- all children remain attached for audit/debug visibility.

### Child Error Bubbling

Hard child errors become top-level `ERROR` results. The normalizer preserves children and data while
making the failure visible through `type=ERROR`, `success=false`, and `errorCode`.

Soft child errors do not sink a successful primary child. Current soft errors are:

- missing action handler, exposed as `ACTION_NOT_FOUND`;
- generation failure attached to a successful primary action/information path, exposed as
  `GENERATION_FAILED` in `metadata.softChildErrorCode`.

### Error Code Derivation

The normalizer derives canonical error codes only from system facts:

- existing `OrchestrationResult.errorCode`;
- `data.actionResult.errorCode`;
- deterministic framework messages such as "No action handler registered".

It must not infer intent or error categories from user text, provider wording, or test-specific
queries.

## Debug Snapshots

`ai.orchestration.result-normalization.debug-snapshot-enabled=true` records a small rolling window in
`OrchestrationResultDebugSnapshotStore`.

Snapshots may include:

- request id;
- normalized type;
- success flag;
- error code;
- extraction path and LLM call count;
- validator issue codes;
- normalization rule names.

Snapshots must not include:

- raw user query;
- provider response text;
- retrieved content;
- document identifiers;
- result message or result data.

## Test Evidence

Current focused tests:

- `OrchestrationResultNormalizerTest`
- `OrchestrationResultNormalizationStepTest`
- `OrchestrationResultDebugSnapshotStoreTest`
- `RAGIntegrationFlowTest` compound-wrapper assertions

Run:

```bash
mvn -f ai-infrastructure-module/pom.xml -pl ai-fabric-core -Dtest=OrchestrationResultNormalizerTest,OrchestrationResultNormalizationStepTest,OrchestrationResultDebugSnapshotStoreTest test
```
