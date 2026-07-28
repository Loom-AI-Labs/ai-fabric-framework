# Agentic AI Action Resolver Implementation Summary

## Status

The independent `agentic-ai-action-resolver` app is the reference proof for
bounded specialist reads plus one durable, confirmation-gated write. It was
copied from Account Resolver to preserve the existing live demo while the new
execution contract is developed and verified.

The copy has its own artifact, Java package, port, database, Lucene index,
durable opaque demo sessions, Dockerfile, and tests. The tracked source under
`ai-fabric-account-resolver` is unchanged.

The implementation and local release gates are complete. A release version has
not yet been assigned or published.

## Specialist

```text
ID: account-resolver@1
Mode: resolver
Strategy: BOUNDED_ITERATIVE
Read action: get_account_profile
Proposable write: update_address
Vector space: account-resolution-policy
Write enabled: true
Read output: AccountResolutionResult
Write output: durable ActionProposalView, then safe ActionOutcomeView
```

`POST /api/agentic-resolver/evaluate` receives application authority and is
read-only. `POST /api/agentic-resolver/chat` receives interactive authority and
may propose `update_address`. They use separate immutable specialist
definitions so the read route never requests write authority:
`account-resolver-read@1` and `account-resolver@1`, respectively.

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
- deadline and conversation recording failure.

## Verification

- Source-based Docker build: 1,175 framework tests across 15 modules.
- Packaged app build: 12 smoke-support tests and 79 copied-app tests.
- Original Account Resolver: 49 app tests and 12 smoke-support tests.
- Full copied-app Spring context: JDBC receipts and HTTP decisions.
- Durable-volume restart: proposal before restart, confirmation after restart,
  and identical terminal replay after a second restart.
- Real OpenAI: blocked read, proposal/confirm, post-action `READY` read,
  rejection, hostile instruction, malformed/extra parameters, idempotent
  replay, and cross-session isolation.
- Invalid provider: visible `INTENT_PROVIDER_FAILED`, no receipt, and no native
  provider message or key in the public result or packaged logs.
- Privacy: synthetic address input and provider keys were absent from packaged
  logs; public action outcomes contained no address or internal account IDs.

These checks completed on 2026-07-28 with zero test failures. Publication and
release version assignment remain explicit follow-up decisions.
