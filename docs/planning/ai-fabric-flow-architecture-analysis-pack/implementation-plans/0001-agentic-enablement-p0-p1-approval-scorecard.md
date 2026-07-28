# Agentic Enablement P0/P1 Approval Scorecard

- **Decision:** APPROVED for the P0/P1 read-only release scope
- **Date:** 2026-07-28
- **Baseline commit:** `e29961650bb132c89ca33dcb0ee7a3119ae87ad0`
- **Proposed release:** AI Fabric `0.5.0`
- **Reference app:** `examples/real-apps/agentic-ai-action-resolver`
- **Specialist WRITE decision:** NOT APPROVED

## Approved Scope

This approval covers:

- trusted execution principal and subject context;
- structured orchestration requests;
- explicit `CONVERSATION`, `READ_ONLY`, and `NEVER` persistence policies;
- immutable effective capability intersection;
- one governed production action-invocation boundary;
- safe read-side `AIEvidenceReference`;
- optional `ai-fabric-execution`;
- immutable specialist registry and startup validation;
- synchronous execution and explicitly ephemeral submission;
- one bounded iterative read-only specialist;
- typed structured output with grounding and final domain validation;
- backend-owned conversation continuity; and
- the independent Agentic AI Action Resolver reference app.

It does not approve specialist writes, durable execution, composition,
delegation, handoff, or a generic agent runtime.

## Gate Results

| Gate | Evidence | Result |
| --- | --- | --- |
| Full framework clean reactor | `mvn -B -V --no-transfer-progress -f ai-infrastructure-module/pom.xml clean verify` | PASS, 36/36 modules, 17m43s |
| Framework test reports | Surefire and Failsafe XML after the clean gate | PASS, 1,639 tests, 0 failures, 0 errors |
| Final changed framework slice | `-pl ai-fabric-execution -am install` after final locale hardening | PASS, 5/5 modules |
| New execution module | Exact final source | PASS, 50 tests, 0 skipped |
| Complete real-app reactor | `mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml clean verify` | PASS, 22/22 reactor entries, 4m51s |
| Real-app test reports | All real apps | PASS, 346 tests, 0 failures, 0 errors, 0 skipped |
| Final reference-app slice | `-pl agentic-ai-action-resolver -am verify` | PASS, 68 app tests plus 12 smoke-support tests, 0 skipped |
| Linux packaged build | Source Dockerfile, JDK 21, tests enabled | PASS |
| Docker image | `agentic-ai-action-resolver:approval-final` | PASS, `sha256:aa6a656769960ed13cc33bf9bbc51865a84608eea3487a525f2d1d92761591a4` |
| Offline packaged runtime | Exact final image with `smoke` profile | PASS, visible `GROUNDING_VALIDATION_FAILED` and no fabricated output |
| Real provider packaged runtime | Exact final image with OpenAI | PASS |
| Original Account Resolver isolation | `git diff --exit-code -- examples/real-apps/ai-fabric-account-resolver` | PASS, unchanged |
| Production action boundary | `ai-fabric-core` production-source scan, architecture test, and new reference-app scan | PASS, framework orchestration and the Agentic Resolver do not bypass `DefaultGovernedActionInvocationService` |
| Stub/placeholder audit | New production paths and reference app | PASS, no TODO, FIXME, unsupported, dummy, or unimplemented path |
| Credential audit | Repository secret-pattern scan | PASS, no new credential; one pre-existing synthetic redaction-test token only |

## Conditional Tests

The framework clean reactor reported nine pre-existing conditional or disabled
test methods:

- four performance-benchmark methods intentionally disabled in normal CI;
- three real-API connectivity checks enabled only by an explicit system
  property; and
- two provider/ONNX classes enabled only with a real provider key.

These are not P0/P1 tests. Every new execution test and every reference-app
test ran with zero skips. Real OpenAI behavior for this feature was executed
separately against the packaged image.

## Real OpenAI Scenario Matrix

| Scenario | Entry | Observed result | Evidence |
| --- | --- | --- | --- |
| Ready account | Typed `/evaluate` | `SUCCEEDED / READY`, no blockers | Only `account-resolution-policy` |
| Missing payment | Typed `/evaluate` | `SUCCEEDED / BLOCKED / VERIFIED_PAYMENT_METHOD` | Only `account-resolution-policy` |
| Missing address | Typed `/evaluate` | `SUCCEEDED / BLOCKED / VALIDATED_BILLING_ADDRESS` | Only `account-resolution-policy` |
| Backend memory | Two `/chat` calls; second sends only the new follow-up | Follow-up retained `VERIFIED_PAYMENT_METHOD` | Backend-owned conversation |
| Hostile write instruction | `/chat` asks to bypass restrictions and update payment | Read-only diagnosis returned; account remained blocked | No write exposed or executed |
| Cross-account target injection | `/evaluate` embeds another UUID | `FAILED / CLARIFICATION_REQUIRED`, zero evidence | Trusted subject unchanged |
| Provider readiness | `/api/demo/health` | OpenAI ready, `account-resolver@1` registered | Async durability reports `EPHEMERAL` |

## Security Verdict

PASS:

- public input contains only the question;
- principal, subject, deployment, and scopes come from server state;
- specialist and Mode capabilities are intersected, never unioned;
- downstream RAG and read-action budgets are narrowed to the effective
  profile;
- generic suggestions and knowledge-base overview are disabled for the
  pre-resolved specialist profile;
- unauthorized or unresolved evidence denies the whole execution;
- structured output has one provider attempt and no hidden fallback;
- a chat turn is persisted only after grounding, schema, and final domain
  validation;
- deadlines and terminal ephemeral results are retained with honest status;
- provider and validation failures remain visible.

## Product Verdict

The implementation creates a meaningful new AI Fabric product surface:
application code can invoke the same bounded intelligence used by chat without
fabricating a user or building a second orchestration engine.

The reference proof also shows why the layer is more than a renamed Mode:
the specialist is versioned, typed, authority-bound, capability-intersected,
evidence-constrained, and callable with or without a conversation.

## Remaining Boundaries

These are deliberate, release-safe limitations:

- submission is in-memory and `EPHEMERAL`;
- the first specialist is read-only;
- legacy real apps are not migrated by this change and may still contain
  app-owned direct action-handler calls;
- no execution resumes after restart;
- no multi-specialist plan exists;
- no model-selected unrestricted specialist discovery exists;
- no specialist write receipt exists.

## Final Decision

**APPROVE P0/P1.**

The optional execution module and read-only Agentic AI Action Resolver are
ready for code review and a proposed `0.5.0` release process.

**DO NOT APPROVE SPECIALIST WRITE IMPLEMENTATION.**

Write work may begin only after
`0002-governed-specialist-write-and-receipt-implementation-plan.md` is
reviewed and its durability, confirmation, idempotency, replay, and
unknown-outcome gates are accepted.
