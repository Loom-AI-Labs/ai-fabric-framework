# One-Level Specialist Delegation

AI Fabric supports one validated specialist result selecting one read-only
child specialist from an application-approved, exact-version allowlist.

Use this when one coordinator needs model intelligence to choose between a
small set of narrow specialists, while the host application keeps ownership
of identity, authority, typed child input, deadlines, and invocation.

Do not use it as a recursive agent graph, dynamic tool catalogue, dialogue
handoff, or WRITE workflow.

## Runtime Boundary

```text
application selects coordinator
  -> coordinator returns validated structured decision
  -> host maps trusted request data to typed child input
  -> SpecialistDelegationGateway validates source and target
  -> existing AIExecutionGateway independently authorizes child
  -> typed child result with parent/child lineage
```

The model can select only a target declared in both:

1. the coordinator output schema, normally as a closed enum; and
2. the coordinator's `spec.delegation.targets` allowlist.

The application, not the model, constructs `TrustedExecutionContext` and the
typed child input.

## Manifest

Declare exact specialist versions:

```yaml
apiVersion: ai.fabric/v1
kind: Specialist
metadata:
  name: account-resolution-coordinator
  version: "1"
spec:
  # normal specialist configuration omitted
  delegation:
    targets:
      - account-resolver-read@1
      - billing-resolution-advisor@1
```

The output schema should independently close the target set:

```yaml
targetSpecialist:
  type:
    - string
    - "null"
  enum:
    - account-resolver-read@1
    - billing-resolution-advisor@1
    - null
```

Startup fails when a target:

- is malformed or duplicated;
- is not registered;
- points back to the source specialist;
- may propose a WRITE; or
- exceeds the maximum of eight declared targets.

Existing manifests and Java definitions remain delegation-disabled unless
they explicitly declare targets.

## Application Invocation

First invoke the coordinator through its typed client:

```java
AIExecutionResult<RoutingDecision> coordinator =
    coordinatorClient.execute(new SpecialistInvocation<>(
        request,
        trustedContext,
        null,
        deadline,
        idempotencyKey
    ));
```

Only a successful, schema-validated result can become a parent. Map the
already validated application request to the selected child's DTO, then call
the gateway:

```java
SpecialistDelegationResult<RoutingDecision, AccountResult> result =
    delegationGateway.delegate(
        new SpecialistDelegationRequest<>(
            coordinator,
            SpecialistId.parse(
                coordinator.output().targetSpecialist()
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

Do not deserialize a model-authored arbitrary child payload and do not copy
identity, tenant, subject, scopes, credentials, or action authority from the
coordinator output.

## Enforcement

`SpecialistDelegationGateway` enforces:

- a successful source result with validated output;
- the current source content hash;
- depth zero before delegation and depth one afterward;
- an exact source-declared target;
- a registered read-only child;
- typed target binding through `SpecialistClientFactory`;
- no child conversation binding;
- the current backend-created trusted context;
- independent target authorization through `AIExecutionGateway`;
- the earlier of the parent and requested deadlines;
- explicit rejection of child input waits and confirmations; and
- scoped, payload-checked, process-local idempotent replay.

Failures remain visible. Provider, policy, schema, deadline, authority,
cancellation, and target execution failures are not converted to success.

## Result And Lineage

`SpecialistDelegationResult<P,O>` returns:

- delegation and parent invocation IDs;
- exact source and target specialist IDs;
- fixed depth `1`;
- validated source output;
- the typed target `AIExecutionResult<O>`;
- safe failure details when delegation cannot proceed;
- replay state; and
- bounded timestamps.

The child diagnostics include safe lineage only:

```text
delegation=true
delegationId
delegationDepth=1
parentInvocationId
sourceSpecialist
```

They do not include raw prompts, trusted context, credentials, private
evidence, or unprojected provider data.

## Idempotency And Durability

The default delegation replay store is process-local and expires with the
configured async result TTL. An identical request under the same trusted
access binding and idempotency key returns the original delegation result.
Changing the parent, target, typed input, deadline, or DTO binding under that
key returns `DELEGATION_IDEMPOTENCY_CONFLICT`.

Delegation state is not durable across restart. Durable delegation requires a
separate product contract; do not infer it from durable specialist jobs,
governed action receipts, or human-review tasks.

## Verification Checklist

Before deployment, prove:

1. strict manifest startup succeeds from the packaged artifact;
2. every declared child is exact-versioned and read-only;
3. each target family succeeds with the real configured provider;
4. an invented target is rejected before child invocation;
5. missing target authority is denied by normal capability resolution;
6. child provider failures remain visible;
7. identical replay does not invoke the child twice;
8. changed work under the same idempotency key conflicts;
9. no conversation is transferred to the child; and
10. restart behavior is documented as process-local.

The executable reference is
[`agentic-ai-action-resolver`](../../../examples/real-apps/agentic-ai-action-resolver).
Its `account-resolution-coordinator@1` may choose only
`account-resolver-read@1` or `billing-resolution-advisor@1`.
