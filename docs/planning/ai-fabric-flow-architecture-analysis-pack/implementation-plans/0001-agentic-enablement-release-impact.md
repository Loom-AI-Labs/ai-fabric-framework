# Agentic Enablement P0/P1 Release Impact

- **Status:** Approved P0/P1 implementation; proposed release documentation
- **Date:** 2026-07-28
- **Proposed release:** AI Fabric `0.5.0`
- **Compatibility baseline:** `0.4.0`

## Summary

This change adds an optional typed execution layer for application-selected,
bounded AI specialists. It reuses the existing AI Fabric pipeline and does not
introduce a second agent runtime.

The release candidate includes:

- trusted execution principal and subject context;
- structured orchestration requests;
- explicit conversation persistence policy;
- effective capability intersection;
- centralized governed action invocation;
- safe read-side evidence references;
- the optional `ai-fabric-execution` module;
- synchronous and explicitly ephemeral execution;
- structured specialist output validation; and
- an independent Agentic AI Action Resolver reference app.

## Compatibility

Existing `0.4.x` users do not need to adopt the new module. Existing Mode,
RAG, action, provider, vector, chat-session, indexing, and synchronization APIs
remain available.

Shared action execution is internally routed through one governed boundary.
There is no public bypass option.

## New Opt-In Artifact

```text
io.github.loom-ai-labs:ai-fabric-execution
```

The artifact is dependency-managed by the framework BOM but is not included in
the default starter.

## Behavioral Guarantees

- Application calls use service/system identity without a fabricated user or
  conversation.
- Interactive calls may use a server-authorized backend conversation.
- Specialist capabilities can only narrow Mode and deployment policy.
- Read-only specialists cannot expose or invoke writes.
- Evidence outside the effective vector profile denies the execution.
- Provider output becomes application output only after grounding, schema, and
  domain validation.
- Provider and validation failures remain visible.
- Asynchronous handles declare `EPHEMERAL` durability.

## Reference Proof

`examples/real-apps/agentic-ai-action-resolver` registers:

- specialist: `account-resolver@1`;
- Mode: `resolver`;
- read action: `get_account_profile`;
- vector space: `account-resolution-policy`; and
- no specialist write action.

It proves typed and conversational entry points against ready, missing-payment,
and missing-address accounts while leaving the existing Account Resolver app
untouched.

## Deferred

This release does not claim:

- specialist write execution;
- durable or resumable execution;
- multi-specialist composition;
- delegation or handoff;
- unrestricted specialist discovery; or
- a generic agent builder.

Governed specialist writes require a separate profile-pinned receipt and
confirmation design.

The implementation gate is recorded in
[the P0/P1 approval scorecard](./0001-agentic-enablement-p0-p1-approval-scorecard.md).
Approval is limited to the read-only P0/P1 scope and is not a release or a
specialist WRITE approval.
