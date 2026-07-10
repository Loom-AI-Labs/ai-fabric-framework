# Default Curated Prompt Optimization Backlog

Status: proposed  
Scope: generalized prompt improvements discovered while hardening the five public demo apps.

This document captures prompt behavior that is useful beyond one demo app and can be promoted into
`ai-infrastructure-module/curated/ai-fabric-curated-default`. Domain-specific wording should stay in
domain packs such as `ai-fabric-curated-commerce`, `ai-fabric-curated-support`, or app-level prompt overlays.

## Source Evidence

The current demo prompt hardening produced these reusable patterns:

- Shopping demo added recent-turn follow-up handling for short shopper queries such as "better for gaming?",
  "compare those", and "which one is best?".
  Source: `examples/real-apps/chat-capabilities-demo/src/main/resources/prompts/intent-extraction/multi-step/v1-shopping-demo/classify.md`
- Account Resolver added first-person owned-resource handling, "do not ask for internal ids", policy-as-guidance
  language, and immediate conversation reference resolution for "ok add it", "do that", and "fix it".
  Sources:
  - `examples/real-apps/ai-fabric-account-resolver/src/main/resources/prompts/intent-extraction/multi-step/v1-account-resolver/classify.md`
  - `examples/real-apps/ai-fabric-account-resolver/src/main/resources/prompts/rag/generation/v1-account-resolver/answer.md`
- Behavior Signals strengthened the default behavior analysis pattern with previous-analysis baseline,
  new-events delta analysis, recovery evidence, conflict handling, and a required `insights.action_family`.
  Source: `examples/real-apps/behavior-churn-signals/src/main/resources/prompts/behavior/analysis/v1-behavior-signals/system.md`
- Behavior Signals Agentic UI introduced a reusable allowlisted-catalog structured-output pattern:
  provide component names/descriptions/uses, ask the LLM for names and reasons only, validate strictly,
  and fail visibly when the structured result is invalid.
  Source: `examples/real-apps/behavior-churn-signals/src/main/java/com/ai/fabric/realapps/behavior/service/AgenticUiComposerService.java`
- Tenant Guard introduced secure evidence-only generation with backend-filtered evidence and exact evidence ids.
  Source: `examples/real-apps/tenant-knowledge-portal/src/main/java/com/ai/fabric/realapps/tenantportal/service/TenantKnowledgeService.java`
- Privacy Shield does not contribute generation prompts. It contributes a product lesson instead: privacy demos
  can prove AI Fabric value without LLM generation by using PII detection, redaction, indexing, and retrieval.
  Source: `examples/real-apps/privacy-first-customer-facing-support/src/main/resources/application.yml`

## P0: Promote To Default Curated

### 1. Recent Conversation Follow-Up Handling

Target prompts:

- `prompts/intent-extraction/multi-step/v1/classify.md`
- `prompts/intent-extraction/compound/v1/system.md`
- `prompts/intent-extraction/compound/v1/system-managed.md`

Generalized rule:

- If the current request is short, elliptical, or clearly depends on prior wording, inspect the latest relevant
  exchange up to the last three user/assistant messages already provided to the prompt.
- If recent messages identify a comparison set, support issue, account issue, document, or recommended next step,
  classify the follow-up in that context instead of `OUT_OF_SCOPE`.
- Do not use conversation history to invent executable identifiers, private data, facts, or action parameters.
- For write actions, require explicit current input, attachments, pinned targets, pending confirmation, or a
  missing-parameter flow.

Why default:

This is not commerce-specific. It fixes common natural chat behavior for any AI Fabric app that persists chat
turns through `ai-fabric-chat-session`.

Test expectations:

- Add default curated prompt tests asserting the classifier template includes recent-turn follow-up rules.
- Add core intent extraction tests where "better option?", "do that", and "what about it?" do not become
  `OUT_OF_SCOPE` when recent context is present.

Final prompt text to add:

Target:

- `prompts/intent-extraction/multi-step/v1/classify.md`
- `prompts/intent-extraction/compound/v1/system.md`
- `prompts/intent-extraction/compound/v1/system-managed.md`

Add this block after the attachment/pinned-target rules and before generic target-resolution rules:

```text
- Recent conversation follow-ups:
  - If the current USER REQUEST is short, elliptical, or depends on prior wording
    (for example "what about it?", "better option?", "compare those", "do that",
    "fix it", "continue", "which one is best?", or "what about below 2000?"),
    inspect the most recent conversation messages already provided to you.
  - Use only the latest relevant exchange, up to the last 3 user/assistant messages,
    to resolve what the user is continuing to ask about.
  - If recent messages show a working set, comparison set, recommended next step,
    blocker explanation, action result, document, record, or answer that the user is
    clearly continuing, classify the follow-up in that context instead of OUT_OF_SCOPE.
  - Prefer answering from recent conversation context when it contains enough explicit
    facts: set requiresRetrieval=false and requiresGeneration=true.
  - If indexed evidence is still needed, set requiresRetrieval=true and build
    optimizedQuery from the current follow-up plus the relevant recent topic.
  - Do not use recent conversation text to invent identifiers, private data, status
    facts, numeric facts, or executable action parameters.
  - For write actions, still require explicit current input, active attachments,
    pinned targets, pending confirmation, or the missing-parameter flow.
```

For compound `system.md` and `system-managed.md`, use the same text but replace
`actionHint` references with the compound schema's `action` language if needed.

### 2. Backend-Owned Identifier Rule

Target prompts:

- `prompts/intent-extraction/multi-step/v1/classify.md`
- `prompts/intent-extraction/multi-step/v1/fill-params.md`
- `prompts/orchestration/read-action-resolution/v1/user.md`

Generalized rule:

- Do not ask users for internal identifiers such as tenant id, account id, user id, subscription id, hidden ticket id,
  database id, or trace id when a trusted backend context, current session, typed system-owned parameter source,
  or action resolver is expected to provide it.
- Do not fill private or owned-resource params unless the schema explicitly accepts user-provided values or the
  value is present in trusted context/attachments.

Why default:

Every domain can have current-user/current-tenant/current-record context. Asking external users for backend-owned
ids makes demos and production apps feel broken and leaks implementation shape.

Test expectations:

- Add prompt packaging assertions.
- Add fill-param tests that missing backend-owned ids are omitted, not fabricated and not requested as user fields.

Final prompt text to add:

Target:

- `prompts/intent-extraction/multi-step/v1/classify.md`
- `prompts/intent-extraction/compound/v1/system.md`
- `prompts/intent-extraction/compound/v1/system-managed.md`

Add this block near action-selection rules:

```text
- Backend-owned identifier rule:
  - Do not ask the user for internal identifiers such as userId, accountId, tenantId,
    subscriptionId, organizationId, sessionId, database id, hidden record id, trace id,
    or provider/runtime id when those values are expected to come from trusted backend
    context, the current session, an authenticated subject, a typed system-owned
    parameter source, or a configured action resolver.
  - If a request is clearly about the current user's/current tenant's/current session's
    owned resource and an available action can use trusted context, classify the action
    with requiresTargetResolution=false unless the user explicitly refers to a separate
    external attached/pinned target.
  - If the chosen action is missing user-supplied fields, omit those fields. The
    backend missing-parameter flow should ask only for user-owned values, not internal ids.
```

Target:

- `prompts/intent-extraction/multi-step/v1/fill-params.md`

Add this block after the existing "only set a parameter" rules:

```text
- Do NOT fill backend-owned, private, owned-resource, tenant, account, subject,
  session, provider, runtime, database, or trace parameters unless the value is
  explicitly present in trusted ATTACHMENTS/pinned targets, a typed system-owned
  parameter source exposed in the action schema, or the action schema clearly accepts
  that value from the user.
- Do NOT ask the user to provide backend-owned identifiers. If such a required
  parameter cannot be populated from trusted context, omit it so backend validation
  can fail safely or a domain resolver can provide it.
```

Target:

- `prompts/orchestration/read-action-resolution/v1/user.md`

Add this block near private/user-owned resource rules:

```text
- For current-user/current-tenant/current-session resource reads, select an eligible
  READ action only when the action schema indicates it can use trusted context or when
  the request/context supplies the required owned-resource identifier.
- Never ask the user for internal action parameters such as userId, accountId, tenantId,
  sessionId, hidden database ids, provider ids, or trace ids.
```

### 3. Policy And Runbook Text Is Guidance, Not Schema

Target prompts:

- `prompts/intent-extraction/multi-step/v1/classify.md`
- `prompts/intent-extraction/multi-step/v1/fill-params.md`
- `prompts/rag/generation/v1/answer.md`
- `prompts/rag/generation/v1/answer-managed.md`

Generalized rule:

- Policy, runbook, troubleshooting, known-issue, and guidance documents can explain decisions and suggest governed
  next steps.
- They must not be treated as executable schemas.
- The LLM must not invent action parameters from policy text.

Why default:

AI Fabric encourages RAG + actions. This rule prevents the LLM from turning explanatory documents into fake action
input.

Test expectations:

- Add a test with a policy document that names an action but no required params; the intent can choose the action,
  but fill-params must omit missing user-owned fields.

Final prompt text to add:

Target:

- `prompts/intent-extraction/multi-step/v1/classify.md`
- `prompts/intent-extraction/compound/v1/system.md`
- `prompts/intent-extraction/compound/v1/system-managed.md`

Add this block near knowledge-base/RAG rules:

```text
- Policy/guidance document rule:
  - Policy, runbook, troubleshooting, known-issue, procedure, and guidance documents
    can explain decisions, constraints, and recommended next steps.
  - Treat those documents as human-readable guidance, not executable schemas.
  - Do not invent action parameters from policy/guidance text.
  - If a policy/guidance document recommends an available action but required
    user-supplied fields are missing, choose the action only when the user request
    clearly asks for it, and leave missing fields empty for the missing-parameter flow.
```

Target:

- `prompts/intent-extraction/multi-step/v1/fill-params.md`

Add this block after the parameter fabrication guardrails:

```text
- Do not copy policy, runbook, procedure, or guidance wording into executable
  action parameters unless the user explicitly supplied that value or the action
  schema says that parameter is a free-text reason/description.
- A policy/guidance document may justify why an action is appropriate, but it must
  not supply hidden ids, amounts, dates, statuses, customer facts, or other required
  executable values.
```

Target:

- `prompts/rag/generation/v1/answer.md`
- `prompts/rag/generation/v1/answer-managed.md`

Add this block near source-of-truth guidance:

```text
Policy, runbook, troubleshooting, known-issue, procedure, and guidance documents
explain rules and recommended handling. Do not expose them as schemas, action
contracts, metadata, or implementation labels. Do not infer missing live facts or
missing action parameters from guidance text alone.
```

### 4. Live Read-Action Facts Override Retrieved Guidance

Target prompts:

- `prompts/rag/generation/v1/answer.md`
- `prompts/rag/generation/v1/answer-managed.md`
- `prompts/orchestration/post-action-generation/v1/user-generic.md`
- `prompts/orchestration/post-action-generation/v1/user-generic-managed.md`

Generalized rule:

- When context contains live READ ACTION EVIDENCE or action facts, those facts are source of truth for fields they
  explicitly contain.
- Retrieved policy/guidance explains why a fact matters; it must not override current live state.
- Do not claim a requirement/status is missing when live facts say it is satisfied.

Why default:

This is core AI Fabric behavior. Apps often combine RAG policy docs with live state reads.

Test expectations:

- Add prompt tests for source-of-truth wording.
- Add RAG generation tests with conflicting policy guidance and live facts; output should follow live facts.

Final prompt text to add:

Target:

- `prompts/rag/generation/v1/answer.md`
- `prompts/rag/generation/v1/answer-managed.md`

Add or replace the existing live-facts wording with:

```text
When the context includes READ ACTION EVIDENCE, live action facts, or current
resource state, use those facts as the source of truth for fields they explicitly
contain. Retrieved documents explain why a fact matters, but they must not override
current live facts. Do not claim a requirement, status, blocker, capability, or
resource field is missing when live facts say it is satisfied or present. If live
facts and retrieved guidance appear to conflict, answer from live facts and use the
guidance only to explain the rule or next step.
```

Target:

- `prompts/orchestration/post-action-generation/v1/user-generic.md`
- `prompts/orchestration/post-action-generation/v1/user-generic-managed.md`
- `prompts/orchestration/post-action-generation/v1/user-relationship-query.md`
- `prompts/orchestration/post-action-generation/v1/user-relationship-query-managed.md`

Add this block near `FACTS` source-of-truth rules:

```text
Use FACTS as the source of truth for fields they explicitly contain. Do not override
FACTS with generic policy, guidance, prior conversation text, or retrieved documents.
If FACTS show a requirement/status/resource field is satisfied or present, do not
describe it as missing. If FACTS are incomplete, state only the missing evidence;
do not infer the missing fact from guidance text.
```

### 5. Behavior Analysis Baseline + New Events

Target prompts:

- `prompts/behavior/analysis/v1/system.md`
- `prompts/behavior/analysis/v1/user.md`

Generalized rule:

- Treat previous analysis as baseline state when present.
- Treat new events as fresh evidence since the last insight.
- Later positive recovery events can reduce risk but should not erase unresolved risk automatically.
- Later negative events can reverse a healthy baseline.
- Require concise, bounded JSON strings and do not copy raw event JSON.

Why default:

The current default behavior prompt is too generic. The Behavior Signals demo uncovered the more useful real-world
pattern: insight evolution, not one-shot classification.

Test expectations:

- Add behavior prompt packaging tests for baseline/new-events language.
- Add behavior analysis service tests for recovery-after-risk and negative-after-healthy scenarios.

Final prompt text to add:

Target:

- `prompts/behavior/analysis/v1/system.md`

Replace the current generic behavior system prompt with:

```text
You are an AI behavior analyst.

Analyze the user's behavior events and produce one compact JSON object. Use the
event evidence only.

Reasoning rules:
- Treat previous analysis as the baseline state when present.
- Treat new events as fresh evidence since that previous analysis.
- Weigh event recency and direction. Later positive recovery events can reduce risk,
  improve sentiment, and move trend to IMPROVING when they show real behavior change.
- Do not erase unresolved risk just because one positive event appears. Explain the
  remaining risk if negative and positive signals conflict.
- Later repeated negative events can reverse an earlier healthy baseline.
- Never invent events, policies, account state, product state, or outcomes that are
  not present in the evidence.

Required output fields:
- segment: short snake_case string
- patterns: array of 3 to 6 short strings
- sentiment: object with score from -1.0 to 1.0 and label exactly one of
  DELIGHTED, SATISFIED, NEUTRAL, CONFUSED, FRUSTRATED, CHURNING
- churn: object with risk from 0.0 to 1.0 and reason as one short sentence
- trend: exactly one of RAPIDLY_IMPROVING, IMPROVING, STABLE, DECLINING,
  RAPIDLY_DECLINING, NEW_USER
- recommendations: array of 2 to 4 short operator actions
- insights: flat JSON object with concise primitive values or arrays only; include
  action_family when the evidence supports one
- confidence: number from 0.0 to 1.0

JSON contract:
- Return only valid JSON. No markdown, prose, comments, or trailing commas.
- Do not include unescaped quote characters inside strings.
- Keep every string under 140 characters.
- Do not copy raw event JSON into the response.
```

Target:

- `prompts/behavior/analysis/v1/user.md`

Replace the current user prompt with:

```text
{{user_context_section}}
{{previous_analysis_section}}
=== NEW EVENTS ({{events_count}}) ===
{{new_events_lines}}

Analyze how this user's behavior has evolved.
Use previous analysis as the starting point when present, then update it based on
the new event sequence.
If new events are mostly positive recovery signals after a negative baseline, show
whether risk is resolved, partially reduced, or still urgent.
If new events are mostly negative after a healthy baseline, reflect the deterioration
in churn.risk, sentiment, trend, patterns, recommendations, and insights.
For mixed evidence, keep the analysis balanced: name both remaining blockers and
recovery signals.

Return one compact JSON object only. Prefer concise evidence summaries over long
explanations. If this is the first analysis for the user, set trend to NEW_USER
unless the event sequence clearly shows rapid improvement or decline.
```

## P1: Add As Reusable Patterns Or Optional Variants

### 6. Secure Evidence-Only RAG With Citations

Potential target:

- New prompt variant under `prompts/rag/generation/v1/answer-cited.md`, or a managed prompt option.

Generalized rule:

- Backend filters evidence by tenant/session/visibility before prompting.
- The LLM answers only from allowed evidence and cites exact evidence ids.
- If evidence is insufficient, say the allowed evidence does not answer.

Why not immediate default:

Some apps do not want citation syntax in normal chat. This is best as a reusable variant or curated pack overlay,
not a mandatory default.

### 7. Allowlisted Catalog Selection For Agentic UI And Similar Planners

Potential target:

- New structured JSON prompt family such as `prompts/structured-json/catalog-selection/v1`.

Generalized rule:

- Backend supplies an allowlisted catalog with name, description, and use cases.
- The LLM returns a short ordered list of allowed names plus short reasons only.
- Backend validates names and fails visibly if the response is invalid.

Why not immediate default:

It is a strong framework pattern, but it is not the same as default chat intent extraction. It should be reusable
without being globally applied.

### 8. Structured Intelligence Must Not Hide LLM Failure

Potential target:

- Structured JSON repair prompts and structured call docs/tests.

Generalized rule:

- Repair malformed JSON when safe.
- If required structured intelligence remains invalid after repair, surface the failure instead of returning a
  deterministic fallback that looks like LLM insight.

Why not just prompt:

This is both prompt guidance and runtime behavior. It belongs in tests and docs as much as prompt templates.

## P2: Domain Pack Promotions

These should not go to default, but should be captured in domain packs:

- Commerce-specific follow-up examples and shopper-safe out-of-scope wording belong in `ai-fabric-curated-commerce`.
- Support-specific troubleshooting, case/ticket, runbook, and operator-workflow wording belongs in
  `ai-fabric-curated-support`.
- Account Resolver readiness facts should stay in app-level prompts until a first-class account-resolution curated
  pack exists.
- Tenant Guard action names, tenant roles, and document archive workflows should stay in Tenant Guard app prompts.
- Privacy Shield should stay mostly promptless unless a future privacy-curated pack needs generation-safe wording.

## Implementation Order

1. Promote P0.1 recent-turn handling to default classifier and compound prompts.
2. Promote P0.2 backend-owned identifier rules to classifier, fill-params, and read-action planner.
3. Promote P0.3 policy/runbook-as-guidance rules to classifier/fill/RAG prompts.
4. Promote P0.4 live-facts-source-of-truth rules to RAG and post-action generation.
5. Promote P0.5 behavior baseline/new-events prompt.
6. Add P1 variants only after a design pass for prompt-family naming and backwards compatibility.

## Non-Goals

- Do not make default prompts mention demo names, products, subscription plans, tenant roles, or component names.
- Do not make default prompts execute actions through prompt wording. Actions still flow through registered handlers,
  confirmation, access control, and backend validation.
- Do not add deterministic text fallbacks that hide missing LLM intelligence.
