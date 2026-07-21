# NotebookLM Single-Source Production Script: Governed Actions And Confirmation

## Generator Instructions - Do Not Narrate

Use this file as the only source for the video. Do not supplement it with general agent, tool-calling,
or Spring AI knowledge. Do not ask for or rely on another source.

Create a structured technical explainer titled **Governed Actions With AI Fabric: From Natural
Language To Confirmed Domain Change**. Follow the fourteen scenes in order. Use every **Visual**
block as production direction and every **Narration** block as the spoken message. Natural
transitions are allowed, but do not omit, replace, or contradict the technical content.

This is the theoretical introduction to CORE-04, not a code-along. Keep AI Fabric's current action
registry, orchestration, confirmation, and result contracts as the subject. Do not invent classes,
annotations, access modes, provider behavior, endpoints, action results, test output, or guarantees.
Apply the final accuracy guardrails to the complete output.

## Production Direction

- Title: **Governed Actions With AI Fabric: From Natural Language To Confirmed Domain Change**
- Target duration: 12-15 minutes.
- Audience: Java and Spring Boot developers who already understand AI Fabric retrieval and RAG.
- Voice: direct, practical, calm, and technically precise. Address the developer as **you**.
- Primary objective: explain how model-selected intent is constrained by a registered action schema,
  application identity, authorization, confirmation state, domain execution, and safe result
  projection.
- Example application: the continuing Spring Boot Support Knowledge Assistant.
- Example request: **Create a support ticket about repeated account lockouts.**
- Visual style: use one persistent action-state diagram, typed schema cards, a trusted-context lane,
  and explicit stopped states. Avoid generic autonomous-agent imagery.

## Scene 1: Natural Language Is A Request, Not A Database Command

**Visual:** Show a user sentence entering a guarded flow. Place the database only after six gates.

```text
"Create a support ticket about repeated account lockouts."
        |
        v
intent -> registered action -> typed parameters -> authorization
       -> confirmation -> application handler -> projected result
                                                |
                                                v
                                          domain database
```

Add a red crossed-out arrow from the LLM directly to the database.

**Narration:**

A governed action lets a user request application work in natural language without granting the
language model direct access to your domain services or database.

The model can identify an action and propose parameter values. That proposal is only the beginning.
AI Fabric matches it against a registered action, validates its schema, combines it with trusted
application context, checks authorization, and creates confirmation state when the action requires
approval. Only then can an application handler perform the domain operation.

The final response is another boundary. The handler returns a structured `ActionResult`; the client
receives a deliberate projection instead of an arbitrary persistence object.

The central rule is simple: model output requests work. Registered code, server policy, and
application services decide whether that work may happen.

## Scene 2: The Registry Defines The Executable Catalog

**Visual:** Show two catalog sources merging into one immutable registry snapshot.

```text
Spring beans annotated @AIAction ----\
                                      -> AIActionRegistry -> name -> handler + metadata
reviewed registry contributors ------/
```

Show startup failures for duplicate names and an action class without exactly one `@ActionExecute`
method.

**Narration:**

AI Fabric does not execute any method whose name resembles model output. It executes only handlers
present in `AIActionRegistry`.

An `@AIAction` class is also a Spring component. At startup, the registry discovers those beans,
finds exactly one `@ActionExecute` method, inspects optional authorization, confirmation-message,
and post-action-facts methods, and builds immutable handler and metadata maps. Reviewed contributors
can add actions from another catalog, such as a connector or database-backed registry.

Action names are normalized for lookup, but collisions are rejected. Two sources cannot silently
replace one another. Invalid method shapes also fail during registry construction.

This registry is the executable allowlist. The intent model sees descriptions and schemas derived
from it. A fabricated action name ends as `ACTION_NOT_FOUND`; it does not become reflection over
arbitrary application code.

## Scene 3: The Annotation Declares Safety Semantics

**Visual:** Display a compact action declaration.

```java
@AIAction(
    name = "create_support_ticket",
    description = "Create a ticket for the current customer.",
    category = "support",
    accessMode = ActionAccessMode.WRITE_ONLY,
    requiresConfirmation = true
)
```

Beside it, show the three current access modes: `READ`, `READ_WRITE`, and `WRITE_ONLY`.

**Narration:**

Every annotation-based action declares a stable name, model-visible description, category, access
mode, and confirmation requirement. These are explicit action-level facts, not conventions inferred
from names such as create, update, or delete.

The current access modes are `READ`, `READ_WRITE`, and `WRITE_ONLY`. A `READ` action is the only mode
treated as read-only and grounding-eligible by default. Planner-driven read-action resolution is
still opt-in through `readActionResolutionEligible`; a read action does not become autonomously
callable merely because it has no side effects.

`READ_WRITE` and `WRITE_ONLY` identify side-effecting work. Confirmation is declared separately and
must be specified for every action. Anonymous execution is denied by default and requires an
explicit action-level opt-in.

These declarations let orchestration apply deterministic safety and presentation rules without
guessing from prose.

## Scene 4: Typed Parameters Become The Model-Visible Contract

**Visual:** Turn a Java method signature into a structured schema card.

```java
@ActionExecute
ActionResult execute(
    @Param(
        value = "subject",
        description = "Short ticket subject",
        required = true
    ) String subject,
    @Param(
        value = "priority",
        allowedValues = {"LOW", "NORMAL", "HIGH"}
    ) String priority,
    ActionContext context
)
```

```text
subject: string, required
priority: string, LOW | NORMAL | HIGH
```

**Narration:**

Parameters annotated with `@Param` form the action schema shown to intent extraction. AI Fabric
records names, descriptions, required flags, Java-oriented types, regular-expression constraints,
allowed values, numeric bounds, arrays, and supported record-shaped objects.

At execution time, `ActionMethodArgumentBinder` converts proposed values into the Java method's
declared types. Missing or invalid values do not become a best-effort domain call. Validation can
return clarification with the public list of values the user must supply.

This is safer than accepting an untyped map inside every handler, but types are not authorization.
A syntactically valid ticket subject can still be forbidden for the current caller. A valid account
identifier can still point to another tenant. Schema validation proves shape; application policy
proves permission.

## Scene 5: Keep Application-Owned Context Out Of `@Param`

**Visual:** Split values into two lanes.

```text
User may provide                         Server already owns
----------------                         -------------------
subject                                  subject/user identity
issue description                        tenant/customer ID
requested priority                       conversation/session ID
refund amount and reason                 active account or cart

@Param                                   ActionContext + domain lookup
```

Show `ActionContext.authContext()` exposing canonical subject, tenant, scopes, issuer, and session
metadata.

**Narration:**

Only values a user should actually state belong in `@Param`.

Identity, tenant, current account, active subscription, cart, and session are usually already known
by the authenticated application. Exposing them as action parameters teaches the model to ask the
user for data the server owns and can allow a caller to propose a value outside their boundary.

Inject `ActionContext`, `OrchestrationContext`, or `PipelineContext` without `@Param`. `ActionContext`
provides the request, conversation, session, and canonical authentication context assembled from
server metadata. The handler then asks an application service for the caller's current domain
record.

The application must populate that metadata from verified authentication, not trust identity or
tenant fields copied from free-form browser input. AI Fabric transports canonical context; your
security integration establishes its truth.

## Scene 6: Intent Selects; Mode And Catalog Policy Constrain

**Visual:** Show structured model output on the left and server constraints on the right.

```text
Model proposal                         Server-owned constraints
--------------                         ------------------------
type = ACTION                   ->      actions enabled in this mode
action = create_support_ticket ->      registered catalog entry
params = {...}                 ->      schema and provenance checks
```

Show stopped branches for actions disabled, anonymous caller denied, and handler missing.

**Narration:**

The intent layer can produce a structured `ACTION` intent with an action name and proposed
parameters. That output does not override the active orchestration mode.

If mutating actions are disabled, AI Fabric returns a controlled result while preserving any
permitted factual or read-only path. If the caller is anonymous and the action did not explicitly
allow anonymous use, execution is denied. If the name is absent from the registry, the result is an
explicit `ACTION_NOT_FOUND` error.

For read planning, the framework applies an additional boundary: the action must be read-only,
eligible for resolution, and allowed by the current policy. Iterative planning must use a narrow
catalog and hard limits on iterations and actions per iteration.

The model chooses among reviewed possibilities. It never expands the set of possibilities.

## Scene 7: Resolve And Validate Before Asking For Confirmation

**Visual:** Show parameter preparation as an ordered sequence.

```text
model parameters
  -> batch-target defaulting, when declared
  -> approved context/read-action resolution
  -> required-parameter validation
  -> executable/trusted-evidence validation
  -> confirmation candidate
```

Show an incomplete action ending at **CLARIFICATION_REQUIRED**, with no pending confirmation and no
handler execution.

**Narration:**

Confirmation should describe a complete, executable proposal. AI Fabric therefore prepares and
validates parameters before it asks the user to approve anything.

The pipeline can apply declared batch targets and resolve reviewed context parameters through
eligible read actions. It checks required fields and tracks parameter provenance. It also validates
whether target values are executable against trusted evidence when the action contract requires
that proof.

Missing user fields create an action draft and a `CLARIFICATION_REQUIRED` result. Missing internal
context is not presented as a form field for the user; the response explains that storefront or
session context is unavailable. Untrusted or placeholder targets remain blocked.

This ordering prevents a misleading confirmation such as, "Approve an update," when the server has
not established which record, tenant, or amount would be changed.

## Scene 8: Application Authorization Runs Before Confirmation

**Visual:** Place `@ActionAllowed` before the pending-action store.

```text
ActionContext -> @ActionAllowed -> allowed? -> build confirmation
                                  |
                                  +-> false/exception -> ACTION_DENIED
```

**Narration:**

An application can add `@ActionAllowed` to the action handler. That method receives framework
context parameters only and returns a boolean. It is evaluated before parameter resolution reaches
confirmation or execution.

If authorization returns false, the result is `ACTION_DENIED`. If the authorization method throws,
the annotated handler fails closed and returns false. The user is not invited to confirm work they
cannot perform.

Use this hook to delegate to your real domain authorization service: check roles, account ownership,
tenant scope, object state, and operation-specific permissions. Do not encode those rules only in
the action description or ask the LLM to judge them.

`@ActionAllowed` is an action-local gate. It complements request-level `EntityAccessPolicy` and
domain-service checks; it does not replace authentication, database constraints, or transactional
authorization inside the application.

## Scene 9: Confirmation Is Backend State, Not A Button Convention

**Visual:** Show a `PendingAction` being stored under a composite boundary.

```text
PendingAction
  action: create_support_ticket
  params: {subject, description, priority}
  description: "Create support ticket ...?"
  createdAt: ...
  trustedEvidenceValuesByKey: ...

stored by: conversationId + ownerId
```

Show the response type `CONFIRMATION_REQUIRED` going to web, mobile, or another client.

**Narration:**

When a complete action requires confirmation and has not been confirmed in the current request, AI
Fabric creates a `PendingAction`. It contains the action name, effective parameters, confirmation
description, timestamp, and any trusted evidence needed to preserve the validated target boundary.

With `ai-fabric-chat-session`, pending actions are stored in backend session metadata under the
conversation and owner. The newest action sits on top of a bounded stack. The response type is
`CONFIRMATION_REQUIRED`, with public parameters and action metadata for the client.

The UI may render Confirm and Reject buttons, but those buttons are presentation. A valid
confirmation is a later orchestration turn resolved against backend pending state. Refreshing the
page, changing clients, or sending the word yes should not require the browser to reconstruct the
action payload as an authoritative command.

## Scene 10: Resolve Yes, No, Expiry, And Repetition Deterministically

**Visual:** Show four branches from the next user turn.

```text
"yes" -> positive confirmation -> pop pending -> mark action confirmed -> execute
"no"  -> negative confirmation -> pop pending -> no mutation
late response -> expired resolver -> clear current pending action
second "yes" -> no pending action -> no second execution
```

Add a clock marked **default pending-confirmation timeout: 5 minutes**.

**Narration:**

Confirmation resolvers run before action execution. A positive confirmation consumes the current
pending action, rebuilds a synthetic action intent from its stored parameters, marks that action as
confirmed for this request, restores trusted evidence, and returns to the normal action path.

A negative confirmation removes the pending action and performs no mutation. The built-in expired
confirmation resolver clears a pending confirmation older than its default five-minute timeout.
Applications can add configured or annotated confirmation interceptors for reviewed flows such as a
retention offer, but those interceptors still operate on the backend stack.

Because positive confirmation pops the pending action before execution, a later duplicate yes does
not find the same proposal. That protects the normal conversational path from duplicate approval.
Your domain operation must still use transactions and idempotency where HTTP retries, messaging, or
external systems can repeat delivery.

## Scene 11: The Handler Owns The Domain Change

**Visual:** Show the boundary between framework orchestration and application code.

```text
AI Fabric                          Application
---------                          -----------
bind typed arguments       ->      re-check domain permission
inject ActionContext       ->      load current customer
invoke @ActionExecute      ->      transaction + persistence/API call
receive ActionResult       <-      business outcome + policy reason
```

**Narration:**

After every gate passes, AI Fabric invokes the registered `@ActionExecute` method with converted
parameters and `ActionContext`.

The application handler owns the actual transaction. It loads the current customer from trusted
identity, re-checks any state-sensitive authorization, applies business thresholds, writes through
the repository or external API, and returns the domain outcome. AI Fabric does not become your
ticket repository, billing system, or policy engine.

The execute method must return `ActionResult` or an `ActionPayload`. Returning arbitrary domain
objects is rejected. If the handler throws, the framework uses the handler error contract and
returns an explicit error result. It does not pretend the action succeeded or substitute a
deterministic success message for a failed provider or domain call.

## Scene 12: Project Trusted Results For Humans And Follow-Ups

**Visual:** Compare a raw persistence graph with a compact action result.

```text
Do not present                         Prefer
--------------                         ------
customer object graph                  message: "Ticket created"
internal authorization arrays          ticketId: T-1042
repository/JPA state                    status: OPEN
secrets and provider payloads           policyDecision: ALLOWED
                                        policyExplanation: ...
```

Show `ActionResult.message`, `data`, `pinnedTargets`, and `errorCode` as distinct fields.

**Narration:**

An `ActionResult` separates success, a concise message, structured `ActionPayload` data, explicit
pinned targets, and an error code.

Use `ActionResultContracts.object` for deliberate fields and the reserved list contract for
list-shaped results. If an action should support a follow-up such as "open it" or "cancel that
ticket," return explicit `ActionTargetRef` values. Do not rely on the framework guessing domain keys.

Keep business explanations in the backend result. If a refund is approved below a threshold or sent
to review above it, return the decision and reason from the domain service. The frontend should
format those facts, not invent the policy.

Optional `@ActionFacts` can expose a reviewed, bounded fact map for post-action LLM wording. It is
not permission to send the entire result graph back to the model. The API can preserve useful
structure while the primary UI card shows only safe, user-facing fields.

## Scene 13: Every Stopped State Is Part Of The Contract

**Visual:** Present a failure matrix.

| State | Expected result | Domain mutation |
| --- | --- | --- |
| Unknown action | `ACTION_NOT_FOUND` error | None |
| Actions disabled by mode | Controlled clarification | None |
| Anonymous caller not allowed | `ACTION_DENIED` | None |
| `@ActionAllowed` denies or fails | `ACTION_DENIED` | None |
| Required parameter missing | `CLARIFICATION_REQUIRED` | None |
| Target lacks trusted evidence | `CLARIFICATION_REQUIRED` | None |
| Awaiting approval | `CONFIRMATION_REQUIRED` | None |
| Rejected or expired | Informational result | None |
| Handler fails | Explicit error | Transaction-dependent rollback |
| Handler succeeds | `ACTION_EXECUTED` | Application-defined change |

**Narration:**

Governance is visible in stopped states, not only in a successful demo.

Test every row in this matrix. A missing parameter must not create a partial record. Authorization
denial must happen before confirmation. Rejection and expiry must leave the domain unchanged. A
handler exception must remain an error. A confirmed action should execute through the same handler
and authorization path as the original proposal.

Also inspect public metadata. Internal context-owned fields and sensitive targets must not reappear
as editable user parameters. If post-action generation is enabled, test both the trusted facts sent
to the model and the behavior when generation fails.

An action workflow is production-oriented when its denied, incomplete, rejected, expired, failed,
and duplicate paths are as deliberate as its green path.

## Scene 14: The Ownership Map And Completion Proof

**Visual:** End with an ownership table followed by a short proof checklist.

| Concern | Owner |
| --- | --- |
| Natural-language intent proposal | LLM through AI Fabric structured intent |
| Executable catalog and parameter schema | AI Fabric registry plus reviewed application declarations |
| Identity and tenant truth | Application authentication integration |
| Mode limits and confirmation state | AI Fabric orchestration and chat-session module |
| Authorization and business policy | Application policy and domain services |
| Transaction, persistence, and idempotency | Application handler |
| Safe result fields and policy explanation | Application result contract |
| Final sanitization and orchestration envelope | AI Fabric |

```text
Done when:
[ ] read and write actions register with the expected metadata
[ ] model-visible parameters exclude application-owned identity
[ ] missing or untrusted parameters clarify without mutation
[ ] authorization denial occurs before confirmation
[ ] write cannot execute before confirmation
[ ] confirm executes the intended handler; reject and duplicate confirm do not
[ ] result projection contains safe facts, not raw persistence objects
```

**Narration:**

AI Fabric governs the transition from natural language to a reviewed application handler. It does
not absorb the application's identity system, authorization rules, transactions, or business
policy.

You have completed the governed-action foundation when action registration is deterministic,
parameters are typed, server-owned context stays out of the model schema, authorization runs before
approval, pending confirmation belongs to the backend conversation, and the handler returns a safe
business result.

The next lesson adds backend-owned conversation memory. That memory lets a user say "escalate it"
and then "yes" without asking the browser to replay authoritative history or rebuild pending action
state.

## Accuracy Guardrails - Do Not Narrate

1. Do not say the LLM invokes Java methods, repositories, databases, or external APIs directly.
2. Do not invent an action access mode. The current enum is `READ`, `READ_WRITE`, and `WRITE_ONLY`.
3. Do not imply that `READ` automatically enables planner execution. `readActionResolutionEligible`
   and orchestration policy still constrain it.
4. Do not present typed parameter validation as authorization.
5. Do not advise placing user identity, tenant, current account, subscription, or session identifiers
   in `@Param` when the application already owns those values.
6. Do not say `ActionContext` authenticates the caller. It carries context that the application must
   populate from verified authentication.
7. Do not move authorization after confirmation. `@ActionAllowed` is evaluated before pending state
   is created, and domain services should re-check state-sensitive rules during execution.
8. Do not describe a Confirm button as the confirmation mechanism. Backend pending-action state is
   authoritative.
9. Do not claim global exactly-once delivery. A consumed pending action prevents normal duplicate
   confirmation, while the application remains responsible for transactional idempotency.
10. Do not claim every action requires confirmation. The requirement is explicit per action.
11. Do not return arbitrary persistence entities from `@ActionExecute`; the current contract accepts
    `ActionResult` or `ActionPayload`.
12. Do not hide action, authorization, provider, or domain failures behind a fabricated success or a
    deterministic fallback.
13. Do not claim post-action LLM generation is required. It is optional and must use bounded trusted
    facts.
14. Do not claim a UI rendering proves governance. Back registration, denial, confirmation,
    execution, and result-projection claims with backend tests.
