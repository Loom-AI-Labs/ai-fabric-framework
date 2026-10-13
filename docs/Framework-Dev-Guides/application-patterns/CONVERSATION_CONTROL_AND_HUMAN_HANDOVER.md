# Conversation Control And Human Handover

AI Fabric supplies reusable mechanics for temporarily changing who may answer
inside one application-owned conversation. It deliberately does not implement
an operator workforce product or a provider-specific live-chat integration.

## Ownership Boundary

AI Fabric owns:

- the conversation-control state and transition contract;
- optimistic versioning and command idempotency;
- participant message roles and ordered append semantics;
- AI turn leases and orchestration/effect guards;
- a reference JDBC store; and
- provider-neutral channel dispatch/event types.

The host application owns:

- trusted tenant, deployment, conversation, and owner identity;
- reviewed production migrations and retention;
- public owner APIs and private connector callbacks;
- optional intake validation, consent, encryption, and expiry;
- transactional outbox delivery and recovery;
- channel credentials and webhook verification;
- operator authentication, assignment, presence, and inbox UI; and
- authorization for every application-domain action.

Human conversation control does not grant domain-action authority. A human
request to modify application data still crosses the application's normal
governed action boundary.

## State Model

The public states are:

```text
AI_ACTIVE
  -> HUMAN_PENDING
  -> HUMAN_ACTIVE
  -> AI_RESUME_PENDING
  -> AI_ACTIVE

Any active state -> CLOSED
HUMAN_PENDING -> AI_ACTIVE (cancel)
```

Every transition carries an expected control version and idempotency key. A
successful authority-changing transition advances the control epoch. A stale
version is a typed conflict, not an implicit retry or overwrite.

Idempotency is exact. Hosts must retain the command fingerprint column from
the reference schema; a key reused with a different command or a message
identity reused with different participant data fails closed.

## Safe AI Turn

Before starting orchestration:

1. derive `ConversationScope` from trusted server identity;
2. acquire a `ConversationTurnLease`;
3. install `ConversationTurnLeaseGuard` on `OrchestrationContext`; and
4. append the final AI participant message using the same lease.

The guard verifies the lease before orchestration, before each governed action
effect, and before result commit. If handover begins during a model call, the
now-stale turn cannot perform the action or publish its answer.

A successful terminal control action is the one intentional exception at
result commit: it may have advanced the epoch itself while committing the
handover. The framework identifies that case from the structured successful
`ACTION_EXECUTED` result and framework-owned `terminalAction` metadata. It
does not relax effect checks, lease expiry, non-terminal stale results, or
later turns.

Do not keep a database transaction open around an LLM call. The epoch is the
race-control mechanism.

## Production Persistence

`JdbcConversationControlStore` can initialize its own schema for isolated
tests and small examples. Production hosts should set
`ai.chat.control.initialize-schema=false` and create equivalent
tables through their normal migration tool.

At minimum preserve:

- the composite tenant/deployment/conversation identity;
- the trusted owner reference;
- control state, version, epoch, active handover, and channel binding;
- command idempotency results; and
- ordered participant messages and client-message idempotency.

Use deployment-local storage. Do not place all customer conversations in a
framework-owned central service.

## Handover Intake

`HandoverIntakeSchema` is declarative. The host decides which fields are
optional or required, validates values deterministically, records explicit
consent, encrypts purpose-bound contact data, and expires it. Anonymous
continuation may be offered by host policy.

The LLM may select a structured handover action. It must not fabricate a
person's identity, infer consent, or extract hidden channel credentials.

## Channel Adapter

Use `ConversationChannelGateway` behind a separately authorized integration
boundary. A production adapter should:

- dispatch only committed outbox records;
- resolve deployment-bound secret references;
- sign and verify callbacks with replay protection;
- normalize provider events into bounded framework event types;
- retry with bounded backoff and expose dead letters; and
- carry only safe transcript and consented intake projections.

`ConversationChannelMessage` is the bounded transcript projection intended for
operator continuity. It carries participant role, safe actor projection, text,
safe attachment references, and time without transferring canonical transcript
ownership to the provider.

The adapter may request human activation, append a human message, release the
handover, or report delivery failure. It cannot silently reactivate AI.

## Terminal Control Actions

A connector action that commits handover can set `terminal: true`. On success,
the orchestration pipeline returns the action result without running sibling
intents, emitting next steps, or running post-action generation. More than one
terminal action in a model-selected turn is rejected explicitly. This flag is
suitable for committed control-plane transitions; it must not be used to hide
ordinary action failures.

## Per-Invocation Confirmation

A trusted action may keep its static confirmation boundary while avoiding a
duplicate question for an invocation that already represents an explicit user
command. Declare the action with `requiresConfirmation = true`, then add one
`@ActionConfirmationRequired` method:

```java
@ActionConfirmationRequired
boolean confirmationRequired(
    @Param("requestProvenance") RequestProvenance provenance
) {
    return provenance != RequestProvenance.EXPLICIT_USER_REQUEST;
}
```

The method receives the same structured parameters and trusted
`ActionContext` available to the action. It is application policy, not a text
matcher: the model produces the typed value and deterministic code validates
the enum and applies policy. Missing values, binding errors, exceptions, or an
absent dynamic method all fail closed to confirmation required. A dynamic
method cannot make a statically non-confirmed action more authoritative.

## Required Tests

Before advertising the feature, prove:

- duplicate and stale transition behavior;
- tenant, deployment, conversation-owner isolation;
- in-flight AI invalidation before effects and result commit;
- participant ordering and message idempotency;
- restart and event replay;
- callback signature, timestamp, nonce, and event-id replay protection;
- explicit return-to-AI behavior; and
- operation with the extension disabled.

Do not add phrase matching as a handover fallback. Model-selected handover is a
structured action decision; deterministic code validates and executes the
result within host policy.
