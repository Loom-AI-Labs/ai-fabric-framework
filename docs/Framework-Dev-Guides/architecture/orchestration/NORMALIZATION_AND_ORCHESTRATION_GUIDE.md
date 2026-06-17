## Normalization & Orchestration Layer Guide (Developer + User)

This guide explains how the **RAG Orchestrator pipeline** and the **Orchestration Result Normalization layer** work, how to configure them, and how to extend/debug them safely across different LLM providers.

This is a “living” guide. It complements:
- `ORCHESTRATION_RESULT_NORMALIZATION.md` (contract + rules)
- `PIPELINE_ARCHITECTURE.md` (pipeline pattern)
- `PIPELINE_STEPS_REFERENCE.md` (step-by-step reference)

---

## 1) What “orchestration” means in this repo

### High-level flow
At runtime the system takes an input query plus an `OrchestrationContext` and runs a **deterministic pipeline**:

- **Security gates** (fail-closed)
- **Intent extraction** (LLM structured output → `MultiIntentResponse`)
- **Intent handling** (execute action handlers, relationship query, or RAG)
- **Normalization** (canonicalize final outcome into a provider-agnostic contract)
- **Enrichment + sanitization + history**

### Why it is a pipeline
The pipeline pattern exists to:
- isolate responsibilities into steps,
- allow early termination with a consistent result shape,
- keep changes localized,
- make debugging and testing easier.

Reference: `PIPELINE_ARCHITECTURE.md`

---

## 2) What “normalization” means (and why we do it)

### The problem it solves
Different providers can produce different upstream shapes for the same logical outcome:
- wrapper types for compound intents,
- inconsistent “success” flags,
- missing structured fields,
- variations in error messaging.

This variability should **never** leak into the system’s public result contract.

### The contract
Normalization enforces the canonical `OrchestrationResult` contract:
- stable `type`
- stable `success`
- stable `errorCode` for deterministic failures

Reference: `ORCHESTRATION_RESULT_NORMALIZATION.md`

### What belongs in normalization (strict rule)
Normalization may only use **system facts**, not LLM “opinions”.

Allowed:
- A handler exists / does not exist (registry lookup)
- An action was attempted
- A child result is a hard `ERROR`

Not allowed:
- “guessing” the user intent from raw text after the fact
- provider-specific workarounds
- test-specific behavior (“if query contains X …”)

---

## 3) How to configure orchestration + normalization

### Core feature flags
Normalization is controlled by:

```yaml
ai:
  orchestration:
    result-normalization:
      enabled: true
      debug-snapshot-enabled: false
```

Typical profiles:
- **Production**: normalization enabled, snapshots disabled
- **CI real-api**: normalization enabled, snapshots enabled
- **Local debugging**: enable snapshots temporarily

### What “debug snapshots” do
When enabled, the pipeline stores a small rolling window of normalized-result snapshots to improve failure messages in provider matrix tests.

Guidelines:
- keep snapshots non-PII
- clear per test combination
- include only minimal metadata needed to diagnose

---

## 4) How to change the orchestrator safely (developer rules)

### Step ordering rules
The standard step ordering in this repo is documented in `PIPELINE_STEPS_REFERENCE.md`.

Normalization must run:
- **after** intent handling produced its raw `OrchestrationResult`
- **before** metadata/suggestions/sanitization/history finalize output

### Adding a new step (checklist)
When you add a step:
- keep the step single-responsibility
- decide whether it is **terminating** (security/compliance) or **non-fatal**
- ensure it respects `shouldSkip()` semantics (skip if pipeline terminated)
- add unit tests for step behavior in isolation

### Changing intent extraction
When changing intent extraction (`IntentQueryExtractor`):
- prioritize provider-agnostic prompt constraints
- ensure required action parameters are present when deterministically known
  - example: for `relationship_query`, `actionParams.query` is required and can be derived from the user query if missing
- do not add production logic keyed to specific test queries

---

## 5) How to debug failures (practical playbook)

### A) Identify which step failed
Look for pipeline logs like:
- `[Pipeline] Step <StepName> failed for request <id>: <message>`

Most failures become obvious when mapped to the step:
- intent extraction parse/validation failures → `IntentExtractionStep` / extractor logs
- unknown action handler → `IntentHandlingStep` + registry logs
- relationship query plan errors → relationship-query module logs

### B) Use normalization snapshots (real-api CI)
If snapshots are enabled, failing provider matrix tests will include:
- last normalized result snapshot
- recent snapshots (last N)

This is often the fastest way to see whether the pipeline:
- failed during provider call (auth/5xx)
- failed due to missing handler
- returned INFORMATION vs ACTION, etc.

### C) Provider request/response logs
Provider implementations should log:
- request metadata (provider/model/maxTokens/temperature)
- redacted endpoint info (never log keys)
- response metadata and a bounded content snippet

When logs differ across providers:
- confirm the workflow log level settings
- confirm provider code uses SLF4J (not `System.out`)

---

## 6) How to write tests without shortcuts (what we enforce)

### Prefer invariant assertions
In integration tests (especially real-api):
- assert canonical `type`, `success`, and `errorCode` invariants
- avoid asserting exact LLM prose

### Don’t patch production code to satisfy a test
If you need determinism for a specific scenario:
- use test-only `@TestConfiguration`
- override registries (available actions list, action handler registry) in test context

### Connectivity pre-check
When real provider credentials are required:
- run connectivity verification before the suite
- fail fast with classified errors (AUTH/RATE_LIMIT/NETWORK/PROVIDER/CONFIG)

---

## 7) Related docs / next steps
- Orchestration contract + rules: `ORCHESTRATION_RESULT_NORMALIZATION.md`
- Pipeline overview: `PIPELINE_ARCHITECTURE.md`
- Step-by-step reference: `PIPELINE_STEPS_REFERENCE.md`
- Orchestration optimizations (profiles/modes/pinned targets): `ORCHESTRATION_OPTIMIZATION_GUIDE.md`
- Progressive extraction plan (compound → repair → multi-step): `PROGRESSIVE_INTENT_EXTRACTION_FALLBACK_PLAN.md`
