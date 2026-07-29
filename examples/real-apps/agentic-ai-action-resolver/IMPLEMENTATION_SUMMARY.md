# Agentic AI Action Resolver Implementation Summary

## Status

The independent `agentic-ai-action-resolver` app is the reference proof for
bounded specialist reads, durable read jobs, confirmation-gated writes, and a
separately authorized durable human-review lifecycle. It now also proves
one-level model-selected delegation across a closed set of exact-version,
read-only specialists and an explicitly distinct one-level responsibility
handoff. It was copied from Account Resolver to preserve the existing live
demo while the new execution contracts are developed and verified.

The copy has its own artifact, Java package, port, database, Lucene index,
durable opaque demo sessions, Dockerfile, and tests. The tracked source under
`ai-fabric-account-resolver` is unchanged.

The implementation, manifest migration, packaged runtime, and real-provider
release gates are complete. A release version has not yet been assigned or
published.

## Specialist

```text
ID: account-resolver@1
Read-only ID: account-resolver-read@1
Source: MANIFEST
Mode: resolver
Strategy: BOUNDED_ITERATIVE
Read action: get_account_profile
Proposable write: update_address
Vector space: account-resolution-policy
Write policy: CONFIRMATION_RECEIPT_REQUIRED
Read output: AccountResolutionResult
Write output: durable ActionProposalView, then safe ActionOutcomeView
Review proposer: support-credit-proposer@1
Review policy: support-credit-review@1
Senior policy: support-credit-senior-review@1
Delegation coordinator: account-resolution-coordinator@1
Delegation targets: account-resolver-read@1, billing-resolution-advisor@1
Handoff intake: account-resolution-intake@1
Handoff successors: account-resolver-read@1, billing-resolution-advisor@1
```

`POST /api/agentic-resolver/evaluate` receives application authority and is
read-only. `POST /api/agentic-resolver/chat` receives interactive authority and
may propose `update_address`. They use separate immutable specialist
definitions so the read route never requests write authority:
`account-resolver-read@1` and `account-resolver@1`, respectively.

Both definitions, their exact JSON schemas, prompt profiles, capabilities,
grounding requirements, and limits are loaded from
`src/main/resources/ai-specialists/account-resolver.yml`. The app retains Java
only for authoritative account projection/validation and registered action
behavior. Health diagnostics expose a canonical content hash for each
manifest definition.

## One-Level Delegation

```text
typed coordinator request
  -> account-resolution-coordinator@1
  -> validated COMPLETE or DELEGATE decision
  -> exact target from closed manifest enum and allowlist
  -> application maps trusted request fields to child DTO
  -> SpecialistDelegationGateway
  -> source hash, depth, deadline, target, type, and authority checks
  -> existing AIExecutionGateway
  -> typed child result with parent/child lineage
```

The coordinator can select only `account-resolver-read@1` for current-account
readiness or `billing-resolution-advisor@1` for a complete typed billing-policy
assessment. It cannot select identity, account, tenant, scopes, provider,
actions, arbitrary child parameters, or another specialist.

Because this coordinator is structured generation with no retrieval,
grounding, or actions, its manifest adapter derives the server-owned
`GENERATION_ONLY` intent policy. Semantic intent extraction still belongs to
the model, while an inconsistent model-produced retrieval flag cannot bypass
the coordinator's closed capability contract.

The child receives the backend-created trusted context but no conversation.
It is independently authorized and grounded through the normal specialist
path. WRITE-capable targets fail startup validation. Child input waits,
confirmations, recursive delegation, and provider failures remain explicit.
Identical scoped work replays in process; changed work conflicts, and restart
does not preserve delegation replay state.

## Explicit Read-Only Handoff

```text
typed intake request
  -> account-resolution-intake@1
  -> validated COMPLETE or HANDOFF decision
  -> exact successor from closed schema enum and handoff allowlist
  -> application maps trusted request fields to successor DTO
  -> SpecialistHandoffGateway
  -> predecessor hash, depth, deadline, target, type, and authority checks
  -> existing AIExecutionGateway
  -> typed successor result with predecessor/successor lineage
```

Handoff is intentionally not delegation. The intake finishes its routing
responsibility and the successor result becomes the relationship outcome.
There is no result callback for intake continuation.

The first handoff boundary is synchronous, one-level, and read-only. It
transfers no conversation, pending action, review task, evidence body, or
hidden model state. The successor is independently authorized with
backend-owned context. Exact work replays in process; changed work conflicts,
and restart does not preserve handoff replay state.

## Governed Write

```text
explicit natural-language address request
  -> model selects registered update_address and typed fields
  -> action metadata/schema validation
  -> current policy, authority, and confirmation preflight
  -> encrypted, identity-bound JDBC receipt
  -> CONFIRMATION_REQUIRED
  -> public decision containing only receiptId + CONFIRM|REJECT
  -> current identity, specialist, profile, action schema, and authority recheck
  -> optimistic PROPOSED -> CONFIRMED -> EXECUTING transitions
  -> GovernedActionInvocationService
  -> application handler updates the system of record and records behavior
  -> application-specific safe outcome projection
  -> SUCCEEDED | FAILED | OUTCOME_UNKNOWN
```

Raw principal/account/tenant/deployment IDs are fingerprinted. Executable
parameters and projected outcomes are AES-GCM protected. The public proposal
does not contain parameters. The public outcome omits subscription IDs and raw
address fields.

## Recovery

- `PROPOSED` and `CONFIRMED` receipts expire after the configured TTL.
- Concurrent confirmation is controlled by optimistic compare-and-set.
- A stale `EXECUTING` receipt becomes `OUTCOME_UNKNOWN` without retry.
- An application-authoritative result can reconcile `OUTCOME_UNKNOWN`.
- Terminal `SUCCEEDED`, `FAILED`, `REJECTED`, and `EXPIRED` receipts may be
  deleted after configured retention.
- `OUTCOME_UNKNOWN` is retained until explicitly reconciled.
- Receipt secrets must remain stable across restart and replicas.
- The reference app persists its opaque browser-session ownership binding so
  an HTTP decision can be resumed after process restart. Real applications
  should re-resolve this context from authentication.

## Durable Human Review

```text
support-credit-proposer@1
  -> real provider + account-resolution-policy evidence
  -> governed request_refund proposal receipt
  -> application-selected support-credit-review@1
  -> encrypted JDBC task committed before dispatch
  -> separate local-inbox dispatch receipt
  -> backend-authenticated reviewer
  -> approve | reject | correct | request information | escalate
  -> current reviewer, policy, source, and action revalidation
  -> safe terminal result or successor review
```

Regular and senior reviewer API keys are mapped server-side to fixed trusted
reviewer principals and scopes. The HTTP body cannot claim reviewer identity
or authority. Review details expose plain safe maps at the Spring Boot 4 HTTP
boundary while encrypted source, decision, and information payloads remain
inside the execution module.

Approval and rejection delegate to `ActionProposalCoordinator`; the review
layer never invokes a domain handler directly. Correction retires the original
receipt and creates a successor. Information is schema-bound and accepted only
from the original trusted source session. Escalation creates one successor
under the senior policy without changing the action.

Review tasks, dispatch attempts, decisions, requested information, safe
outcomes, leases, and terminal status survive restart. Exact replay includes
the task version and reviewer fingerprint. Cleanup removes retained dispatch
history with its terminal task.

## Failure Behavior

There is no success fallback. Typed failures cover:

- capability, policy, or authority denial;
- malformed or out-of-schema write parameters;
- missing application outcome projector;
- provider, retrieval, grounding, or structured-output failure;
- receipt persistence and decision-store failure;
- explicit handler rejection and unexpected write exceptions;
- outcome projection/persistence failure;
- profile, specialist version, or action-schema drift;
- cross-principal, cross-account, and cross-tenant receipt access;
- cross-tenant review access, missing reviewer scope, and separation of duty;
- changed review policy, receipt source fingerprint, or decision version;
- invalid correction or information schemas;
- review dispatch failure and exhausted decision recovery;
- deadline and conversation recording failure;
- undeclared, stale, recursive, WRITE-capable, or type-incompatible
  delegation;
- malformed or expired parent deadline;
- unsupported child input wait or confirmation; and
- failure to cancel an unsupported child input wait.

## Verification

- Final execution reactor: 970 tests with no failures or skips: 5
  curated-default, 673 core, 56 chat-session, and 236 execution tests.
- Final real-app reactor: 12 smoke-support tests and 117 copied-app tests.
- Clean packaged app build: 117 copied-app tests. Its nested core and
  execution JAR hashes matched the locally verified Maven artifacts.
- Original Account Resolver: 49 app tests and 12 smoke-support tests.
- Manifest contract, compiler, registry, schema adapter, typed client,
  published example, metrics, and receipt-hash migration tests.
- Full copied-app Spring context: JDBC receipts, durable review tasks, and HTTP
  decisions.
- Review acceptance: approve, reject, correct/successor, typed information,
  regular-to-senior escalation, cross-session denial, and safe result
  projection.
- Durable-volume review restart: a real-provider proposal and waiting task
  before restart, reviewer approval after restart, and identical terminal
  replay after another restart with one authoritative account-credit mutation.
- Real OpenAI: blocked read, proposal/confirm, post-action `READY` read,
  rejection, hostile instruction, malformed/extra parameters, idempotent
  replay, cross-session isolation, and a genuine support-credit proposal
  routed into durable human review.
- Packaged real OpenAI delegation: current-account and account-credit requests
  selected the two declared target families, each child succeeded with safe
  policy evidence, exact replay preserved all invocation identities, changed
  work conflicted, and an unsupported request completed without a child.
- Invalid provider: visible `INTENT_PROVIDER_FAILED`, no receipt, and no native
  provider message or key in the public result or packaged logs.
- Privacy: synthetic address input and provider keys were absent from packaged
  logs; public action outcomes contained no address or internal account IDs.

These checks completed on 2026-07-29 with zero test failures. Publication and
release version assignment remain explicit follow-up decisions.
