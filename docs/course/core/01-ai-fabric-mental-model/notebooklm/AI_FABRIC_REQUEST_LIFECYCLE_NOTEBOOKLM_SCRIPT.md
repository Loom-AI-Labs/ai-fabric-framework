# NotebookLM Single-Source Production Script: AI Fabric Request Lifecycle

## Generator Instructions - Do Not Narrate

Use this file as the only source for the video. Do not supplement it with general AI, Spring AI,
agent, RAG, or security knowledge. Do not ask for or rely on another source.

Create a structured technical explainer titled **AI Fabric Request Lifecycle: From Spring Boot Entry
To Governed Response**. Follow the fourteen scenes in order. Use every **Visual** block as production
direction and every **Narration** block as the spoken message. Natural transitions are allowed, but
do not omit, replace, or contradict the technical content.

Keep the current AI Fabric request lifecycle as the subject. This is an architecture explainer, not
a code-along and not a generic request-processing lesson. Do not invent pipeline steps, step order,
classes, result types, provider behavior, endpoints, benchmarks, customers, or compliance claims.
Apply the accuracy guardrails at the end of this file to the complete output.

## Production Direction

- Title: **AI Fabric Request Lifecycle: From Spring Boot Entry To Governed Response**
- Target duration: 12-15 minutes.
- Audience: Java and Spring Boot developers who already understand what AI Fabric is and how its
  modules are organized.
- Voice: direct, calm, technically precise, and practical. Address the developer as **you**.
- Learning objective: by the end, you can trace an information request, a retrieval request, and a
  confirmation-gated action through the actual AI Fabric pipeline and identify which owner performs
  each decision.
- Example application: a Spring Boot Support Knowledge Assistant with indexed support evidence and
  a registered `create_support_ticket` action.
- Visual style: use one horizontal request pipeline, branch diagrams, a provider invocation map, and
  concise result cards. Keep labels readable and avoid decorative AI imagery.

## Scene 1: One Entry, Several Possible Workflows

**Visual:** Show three user messages entering the same Spring Boot application.

```text
"How do I recover account access?"
"Open a support ticket for this issue."
"Yes, submit it."
```

Then show three possible outcomes:

```text
INFORMATION_PROVIDED
CONFIRMATION_REQUIRED
ACTION_EXECUTED
```

**Narration:**

An AI Fabric request does not begin as a predetermined RAG call or action call. It begins as natural
language plus trusted application context.

The first message may need evidence retrieval and generated explanation. The second may identify a
registered application action but pause before execution. The third may resolve the action waiting
in backend conversation state.

All three enter the same orchestration boundary. Server policy and structured intent decide which
branch is allowed. Later lessons will examine retrieval and actions separately. This video explains
the complete lifecycle that connects them.

## Scene 2: The Spring Boot Application Owns Request Entry

**Visual:** Show an application controller constructing two inputs.

```java
String query = "Open a support ticket for this issue.";

OrchestrationContext context = OrchestrationContext.builder()
    .userId(authenticatedUserId)
    .sessionId(sessionId)
    .conversationId(conversationId)
    .mode(requestedMode)
    .position(uiPosition)
    .metadata(trustedServerMetadata)
    .build();
```

```text
query + OrchestrationContext -> RAGOrchestrator.orchestrate(...)
```

**Narration:**

The application owns the HTTP endpoint. It authenticates the caller, resolves the current user and
tenant, chooses which client values may be trusted, and builds the `OrchestrationContext`.

The context requires either an authenticated user ID or an anonymous session ID. It can also carry a
conversation ID, request ID, locale, mode, position, normalized request metadata, and attachments.
Transient provider inputs can travel for the current request without being serialized into metadata
or chat history.

The application then calls `RAGOrchestrator.orchestrate` with the user query and context. Despite the
class name, the orchestrator coordinates more than RAG. It delegates information, action,
confirmation, compound, and out-of-scope handling to the pipeline.

Do not let the browser establish trusted tenant identity or directly invoke an action handler. The
backend creates the authority-bearing context.

## Scene 3: The Pipeline Is An Ordered State Machine

**Visual:** Show `RAGOrchestrator` delegating to `DefaultOrchestrationPipeline`. Animate a
`PipelineContext` moving through ordered Spring beans.

```text
RAGOrchestrator
  -> DefaultOrchestrationPipeline
  -> ordered PipelineStep beans
  -> OrchestrationResult
```

**Narration:**

`RAGOrchestrator` validates its inputs and delegates to `DefaultOrchestrationPipeline`.

Spring injects every available `PipelineStep`. The pipeline sorts them by ascending order. Core
modules contribute the default steps, while an installed module such as chat-session can contribute
additional steps at defined positions.

The pipeline creates an immutable `PipelineContext`. It starts with the original query,
`OrchestrationContext`, timestamp, and request ID. As steps complete, new context copies accumulate a
processed query, policy, history messages, extracted intents, resolved targets, retrieval routing,
result data, suggestions, and sanitized output.

Any step can terminate the main workflow with a controlled result. Unexpected exceptions are
converted into `Pipeline step failed: <StepName>`. Final sanitization and selected persistence steps
can still process a terminated result, so failure remains structured rather than escaping as an
unhandled provider or application exception.

## Scene 4: Security And Access Run Before AI Interpretation

**Visual:** Highlight the first two core steps.

```text
10 SecurityAnalysis
20 AccessControl
```

Show a blocked path ending before intent extraction.

**Narration:**

The first core gate is `SecurityAnalysisStep`. It builds a security request from the original query,
authenticated or anonymous subject context, request metadata, IP address, and user agent. Baseline
checks and optional application security policy can block the request. Optional input PII processing
is a later, separately configured module step.

Next, `AccessControlStep` asks `AIAccessControlService` whether the subject may enter the orchestration
resource. Trusted subject, tenant, deployment, issuer, and scope metadata are copied from the backend
context into the access request. A denial terminates the pipeline.

These gates run before an LLM interprets intent and before retrieval or action execution. They do not
replace action-level authorization or vector metadata filtering later in the request. They establish
the first fail-closed boundary.

If the UI displays `Pipeline step failed: AccessControl`, the correct investigation is the
application access policy and trusted identity context, not a prompt rewrite.

## Scene 5: Server Policy Resolves The Effective Mode

**Visual:** Show request hints entering a server-owned policy resolver.

```text
profile + configured modes + default mode + request mode hint
  -> OrchestrationPolicy
```

Display capability switches:

```text
actions
retrieval
deep retrieval
suggestions
read-action planning
RAG spaces and budgets
```

**Narration:**

At order twenty-two, `OrchestrationPolicyResolutionStep` turns configuration and request context into
the server-authoritative `OrchestrationPolicy`.

Named modes are allowlisted under `ai.orchestration.modes`. A request may ask for a mode, but the
server decides whether it exists. Strict routing can reject an unknown mode. Otherwise the configured
default mode or profile supplies the behavior.

The policy controls whether actions, retrieval, deep retrieval, suggestions, and planner-driven read
actions are enabled. It also carries RAG allowlists and budgets such as selected vector spaces,
fan-out, top K, context size, document limits, and similarity threshold.

Position is not mode. Position is application or UI context. Core records it in policy, while any
mapping from position to mode belongs at the app or web boundary. A client signal cannot create a
server capability that was not configured.

## Scene 6: Attachments, Backend Memory, Privacy, And Governance Prepare Context

**Visual:** Add the steps that run before intent extraction.

```text
23 AttachmentNormalization
25 ConversationEnrichment       optional with chat-session
26 AttachmentPromptAugmentation
27 PendingActionPromptAugmentation
30 PIIDetection                 optional with PII enabled
40 ComplianceCheck             optional with governance compliance enabled
```

Show three separate inputs:

```text
current user query
bounded history messages
pinned target context
```

**Narration:**

Before intent extraction, AI Fabric prepares only the context the request is allowed to use.

Attachment normalization validates and bounds client attachments. Safe attachment content can become
pinned target context for the current model message. It must not silently become trusted identity or
an unbounded embedding query.

When `ai-fabric-chat-session` is installed and a conversation ID is present,
`ConversationEnrichmentStep` loads backend-owned conversation turns. It supplies typed history
messages to the provider and can restore recently persisted targets within configured limits.

Pending-action prompt augmentation adds the fact that an action is waiting for confirmation. This
helps the model interpret a short reply such as "yes" in the correct context.

When `ai-fabric-pii` is installed and input detection is enabled, `PIIDetectionStep` inspects the
effective user query at order thirty. If it detects PII, downstream model processing receives the
processed or masked query and the context records detected PII types. The original text must not be
forwarded to later LLM steps merely because detection mode preserved it elsewhere.

When governance and its compliance feature are enabled with a `ComplianceCheckProvider`,
`ComplianceCheckStep` evaluates the effective query at order forty. A missing or non-compliant
response terminates processing before intent extraction. These are optional module-contributed
steps, not always-active core behavior.

The browser sends the newest message. Backend chat-session supplies trusted prior turns and pending
state. Requiring the browser to replay trusted history would move memory ownership to the wrong
boundary.

## Scene 7: Intent Extraction Uses The Orchestration Provider Purpose

**Visual:** Show three inputs entering `IntentExtractionStep` and a structured result leaving it.

```text
current message + typed history + pinned targets
  -> LLM through AI Fabric orchestration purpose
  -> MultiIntentResponse
```

Example structured concepts:

```text
type
action
action parameters
requires retrieval
requires generation
vector space
target requirement
next steps
```

**Narration:**

After the optional privacy and compliance gates, `IntentExtractionStep` runs at order fifty and asks
the configured LLM to interpret the request as structured intent.

The provider receives the current user message, bounded typed history, pinned target context, the
available action catalog, knowledge-base information allowed by policy, and curated prompts. The
result can contain one or several intents such as `INFORMATION`, `ACTION`, confirmation, or
`OUT_OF_SCOPE`.

AI Fabric uses its orchestration LLM purpose for this call. That purpose can have provider and model
configuration distinct from final answer generation. The framework parses, validates, and when
configured progressively repairs structured output rather than trusting arbitrary provider text as
an executable command.

If extraction cannot produce a valid intent, current behavior records diagnostics and admits a safe
out-of-scope result with zero confidence. It must not turn a provider failure into a successful
retrieval or action result.

## Scene 8: Targets And Vector Spaces Are Resolved After Intent

**Visual:** Add the post-extraction routing steps.

```text
50 IntentExtraction
51 WorkingSetTargetSeeding      optional with chat-session
52 TargetResolution
55 VectorSpaceResolution
57 ConfirmationResolution      optional with chat-session
```

**Narration:**

The extracted intent describes what it appears to need. Server-side steps then resolve whether those
needs can be satisfied safely.

Working-set seeding can restore approved recent targets from session state. `TargetResolutionStep`
prefers normalized current attachments and can use a bounded working set. If an intent explicitly
requires a target and none can be resolved, the pipeline returns `CLARIFICATION_REQUIRED` instead of
guessing an identifier.

For information intents requiring retrieval, `VectorSpaceResolutionStep` validates or resolves the
knowledge domains to search. It applies mode allowlists, fan-out budgets, request hints, known vector
spaces, and deep-retrieval policy. When the correct space is ambiguous and policy does not allow a
deterministic choice, the result asks for clarification.

With chat-session, confirmation resolution can reinterpret a short confirmation against the pending
action before intent handling. These deterministic routing steps constrain the LLM's proposal; they
do not let model output bypass server policy.

## Scene 9: Intent Handling Chooses The Information Branch

**Visual:** Split `INFORMATION` into four paths.

```text
direct response
generation only
retrieval only
retrieval + grounded generation
```

Add an optional reviewed read-action path that can supply live evidence.

**Narration:**

At order sixty, `IntentHandlingStep` executes the policy-approved branch.

An information intent can return a direct structured response, invoke generation without retrieval,
retrieve evidence without generation, or perform retrieval followed by grounded generation. A
configured read-action resolver can also call allowlisted, grounding-eligible application read
actions and use their returned facts alone or alongside RAG.

When retrieval is required, the handler checks that retrieval is enabled, required vector-space
allowlists exist, and the selected spaces fit policy budgets. It prepares an embedding query from the
actual user request, not from a carrier string that mixes the whole chat history into semantic
search. The embedding provider creates the query vector, the vector provider retrieves evidence, and
RAG can build bounded model context from that evidence.

If generation is required, the final answer call uses AI Fabric's generation purpose. Retrieval
evidence and generated wording remain different outputs that tests should inspect independently.

## Scene 10: Intent Handling Chooses The Governed Action Branch

**Visual:** Split the action branch into ordered gates.

```text
action name
  -> mode permits actions
  -> registry contains handler
  -> anonymous and application policy checks
  -> trusted target/context parameter resolution
  -> required and executable parameter validation
  -> confirmation or execution
```

**Narration:**

For an `ACTION` intent, AI Fabric does not execute a method named by arbitrary model text.

`AIActionRegistry` must contain a registered action with that stable name. The effective mode must
permit the action. Anonymous access must be explicitly allowed. The application action handler then
checks whether the current subject may perform it.

AI Fabric combines user-visible parameters with approved values resolved from backend context,
attachments, prior evidence, or reviewed read actions. It validates required values, schema,
provenance, and executable targets. Missing information returns `CLARIFICATION_REQUIRED` and can be
stored as an action draft for the next turn.

Only after those gates can the application-owned action handler run. The handler calls the real
domain service and returns a structured `ActionResult`. The LLM interprets the request; it does not
own the repository write.

## Scene 11: Confirmation Is A Two-Turn Backend Workflow

**Visual:** Show two requests connected by server-side pending state.

```text
Turn 1: "Open a support ticket"
  -> validated action proposal
  -> PendingAction stored by conversation and owner
  -> CONFIRMATION_REQUIRED

Turn 2: "Yes"
  -> history and pending state loaded
  -> confirmation resolved
  -> same action marked confirmed
  -> application handler executes
  -> ACTION_EXECUTED
```

**Narration:**

A confirmation-gated action is not one long request and not a button that directly calls the domain
endpoint.

When the action requires confirmation and the request has backend conversation state, AI Fabric
stores a `PendingAction` containing the action name, approved parameters, confirmation message, and
trusted evidence references. The first result is `CONFIRMATION_REQUIRED`; no domain side effect has
occurred.

On the next request, conversation enrichment and pending-action context help interpret "yes" or
"no." A positive decision pops the pending action, marks it confirmed for that pipeline execution,
and re-enters the same action validation path before execution. A negative decision removes pending
state without executing.

Production confirmation workflows must supply a conversation ID, backend pending-state support, and
tests proving first-turn pause, positive execution, negative cancellation, ownership isolation, and
replay behavior. UI state alone is not confirmation enforcement.

## Scene 12: Know Exactly When Providers Are Invoked

**Visual:** Show a provider invocation matrix.

```text
Security and access policy       -> application/framework services, no LLM required
Optional input PII processing   -> PII detection service, no LLM required
Optional compliance gate       -> configured compliance provider, no LLM required
Intent extraction and planning  -> LLM, ORCHESTRATION purpose
Semantic retrieval              -> embedding provider + vector provider
Grounded answer generation      -> LLM, GENERATION purpose
Application action execution    -> domain handler, no LLM required
Optional post-action summary    -> LLM, GENERATION purpose
Optional smart suggestion       -> configured retrieval/generation support
```

**Narration:**

One orchestration request can make no provider call, one provider call, or several provider calls.

Security and access checks are framework and application policy operations. Optional PII processing
uses the configured detection service, while optional compliance uses its configured compliance
provider. Neither requires an LLM. Intent extraction usually invokes the orchestration LLM.
Retrieval invokes an embedding provider and vector provider. A grounded answer invokes the
generation LLM after evidence is available. A registered action executes ordinary application code;
it does not need an LLM at the moment of the side effect. Optional post-action generation can
summarize trusted action facts afterward.

AI Fabric's `AICoreService` and provider manager keep orchestration and generation purposes explicit.
The Spring AI provider module can implement LLM and supported remote embedding calls. ONNX can
implement local embeddings. AI Fabric vector modules implement vector operations separately.

Provider failure should remain observable in the appropriate branch. It must not be hidden by a
canned successful answer or a fake UI result.

## Scene 13: Finalization Produces A Stable Client Result

**Visual:** Show the final core and optional steps.

```text
65 OrchestrationResultNormalization
70 MetadataBuilding
80 SmartSuggestions
90 ResponseSanitization
95 ConversationRecording       optional with chat-session
100 HistoryPersistence
```

Then show the stable result shape.

```text
type
success
message or stable errorCode
data
nextSteps
metadata
smartSuggestion
sanitizedPayload
```

**Narration:**

After intent handling, AI Fabric prepares a provider-neutral result for the application.

Normalization canonicalizes result shapes. Metadata building adds safe request and processing
details. Smart suggestions can add optional next-step guidance when enabled. Response sanitization
cleans the message, answer, nested results, and client payload before they leave the pipeline.

With chat-session enabled, `ConversationRecordingStep` records the user message after configured
PII redaction when available, and the sanitized assistant response, plus bounded working-set and
action references when configured. A never-persist request mode can skip this storage. Core
`HistoryPersistenceStep` separately records intent processing for analytics and auditing; its
failure is non-fatal.

Finally, the pipeline attaches total duration, early-termination state, and per-step timing metadata.
The Spring Boot application decides which approved fields become its public REST response.

## Scene 14: Trace Two Requests And Read Failures By Boundary

**Visual:** First show an information request trace.

```text
"How do I recover account access?"
  -> security and access pass
  -> support mode permits retrieval
  -> intent = INFORMATION
  -> support vector space resolved
  -> embedding + vector evidence
  -> generation from evidence
  -> INFORMATION_PROVIDED
```

Then show a two-turn action trace.

```text
"Open a support ticket for this issue"
  -> action discovered and parameters validated
  -> CONFIRMATION_REQUIRED

"Yes"
  -> pending action resolved
  -> application service executes
  -> ACTION_EXECUTED
```

**Narration:**

Read the request lifecycle as a chain of evidence-bearing boundaries.

For an information request, prove the trusted context, effective mode, extracted intent, selected
vector space, retrieved source IDs, optional generation, sanitized result, and persisted turn.

For a confirmed action, prove the registered action, application policy decision, parameters and
their trusted source, pending state, confirmation turn, handler invocation, domain side effect, and
structured result.

Failure names tell you where to start. `AccessControl` points to identity and application policy.
`IntentExtraction` points to prompt, provider, structured output, and history context.
`VectorSpaceResolution` points to mode allowlists and knowledge-space configuration.
`IntentHandling` points to retrieval, action registry, parameters, confirmation, or domain execution.
`ResponseSanitization` points to unsafe output or sanitization policy.

You now have the dynamic map that connects AI Fabric's modules. The next Core lesson can zoom into
one branch: how application records become searchable evidence before RAG generation begins.

## Final Ordered Pipeline Reference - Do Not Narrate As A List

Use this table to verify on-screen labels. Steps marked optional appear only when their module and
conditions are active.

| Order | Step | Purpose |
| --- | --- | --- |
| 10 | `SecurityAnalysis` | Analyze and optionally block the incoming request. |
| 20 | `AccessControl` | Enforce entry access using trusted subject context. |
| 22 | `OrchestrationPolicyResolution` | Resolve server-authoritative profile, mode, capabilities, and budgets. |
| 23 | `AttachmentNormalization` | Validate and bound request attachments. |
| 25 | `ConversationEnrichment` | Optional chat-session history and recent target enrichment. |
| 26 | `AttachmentPromptAugmentation` | Add bounded pinned-target context to the current model message. |
| 27 | `PendingActionPromptAugmentation` | Add pending confirmation context. |
| 30 | `PIIDetection` | Optionally process or mask input PII before downstream model use. |
| 40 | `ComplianceCheck` | Optionally enforce a configured governance compliance provider before intent extraction. |
| 50 | `IntentExtraction` | Produce validated structured intent through the orchestration provider path. |
| 51 | `WorkingSetTargetSeeding` | Optional chat-session working-set target seeding. |
| 52 | `TargetResolution` | Resolve explicit targets or request clarification. |
| 55 | `VectorSpaceResolution` | Resolve allowed retrieval spaces and fan-out. |
| 57 | `ConfirmationResolution` | Optional chat-session confirmation interpretation. |
| 60 | `IntentHandling` | Run information, retrieval, generation, action, confirmation, compound, or scope behavior. |
| 65 | `OrchestrationResultNormalization` | Canonicalize the result contract. |
| 70 | `MetadataBuilding` | Add safe request and processing metadata. |
| 80 | `SmartSuggestions` | Add optional next-step suggestions. |
| 90 | `ResponseSanitization` | Sanitize result content and construct the client payload. |
| 95 | `ConversationRecording` | Optional chat-session turn and bounded target persistence. |
| 100 | `HistoryPersistence` | Record intent-processing history for analytics and audit. |

## Final Ownership Reference - Do Not Narrate As A List

| Concern | Owner |
| --- | --- |
| HTTP authentication and trusted tenant/user context | Spring Boot application |
| Pipeline order and framework control flow | AI Fabric core and installed capability modules |
| Allowed modes, actions, retrieval spaces, and budgets | Server configuration plus application policy |
| Natural-language intent understanding | Configured LLM through AI Fabric orchestration purpose |
| Query embeddings | Configured `EmbeddingProvider` |
| Vector retrieval | Configured `VectorDatabaseService` implementation |
| Domain read or write side effect | Registered application action handler and domain service |
| Pending confirmation and conversation turns | AI Fabric chat-session module when installed |
| Public API projection and UI presentation | Spring Boot application and its UI |

## Accuracy Guardrails For NotebookLM

- Keep the current AI Fabric request lifecycle as the subject.
- State that the Spring Boot application owns HTTP entry, authentication, trusted context, and public
  response projection.
- Do not describe browser-supplied user, tenant, action, target, history, or confirmation state as
  trusted authority.
- Use the pipeline step names and order exactly as listed in this script.
- Mark chat-session steps as optional; do not imply that core alone persists provider-native chat
  turns or pending confirmation across requests.
- Mark `PIIDetectionStep` and `ComplianceCheckStep` as optional module-contributed steps. PII requires
  its module and input detection configuration. Compliance requires governance, its compliance
  feature, and a `ComplianceCheckProvider`.
- Do not imply that every step performs work for every request. Steps can skip by condition, module,
  mode, intent, termination state, or missing conversation context.
- Do not call request position an orchestration mode.
- Do not imply that the LLM chooses capabilities outside the server-resolved policy.
- Do not imply that chat history is concatenated into the semantic embedding query.
- Do not imply that target or vector-space clarification is a framework failure.
- Do not imply that an LLM directly invokes a repository or owns a domain side effect.
- Do not imply that action execution itself requires an LLM call.
- Do not describe `CONFIRMATION_REQUIRED` as a successful action execution.
- State that production two-turn confirmation requires backend conversation and pending-action state.
- Do not imply that Spring AI supplies AI Fabric vector lifecycle behavior.
- Do not invent APIs, endpoints, properties, result fields, enum values, pipeline stages, provider
  calls, execution output, metrics, compliance claims, or customer outcomes.
- Do not present a generated video as runtime or test evidence.
