# ADR 0021 - Policy-Authorized Durable Specialist Writes

- **Status:** Proposed framework change plan
- **Date:** 2026-09-15
- **Framework baseline:** AI Fabric `0.6.1`
- **Target release:** Next reviewed feature release after `0.6.1`; not a patch release
- **Primary module:** `ai-fabric-execution`
- **Supporting modules:** `ai-fabric-core`, `ai-fabric-actions-connector`, real-app and integration-test modules
- **Primary adoption:** Trusted `APPLICATION`, `EVENT`, or `SCHEDULED` specialist execution
- **Out of scope:** Unrestricted autonomous writes, write-capable chains, dynamic policies, exactly-once external side effects, and compatibility shims

Related current contracts:

- [Bounded Agentic Enablement](../Framework-Dev-Guides/application-patterns/AGENTIC_APP_GUIDE.md)
- [Durable Read-Only Specialist Jobs](../Framework-Dev-Guides/application-patterns/DURABLE_READ_ONLY_SPECIALIST_JOBS.md)
- [Governed Specialist Writes And Durable Receipts](../Framework-Dev-Guides/actions-governance/GOVERNED_SPECIALIST_WRITES_AND_RECEIPTS.md)
- [Durable Human Review](../Framework-Dev-Guides/application-patterns/DURABLE_HUMAN_REVIEW.md)
- [AI Fabric 0.6.1 Release Notes](../release-notes/0.6.1.md)

## 1. Decision Summary

Add an opt-in framework contract that allows one exact, machine-triggered specialist to propose one exact registered write and have that proposal authorized by a deterministic application-owned service policy instead of a user confirmation.

The target flow is:

```text
trusted application event or schedule
  -> exact specialist selected by application code
  -> specialist analyzes approved evidence
  -> specialist emits one typed write proposal
  -> framework validates the proposal and effective capabilities
  -> exact application policy evaluates it
  -> durable authorization receipt is committed
  -> compare-and-set transition claims execution
  -> registered application action executes with stable idempotency context
  -> safe outcome is persisted
  -> exact replay returns the stored result
  -> ambiguous execution becomes OUTCOME_UNKNOWN and requires reconciliation
```

The model still does not authorize or directly execute the write. The application policy is a separate trusted component with an exact identity, version, content hash, action binding, source binding, and deterministic decision contract.

The first release supports this path only for:

- `ExecutionSource.APPLICATION`, `EVENT`, or `SCHEDULED`;
- `SERVICE` or `SYSTEM` principals;
- one direct exact-version specialist;
- one registered non-read action;
- one exact registered policy binding;
- durable JDBC receipt storage;
- application-owned idempotency and reconciliation; and
- actions explicitly marked by application registration as policy-authorizable.

It does not enable writes inside bounded multi-specialist chains, fixed plans, delegation, handoff, conversation managers, interactive input waits, or arbitrary orchestration requests.

## 2. Problem Statement

AI Fabric `0.6.1` intentionally separates three existing paths:

1. ordinary actions may execute without confirmation when their registered action contract says confirmation is not required;
2. specialist writes may only become confirmation-backed durable proposals; and
3. machine-triggered durable specialist jobs must be read-only.

Those boundaries are safe, but they leave a legitimate application use case unsupported:

> A trusted application event asks one exact specialist to analyze approved facts. The specialist may recommend one narrow, low-risk, idempotent write. The application has an explicit deterministic policy that can authorize that exact write without asking a person.

Examples include:

- creating a bounded internal investigation case;
- setting a low-risk derived workflow flag;
- attaching a validated AI classification to an application-owned review record;
- recording a deduplicated operational recommendation for downstream processing; or
- opening a manual-retention work item without contacting a customer or changing money, permissions, ownership, or irreversible state.

Today an application can write custom code around AI Fabric to perform such a mutation, but the framework does not provide the durable identity, authorization, replay, crash ambiguity, safe outcome, and reconciliation contract needed to call that a supported specialist capability.

Do not solve this by:

- automatically confirming a confirmation-required receipt;
- invoking a non-confirmable action directly from specialist orchestration;
- treating `requiresConfirmation=false` as automatic machine authority;
- retrying a write after a worker lease expires;
- hiding the write in a result callback or output projector;
- adding a product-specific exception or action-name check;
- accepting policy, action, target, scopes, or authority from event/model input; or
- copying the receipt implementation into a private product runtime.

## 3. Current Framework Boundary

### 3.1 Ordinary action execution

The ordinary orchestration path can invoke a registered non-read action when:

- the action is visible and executable in the effective capability profile;
- required parameters and application validation pass; and
- the action does not require confirmation, or confirmation has already been established.

This is a synchronous governed action capability. It is not a durable machine-triggered specialist write guarantee.

### 3.2 Specialist write proposals

Current specialist write support requires:

- registered non-read action;
- effective specialist write capability;
- action metadata requiring confirmation;
- `SpecialistWritePolicy.CONFIRMATION_RECEIPT_REQUIRED`;
- durable action receipt storage;
- application confirmation or authorized human review; and
- application-owned idempotency and reconciliation.

The model emits an internal `ActionProposalCandidate`. The framework persists a protected receipt and returns a safe confirmation view. The model cannot confirm it.

### 3.3 Durable machine execution

Current durable specialist jobs:

- accept only machine sources and service/system principals;
- persist before dispatch;
- use leases and restart recovery;
- provide exact replay and changed-payload conflicts; and
- reject every write-enabled specialist.

The read-only limit exists because provider work may be repeated after a crash. Repeating a read is acceptable; blindly repeating a write is not.

### 3.4 Bounded chains

AI Fabric `0.6.1` bounded chain workers are exact-version, read-only, non-interactive leaves. The new capability does not change that rule.

## 4. Core Safety Decision

Every model-selected write remains a proposal.

Authorization is supplied by exactly one trusted application mechanism:

```text
USER_CONFIRMATION
HUMAN_REVIEW
SERVICE_POLICY
```

The framework must represent the authorization mechanism explicitly in durable state. It must not reinterpret one mechanism as another. In particular, a service policy must not call the existing confirmation API or create a false `confirmedByUser` audit record.

The clean target is an authorization-neutral durable action receipt shared by all three mechanisms.

## 5. Proposed Public Contract

Names below are proposed API names. Implementation may adjust package-local names during review, but the semantics are required.

### 5.1 Specialist write policy

Evolve `SpecialistWritePolicy` to:

```java
public enum SpecialistWritePolicy {
    DISABLED,
    USER_CONFIRMATION_RECEIPT_REQUIRED,
    SERVICE_POLICY_RECEIPT_REQUIRED
}
```

Human Review remains a lifecycle around a user-confirmable proposal in the first implementation. A later cleanup may expose `HUMAN_REVIEW_RECEIPT_REQUIRED` directly if it removes real complexity.

Migration rule:

- replace `CONFIRMATION_RECEIPT_REQUIRED` with `USER_CONFIRMATION_RECEIPT_REQUIRED`;
- update framework source, manifests, tests, guides, and real apps in one release;
- do not keep a deprecated enum alias or dual manifest reader.

### 5.2 Authorization mode

Add:

```java
public enum ActionAuthorizationMode {
    USER_CONFIRMATION,
    HUMAN_REVIEW,
    SERVICE_POLICY
}
```

This mode describes who or what authorized execution. It is not supplied by model output or a public request.

### 5.3 Policy identity

Add an exact immutable identity:

```java
public record ActionAuthorizationPolicyId(
    String name,
    String version
) {}
```

Each registered policy also exposes a content hash that changes whenever its executable authorization semantics, referenced configuration, or declared input contract changes.

Do not infer the hash only from the Java class name. The host application must register an exact policy definition with a canonical versioned configuration and hash.

### 5.4 Policy definition and registry

Proposed shape:

```java
public interface ActionAuthorizationPolicy {

    ActionAuthorizationPolicyId id();

    String contentHash();

    PolicyAuthorizationDecision evaluate(
        PolicyAuthorizationRequest request
    );
}
```

```java
public record PolicyAuthorizationRequest(
    String invocationId,
    SpecialistId specialistId,
    String specialistContentHash,
    TrustedExecutionContext trustedContext,
    String effectiveProfileHash,
    String actionName,
    Map<String, Object> validatedParameters,
    List<AIEvidenceReference> trustedEvidence,
    String requestFingerprint
) {}
```

```java
public record PolicyAuthorizationDecision(
    PolicyAuthorizationOutcome outcome,
    String reasonCode,
    String publicMessage,
    String policyStateRevision,
    Map<String, Object> safeAuditFacts
) {}
```

```java
public enum PolicyAuthorizationOutcome {
    AUTHORIZED,
    DENIED
}
```

Requirements:

- policy evaluation is synchronous, bounded, deterministic for the supplied trusted state revision, and side-effect free;
- policies cannot invoke an LLM to decide authority;
- policies cannot modify action parameters;
- policies cannot select another action or specialist;
- policies return only bounded JSON-safe audit facts;
- secret values, raw domain objects, database entities, and PII are prohibited from public/audit projections;
- duplicate policy IDs fail startup;
- duplicate action/specialist/source bindings fail startup;
- missing or changed policy definitions fail closed.

### 5.5 Exact policy binding

Register application-owned bindings:

```java
public record PolicyAuthorizedActionBinding(
    SpecialistId specialistId,
    String actionName,
    ActionAuthorizationPolicyId policyId,
    Set<ExecutionSource> allowedSources
) {}
```

Validation requires:

- exact registered specialist;
- exact registered non-read action;
- exact registered policy ID and content hash;
- specialist write policy `SERVICE_POLICY_RECEIPT_REQUIRED`;
- action present in `proposableWriteActions`;
- source limited to `APPLICATION`, `EVENT`, or `SCHEDULED`;
- action metadata not requiring interactive confirmation;
- mandatory safe outcome projector;
- mandatory durable JDBC receipt repository; and
- mandatory idempotency/reconciliation support.

An action with `requiresConfirmation=false` is not automatically policy-authorizable. The exact binding is the application-level opt-in.

### 5.6 Receipt generalization

Replace the confirmation-specific durable model with one authorization-neutral model. Proposed names:

- `ActionExecutionReceipt`;
- `ActionExecutionReceiptStatus`;
- `ActionExecutionCoordinator`;
- `ActionExecutionReceiptRepository`;
- `ActionExecutionReceiptView`.

The existing confirmation and Human Review APIs migrate to the generalized coordinator in the same release. Do not maintain two receipt engines.

Required receipt fields:

- receipt ID and originating specialist invocation ID;
- specialist ID and effective content hash;
- effective capability profile hash;
- principal, subject, tenant, deployment, and source fingerprints;
- action name;
- protected parameters and canonical parameter hash;
- parameter schema hash;
- trusted evidence hashes;
- action authorization mode;
- policy ID, version, content hash, state revision, and decision hash when mode is `SERVICE_POLICY`;
- confirmer/reviewer authorization evidence when those modes apply;
- idempotency fingerprint;
- action idempotency material protected for trusted handler/connector use;
- status and optimistic version;
- created, authorized, execution-started, executed, terminal, expiry, and updated timestamps;
- protected safe outcome;
- bounded failure reason.

### 5.7 Receipt state machine

Use one semantic state machine:

```text
PROPOSED -> AUTHORIZED -> EXECUTING -> SUCCEEDED
                                    -> FAILED
                                    -> OUTCOME_UNKNOWN

PROPOSED -> REJECTED
PROPOSED -> POLICY_DENIED
PROPOSED | AUTHORIZED -> EXPIRED
OUTCOME_UNKNOWN -> SUCCEEDED | FAILED through reconciliation
```

Authorization transitions:

- user confirmation: `PROPOSED -> AUTHORIZED` with mode `USER_CONFIRMATION`;
- approved human review: `PROPOSED -> AUTHORIZED` with mode `HUMAN_REVIEW`;
- service policy approval: receipt is persisted with the exact policy decision and transitions to `AUTHORIZED` before execution;
- service policy denial: terminal `POLICY_DENIED`, with no action invocation.

Replace `CONFIRMED` with `AUTHORIZED` and `confirmedAt` with `authorizedAt`. Use a one-way schema and source migration. Do not keep parallel states or compatibility readers.

## 6. Specialist Orchestration Changes

### 6.1 Candidate creation

For every specialist non-read action:

1. keep the normal action invisible unless it survives capability intersection;
2. validate required parameters, provenance, application-owned target resolution, and executable constraints;
3. never invoke the action directly from `IntentHandlingStep`;
4. emit one internal `ActionProposalCandidate`;
5. route that candidate according to the specialist's registered write policy;
6. fail if the action metadata and policy binding do not match.

Required outcomes:

| Specialist policy | Action contract | Outcome |
| --- | --- | --- |
| `DISABLED` | Any non-read action | Denied |
| `USER_CONFIRMATION_RECEIPT_REQUIRED` | Confirmation-required exact action | Persist proposal and return confirmation required |
| `SERVICE_POLICY_RECEIPT_REQUIRED` | Non-confirmable exact action with exact service-policy binding | Evaluate policy and use durable execution receipt |
| `SERVICE_POLICY_RECEIPT_REQUIRED` | Missing/changed policy or unbound action | Denied before execution |
| Any | Read action declared as write | Startup failure |

### 6.2 One-write limit

One specialist execution may emit at most one write proposal. Multiple candidates, nested candidates, compound-result smuggling, or write candidates from a failed orchestration result fail validation.

### 6.3 Structured output

The write proposal remains an internal orchestration artifact. Public structured output must not be parsed for an action name or treated as authorization. Output-shape correction may correct structure; it cannot change policy, action eligibility, identity, authority, or receipt state.

## 7. Durable Machine Execution Integration

### 7.1 Submission eligibility

Update durable submission policy to permit a write-enabled specialist only when all conditions hold:

- source is `APPLICATION`, `EVENT`, or `SCHEDULED`;
- principal is `SERVICE` or `SYSTEM`;
- application-owned subject is present;
- no conversation binding or input continuation exists;
- write policy is exactly `SERVICE_POLICY_RECEIPT_REQUIRED`;
- every proposable action has an exact policy binding for the source;
- JDBC durable specialist and receipt repositories are configured;
- stable encryption and fingerprint secrets are configured;
- a bounded idempotency key is present;
- application-owned action idempotency and reconciliation contracts are registered.

`USER_CONFIRMATION_RECEIPT_REQUIRED` remains ineligible for durable machine jobs because those jobs cannot wait for a user decision.

### 7.2 Execution ordering

Required ordering:

```text
durable specialist request committed
  -> worker lease claimed
  -> specialist/provider work
  -> candidate validation
  -> exact service policy evaluated
  -> authorization receipt committed
  -> AUTHORIZED -> EXECUTING compare-and-set succeeds
  -> application action invoked
  -> safe outcome committed to receipt
  -> typed specialist result committed
```

No action invocation may occur before the durable authorization receipt is committed.

### 7.3 Crash windows

| Failure point | Required behavior |
| --- | --- |
| Before receipt persistence | No action ran; durable specialist retry may safely rerun analysis |
| Receipt persisted as `AUTHORIZED`, before `EXECUTING` claim | Recovery may claim and execute once |
| After transition to `EXECUTING`, before or during handler call | Mark `OUTCOME_UNKNOWN`; never blindly invoke again |
| After handler returns, before outcome persistence | `OUTCOME_UNKNOWN`; reconcile against system of record |
| After terminal receipt persistence, before specialist result persistence | Durable specialist replay returns the stored receipt outcome without re-invoking the action |
| After both terminal records persist | Exact replay returns the original typed result and receipt reference |

The framework does not claim exactly-once external execution. The contract is durable authorization plus at-most-one framework invocation attempt after the receipt reaches `EXECUTING`, with explicit ambiguity and reconciliation.

### 7.4 Definition and policy drift

Before execution, revalidate:

- specialist ID and content hash;
- effective capability profile hash;
- action registration and access mode;
- parameter schema hash and protected parameter integrity;
- trusted identity and current authority;
- policy ID, version, content hash, and binding;
- source eligibility;
- application-owned target validity;
- current policy decision under the same policy state revision contract.

If executable semantics changed, fail before action invocation. Do not rewrite receipt hashes or silently adopt a newer policy.

## 8. Stable Action Idempotency

### 8.1 Framework requirement

Current connector execution can generate an idempotency key for a write. Policy-authorized durable writes instead need one stable action idempotency value derived from the protected receipt and supplied consistently on every observation or reconciliation attempt.

Add trusted action invocation context containing:

- receipt ID;
- protected/stable action idempotency value;
- authorization mode;
- policy identity where applicable;
- originating specialist invocation ID.

This context is backend-owned. It cannot be supplied in model parameters or a public event body.

### 8.2 Connector behavior

The connector module must:

- prefer the trusted stable action idempotency value for non-read action calls;
- forward it through the existing connector/MCP protocol where supported;
- never replace it with a random value;
- fail closed when a policy-authorized connector write cannot carry the required idempotency contract;
- preserve current read retry behavior;
- never retry `OUTCOME_UNKNOWN` as though no write occurred.

### 8.3 Local action behavior

Local action handlers must be able to read the trusted idempotency value from `ActionContext` or a new bounded action execution context. The value must not appear in normal public result payloads or logs.

The host application remains responsible for deduplicating the business mutation and reconciling authoritative state.

## 9. Policy And Authority Rules

### 9.1 Application-owned policy

The policy may inspect:

- trusted execution source and service/system principal;
- exact specialist identity and hash;
- exact action name;
- validated parameters and provenance;
- backend-owned subject, tenant, and deployment;
- current effective capability hash;
- approved evidence references;
- application-owned domain state and a stable revision;
- trigger type from trusted adapter metadata.

The policy may not trust:

- caller-supplied tenant, deployment, principal, scopes, specialist, or action;
- model-authored policy IDs or authorization modes;
- arbitrary URLs, credentials, provider settings, or output destinations;
- hidden/system-owned target IDs sourced from model parameters;
- natural-language matching as an authority check;
- generated claims that are not grounded in supplied trusted facts.

### 9.2 Safe first-use classification

The framework must not contain domain-specific action risk names. The application decides which exact actions are eligible.

Framework documentation should recommend first-use actions that are:

- reversible or naturally reconcilable;
- idempotent;
- narrow in scope;
- bounded in financial, security, privacy, and user impact;
- observable in the system of record;
- safe to leave as `OUTCOME_UNKNOWN` pending reconciliation.

Do not recommend the first release for:

- money movement, refunds, credits, purchasing, or billing changes;
- deletion or irreversible state;
- permission, role, credential, or security changes;
- legal/compliance decisions;
- customer communication or notification without separate communication policy;
- ownership transfer;
- broad bulk mutation;
- arbitrary external tools.

These are product/application policy boundaries, not action-name matches in framework code.

## 10. Chain, Plan, Delegation, And Conversation Boundaries

The first release keeps all composed specialist paths read-only:

- bounded chain manager and workers;
- fixed sequential plans;
- parallel plans;
- delegation targets;
- handoff targets;
- conversation-manager workers.

Rationale:

- a chain can invoke multiple workers and complicate which result owns the write;
- parallel branches create ambiguous ordering and partial failure;
- delegation and handoff complicate policy and receipt identity;
- conversation state is incompatible with unattended durable authorization;
- the direct exact-specialist path is enough to prove the generic primitive safely.

A later proposal may add one terminal policy-authorized write after a read-only chain has completed, but only as a separate application-selected action phase with one exact synthesized input contract. Do not include it in this change.

## 11. Persistence Migration

Generalize the existing receipt schema through one application-owned Flyway/Liquibase migration.

Target table name may remain `ai_action_proposal_receipt` because every AI-selected write begins as a proposal. Do not create a second policy receipt table.

Required schema changes include:

- replace confirmation-only status semantics with generic authorization status;
- replace `confirmed_at` with `authorized_at`;
- allow confirmation message to be absent for service-policy mode;
- add authorization mode;
- add execution source fingerprint;
- add policy name, version, content hash, state revision, and decision hash;
- add protected stable action idempotency material or a reference to it;
- retain specialist, capability, identity, parameter, schema, evidence, outcome, failure, expiry, and optimistic-version fields;
- add recovery and lookup indexes for active authorization/execution states.

Migration rules:

- existing `CONFIRMED` rows map to `AUTHORIZED` with mode `USER_CONFIRMATION`;
- existing proposal and terminal states retain their semantic outcome;
- active receipts must be drained before an application deploys the new schema when safe migration cannot prove exact state mapping;
- no dual table reader or old/new status fallback;
- tests must prove migrated terminal receipts remain byte-stable in public outcome projection.

Framework production schema initialization remains disabled by default. Applications own migrations.

## 12. Configuration

Proposed opt-in configuration:

```yaml
ai:
  execution:
    enabled: true
    async:
      repository: JDBC
      initialize-schema: false
    receipts:
      enabled: true
      repository: JDBC
      initialize-schema: false
      policy-authorized-writes-enabled: true
      encryption-secret: ${AI_EXECUTION_RECEIPT_ENCRYPTION_SECRET}
      fingerprint-secret: ${AI_EXECUTION_RECEIPT_FINGERPRINT_SECRET}
```

Startup must fail when policy-authorized writes are enabled and any of these is missing:

- JDBC specialist execution repository;
- JDBC action receipt repository;
- datasource;
- stable distinct receipt encryption/fingerprint secrets;
- exact policy registry and bindings;
- safe outcome projector;
- required action idempotency support;
- required reconciliation adapter.

`IN_MEMORY` policy-authorized writes are allowed only in explicit deterministic tests. They are rejected in production profiles without an explicit test-only override, and no production guide should recommend that override.

## 13. Result And Public API Semantics

### 13.1 Authorized and completed

A successful machine-triggered result returns a safe specialist output plus a bounded receipt reference:

```json
{
  "status": "COMPLETED",
  "result": {
    "classification": "REVIEW_RECOMMENDED",
    "summary": "An internal retention review case was created."
  },
  "actionExecution": {
    "receiptId": "action-receipt-...",
    "actionName": "create_retention_review_case",
    "authorizationMode": "SERVICE_POLICY",
    "status": "SUCCEEDED"
  }
}
```

Do not expose raw parameters, policy internals, protected fingerprints, hidden target IDs, provider output, or application entities.

### 13.2 Policy denied

Return a safe terminal denial with:

- stable denial code;
- safe public message;
- receipt/reference when audit policy permits;
- no action outcome and no mutation.

The model must not retry with another action or modify parameters to evade the policy.

### 13.3 Outcome unknown

Return:

- `OUTCOME_UNKNOWN`;
- receipt ID;
- non-retryable flag;
- safe statement that reconciliation is required;
- no fabricated success or failure.

The durable specialist job must also end in a visible terminal state referencing the unknown receipt. Recovery must not call the action again.

### 13.4 Replay

- identical replay returns the same specialist invocation and action receipt outcome;
- changed event facts, parameters, policy binding, subject, tenant, deployment, source, or action conflict;
- another access binding cannot inspect, cancel, reconcile, or replay the receipt;
- terminal outcome projection remains stable across restart.

## 14. Metrics And Diagnostics

Add bounded metrics without tenant, subject, receipt, or parameter labels:

- policy authorization evaluated, authorized, and denied;
- receipt persisted, replayed, expired, and store failure;
- action execution started, succeeded, failed, and outcome unknown;
- reconciliation succeeded and failed;
- policy definition drift;
- action schema drift;
- cross-boundary denial;
- stable-idempotency transport unsupported.

Safe diagnostics may expose:

- feature enabled/disabled;
- repository type;
- registered policy IDs and versions;
- binding counts;
- active/terminal counts;
- oldest active age;
- outcome-unknown count;
- recovery and cleanup posture.

Do not expose policy code, secret material, raw decision input, parameters, protected payloads, or application state.

## 15. Module-Level Implementation Plan

### Phase A: Contract and state generalization

Status: `NOT_STARTED`

Modules:

- `ai-fabric-execution`;
- current action receipt tests and real-app consumers.

Tasks:

- add authorization mode and policy identity contracts;
- generalize receipt, status, coordinator, repository, security, recovery, metrics, and public projections;
- migrate user confirmation and Human Review onto the generalized receipt;
- rename confirmation-specific fields and APIs where they no longer represent truth;
- provide one-way SQL reference migration;
- remove replaced confirmation-only types rather than leave wrappers.

Exit:

- existing confirmation and review behavior passes through one authorization-neutral receipt engine;
- no service-policy execution exists yet;
- source and persistence semantics contain no false confirmation claims.

### Phase B: Policy registry and binding validation

Status: `NOT_STARTED`

Modules:

- `ai-fabric-execution` auto-configuration, manifests, registry, validation.

Tasks:

- implement policy definitions, exact IDs, hashes, registry, decisions, and bindings;
- add duplicate/missing/drift fail-fast validation;
- add `SERVICE_POLICY_RECEIPT_REQUIRED` manifest/compiler support;
- update specialist definition validation by write-policy mode;
- require exact action, source, specialist, projector, idempotency, and reconciliation support;
- add safe policy audit projection and metrics.

Exit:

- applications can register exact policy-authorized action bindings;
- invalid combinations fail startup before provider or action execution.

### Phase C: Specialist proposal routing

Status: `NOT_STARTED`

Modules:

- `ai-fabric-core` specialist orchestration path;
- `ai-fabric-execution` gateway and action coordinator.

Tasks:

- preserve direct non-confirmable ordinary action behavior outside specialist purpose;
- prevent every specialist non-read action from direct execution;
- emit one internal proposal candidate for either supported write policy;
- route confirmation-backed proposals to user/review lifecycle;
- route service-policy proposals to policy evaluation and durable authorization;
- reject compound, failed, nested, or multiple candidates;
- return stable safe status and receipt projections.

Exit:

- model-selected specialist writes always cross the durable authorization boundary;
- no confirmation bypass or action-name special case exists.

### Phase D: Durable machine-job integration

Status: `NOT_STARTED`

Modules:

- `ai-fabric-execution` durable job gateway, runner, repositories, recovery.

Tasks:

- permit only exact service-policy write specialists through durable eligibility;
- require stable job and action idempotency;
- persist authorization before action invocation;
- link job and receipt terminal state;
- make receipt replay authoritative after action execution begins;
- terminalize crash ambiguity as `OUTCOME_UNKNOWN`;
- integrate reconciliation and retention;
- keep confirmation-backed and interactive specialists ineligible.

Exit:

- one durable machine-triggered specialist can perform one policy-authorized write without blind retry;
- restart and exact replay return the same receipt and safe outcome.

### Phase E: Action idempotency and connector propagation

Status: `NOT_STARTED`

Modules:

- `ai-fabric-core` action context/invocation;
- `ai-fabric-actions-connector` and MCP execution path;
- local-action test fixtures.

Tasks:

- add trusted stable idempotency context;
- expose it safely to local handlers;
- forward it to connector/MCP actions;
- fail closed when required transport support is absent;
- verify retries do not generate a different idempotency value;
- ensure logs and public payloads do not expose protected material.

Exit:

- application/connector actions can deduplicate the exact receipt-backed mutation;
- framework recovery never substitutes a random action idempotency key.

### Phase F: Real-app proof

Status: `NOT_STARTED`

Use an existing real app rather than create another overlapping demo. Preferred proof: `behavior-churn-signals`.

Scenario:

```text
trusted account-behavior event
  -> exact churn-analysis specialist
  -> approved behavior/account evidence
  -> proposal: create_retention_review_case
  -> exact service policy checks source, tenant, account, risk threshold,
     evidence, no existing case, and bounded action parameters
  -> durable receipt
  -> idempotent application action
  -> safe case reference and specialist result
```

The action must not:

- send an offer or customer communication;
- issue credit or refund;
- change subscription or payment state;
- alter permissions;
- accept account/tenant authority from the event or model.

Proof requirements:

- deterministic no-key suite;
- keyed real-provider suite with no fallback;
- PostgreSQL packaged restart test;
- exact replay after restart;
- changed-event conflict;
- policy denial with zero write delta;
- policy-definition and action-schema drift denial;
- concurrent duplicate submission with one business mutation;
- crash/unknown-outcome simulation and reconciliation;
- cross-tenant/deployment denial;
- public result contains no raw provider, policy, receipt payload, or protected IDs.

### Phase G: Documentation and release

Status: `NOT_STARTED`

Tasks:

- add `POLICY_AUTHORIZED_SPECIALIST_WRITES.md`;
- update Agentic App, durable job, governed write, Human Review, manifest, and action-connector guides;
- update capability map and real-app capability guide;
- document the one-way source/schema migration;
- add release notes with exact boundaries and unsupported claims;
- update standalone external consumer proof;
- run full framework, real-app, release-profile, empty-cache, Docker/PostgreSQL, and keyed-provider gates;
- publish immutable artifacts only after all blocking evidence passes.

Exit:

- public artifacts, source tag, release notes, guides, real-app proof, and external consumer agree on the same contract;
- no application needs private framework source or a compatibility shim.

## 16. Required Test Matrix

### 16.1 Contract and startup

- exact policy identity/version/hash registration;
- duplicate IDs and bindings rejected;
- missing action, specialist, policy, projector, datasource, secrets, idempotency, or reconciliation rejected;
- action must be non-read;
- action confirmation posture must match selected specialist write policy;
- unsupported source rejected;
- chain/plan/delegation/handoff use rejected;
- production `IN_MEMORY` configuration rejected.

### 16.2 Authority and tampering

- event cannot supply principal, subject, tenant, deployment, scopes, specialist, action, policy, provider, vector space, target, or output destination;
- model cannot select policy or authorization mode;
- hidden/system-owned target comes from trusted application context;
- cross-principal, subject, tenant, deployment, and source lookup denied;
- changed policy hash, specialist hash, effective profile, schema, parameters, or evidence fails closed;
- policy audit facts bounded and sanitized.

### 16.3 Policy decisions

- authorized decision persists before invocation;
- denied decision causes zero action calls;
- policy exception causes visible denial/failure and zero action calls;
- current authority revoked before execution prevents action;
- current policy state no longer permits action prevents action;
- model cannot change parameters after policy decision;
- duplicate decision produces the same receipt.

### 16.4 Persistence and concurrency

- receipt persistence failure causes zero action calls;
- only one concurrent compare-and-set claimant invokes the action;
- authorized-but-not-executing recovery may execute once;
- stale `EXECUTING` becomes `OUTCOME_UNKNOWN` with zero automatic reinvocation;
- terminal outcome persistence failure is `OUTCOME_UNKNOWN`;
- identical replay returns byte-stable safe result;
- changed payload conflicts;
- cleanup excludes active and unknown receipts;
- reconciliation is identity-bound and terminal.

### 16.5 Action invocation

- local handler receives stable trusted idempotency value;
- connector/MCP receives the same stable value;
- unsupported connector idempotency fails before mutation;
- handler authorization is reevaluated immediately before execution;
- action result projection required and bounded;
- explicit action failure persists as `FAILED`;
- thrown non-read action exception becomes `OUTCOME_UNKNOWN` where execution may have begun;
- no raw action result leaks.

### 16.6 Regression

- ordinary non-specialist, non-confirmable actions retain current behavior;
- user confirmation still requires a user decision;
- Human Review still requires reviewer authority;
- existing confirmation receipt replay/reconciliation semantics remain after migration;
- durable read-only jobs remain unchanged;
- all chain workers remain read-only;
- provider failure and structured-output exhaustion remain visible;
- no deterministic action or answer fallback appears.

## 17. Release Gates

Before publication:

1. full framework reactor with tests enabled;
2. focused `ai-fabric-core`, `ai-fabric-execution`, chat-session, connector, and review suites;
3. all real-app modules;
4. deterministic policy-authorized real-app matrix;
5. keyed real-provider matrix with no fallback;
6. Docker/PostgreSQL persistence, restart, replay, crash ambiguity, and reconciliation proof;
7. concurrent duplicate-submission proof;
8. source-independent Maven consumer;
9. release profile with source and Javadoc artifacts;
10. empty-cache Maven Central resolution after publication;
11. hosted canary showing exact release version and source commit;
12. security review confirming no authority from event/model input and no direct specialist write bypass.

Tests must run normally. Do not use Maven test-skipping flags or replace provider calls with hidden deterministic results in keyed suites.

## 18. Explicit Non-Goals

This change does not provide:

- unrestricted autonomous agents;
- model-selected policies, tools, credentials, or targets;
- policy rules expressed as arbitrary scripts, SQL, or natural-language matching;
- writes from bounded chain workers;
- writes from fixed or parallel plans;
- multi-write transactions;
- partial-success fan-in;
- dynamic action discovery;
- exactly-once provider or external action invocation;
- automatic retry of an unknown write outcome;
- automatic financial, security, permission, deletion, legal, or irreversible decisions;
- customer-authored executable policy code through a manifest;
- a product-specific event endpoint or control plane.

## 19. Framework/Product Boundary

AI Fabric owns:

- generic policy identity and registry contracts;
- exact policy/action/specialist/source binding;
- authorization-neutral durable receipt lifecycle;
- protected persistence and fingerprints;
- compare-and-set execution ownership;
- stable idempotency propagation;
- safe outcome projection;
- replay, `OUTCOME_UNKNOWN`, reconciliation, recovery, cleanup, metrics, and diagnostics;
- fail-closed capability and definition validation.

Host applications and products own:

- event validation and transport;
- service/system authentication;
- subject, tenant, deployment, and scopes;
- specialist and action selection;
- the deterministic authorization policy and its exact version/hash;
- domain risk classification;
- target resolution and current domain validation;
- database transaction and business idempotency;
- authoritative reconciliation;
- product UI, operations, claims, support, and rollout.

The framework must not contain LoomAI, Smart Brain, Shopify, ProdUS, churn, account, or incident-specific rules. Those may appear only in documentation examples and real-app fixtures.

## 20. Adoption Rule For LoomAI And Other Platforms

A platform may claim policy-authorized proactive writes only after it:

- consumes the immutable framework release containing this capability;
- packages the execution and connector contracts without copied framework source;
- registers an exact low-risk action and policy;
- provisions durable specialist and receipt storage;
- preserves stable secrets and idempotency across restart;
- exposes status, result, unknown outcome, and reconciliation operations;
- verifies two-tenant and two-deployment isolation;
- proves one mutation under duplicate delivery;
- proves zero mutation on policy denial;
- proves restart/replay and ambiguous-outcome handling;
- publishes accurate unsupported-use boundaries.

Until then, proactive specialist execution remains read-only. An application may still perform its own deterministic automation outside AI Fabric, but that is not evidence that this framework capability exists.

## 21. Definition Of Done

The framework change is complete only when:

1. The public API distinguishes user confirmation, Human Review, and service-policy authorization without semantic aliases.
2. Every specialist-selected write becomes a durable proposal before execution.
3. An exact application policy can authorize one exact non-confirmable write for one exact machine source and specialist.
4. No action runs before its authorization receipt commits.
5. Stable trusted idempotency reaches local and connector actions.
6. Duplicate and replayed jobs produce one receipt and no duplicate known mutation.
7. Crash ambiguity becomes `OUTCOME_UNKNOWN` and never causes blind re-execution.
8. Current identity, authority, specialist, action, schema, parameters, evidence, policy, and target validity are rechecked before execution.
9. Safe outcome projection and application reconciliation work across restart.
10. Existing user confirmation and Human Review use the same generalized receipt engine.
11. Chains, plans, delegation, handoff, and conversation managers remain read-only for this release.
12. Deterministic, keyed-provider, Docker/PostgreSQL, concurrency, security, external-consumer, and hosted gates pass.
13. Documentation states clearly that the capability is policy-authorized and bounded, not autonomous authority or exactly-once execution.

## 22. Final Recommendation

Implement this as a generic `ai-fabric-execution` capability in the next reviewed feature release.

Do not weaken the current durable read-only rule by special-casing one action. First generalize the existing durable receipt into an authorization-neutral execution receipt, migrate confirmation and Human Review onto it, then add exact service-policy authorization for one direct machine-triggered specialist write.

This gives applications a safe path for explicitly pre-authorized low-risk automation while preserving the central AI Fabric rule:

> The model may propose an action. Only trusted application authority may authorize and execute it.
