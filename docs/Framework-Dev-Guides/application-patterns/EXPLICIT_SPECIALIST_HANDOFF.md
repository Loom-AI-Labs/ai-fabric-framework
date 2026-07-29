# Explicit Specialist Handoff

AI Fabric supports one successful specialist transferring responsibility for a
typed request to one independently authorized, exact-version, read-only
successor.

Use this when an intake or triage specialist should finish its routing
responsibility and another specialist should own the resulting domain outcome.
Do not use handoff as a different name for delegation, a fixed plan step,
conversation forwarding, or unrestricted multi-agent routing.

## Relationship Semantics

```text
delegation
  parent -> child -> typed child result returns to parent
  parent remains responsible

handoff
  predecessor completes routing responsibility
    -> successor owns the relationship outcome
  no result returns for predecessor continuation

fixed plan
  coordinator-authored step A -> step B
  neither step dynamically selects the other
```

The first handoff contract is synchronous and non-interactive. It transfers no
conversation, pending action, review task, private evidence, or hidden model
state.

## Runtime Boundary

```text
application invokes intake specialist
  -> intake returns validated COMPLETE or HANDOFF
  -> host maps trusted request fields to a typed successor DTO
  -> SpecialistHandoffGateway validates predecessor and successor
  -> existing AIExecutionGateway independently authorizes successor
  -> typed successor result with predecessor/successor lineage
```

The model may select only an exact target declared in both:

1. the intake output schema, normally as a closed enum; and
2. the intake specialist's `spec.handoff.targets` allowlist.

The host application constructs the successor input and
`TrustedExecutionContext`. Model output cannot grant identity, subject,
tenant, scopes, provider access, action authority, or a deadline.

## Manifest

Declare exact successor versions:

```yaml
apiVersion: ai.fabric/v1
kind: Specialist
metadata:
  name: account-resolution-intake
  version: "1"
spec:
  # normal specialist configuration omitted
  handoff:
    targets:
      - account-resolver-read@1
      - billing-resolution-advisor@1
```

Close the same target set in the structured output schema:

```yaml
decision:
  type: string
  enum: [COMPLETE, HANDOFF]
targetSpecialist:
  type:
    - string
    - "null"
  enum:
    - account-resolver-read@1
    - billing-resolution-advisor@1
    - null
```

Startup rejects malformed, duplicate, unknown, self-referencing, excessive,
or WRITE-capable successors. Existing Java definitions and manifests remain
handoff-disabled until they explicitly declare targets. Delegation and
handoff policies are independent.

## Application Invocation

Invoke the predecessor through its typed client and wait for its validated
result:

```java
AIExecutionResult<IntakeDecision> predecessor =
    intakeClient.execute(new SpecialistInvocation<>(
        request,
        trustedContext,
        null,
        deadline,
        idempotencyKey
    ));
```

For a validated `HANDOFF`, map application-owned fields to the selected
successor's DTO:

```java
SpecialistHandoffResult<IntakeDecision, AccountResult> result =
    handoffGateway.handoff(
        new SpecialistHandoffRequest<>(
            predecessor,
            SpecialistId.parse(
                predecessor.output().targetSpecialist()
            ),
            new AccountRequest(request.question()),
            trustedContext,
            deadline,
            idempotencyKey
        ),
        AccountRequest.class,
        AccountResult.class
    );
```

Do not deserialize a model-authored arbitrary successor payload. Do not copy
credentials, identity, tenant, account IDs, scopes, action parameters, or
provider options from the predecessor output.

## Enforcement

`SpecialistHandoffGateway` enforces:

- a successful predecessor with validated output;
- the current predecessor content hash;
- depth zero before handoff and depth one afterward;
- an exact predecessor-declared successor;
- a registered read-only successor;
- typed binding through `SpecialistClientFactory`;
- no successor conversation binding;
- backend-owned trusted context;
- independent authorization through `AIExecutionGateway`;
- the earlier of predecessor and requested deadlines;
- explicit rejection of successor input waits and confirmations; and
- access-scoped, payload-checked, process-local replay.

A delegated child or handoff successor cannot initiate another transition.
Provider, schema, authority, deadline, cancellation, and successor failures
remain visible.

## Result And Lineage

`SpecialistHandoffResult<P,O>` returns:

- handoff and predecessor invocation IDs;
- exact predecessor and successor specialist IDs;
- fixed depth `1`;
- validated predecessor output;
- the typed successor `AIExecutionResult<O>`;
- safe failure details;
- replay state; and
- timestamps.

Successor diagnostics use handoff-specific lineage:

```text
handoff=true
handoffId
handoffDepth=1
predecessorInvocationId
predecessorSpecialist
```

They do not contain delegation fields such as `parentInvocationId`.

## Current Boundary

This first implementation does not transfer dialogue ownership. A
conversation-capable handoff requires an atomic persistent execution-owner
record, a frozen conversation revision, explicit pending-action disposition,
and durable recovery. Do not simulate this by copying a
`ConversationBinding`.

The default replay store is process-local and expires with the configured
async result TTL. Identical work under the same trusted access binding and
idempotency key replays. Changed predecessor, successor, input, deadline, or
typed binding returns `HANDOFF_IDEMPOTENCY_CONFLICT`.

## Verification Checklist

Before deployment, prove:

1. strict manifest startup succeeds from the packaged artifact;
2. every successor is exact-versioned and read-only;
3. each successor family succeeds with the real configured provider;
4. unsupported intake returns `COMPLETE` without a successor;
5. invented targets fail before successor invocation;
6. missing successor authority is denied normally;
7. provider failures remain visible;
8. exact replay does not invoke the successor twice;
9. changed work under one key conflicts;
10. no conversation or pending action is transferred; and
11. process-local restart behavior is documented.

The executable reference is
[`agentic-ai-action-resolver`](../../../examples/real-apps/agentic-ai-action-resolver).
Its `account-resolution-intake@1` may hand off only to
`account-resolver-read@1` or `billing-resolution-advisor@1`.
