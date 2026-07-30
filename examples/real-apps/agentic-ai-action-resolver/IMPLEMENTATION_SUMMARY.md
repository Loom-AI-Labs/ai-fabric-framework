# Agentic AI Action Resolver Implementation Summary

## Status

The independent `agentic-ai-action-resolver` app is the reference proof for
bounded specialist reads, durable read jobs, confirmation-gated writes, and a
separately authorized durable human-review lifecycle. It now also proves
one-level model-selected delegation across a closed set of exact-version,
read-only specialists and an explicitly distinct one-level responsibility
handoff. Its interactive route now also proves explicit dialogue ownership
over one backend-frozen conversation snapshot. A separate bounded manager
route now proves one model-selected choice from two exact-version read-only
workers, one focused clarification, or one terminal completion, with exactly
one external conversation append. It was copied from Account Resolver to
preserve the existing live demo while the new execution contracts are
developed and verified.

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
Interactive dialogue owner: account-resolver@1
Conversation manager: account-conversation-manager@1
Manager workers: account-resolver-manager-read@1, billing-resolution-manager-advisor@1
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

## Interactive Dialogue Ownership

```text
latest typed question + stable idempotency key
  -> backend-owned principal, account, tenant, scopes, and conversation
  -> AIInteractiveExecutionGateway
  -> exact DIALOGUE_CAPABLE account-resolver@1
  -> one authorized bounded history snapshot
  -> normal AIExecutionGateway submit/find execution
  -> existing Mode, RAG, action, provider, and validation pipeline
  -> at most one validated conversation append
```

The public request cannot submit history, a snapshot, a specialist, or an
account identifier. One process-local turn may own a conversation at a time.
Exact retries replay the original invocation and changed payloads under the
same key fail as `IDEMPOTENCY_CONFLICT`. Delegation, handoff, event, and plan
workers remain non-interactive and receive no conversation implicitly.

## Bounded Conversation Manager

```text
latest typed question + stable idempotency key
  -> backend-owned principal, account, tenant, scopes, and conversation
  -> one frozen authorized conversation snapshot
  -> exact account-conversation-manager@1
  -> STRUCTURED_OUTPUT_ONLY manager stage
  -> ASK_USER | COMPLETE | INVOKE_SPECIALIST
  -> zero or one exact read-only worker
  -> registered safe worker-result projector
  -> exactly one validated external append
```

The browser cannot submit history, a target, authority, provider, prompt,
Mode, or snapshot. The manager sees only a bounded approved target catalogue
and cannot author worker payloads. Application mappers construct typed worker
input, and the selected worker is independently authorized through the normal
execution gateway with no conversation.

The manager prompt profile uses closed semantic categories so only supported
account-state and billing-assessment work can select a worker. The account
worker receives a stable current-account readiness task instead of ambiguous
follow-up prose. This preserves model-assisted route selection while keeping
the selected capability narrow.

Manager and direct dialogue share one active-turn coordinator. Exact replay
returns the original result without another manager call, worker call, or
append. Changed input under a retained scoped key returns
`MANAGER_IDEMPOTENCY_CONFLICT`. Provider, directive, mapping, worker,
projection, and persistence failures remain visible; there is no deterministic
success fallback.

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
`STRUCTURED_OUTPUT_ONLY` intent policy. The coordinator's structured-output
stage remains model intelligence, while the ordinary intent-extraction model
call is skipped and an inconsistent retrieval flag cannot bypass the
coordinator's closed capability contract.

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
- unsupported child input wait or confirmation;
- failure to cancel an unsupported child input wait;
- malformed, undeclared, or stale manager directives;
- manager target mapping or safe projection failure;
- manager/worker authorization, provider, or persistence failure; and
- manager replay conflict or a busy shared conversation.

## Verification

- Final execution reactor: 1,044 tests with no failures or skips: 5
  curated-default, 677 core, 59 chat-session, and 303 execution tests.
- Final clean real-app package: 12 smoke-support tests and 135 copied-app
  tests.
- Packaged/local core SHA-256:
  `b2543edcc887209513060e4ec0d4246fcfa2ecc524296bc4e79dd319ffd0c9a4`.
- Packaged/local execution SHA-256:
  `82271608dc71598365a2c8b56d5e8fa3697d58bf965b3d2fc8bf31067d47d6a1`.
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
- Packaged real OpenAI dialogue: the first turn returned the verified-payment
  blocker with four policy citations; the short follow-up used a frozen
  two-message/one-turn backend snapshot; exact replay preserved invocation and
  snapshot revision; changed input conflicted; and caller-supplied history was
  rejected.
- Packaged real OpenAI manager: account readiness and complete account-credit
  requests selected the two exact worker families; incomplete billing facts
  produced focused questions; an unsupported poem completed without a worker;
  a follow-up consumed one backend-owned prior turn; exact replay preserved
  manager/worker lineage; and changed input returned
  `MANAGER_IDEMPOTENCY_CONFLICT`.
- Recorded real-manager wall times were 9.91 seconds for account readiness,
  9.39 seconds for billing, 1.42 seconds for clarification, and 1.51 seconds
  for unsupported completion. Worker routes used one manager plus one worker
  invocation; direct manager responses used one manager invocation.
- Packaged real OpenAI delegation: current-account and account-credit requests
  selected the two declared target families, each child succeeded with safe
  policy evidence, exact replay preserved all invocation identities, changed
  work conflicted, and an unsupported request completed without a child.
- Invalid provider: visible `INTENT_PROVIDER_FAILED`, no receipt, and no native
  provider message or key in the public result or packaged logs.
- Privacy: synthetic address input and provider keys were absent from packaged
  logs; public action outcomes contained no address or internal account IDs.

These checks completed on 2026-07-30 with zero test failures. Publication and
release version assignment remain explicit follow-up decisions.
