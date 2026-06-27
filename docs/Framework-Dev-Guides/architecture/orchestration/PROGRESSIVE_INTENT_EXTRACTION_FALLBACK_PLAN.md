# Progressive Intent Extraction Fallback Plan

## Goal

Intent extraction must be reliable across providers without allowing malformed or incomplete LLM
output to drive actions. AI Fabric uses a bounded progressive ladder implemented by
`ProgressiveIntentExtractionEngine`.

Progressive extraction is enabled by default:

```yaml
ai:
  intent-extraction:
    progressive:
      enabled: true
      repair-enabled: true
      repair-max-attempts: 1
      completion-enabled: true
      completion-max-attempts: 1
      multi-step-enabled: true
      max-total-llm-calls: 5
```

## Ladder

1. `compound`: ask for the complete `MultiIntentResponse` in one structured call.
2. `repair`: repair structurally invalid output when the compound response cannot be parsed or
   validated.
3. `completion`: complete structurally valid but contract-incomplete output, such as an action with
   missing required parameters.
4. `multi_step`: fall back to classification, action selection, and parameter filling when earlier
   attempts fail and the remaining LLM call budget allows it.
5. `fallback`: return a safe `OUT_OF_SCOPE` response when extraction is still unreliable.

## Budgeting

`max-total-llm-calls` bounds the entire extraction path. Multi-step extraction can need up to three
LLM calls, so it runs only when enough budget remains.

Forced debug modes are available through `force-mode`:

- `compound`
- `repair`
- `completion`
- `multi_step`
- `auto` or blank

Forced modes still fail closed when validation does not pass.

## Validation

Every attempt is post-processed and validated before it is accepted. Accepted responses must satisfy
the framework `MultiIntentResponse` contract, including required action parameters when they are
deterministically required by the registered action contract.

Diagnostics are attached to pipeline metadata under `extractionDiagnostics` and may include:

- `extractionPath`;
- `extractionAttempts`;
- `llmCalls`;
- processing times;
- model names;
- attempt issue codes.

These diagnostics are provider-agnostic and are safe for CI summaries. They must not include raw
queries, raw provider responses, retrieved content, or secrets.

## Test Evidence

Current focused tests:

- `ProgressiveIntentExtractionEngineTest`
- `IntentExtractionStepProgressiveEngineTest`
- `IntentExtractionJsonSupportTest`
- `IntentExtractionValidatorTest`
- `IntentExtractionPostProcessorRelationshipQueryTest`

Run:

```bash
mvn -f ai-infrastructure-module/pom.xml -pl ai-fabric-core -Dtest=ProgressiveIntentExtractionEngineTest,IntentExtractionStepProgressiveEngineTest test
```
