# Bounded Read/Write Workflows

Workflows are an opt-in execution posture for an existing native specialist.
The model chooses one next action, observes its approved result, and chooses
again. Existing chat, read planning and specialist chains are unchanged when
workflow execution is disabled, which is the default.

```yaml
execution:
  strategy: BOUNDED_ITERATIVE
  writePolicy: ACTION_POLICY
  workflow:
    enabled: true
    maxActions: 8
    maxWrites: 3
    maxModelCalls: 16
    maxCorrections: 2
    timeout: PT120S
capabilities:
  actions:
    visible: [read_draft, check_draft, update_draft]
    requestableReads: [read_draft, check_draft]
    proposableWrites: [update_draft]
```

This is a fragment of a normal `Specialist` manifest, not a new deployment mode.
The specialist still declares its input/output schemas, instructions, limits
and conversation contract. Its visible tools are intersected with the caller's
effective capabilities. A manifest cannot create permission to execute a tool.

## Host Integration

The application implements `WorkflowExecutionBindingProvider` and returns a
trusted `WorkflowExecution` for the registered definition and verified request.
Never deserialize that binding from a chat body. It supplies:

- A stable work ID and request, authority and definition fingerprints.
- The narrower of published policy and verified request limits/deadlines.
- A leased `WorkflowCheckpointStore` and current authorization checks.
- Approved observation projection and receiver receipt reconciliation.
- Optional typed, noncommitted validation findings eligible for correction.

`DurableWorkflowCheckpointStore` uses the existing encrypted durable execution
record and lease. The host must use a real durable repository and configured
encryption/fingerprint keys. There is no implicit production in-memory store.

The normal gateway opens the root model-call scope before its preflight and
keeps it through output binding. Planner calls, structured repairs and any
inherited child work use that root allowance. Child scopes cannot replace it.
Model limits are distinct from the application's financial/provider ledger;
hosts must retain their existing provider accounting and admission guards.

## Effects and Confirmation

All action dispatch uses `DefaultGovernedActionInvocationService`. Registered
READ tools remain READ, and draft writers remain genuine WRITE tools. With
`ACTION_POLICY`, a writer whose actual policy does not require confirmation can
execute immediately. Confirmation-required writers pause with one ordinary
pending proposal; they are not silently treated as confirmed.

The host's existing proposal lifecycle authorizes and executes a confirmed
operation. Only then may its trusted authority request
`resumeConfirmedReceipt()`. The runner reconciles the original operation's
receiver receipt before continuing; it does not dispatch the writer again.
Without an executed receipt the work remains paused. The original deadline,
scope, counters and definition still apply. The host must connect its real
confirmation lifecycle; a model or request boolean is not confirmation.

Before dispatch, each step records a stable operation ID and parameter
fingerprint. Receiver mutation and idempotency receipt must commit atomically
in the receiver's transaction. A lost response is an unknown effect until
receipt readback resolves it. A different payload under the same operation ID
is a conflict, not another attempt. Distinct corrective writes use distinct
step IDs, even if they call the same action.

## Stops and Privacy

`NEEDS_INPUT`, unsupported work, actual authorization denial, cancellation,
provider unavailability, funding limits and deadlines stop the loop. Only typed
parameter or candidate-validation findings may use the correction allowance.
No phrase matcher judges the model's message, and there is no canned-answer or
unconfigured extra-generation fallback.

Completed effects survive narration failure and cancellation. Public work
metadata contains actual step identities, effects, counters and stop codes,
not private parameters, credentials or raw observations. Preview blocks writes.
Cancellation is not rollback of a committed receiver transaction.

When canonical conversation control owns history, keep specialist validated-turn
recording disabled and supply its approved snapshot instead of starting a second
history owner. `ConversationMessageProtection` is an optional host SPI for
protecting canonical message bodies at rest; existing products remain unchanged
by default. `INTERRUPT_AI` advances the control epoch without closing the owned
conversation. Application erasure should retain closed ownership tombstones and
independent financial records while removing private content.

## Declared Completion Contracts

A trusted host can optionally implement `WorkflowExecution.Authority.validateCompletion`.
It returns a bounded `CompletionFinding` only when the requested execution outcome
is not established by actual observations or receipts. For example, an explicit
draft-save command cannot be completed from a validation-only receipt while its
checked changes remain unapplied. A no-op or preview does not require a write.

This protects an execution-result contract, not semantic confidence or answer
wording. Do not classify a model's prose, require an arbitrary number of actions,
or invent a replacement action. The finding is supplied to the model within the
same correction, call, action and deadline allowance. The model still selects the
next authorized action or chooses NEEDS_INPUT/UNSUPPORTED. Zero corrections stops
with visible findings; completion checks never dispatch effects automatically.
Existing hosts return no finding by default. Ordinary chat and read-only flows
remain unchanged. Schema repair also receives a bounded decision-contract detail
without provider exception bodies or raw invalid model output.
