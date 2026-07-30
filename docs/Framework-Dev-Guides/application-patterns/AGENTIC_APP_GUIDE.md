# Bounded Agentic Enablement With AI Fabric

AI Fabric `0.5.0` adds an optional execution layer for versioned,
application-selected AI specialists. It is designed for Java applications
that need more than one ordinary chat response while keeping identity,
authority, business state, and side effects under application control.

This is bounded agentic enablement, not an unrestricted autonomous-agent
runtime.

## 1. The Ownership Rule

The model may:

- interpret untrusted user or event input;
- reason over approved evidence;
- produce schema-bound output;
- ask for missing supported input;
- select one read-only target from a closed application catalogue; and
- propose a registered write that requires confirmation.

The model may not:

- choose its principal, subject, tenant, deployment, or scopes;
- discover arbitrary specialists, tools, endpoints, or vector spaces;
- approve or directly execute a write;
- create a workflow topology;
- bypass Mode, action, retrieval, or tenant policy;
- turn vector evidence into system-of-record truth; or
- hide provider, validation, authorization, or persistence failure.

The host application authenticates the caller, selects an exact specialist,
constructs trusted context, registers domain capabilities, owns database
transactions, and projects the final public result.

## 2. When To Use The Execution Module

Use `ai-fabric-execution` when an operation needs one or more of:

- a stable `name@version` specialist identity;
- typed or JSON Schema input and output;
- an explicit action and vector-space capability boundary;
- required grounding and safe evidence references;
- backend-owned conversation memory;
- bounded input waits;
- fixed sequential or read-only parallel composition;
- one-level closed delegation or handoff;
- service-owned asynchronous read execution;
- a confirmation-gated write receipt; or
- separately authorized durable human review.

Keep using the normal `RAGOrchestrator` path when one interactive
orchestration request already solves the application problem. The execution
module is additive and is not part of `ai-fabric-starter`.

## 3. Install The Optional Module

Import the AI Fabric BOM once, then add:

```xml
<dependency>
  <groupId>io.github.loom-ai-labs</groupId>
  <artifactId>ai-fabric-execution</artifactId>
</dependency>
```

The application still needs its selected provider, vector, RAG, action, and
optional chat-session modules.

## 4. Execution Architecture

```text
authenticated application request or trusted application event
  -> application selects exact specialist name@version
  -> application builds TrustedExecutionContext
  -> specialist request intersects with:
       Mode policy
       deployment inventory
       registered actions and vector spaces
       trusted caller scopes
  -> existing AI Fabric orchestration runs
  -> grounding and structured output are validated
  -> application receives typed result plus safe evidence
```

A specialist narrows an existing Mode. It does not create a second
orchestration engine or grant capabilities.

## 5. Start With One Read-Only Specialist

The safest first adoption is a synchronous read-only specialist:

- one exact version;
- one existing Mode;
- one or two registered vector spaces;
- optional registered READ actions;
- no writes;
- no conversation binding unless follow-up context is required;
- required grounding;
- schema-bound output; and
- visible failure.

Do not begin with plans, delegation, review, background execution, and writes
all enabled at once.

## 6. Manifest-First Authoring

Package immutable YAML or JSON under `src/main/resources/ai-specialists`, or
mount an immutable deployment resource:

```yaml
ai:
  execution:
    enabled: true
    manifests:
      enabled: true
      fail-fast: true
      locations:
        - classpath*:ai-specialists/*.yml
        - classpath*:ai-specialists/*.yaml
        - classpath*:ai-specialists/*.json
    capabilities:
      registered-vector-spaces:
        - support-policy
      allowed-actions:
        - get_ticket
```

A typical bundle contains:

1. an INPUT `SpecialistSchema`;
2. an OUTPUT `SpecialistSchema`;
3. a `SpecialistPromptProfile`; and
4. a `Specialist`.

The complete format and packaged example are documented in the
[Specialist Manifest Authoring Guide](SPECIALIST_MANIFEST_AUTHORING_GUIDE.md).

Manifests may reference only capabilities and named extensions already
registered by the application. They cannot contain:

- Java class names;
- scripts or SQL;
- arbitrary HTTP endpoints;
- provider credentials;
- identity or tenant values;
- authority scopes; or
- unregistered actions and vector spaces.

A semantic change requires a new specialist version. Do not replace content
under an active `name@version`.

## 7. Trusted Context

Construct `TrustedExecutionContext` after authentication:

```java
TrustedExecutionContext context = new TrustedExecutionContext(
    new ExecutionPrincipal(
        authenticatedUserId,
        ExecutionPrincipalType.END_USER
    ),
    new ExecutionSubjectRef("support-account", currentAccountId),
    ExecutionSource.INTERACTIVE,
    currentTenantId,
    deploymentId,
    Set.of(
        "specialist:support-knowledge@1",
        "action:get_ticket",
        "vector:support-policy"
    ),
    correlationId,
    authenticatedAt
);
```

Use `INTERACTIVE` only with an END_USER principal. Use `APPLICATION`,
`EVENT`, or `SCHEDULED` only with SERVICE or SYSTEM principals.

Never deserialize trusted context from the public request. The public body
contains untrusted domain input only.

The default authority resolver accepts exact scopes:

```text
specialist:<name>
specialist:<name>@<version>
action:<normalized-action-name>
vector:<normalized-vector-space>
```

Wildcard scopes are not supported.

## 8. Invoke A Typed Specialist

Bind a client once:

```java
SpecialistClient<SupportQuestion, SupportAnswer> client =
    specialistClientFactory.bind(
        SpecialistId.of("support-knowledge", "1"),
        SupportQuestion.class,
        SupportAnswer.class
    );
```

Execute synchronously:

```java
AIExecutionResult<SupportAnswer> result = client.execute(
    SpecialistInvocation.synchronous(
        new SupportQuestion(question),
        trustedContext
    )
);
```

Treat status and bounded failure reason as first-class outcomes. Do not assume
exceptions are the only failure surface, and do not replace a failed result
with an invented successful answer.

Return only the typed public output, correlation data, and approved
`AIEvidenceReference` values. Do not expose embeddings, internal prompts,
provider payloads, unsafe metadata, or trusted context.

## 9. Grounding And Structured Output

Provider text is not application truth.

For structured generation, AI Fabric:

1. verifies effective retrieval and READ-action capability;
2. runs existing orchestration within those limits;
3. projects approved evidence and safe read observations;
4. validates required grounding;
5. asks the configured provider for one structured value;
6. validates JSON Schema;
7. runs named domain validators;
8. optionally normalizes an already valid result; and
9. returns typed output plus canonical evidence references.

Zero approved evidence is a visible grounding failure when evidence is
required. A model cannot invent citation IDs to satisfy grounding.

Use application-owned validators or direct projectors when a conclusion must
be reconciled with the system of record.

## 10. Backend-Owned Conversation

A specialist is `NON_INTERACTIVE` by default.

For a dialogue-capable specialist:

- declare `DIALOGUE_CAPABLE`;
- require or allow a backend-created `ConversationBinding`;
- accept only the latest user message from the UI;
- let the backend load a bounded frozen history;
- enforce conversation ownership and one active turn;
- run orchestration without duplicate pipeline persistence; and
- record only a validated final turn.

See
[Interactive Dialogue Ownership](INTERACTIVE_DIALOGUE_OWNERSHIP.md).

Ordinary chat pending actions remain chat-session state. Specialist write
receipts are a separate durable state machine.

## 11. Missing Input And Safe Resume

Use a typed input continuation when a supported specialist operation lacks
required domain input.

AI Fabric can return a bounded input request and resume the same invocation
after:

- the backend verifies the caller again;
- the pending request is still active;
- authority and specialist content still match;
- attempts and TTL remain within limits; and
- the supplied value passes typed validation.

Input-wait state is process-local in `0.5.0`. It is lost on restart and must
not be described as durable workflow state.

## 12. Fixed Composition

Use an application-selected fixed plan when decomposition is known in advance.
The application registers:

- exact specialist IDs;
- typed step input mappers;
- dependency order;
- deterministic aggregation; and
- shared limits.

The model does not create the topology.

Read-only parallel stages are opt-in and support independent branches with
`ALL_REQUIRED` fan-in. A failure cancels outstanding siblings and prevents
partial aggregate publication.

See
[Bounded Read-Only Parallel Plans](BOUNDED_READ_ONLY_PARALLEL_PLANS.md).

## 13. Delegation, Handoff, And Managers

Use these only for distinct problems:

- **Delegation:** a validated coordinator selects one exact read-only child
  from a closed catalogue and receives its result.
- **Handoff:** an intake specialist transfers responsibility once to one
  exact read-only successor.
- **Conversation manager:** a dialogue-capable manager returns only
  `ASK_USER`, `INVOKE_SPECIALIST`, or `COMPLETE`, and may invoke at most one
  independently authorized read-only worker.

All targets are exact-versioned, closed, typed, depth-bounded, and
independently authorized. There is no recursive discovery or manager-authored
worker payload.

Read:

- [One-Level Specialist Delegation](ONE_LEVEL_SPECIALIST_DELEGATION.md)
- [Explicit Specialist Handoff](EXPLICIT_SPECIALIST_HANDOFF.md)
- [Interactive Dialogue Ownership](INTERACTIVE_DIALOGUE_OWNERSHIP.md)

## 14. Application Events And Durable Reads

A trusted event adapter may map a validated raw application event to a typed
read-only specialist invocation.

The adapter owns:

- event validation;
- deterministic subject resolution;
- SERVICE or SYSTEM identity;
- exact scopes;
- specialist selection;
- idempotency key; and
- typed input mapping.

The event body does not provide trusted identity or authority.

Use JDBC durable execution only for eligible machine-owned read work that
must survive restart. The contract is at-least-once read execution, not
exactly-once provider invocation.

See
[Durable Read-Only Specialist Jobs](DURABLE_READ_ONLY_SPECIALIST_JOBS.md).

## 15. Governed Writes

A specialist may propose a WRITE only when:

- the action is already registered;
- Mode, deployment inventory, and trusted authority all allow it;
- the manifest lists it as visible and proposable;
- the action metadata requires confirmation;
- write policy is `CONFIRMATION_RECEIPT_REQUIRED`;
- JDBC receipt storage is configured; and
- the application action is idempotent and reconciles with its system of
  record.

The model cannot confirm or execute its proposal.

AI Fabric persists a receipt that pins specialist version, content, typed
parameters, subject, tenant, authority, and idempotency. Confirmation rechecks
all boundaries before invoking application code.

See
[Governed Specialist Writes And Durable Receipts](../actions-governance/GOVERNED_SPECIALIST_WRITES_AND_RECEIPTS.md).

## 16. Human Review

Human review is a separate optional lifecycle around a governed proposal.
The application owns review policy, reviewer authentication, dispatcher
integration, and user experience. AI Fabric persists review tasks and delivery
attempts separately and advances an approved write only through the governed
receipt.

Delivery is not approval. The model cannot choose a reviewer or decision.

See [Durable Human Review](DURABLE_HUMAN_REVIEW.md).

## 17. Storage And Durability

| State | Storage in `0.5.0` |
| --- | --- |
| Manifests | Immutable artifact or mounted config |
| Registry | Process memory, rebuilt at startup |
| Chat turns | Configured chat-session store |
| Ordinary pending chat action | Chat-session state |
| Input waits | Process memory |
| Fixed plan checkpoints/results | Process memory |
| Active dialogue claim | Process memory |
| Manager replay result | Process memory |
| Durable read jobs | JDBC `ai_specialist_execution` |
| Governed write receipts | JDBC `ai_action_proposal_receipt` |
| Review tasks | JDBC `ai_review_task` |
| Review deliveries | JDBC `ai_review_dispatch` |
| Vector evidence | Selected vector provider |
| Domain truth | Host application system of record |

For production JDBC features:

- install application-owned Flyway or Liquibase migrations;
- set `initialize-schema: false`;
- use stable, distinct encryption and fingerprint secrets;
- test restart, replay, lease recovery, cleanup, and key mismatch; and
- monitor backlog and terminal failures.

## 18. Verification

Every adopting application should prove:

- manifest startup validation and fail-fast behavior;
- exact specialist and capability authority;
- tenant isolation;
- grounding and output-schema rejection;
- provider failure visibility;
- no action execution outside the governed invoker;
- latest-message-only dialogue behavior when enabled;
- idempotent replay for durable features;
- packaged JAR and Docker behavior;
- real-provider behavior with secrets kept out of logs; and
- rollback to the existing orchestration endpoint.

The reference implementation is
[`agentic-ai-action-resolver`](../../../examples/real-apps/agentic-ai-action-resolver/README.md).

## 19. Deliberately Not Included

AI Fabric `0.5.0` does not provide:

- unrestricted autonomous agents;
- dynamic or recursive workflow graphs;
- arbitrary model-selected tools;
- WRITE-capable composed plans;
- durable plans or input waits;
- model-selected reviewers;
- exactly-once external side effects;
- tenant-authored executable code;
- arbitrary provider endpoints in manifests; or
- a specialist-definition database or hot reload.

Choose the smallest bounded pattern that solves the application problem.
