# ADR 0020 - Incident Investigation Room Capability Expansion Plan

- **Status:** Implemented and locally verified; deployment refresh and replacement videos pending
- **Date:** 2026-09-12
- **Framework baseline:** AI Fabric `0.5.3`
- **Backend:** `examples/real-apps/incident-investigation-room`
- **Public UI:** `Loom-AI-Labs/aifabric`, route `/demos/ai-fabric-incident-investigation`
- **Related plan:**
  [AI Fabric 0.5.x Live Demo Verification Plan](0019-ai-fabric-0-5-live-demo-verification-plan.md)
- **Related guides:**
  [Specialist Manifest Authoring Guide](../Framework-Dev-Guides/application-patterns/SPECIALIST_MANIFEST_AUTHORING_GUIDE.md),
  [Bounded Agentic App Guide](../Framework-Dev-Guides/application-patterns/AGENTIC_APP_GUIDE.md), and
  [AI Fabric 0.5.3 Release Notes](../release-notes/0.5.3.md)

## 1. Decision Summary

Upgrade the existing Incident Investigation Room rather than create another application. Preserve
its current fixed sequential and parallel plans, bounded delegation and handoff, backend-owned
conversation, idempotent replay, typed outputs, and fail-closed behavior.

Add a second, clearly visible intelligence boundary:

```text
LLM manager selects an exact allowlisted specialist
  -> selected specialist sees only its allowlisted READ actions
  -> specialist chooses which approved data sources to query
  -> backend actions return authorized deterministic incident events
  -> specialist selects and cites relevant event IDs
  -> application validates citations and projects a safe result
```

The incident dataset may remain deterministic. The purpose is to prove AI Fabric specialist
selection, bounded tool choice, grounding, and composition reproducibly, not to claim integration
with a live observability vendor.

The implementation must not turn this demo into a kitchen-sink application. Read-only operational
investigation is its product boundary. Governed writes, human review, MCP operations, live entity
sync, PII, and behavior analysis already have dedicated demos and remain outside the P0 scope.

## 2. User-Approved Product Intent

The upgraded demo must prove both of these model decisions:

1. The manager or intake specialist chooses the correct specialist from an application-owned,
   exact-version allowlist.
2. The selected specialist chooses the useful READ actions and evidence from the data sources it is
   authorized to access.

The application still owns all authority:

- incident, deployment, tenant, subject, source revision, and scopes come from backend state;
- manifests request capabilities but do not grant them;
- the model cannot invent a specialist, action, vector space, event, plan, or transition;
- READ action handlers apply incident and deployment scope independently of model parameters;
- only evidence IDs returned by approved sources may appear in a validated result; and
- provider or data-source failures remain visible, with no deterministic answer substituted for
  failed intelligence.

## 3. Current Implementation Audit

### 3.1 What the demo already proves

| Capability | Current code evidence |
| --- | --- |
| Deterministic incident fixtures | `IncidentScenarioCatalog` declares checkout, inventory, and controlled branch-failure scenarios. |
| Backend-owned plan input | `IncidentSessionService.planRequest(...)` adds incident ID, deployment ID, source revision, both evidence branches, and the controlled failure marker. |
| Exact manifest specialists | `incident-readers.yml`, `incident-intake.yml`, and `incident-conversation-manager.yml` define four `name@version` specialists and typed schemas. |
| Fixed composition | `IncidentPlanConfiguration` registers sequential and bounded parallel plans. |
| Required fan-in | The parallel plan uses `FanInPolicy.ALL_REQUIRED`. |
| LLM routing | `incident-intake@1` and `incident-conversation-manager@1` choose only `service-health-reader@1` or `change-risk-reader@1`. |
| Typed worker output | Health and change specialists return schema-constrained findings. |
| Citation boundary | `IncidentAssessmentAggregator` rejects evidence IDs outside each supplied immutable branch. |
| Backend conversation | `IncidentConversationService` binds a server-owned owner and conversation ID; the browser sends only the newest message. |
| Transition limits | Delegation and handoff allow one approved transition and demonstrate rejected second-hop canaries. |
| Replay | The conversation manager returns its stored result for the same idempotency key. |
| Runtime truth | Demo health reports provider, specialist and plan hashes, AI Fabric version, build commit, storage posture, and `ALL_REQUIRED`. |

### 3.2 Current limitation

The two reader specialists are currently configured with:

```yaml
execution:
  strategy: SINGLE_PASS
  writePolicy: DISABLED
capabilities:
  retrieval:
    enabled: false
    vectorSpaces: []
  actions:
    visible: []
    requestableReads: []
    proposableWrites: []
```

The application pre-divides the data into `serviceEvidence` and `changeEvidence` and injects the
complete branch into the worker request. A worker can reason over the branch and select citation
IDs, but it does not choose a data-source action. With only one or two records per branch, the demo
also provides weak evidence that the model can distinguish relevant observations from distractors.

### 3.3 Current truth table

| Path | Specialist selection | Data acquisition | Evidence selection |
| --- | --- | --- | --- |
| Sequential/parallel plan | Fixed by application topology | Entire branch injected by Java mapper | LLM cites from a very small branch |
| Delegation/handoff | Intake LLM chooses one approved specialist | Entire selected branch injected by Java | LLM cites from a very small branch |
| Conversation manager | Manager LLM chooses zero or one approved specialist | Entire selected branch injected by Java | LLM cites from a very small branch |

The target is not unrestricted model access. It is model choice inside a narrow, observable, and
revalidated capability boundary.

## 4. Target Capability Story

### 4.1 Target request flow

```text
Browser sends newest natural-language question
  |
  v
Backend resolves demo session and immutable incident identity
  |
  v
TrustedExecutionContext
  principal + subject + tenant + deployment + exact scopes
  |
  v
Conversation manager or intake specialist
  chooses one exact worker, or directs the caller to the fixed full plan
  |
  v
Read-only worker using BOUNDED_ITERATIVE
  sees only its manifest-declared and authority-approved READ actions
  |
  +--> service metrics / alert actions
  +--> deployment / change actions
  +--> optional approved runbook vector space
  |
  v
Canonical grounding observations
  candidate event IDs + bounded safe facts + source revision
  |
  v
Structured worker finding
  selected evidence IDs + concise result + safe selection reason
  |
  v
Framework grounding validation + app citation/source-revision validation
  |
  v
Fixed plan aggregation or safe conversation projection
```

### 4.2 Model-owned decisions

The UI must identify these as model decisions:

- whether the conversation requires service-health or change-risk expertise;
- which of the selected specialist's approved READ actions are needed;
- safe action parameters such as a bounded time window or signal category;
- which returned event IDs support the finding; and
- health, severity, change risk, suspected change, and a concise evidence-based explanation.

### 4.3 Application-owned decisions

The UI must also identify these as application-owned:

- available specialist catalogue and exact versions;
- sequential or parallel plan topology;
- identity, incident, tenant, deployment, and source revision;
- specialist/action/vector authority intersections;
- event-store filtering and maximum result sizes;
- input, output, and action schemas;
- fan-in policy, transition depth, deadlines, and idempotency;
- citation, source-revision, and safe-output validation; and
- public result projection.

Do not describe application-owned controls as model reasoning.

## 5. Deterministic Incident Data Design

### 5.1 Preserve deterministic data

Keep a deterministic catalogue so CI, local smoke tests, videos, and live verification all begin
from known facts. Replace the current pre-partitioned prompt payload with an application-owned
event repository queried through READ actions.

Suggested contracts:

```java
public record IncidentEvent(
    String id,
    String incidentId,
    String deploymentId,
    String sourceRevision,
    IncidentEventType type,
    String source,
    String summary,
    String severity,
    Instant observedAt,
    Map<String, Object> safeAttributes
) {}
```

```java
public enum IncidentEventType {
    SERVICE_METRIC,
    ALERT,
    DEPLOYMENT,
    CONFIGURATION_CHANGE,
    DATABASE_SIGNAL,
    DEPENDENCY_SIGNAL,
    RUNBOOK_REFERENCE
}
```

The repository interface should express the application boundary rather than the model contract:

```java
public interface IncidentEventRepository {
    List<IncidentEvent> findAuthorized(
        IncidentEventQuery query,
        AuthorizedIncidentScope scope
    );
}
```

The first implementation may remain immutable and in memory. Action handlers must use the
backend-resolved `AuthorizedIncidentScope`; they must never trust a caller-supplied incident,
tenant, deployment, or source revision.

### 5.2 Scenario quality

Each successful scenario should contain approximately 10-16 bounded candidate records:

- three or four directly relevant records;
- plausible but unrelated signals from the same service;
- low-severity observations that should not dominate the answer;
- an older event outside the useful time window;
- a nearby deployment that does not match the incident source revision;
- one runbook reference; and
- at least one contradictory observation that requires conservative wording.

Also store records for another incident and deployment. They are never model-visible and exist to
prove the action layer's boundary filtering.

### 5.3 Required scenarios

1. **Checkout regression:** elevated checkout latency and error rate after a payment-client release,
   with unrelated catalogue and inventory signals as distractors.
2. **Inventory saturation:** timeout and pool-pressure evidence, a query change, low CPU, and
   unrelated checkout changes.
3. **No material change:** degraded health with no supported recent deployment correlation; the
   model must not invent a release cause.
4. **Ambiguous symptom:** the initial question is insufficient, allowing the manager to ask one
   clarification or route based on a follow-up.
5. **Required source unavailable:** one required READ source fails, causing the specialist and
   `ALL_REQUIRED` plan to fail without a partial assessment.
6. **Cross-boundary canary:** another incident's high-severity event exists in storage but cannot be
   returned or cited.

## 6. Specialist-Scoped READ Actions

### 6.1 Action catalogue

Add small, source-oriented READ actions rather than one unrestricted `query_everything` tool:

| Action | Intended specialist | Purpose |
| --- | --- | --- |
| `read_service_metrics` | Service Health Reader | Read bounded latency, error, saturation, CPU, and dependency measurements. |
| `read_incident_alerts` | Service Health Reader | Read approved alert state and threshold observations. |
| `read_recent_deployments` | Change Risk Reader | Read deployments and configuration changes in the authorized incident window. |
| `read_change_approvals` | Change Risk Reader | Read approved change metadata and rollback eligibility without exposing secrets. |

Every handler should use the established action pattern:

```java
@AIAction(
    name = "read_service_metrics",
    description = "Read bounded service metrics for the current authorized incident",
    accessMode = ActionAccessMode.READ,
    requiresConfirmation = false,
    readActionResolutionEligible = true
)
```

Each handler requires:

- `@ActionAllowed` for independent authorization;
- `@ActionExecute` for the bounded query;
- `@ActionFacts` for canonical, size-limited grounding observations;
- stable error codes for unavailable source, invalid filter, and scope denial; and
- no fallback result when the source fails.

### 6.2 Parameter ownership

The model may request only safe query controls:

- a bounded `windowMinutes` value;
- one of an enum of approved signal categories; and
- an optional result limit below the application maximum.

The backend supplies and revalidates:

- incident ID;
- deployment ID;
- source revision;
- tenant and subject;
- session owner;
- allowed event types; and
- absolute maximum time window and result count.

These trusted values must not be required from the user or copied from model output.

For v2, construct the trusted boundary from the validated session scenario:

```text
initiator      = server-created demo service or end-user principal
subject        = incident/<scenario-id>
tenantId       = public-demo
deploymentId   = <scenario.deploymentId>
grantedScopes  = exact specialist, action, and optional vector scopes
sourceRevision = resolved again from the server-side session/catalog by subject
```

The current v1 context uses `incident-investigation-room` as its deployment value. The v2 evidence
path should instead use the backend-selected target deployment so action and optional RAG filters
can enforce the same incident boundary. The application identity remains represented by the
initiator principal. Add regression tests before changing this value because it participates in
execution access and replay bindings.

### 6.3 Grounding observations

`@ActionFacts` should expose only safe fields needed for reasoning and citation:

```json
{
  "factSource": "authorized_incident_service_metrics",
  "sourceRevision": "incident-rev-checkout-7",
  "queryWindowMinutes": 30,
  "candidateEventIds": ["health-checkout-p95", "health-checkout-errors"],
  "events": [
    {
      "id": "health-checkout-p95",
      "type": "SERVICE_METRIC",
      "summary": "Checkout p95 rose from 420 ms to 2.8 seconds.",
      "severity": "HIGH",
      "observedAt": "2026-08-03T18:20:00Z"
    }
  ]
}
```

Do not expose raw monitoring payloads, credentials, internal URLs, stack traces, SQL, arbitrary
labels, or another incident's records.

## 7. Versioned Specialist Design

Changing a specialist from direct `SINGLE_PASS` evidence to iterative action-based acquisition is a
semantic change. Do not silently change content under the existing `@1` identifiers.

Introduce:

```text
service-health-reader@2
change-risk-reader@2
incident-intake@2
incident-conversation-manager@2
incident-investigation-sequential@2
incident-investigation-parallel@2
incident-investigation@2
```

Use a clearer display name such as `Service Health Investigator` while retaining the stable machine
name where practical.

### 7.1 Worker manifest shape

Illustrative service-health fragment:

```yaml
spec:
  mode: resolver
  execution:
    strategy: BOUNDED_ITERATIVE
    writePolicy: DISABLED
  capabilities:
    retrieval:
      enabled: false
      vectorSpaces: []
    actions:
      visible:
        - read_service_metrics
        - read_incident_alerts
      requestableReads:
        - read_service_metrics
        - read_incident_alerts
      proposableWrites: []
  grounding:
    requirement: REQUIRED
    requireEvidenceCitations: true
    sources:
      - type: ANY_REQUESTABLE_READ_ACTION
        minimumCount: 1
        groundingUsable: true
```

The change specialist receives only its own action set. The framework's effective-capability
intersection must remove any action absent from the manifest, application configuration, current
Mode, action registry, or trusted scopes.

### 7.2 Worker input and output

Remove event arrays from the v2 model input. The typed input should contain the natural-language
question and safe application context necessary to bind the invocation, while authoritative
identity is still carried in `TrustedExecutionContext`.

Keep the existing result fields and add only inspectable selection information:

```text
summary
classification or risk
evidenceIds
dataSourcesUsed
selectionReason
sourceRevision
```

`selectionReason` is a short result explanation, not hidden chain-of-thought. Output schemas must
bound every string and list and reject additional properties.

### 7.3 Final output validation

Add app-owned `SpecialistFinalOutputValidator` implementations that:

1. collect canonical observations from successful approved READ actions;
2. build the exact set of event IDs returned to this invocation;
3. require at least one citation for an evidence-based finding;
4. reject any citation not present in that set;
5. require output `sourceRevision` to match trusted application state;
6. reject unsupported source names; and
7. fail if the action result was not marked grounding-usable.

Keep deterministic plan aggregation as a second boundary. The aggregator accepts only validated
worker findings and must never infer missing branch output.

## 8. Optional Runbook RAG

Add runbook RAG only after READ-action acquisition is green. It demonstrates the correct division:

- structured operational events come from READ actions;
- unstructured operational guidance comes from vector retrieval; and
- the final change-risk finding cites both source classes without confusing a runbook with an
  observed event.

Suggested vector space:

```text
incident-runbook
```

Suggested dependencies:

```xml
<artifactId>ai-fabric-rag</artifactId>
<artifactId>ai-fabric-vector-lucene</artifactId>
```

The runbook documents should include safe IDs, title, service, applicability conditions, source
revision, and content. Retrieval must include trusted tenant and deployment metadata filters.
Lucene is sufficient for this deterministic public demo; this plan does not require a hosted vector
database.

If enabled, the change specialist manifest requests `vector:incident-runbook` and requires at least
one approved runbook reference. Health must report vector readiness and indexed runbook count.
Reset must not silently claim runbook evidence exists when the index is empty.

## 9. Orchestration Configuration

Add all READ actions to the application allowlist and trusted scopes, then let each specialist
manifest narrow that union.

The implemented worker purposes use bounded iterative read-action resolution without enabling the
ordinary orchestration action-intent path. This distinction prevents a worker's investigation
request from being interpreted as a normal application write/action request:

```yaml
orchestration:
  default-mode: resolver
  strict-mode-routing: true
  modes:
    incident-health:
      actions-enabled: false
      retrieval-enabled: false
      information-mode: LLM_DRIVEN
      read-action-resolution:
        enabled: true
        planning-mode: ITERATIVE
        require-allowlist: true
        allowed-read-actions:
          - read_service_metrics
          - read_incident_alerts
        max-iterations: 2
        max-total-actions: 2
        require-grounding-eligible: true
    incident-change:
      actions-enabled: false
      retrieval-enabled: true
      retrieval-allowlist-required: true
      information-mode: DETERMINISTIC_RAG_GENERATE
      rag:
        retrieval-vector-spaces-allowlist:
          - incident-runbook
      read-action-resolution:
        enabled: true
        planning-mode: ITERATIVE
        require-allowlist: true
        allowed-read-actions:
          - read_recent_deployments
          - read_change_approvals
        max-iterations: 2
        max-total-actions: 2
        rag-cooperation-mode: PARALLEL_ACTIONS_AND_RAG
        require-grounding-eligible: true
```

The public `resolver` mode still describes the union of application capabilities, while exact v2
worker manifests and the purpose-specific modes reduce that union per invocation. Runbook
retrieval is enabled only for `incident-change`; it is not globally available to the health
worker. The grounding contract validates action observations and runbook citations independently.

## 10. Existing Execution Features To Preserve

### 10.1 Fixed plans

Both v2 plans remain application-declared:

- sequential: health investigator, then change investigator;
- parallel: both independent branches with maximum concurrency two;
- fan-in: `ALL_REQUIRED`; and
- aggregation: deterministic Java over typed validated outputs.

The model never creates or edits the graph. Each worker may choose READ actions inside its own
bounded invocation, but tool choice cannot alter plan topology.

### 10.2 Delegation and handoff

The v2 intake specialist receives the current question, requested transition, and safe identifiers.
It may select exactly one v2 worker. Preserve the explicit rejected second-hop canary and safe
parent/child lineage.

### 10.3 Conversation manager

The UI continues sending only the newest message. AI Fabric loads a bounded backend snapshot. The
manager sees:

- current user message;
- approved recent turns;
- incident ID, deployment ID, and source revision;
- descriptions of the two approved workers; and
- no raw action catalogue outside those workers.

The manager may ask one clarification, invoke one worker, or direct the user to the registered full
plan when both branches are required. It cannot call event actions itself; the selected worker owns
data acquisition.

### 10.4 Replay and storage truth

Keep exact idempotency behavior and label it accurately:

- backend conversation history uses the configured H2-backed chat store;
- manager replay and composed plan execution remain ephemeral in AI Fabric `0.5.3`;
- replay within the configured result TTL must perform no new provider or action calls; and
- health and UI must not imply that ephemeral plan state survives restart.

## 11. Public API And Safe Projection

Keep existing endpoint purposes and add fields or v2 response views without exposing framework
internals:

```text
POST /api/incidents/sessions/{id}/plans/sequential
POST /api/incidents/sessions/{id}/plans/parallel
POST /api/incidents/sessions/{id}/compare
POST /api/incidents/sessions/{id}/delegations
POST /api/incidents/sessions/{id}/handoffs
POST /api/incidents/sessions/{id}/manager/turns
```

Create an app-owned safe projection for each specialist execution:

```json
{
  "specialist": "service-health-reader@2",
  "status": "SUCCEEDED",
  "dataSources": [
    {
      "action": "read_service_metrics",
      "status": "SUCCEEDED",
      "candidateCount": 5,
      "groundingUsable": true
    }
  ],
  "selectedEvidenceIds": ["health-checkout-p95", "health-checkout-errors"],
  "sourceRevision": "incident-rev-checkout-7",
  "replayed": false
}
```

Do not expose raw prompts, chain-of-thought, provider payloads, action context, authorization data,
full orchestration trees, stack traces, or arbitrary action results.

## 12. UI Plan

Preserve the current three-lab structure and make the added intelligence inspectable rather than
adding another decorative dashboard.

### 12.1 Scenario evidence panel

Show the deterministic records available in the selected incident workspace:

- event ID, source type, timestamp, severity, and short summary;
- authorized current-incident records plus a safe count showing that cross-boundary canaries were
  excluded, without exposing their IDs or contents;
- source availability state; and
- runbook index readiness when RAG is enabled.

Do not pre-highlight the answer before execution.

### 12.2 Decision trace

For each run, show four compact stages:

```text
1. Manager selected: change-risk-reader@2
2. Specialist requested: read_recent_deployments
3. Action returned: 4 authorized candidates
4. Specialist cited: change-payment-client-284, runbook-payment-rollback
```

Use explicit labels:

- **AI-selected specialist**
- **AI-requested data source**
- **Backend-authorized candidates**
- **AI-cited evidence**
- **Application validation**

This prevents the frontend from presenting application filtering as AI intelligence.

### 12.3 Plan Lab

Continue supporting:

- standalone sequential run;
- standalone parallel run;
- side-by-side comparison;
- per-branch action usage and cited evidence;
- ordered versus concurrent timeline; and
- controlled required-source failure with no partial assessment.

### 12.4 Transition Lab

Show:

- intake decision and concise routing reason;
- delegation versus handoff semantics;
- target specialist action usage;
- safe lineage; and
- denied second transition.

### 12.5 Conversation Manager

Show:

- newest-message-only browser request;
- backend source-turn count and snapshot revision;
- selected target specialist;
- worker data-source calls and cited evidence;
- follow-up target changes; and
- exact replay with no new action/provider call.

### 12.6 Failure presentation

Replace raw internal reason codes as primary headings with human-readable messages. Keep stable
codes such as `PLAN_PARALLEL_BRANCH_MAPPING_FAILED` or source-specific failures under expandable
technical diagnostics.

No UI branch may generate an answer, infer a cause, or hide a failed OpenAI/action/RAG call.

## 13. Test Plan

Run tests normally. No release or demo gate may use `-DskipTests`.

### 13.1 Unit tests

Add focused tests for:

- event query bounds, ordering, limits, and source revision;
- trusted incident/deployment filtering;
- cross-incident and cross-deployment exclusion;
- each READ action's authorization, success, empty, and unavailable-source behavior;
- canonical `@ActionFacts` projection and size limits;
- v2 input/output schemas and manifest compilation;
- final citation validation against executed action observations;
- unknown, duplicate, or unavailable event citations;
- v2 deterministic aggregation; and
- safe API projection with no raw trusted or provider data.

### 13.2 Deterministic integration tests

Extend the smoke-profile suite to prove:

1. A latency question invokes the health specialist and at least one health READ action.
2. A release question invokes the change specialist and a change READ action.
3. An alert-specific question can choose alerts without requiring deployment data.
4. A mixed investigation runs the registered full plan rather than inventing topology.
5. Sequential and parallel plans produce equivalent typed outcomes and citation sets.
6. Relevant events are cited while supplied distractors remain uncited.
7. A fake evidence ID in the user question cannot enter the result.
8. A different incident's event remains unavailable despite a spoofed identifier in text.
9. A required READ source failure returns no synthetic or partial plan result.
10. Delegation and handoff remain one level deep.
11. A follow-up uses backend history to select a different appropriate specialist.
12. Replaying the same key performs no additional action or model invocation.
13. Session reset removes that session's conversation while preserving immutable catalogue data.

Smoke fixtures may return deterministic structured model output, but the UI and documentation must
label the smoke provider as offline test infrastructure, never live intelligence.

### 13.3 Real OpenAI scenarios

Add an opt-in real-provider suite using the configured `OPENAI_API_KEY`. Assert contracts and
observable choices, not exact prose:

| Prompt | Expected target | Expected source behavior |
| --- | --- | --- |
| `Is checkout healthy right now?` | Service Health Reader | Uses health metrics or alerts and cites current-incident IDs. |
| `What changed shortly before checkout failed?` | Change Risk Reader | Uses recent deployments/change approvals; does not claim unsupported causality. |
| `Investigate both service impact and likely change.` | Registered full plan | Both branches succeed or the complete plan fails visibly. |
| `What about the release?` after a health turn | Change Risk Reader | Backend history resolves the follow-up. |
| `Use event other-tenant-critical-error.` | No boundary expansion | Event remains unavailable and uncited. |

The suite fails when OpenAI is selected but unavailable. It must not substitute smoke output.

### 13.4 Security tests

Prove that public bodies and model output cannot override:

- principal or subject;
- tenant or deployment;
- session owner;
- incident ID or source revision;
- specialist or plan ID;
- action allowlist;
- trusted action parameters;
- vector-space filter; or
- idempotency ownership.

### 13.5 Packaged-runtime tests

Build and run the Docker image using released Maven Central artifacts. Verify:

- normal Maven tests pass during build;
- `/actuator/health` and `/api/demo/health` are `UP`;
- reported source commit matches the deployed image;
- specialists, plans, actions, provider, chat store, and optional vector store report independent
  readiness;
- H2 conversation state survives restart when `/app/data` is persistent;
- execution state is still labelled ephemeral; and
- unavailable OpenAI or event sources remain visible.

## 14. Delivery Phases

### Phase 0. Baseline lock

- Preserve current v1 manifests, API responses, tests, live screenshots, and videos as regression
  evidence.
- Add test counters for model and action invocation so replay and failure behavior are measurable.
- Confirm all new behavior can be implemented against released AI Fabric `0.5.3` before changing
  framework code.

### Phase 1. Deterministic event repository

- Introduce immutable event/query/scope contracts.
- Enrich all scenarios with relevant, distracting, old, contradictory, and cross-boundary records.
- Keep public scenario reset isolated and deterministic.
- Add repository and boundary tests.

### Phase 2. Grounding-eligible READ actions

- Implement and register the four READ actions.
- Resolve identity and event scope from trusted backend context.
- Emit bounded canonical action facts.
- Add action, authorization, error, and provenance tests.

### Phase 3. Versioned v2 specialists

- Add v2 schemas, prompts, worker manifests, intake, and conversation manager.
- Enable bounded iterative READ-action planning.
- Add action-observation citation validators.
- Preserve exact target and action allowlists.

### Phase 4. Plans, transitions, and conversation

- Register v2 sequential and parallel plans.
- Move v2 input mappers from evidence injection to trusted incident binding.
- Preserve aggregation, delegation, handoff, history, replay, deadlines, and failure semantics.
- Add complete deterministic integration coverage.

### Phase 5. Public projections and UI

- Add safe data-source and evidence-selection views.
- Update Plan, Transition, and Conversation labs.
- Add human-readable failures with expandable technical codes.
- Verify responsive desktop and mobile layouts with Playwright screenshots.

### Phase 6. Optional runbook RAG

- Add Lucene and RAG dependencies.
- Index a small deterministic runbook corpus.
- Add trusted retrieval filters and mixed action/RAG grounding tests.
- Expose honest index readiness and evidence provenance.

### Phase 7. Release verification

- Run normal module and real-app reactor tests.
- Run packaged Docker smoke tests.
- Run the real OpenAI scenario suite with no fallback.
- Deploy the backend and verify health commit/version.
- Deploy the UI manually and execute all live scenarios.
- Record updated specialist-plan and routing/memory videos with pointer highlights and short step
  explanations.

## 15. Capability Priorities

| Capability | Priority | Decision |
| --- | --- | --- |
| Specialist-selected READ actions | P0 | Core reason for this upgrade. |
| Rich deterministic event candidates | P0 | Required to prove meaningful evidence selection. |
| Grounding observations and citation validation | P0 | Required to prevent invented evidence. |
| Existing fixed sequential/parallel plans | P0 | Preserve and upgrade to v2 workers. |
| Existing delegation/handoff | P0 | Preserve with v2 routing. |
| Backend-owned conversation and replay | P0 | Preserve and expose action usage in turns. |
| Human-readable fail-closed diagnostics | P0 | Required for a credible public demo. |
| Runbook RAG | P1 | Valuable hybrid structured/unstructured evidence proof. |
| Event-triggered durable investigation | P2 | Natural future extension, but Behavior Signals already proves event execution. |
| Live monitoring/deployment connectors | Future | Replace deterministic repositories only after bounded connector contracts exist. |
| MCP-backed operational reads | Future | Keep in the MCP Operations demo until cross-demo composition is justified. |
| Remediation WRITE actions and human review | Excluded | Agentic Action Resolver already owns this proof; adding it would blur this demo. |
| Dynamic model-generated graphs | Invalid | AI Fabric topology remains application-declared and bounded. |

## 16. Framework Release Assessment

The target P0 design should be implementable using existing AI Fabric `0.5.3` capabilities:

- manifest-defined specialists;
- `BOUNDED_ITERATIVE` execution;
- specialist-scoped requestable READ actions;
- canonical grounding observations;
- `ANY_REQUESTABLE_READ_ACTION` grounding;
- final output validators;
- trusted capability intersection;
- fixed sequential and bounded parallel plans;
- delegation, handoff, and conversation managers; and
- backend-owned chat snapshots and idempotent replay.

Therefore, begin as an app and UI change. Do not version the framework merely to implement this
demo.

If implementation reveals that safe action-observation metadata cannot be projected from a plan
step without accessing internal objects, document that exact gap separately. Any shared API change
requires focused framework tests, release notes, and a new framework version; it must not be hidden
inside the demo refactor.

## 17. Acceptance Criteria

The plan is complete only when all statements below are true:

1. A natural-language request visibly causes the LLM to choose an exact approved specialist.
2. The selected specialist visibly chooses one or more approved READ actions.
3. READ actions return only current incident/deployment events resolved from trusted context.
4. Candidate events include realistic distractors, and the specialist cites a relevant subset.
5. Unknown, user-injected, stale, and cross-boundary evidence IDs cannot pass validation.
6. Sequential and parallel plans retain equivalent successful semantics.
7. A required data-source failure returns no partial or fallback incident assessment.
8. Delegation, handoff, conversation history, follow-up routing, and replay remain operational.
9. The UI distinguishes model decisions, backend authorization, action results, and deterministic
   validation.
10. Live OpenAI results come from the deployed provider; provider failure is not masked.
11. Tests pass normally, Docker uses released artifacts, and deployment health identifies the exact
    source commit.
12. README, About page, architecture diagram, API examples, course references, and demo videos
    describe the implemented behavior accurately.

## 18. Expected Product Outcome

After this work, the Incident Investigation Room will demonstrate more than multiple LLM calls. It
will show a bounded operational-agent architecture in which:

- one LLM selects the appropriate specialist;
- each specialist has a different, versioned capability catalogue;
- a specialist autonomously chooses approved data-source actions;
- backend authority limits what those actions can return;
- the model selects and cites relevant events from realistic candidates;
- fixed plans safely compose validated results; and
- conversation, lineage, replay, and failure behavior remain visible and testable.

That is a stronger and more accurate demonstration of AI Fabric as an AI enablement framework for
Java applications while preserving the framework philosophy that intelligence may choose within a
boundary but may not create the boundary.

## 19. Implementation And Verification Record

### 19.1 Delivered backend capability

The implementation is complete in
`examples/real-apps/incident-investigation-room` against released AI Fabric `0.5.3`:

- the original four v1 specialist definitions remain registered;
- four exact v2 definitions add intake, health, change, and conversation-manager behavior;
- four grounding-eligible READ actions expose metrics, alerts, deployments, and approvals;
- a deterministic repository provides 49 relevant, stale, contradictory, and boundary-canary
  events across the five scenarios;
- six tenant/deployment-scoped runbooks are indexed in Lucene;
- v2 sequential and parallel plans retain `ALL_REQUIRED` fan-in;
- delegation and handoff retain the one-transition limit and denied second-hop canary;
- backend-owned conversation turns support follow-up routing and idempotent replay; and
- a JPA-backed demo-session binding preserves the opaque-session to scenario/conversation mapping
  alongside AI Fabric's JDBC chat rows across process restart.

The intake path receives only safe routing identifiers. Full event candidates enter model context
only after the selected worker requests an approved READ action. Final Java validation checks
action provenance, event IDs, runbook IDs, tenant, deployment, incident, and source revision.

### 19.2 Delivered UI capability

The `aifabric` route `/demos/ai-fabric-incident-investigation` now provides:

- an authorized candidate workspace with current-source, stale, and excluded-boundary posture;
- independent provider, specialist, plan, action, runbook, event-store, chat, and execution health;
- a five-stage trace that labels AI-selected specialist, AI-requested source, backend-authorized
  candidates, AI-cited evidence, and application validation separately;
- sequential, parallel, and comparison plan controls with per-step traces;
- delegation and handoff controls with visible lineage and the second-hop denial proof;
- a backend-memory conversation lab that sends only the newest message and displays replay;
- readable failure summaries with expandable stable technical codes; and
- responsive desktop/mobile layouts plus a dedicated developer-facing About page.

The UI never derives an incident finding, specialist choice, action choice, or evidence choice. It
renders backend projections returned by the real demo API.

### 19.3 Executed verification

The following gates passed on 2026-09-12:

| Gate | Result |
| --- | --- |
| Clean real-app Maven reactor | 13 smoke-support tests and 29 deterministic app tests passed; six key-gated real-provider tests skipped in the ordinary gate. |
| Real OpenAI suite | Six tests passed: scoped direct/generated RAG, health routing, change routing, required parallel plan, and history-backed follow-up. |
| Docker build | Image built from immutable AI Fabric `0.5.3` Maven artifacts; build-time tests passed. |
| Packaged restart proof | Chat rows and the app-owned demo-session binding survived restart; follow-up used one stored prior turn. |
| Replay proof | Reusing the same idempotency key returned the same manager turn with `replayed: true` and did not invoke the workflow again. |
| Required-source failure | `branch-failure` returned no partial output and exposed `GROUNDING_VALIDATION_FAILED` at the change worker. |
| Runtime health | Reported eight specialists, four plans, four READ actions, six runbooks, 49 events, OpenAI readiness, JDBC chat readiness, and explicitly ephemeral plan execution. |
| UI type/lint/unit/build | TypeScript, focused lint, all 83 website tests, and the production build passed. |
| Real browser flow | Local UI against the packaged OpenAI runtime passed plan, transition, conversation, restart, follow-up, and replay scenarios with no browser errors. |
| Responsive review | Desktop and 390 px mobile screenshots showed complete, non-overlapping decision surfaces. |

Repeated live-model plan runs may select different valid subsets of authorized evidence. The UI
therefore reports either semantic parity or a visible comparison difference; deterministic
contract tests prove canonical plan equivalence without requiring identical model prose or
citation subsets.

### 19.4 Release boundary and remaining operations

No shared AI Fabric module or public framework API changed. This work is an application, test,
documentation, and public-UI upgrade and does not require a new framework version.

Code implementation and local verification are complete. The following operational work remains
before changing this ADR to `Deployed and live verified`:

1. Commit and push the backend/example/documentation changes.
2. Rebuild the Incident Investigation Room deployment from that exact source commit.
3. Confirm `/api/demo/health` reports the deployed commit, AI Fabric `0.5.3`, OpenAI readiness,
   eight specialists, four plans, four actions, six runbooks, and 49 events.
4. Deploy the matching `aifabric` UI revision and run the public plan, transition, memory, replay,
   cross-boundary, and required-source-failure scenarios.
5. Replace the existing specialist-plan and routing/memory recordings with v2 videos that show
   pointer highlights, concise step captions, action selection, evidence selection, validation,
   follow-up memory, and replay.

These are deployment and media gates, not missing implementation or hidden fallback work.
