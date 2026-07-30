# Interactive Dialogue Ownership

AI Fabric can run one explicitly eligible specialist as the dialogue owner for
an authenticated conversation turn. The host sends the user's latest message,
while the backend owns identity, subject, tenant, authority, conversation
history, specialist selection, and idempotency.

Use this boundary when a specialist must understand a follow-up such as
`What should I fix first?` from backend conversation history. Do not use it for
background jobs, events, fixed plans, delegation workers, or handoff
successors.

## Required Modules

```xml
<dependency>
  <groupId>io.github.loom-ai-labs</groupId>
  <artifactId>ai-fabric-execution</artifactId>
</dependency>

<dependency>
  <groupId>io.github.loom-ai-labs</groupId>
  <artifactId>ai-fabric-chat-session</artifactId>
</dependency>
```

The execution module supplies the specialist boundary. The chat-session module
remains the only conversation store and supplies the authorized, bounded
history snapshot.

## Manifest Contract

Dialogue ownership is opt-in. A manifest that omits
`interactionCapability` remains `NON_INTERACTIVE`.

```yaml
spec:
  conversation:
    binding: REQUIRED
    recordValidatedTurns: true
    interactionCapability: DIALOGUE_CAPABLE
```

A `DIALOGUE_CAPABLE` specialist must:

- accept a conversation binding;
- record validated turns;
- use an exact, registered specialist version; and
- have no specialist input-continuation contract.

The manifest compiler rejects an invalid combination at startup. Dialogue
capability is also part of the specialist definition fingerprint.

## Backend Call

Construct trusted context after authentication. Resolve the conversation and
current subject from backend state. Do not accept these values from the
browser. Bind manifest-backed specialists through `SpecialistClientFactory` so
the client converts application DTOs to and from the manifest's JSON schemas.

```java
SpecialistClient<AccountResolutionRequest, AccountResolutionResult> client =
    specialistClientFactory.bind(
        SpecialistId.of("account-resolver", "1"),
        AccountResolutionRequest.class,
        AccountResolutionResult.class
    );

AIExecutionResult<AccountResolutionResult> result =
    client.executeInteractive(
        new SpecialistInvocation<>(
            new AccountResolutionRequest(question),
            trustedExecutionContext,
            new ConversationBinding(currentUserId, conversationId),
            null,
            idempotencyKey
        ),
        interactiveExecutionGateway
    );
```

For each submitted user turn:

- `ExecutionSource` must be `INTERACTIVE`;
- `Idempotency-Key` is required and stable across transport retries;
- request input contains only the current typed message and domain fields;
- the caller cannot supply history or a snapshot token; and
- the application selects the exact specialist.

The same key and same payload replay the original invocation. Reusing the key
with changed input returns `IDEMPOTENCY_CONFLICT`.

If `ai.execution.async.repository=JDBC`, typed `submit` still routes
`INTERACTIVE` calls to the bounded process-local gateway. JDBC durability is
reserved for eligible machine-owned `APPLICATION`, `EVENT`, and `SCHEDULED`
read jobs. Chat history remains durable through `ai-fabric-chat-session`; an
active interactive execution itself is not restart-resumable in this first
contract.

## Runtime Flow

```text
authenticated request
  -> backend resolves principal, subject, tenant, scopes, and conversation
  -> AIInteractiveExecutionGateway validates the exact dialogue owner
  -> one active process-local turn is claimed for the conversation
  -> ChatSessionService captures one authorized bounded history snapshot
  -> an opaque, short-lived, one-use approval token binds snapshot and owner
  -> normal AIExecutionGateway submit/find path
  -> existing Mode, intent, RAG, action, provider, and validation pipeline
  -> validated output is recorded once through the existing chat recorder
  -> token and active-turn claim are released
```

`ConversationEnrichmentStep` uses the approved frozen snapshot. It does not
reload history or seed live targets during that invocation. This prevents a
conversation from changing underneath an active turn.

## Safe Diagnostics

Successful interactive executions may expose:

```text
interactiveTurn
interactionTurnId
dialogueOwner
dialogueOwnerSpecialist
conversationSnapshotRevision
conversationSnapshotMessageCount
conversationSnapshotTurnCount
```

Diagnostics never expose the transcript, snapshot token, user ID,
conversation ID, trusted context, prompt, or provider-native payload.

## Failures

| Reason | Meaning |
| --- | --- |
| `INTERACTIVE_SOURCE_REQUIRED` | The call did not use an authenticated interactive source. |
| `CONVERSATION_BINDING_REQUIRED` | The backend did not bind an authorized conversation. |
| `INTERACTIVE_IDEMPOTENCY_KEY_REQUIRED` | The call omitted its stable request key. |
| `DIALOGUE_OWNER_INELIGIBLE` | The specialist is not explicitly dialogue-capable. |
| `CONVERSATION_SNAPSHOT_FAILED` | Authorized history could not be frozen. |
| `CONVERSATION_BUSY` | Another supported turn owns this conversation in this process. |
| `IDEMPOTENCY_CONFLICT` | The key was reused with a different request. |
| `INTERACTIVE_WAIT_TIMEOUT` | The bounded synchronous wait expired. |

Provider, retrieval, policy, grounding, output-validation, and persistence
failures remain visible. The interactive gateway does not create a
deterministic answer or hide an LLM failure.

## Ownership Compared With Other Contracts

| Contract | Owns user dialogue? | Receives conversation history? | Purpose |
| --- | --- | --- | --- |
| Dialogue owner | Yes, for one active turn | One approved snapshot | Answer the current user turn |
| Authority | No | No | Bound what an execution may access or propose |
| Delegation | No | No | Ask one allowed read-only child for bounded work |
| Handoff | No | No | Transfer one typed responsibility to a successor |
| Fixed plan | No | No shared worker history | Run application-selected typed steps |
| Event/job | No | No | Run machine-owned asynchronous work |
| Conversation manager | Not implemented by this contract | Deferred | Potentially choose among closed dialogue-capable specialists |

Dialogue capability never grants evidence, vector spaces, actions, subject
access, or tenant access. Those permissions still come from the intersection
of manifest, Mode, deployment inventory, registered capabilities, and trusted
backend authority.

## Runtime Limits

The first boundary is synchronous and process-local:

- active-turn exclusion is not a distributed lease;
- snapshot approval tokens are ephemeral and not restart-safe;
- a timed-out provider call inherits the normal execution gateway's
  cancellation semantics;
- concurrent turns are rejected, not queued or merged;
- owner transfer and conversation-manager routing are not implemented; and
- pending specialist input is a separate contract and is not accepted here.

For a horizontally scaled deployment, route one conversation consistently or
add a future distributed active-turn lease before relying on cross-instance
exclusion.

## Reference Proof

`examples/real-apps/agentic-ai-action-resolver` declares
`account-resolver@1` as `DIALOGUE_CAPABLE` and routes
`POST /api/agentic-resolver/chat` through
`AIInteractiveExecutionGateway`.

The reference app proves:

- latest-message-only public requests;
- backend-owned history for follow-ups;
- exact replay without a duplicate turn;
- changed-payload conflict;
- one active turn per conversation;
- independent concurrent conversations; and
- visible provider and snapshot failures without fallback.

Its packaged-runtime gate uses a clean build and verifies that the
`ai-fabric-execution` JAR nested in the executable application has the same
SHA-256 digest as the locally verified Maven artifact. This prevents a stale
nested dependency from masquerading as a successful source-level test.
