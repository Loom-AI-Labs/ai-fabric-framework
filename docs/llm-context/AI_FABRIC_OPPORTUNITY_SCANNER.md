# AI Fabric Opportunity Scanner

Use this guide when a user asks an AI coding assistant to look at an existing Java/Spring Boot
application and identify what AI Fabric can unlock.

This is a discovery guide. Do not edit application code during the scan unless the user explicitly
asks for implementation after reviewing the opportunities.

## Goal

Given a Java application, produce a code-backed opportunity report that answers:

- where AI Fabric can create product value;
- which application files prove the opportunity exists;
- which AI Fabric modules are needed;
- what the smallest useful proof of concept should be;
- what tests are required before the change is trusted;
- which risks need product or engineering decisions.

## When To Use This

Use this guide for requests like:

- "How can AI Fabric help this app?"
- "Review this Java app and suggest AI features."
- "Find AI Fabric opportunities in this Spring Boot project."
- "What demos or workflows can we build from this application?"
- "Help my coding assistant understand how to AI-enable this app."

If the user already chose a specific feature, use the matching guide instead:

- semantic search: `docs/getting-started/03-first-semantic-search.md`
- RAG: `docs/getting-started/04-first-rag-chat.md`
- actions: `docs/getting-started/05-first-governed-action.md`
- chat memory: `docs/getting-started/06-chat-session-memory.md`
- providers: `docs/getting-started/07-real-provider-openai.md`
- tenant/security: `docs/getting-started/10-security-access-policy.md`
- tests/release: `docs/getting-started/11-testing-and-verification.md`

## Non-Negotiable Rules

- Do not invent capabilities that are not supported by the application code or AI Fabric modules.
- Do not claim a workflow is RAG-backed unless indexed evidence exists or the plan includes indexing.
- Do not suggest frontend keyword matching as an AI feature.
- Do not hide LLM/provider failure behind silent deterministic fallbacks.
- Do not let the LLM decide tenant, owner, account, or authorization boundaries.
- Do not ask users for identifiers the backend can resolve from authenticated context.
- Do not suggest side-effect actions without confirmation, access checks, and tests.
- Keep policies as user-friendly domain text unless the app explicitly needs machine-only policy files.
- Cite local files/classes as evidence for every high-confidence opportunity.

## Minimum Context To Gather

Read only enough source to classify the app. Start with fast file discovery.

```bash
rg --files
rg -n "@Entity|@Table|@RestController|@Controller|@Service|@Repository|SecurityFilterChain|tenant|owner|account|userId|event|audit|message|chat|ticket|order|cart|subscription|payment|policy|document|knowledge"
```

Then inspect:

| Area | What to look for | Why it matters |
| --- | --- | --- |
| Build files | `pom.xml`, modules, Java/Spring Boot versions | Confirms AI Fabric compatibility and module boundaries. |
| Domain entities | `@Entity`, aggregate roots, document-like fields | Reveals searchable/RAG candidates. |
| Repositories | query methods, filters, tenant/user predicates | Reveals retrieval boundaries and relationship-query potential. |
| Controllers | public APIs, user workflows, request/response DTOs | Reveals natural-language entry points and action surfaces. |
| Services | business operations and validation rules | Reveals `@AIAction` candidates. |
| Security | auth principal, roles, tenant/account resolution | Determines access policy and metadata filtering requirements. |
| Events/logs | raw activity, audit, support events, product usage | Reveals behavior intelligence opportunities. |
| Existing docs/policies | markdown, CMS tables, FAQs, terms, product docs | Reveals RAG/policy retrieval candidates. |
| Tests | unit, integration, smoke profiles, Testcontainers | Determines safe implementation path. |

## Opportunity Signals

Use these signals to map app shape to AI Fabric value.

| Code or product signal | AI Fabric opportunity | Typical modules |
| --- | --- | --- |
| Entities with names, descriptions, notes, reviews, policies, tickets, articles | Semantic search | `ai-fabric-starter`, embedding provider, vector provider, optional `ai-fabric-indexing` |
| Users ask questions that should be answered from app-owned evidence | RAG answers | semantic search stack, `ai-fabric-rag`, LLM provider |
| Existing service methods update app state | Governed actions | `ai-fabric-core`, `@AIAction`, optional `ai-fabric-chat-session` |
| Users say "yes", "do it", "compare those", "add it", or "continue" | Chat memory and pending actions | `ai-fabric-chat-session` |
| App handles emails, phones, SSNs, payment details, support notes, health/legal text | PII and privacy guard | `ai-fabric-pii`, sanitized indexing/search |
| Multi-tenant app or role-based visibility | Tenant-safe retrieval | vector provider metadata filters, access policy implementation |
| Raw product events, renewals, cancellations, usage logs, failed payments | Behavior insights | `ai-fabric-behavior`, LLM provider |
| UI should adapt to user risk, loyalty, confusion, or next-best action | Agentic UI | `ai-fabric-behavior`, structured output, allowlisted components |
| Users ask natural-language questions over relational data | Relationship query | `ai-fabric-relationship-query`, LLM provider, schema metadata |
| Data must be indexed from existing DB rows or external APIs | Migration/backfill/data sync | `ai-fabric-migration`, `ai-fabric-data-sync`, indexing/vector provider |
| App needs local/no-key dev mode | Smoke profile | memory vector provider, smoke provider, local fixtures |
| App needs local embeddings or private retrieval | ONNX embeddings | `ai-fabric-onnx-starter`, vector provider |

## Opportunity Catalog

### 1. Semantic Search

Use when users need to find records by meaning instead of exact filters.

Look for:

- product descriptions;
- support ticket text;
- knowledge base articles;
- customer notes;
- policies;
- reviews;
- documents;
- catalog items.

Recommend:

- annotate domain objects with `@AICapable`;
- mark important text with `@AISearchable`;
- add useful non-ranking context with `@AIContext`;
- configure indexing in `ai-entity-config.yml` when annotation-only mapping is not enough;
- select a vector provider.

Smallest POC:

1. Pick one entity type.
2. Index 10 to 50 realistic records.
3. Add one search endpoint or test.
4. Verify synonym-style queries return relevant records.

Tests:

- indexing test proves records are written;
- semantic query test with wording not found verbatim in records;
- empty-index behavior test.

### 2. RAG Answers

Use when users need natural-language answers grounded in app evidence.

Look for:

- questions currently answered manually by support or internal staff;
- docs/policies users need to understand;
- records that require synthesis across multiple fields;
- answer screens that need citations or retrieved evidence.

Recommend:

- semantic search stack;
- `ai-fabric-rag`;
- LLM provider, usually `ai-fabric-provider-spring-ai`;
- evidence display in UI or diagnostics.

Smallest POC:

1. Index one evidence source.
2. Ask one question that requires the indexed evidence.
3. Show retrieved documents, scores, and the generated answer.
4. Test no-evidence behavior.

Tests:

- answer includes app-owned facts;
- no-evidence query fails honestly or asks for more information;
- tenant/user filters are applied before evidence reaches the LLM.

### 3. Read Actions

Use when the assistant should inspect current app state before answering.

Look for:

- account readiness;
- cart status;
- subscription status;
- order status;
- invoice state;
- profile completeness;
- entitlement checks.

Recommend:

- `@AIAction` with `ActionAccessMode.READ`;
- backend-resolved current user/account/tenant;
- concise domain-shaped result;
- optional RAG/policy follow-up.

Smallest POC:

1. Add one read action that returns current user state.
2. Let AI explain the state against one policy or domain rule.
3. Verify the UI shows a friendly result, not raw JSON.

Tests:

- authenticated context is used;
- user cannot request another user's data by ID;
- result fields are stable and UI-friendly.

### 4. Governed Write Actions

Use when the assistant can do app work, but the application must stay in control.

Look for service methods like:

- add to cart;
- checkout;
- update payment method;
- update address;
- cancel subscription;
- create support ticket;
- request refund;
- change plan;
- invite user;
- approve workflow.

Recommend:

- `@AIAction`;
- `ActionAccessMode.WRITE`;
- `requiresConfirmation = true`;
- server-side identity/tenant resolution;
- validation in normal app services;
- domain-shaped action result.

Smallest POC:

1. Add one side-effect action.
2. Require confirmation.
3. Test confirm and reject.
4. Return a clean user-facing result.

Tests:

- missing parameter flow;
- confirmation required;
- confirmation accepted;
- confirmation rejected;
- access denied;
- service validation failure;
- post-action answer does not expose raw internal data.

### 5. Chat Memory

Use when follow-up turns must refer to prior context.

Look for flows where users say:

- "yes";
- "add it";
- "compare those";
- "use the second one";
- "continue";
- "why?";
- "fix that";
- "do the same for this account."

Recommend:

- `ai-fabric-chat-session`;
- stable `conversationId`;
- owner/current-user checks;
- server-side chat turns instead of UI-built history prompts.

Smallest POC:

1. Ask a question that returns a suggestion or pending action.
2. Follow with "yes" or "add it".
3. Verify the framework resolves the follow-up from stored chat state.

Tests:

- follow-up action resolution;
- owner mismatch;
- deletion/cleanup;
- old conversation cannot confirm a new user's action.

### 6. PII And Privacy Guard

Use when users may paste sensitive information into AI flows.

Look for:

- support text boxes;
- chat intake;
- notes;
- medical/legal/financial data;
- emails/phones/SSNs;
- payment-like input;
- logs indexed for search.

Recommend:

- `ai-fabric-pii`;
- redaction before persistence/indexing when required;
- encrypted original only when product policy allows it;
- safe search over sanitized records;
- explicit UI proof that raw input is withheld.

Smallest POC:

1. Submit a message containing sensitive text.
2. Detect and redact it.
3. Store only the allowed representation.
4. Search with a sensitive query and prove sanitized search behavior.

Tests:

- detection;
- redacted storage;
- raw input withheld;
- sanitized search;
- mode-specific behavior such as redact, reject, or pass-through.

### 7. Tenant-Safe Retrieval

Use when multiple tenants, workspaces, teams, or accounts share infrastructure.

Look for:

- `tenantId`;
- `workspaceId`;
- `organizationId`;
- role-based visibility;
- owner filters;
- shared vector index;
- "restricted" or "private" flags.

Recommend:

- trusted tenant context from backend auth;
- metadata stored with indexed records;
- vector provider metadata filtering;
- post-retrieval verification;
- fail-closed behavior when metadata filtering is unavailable.

Smallest POC:

1. Seed two tenants with similar records.
2. Query as tenant A.
3. Prove tenant B evidence never reaches the LLM.
4. Test restricted visibility.

Tests:

- tenant filter applied;
- role visibility respected;
- cross-tenant query returns no forbidden evidence;
- deletion cleanup is tenant-scoped;
- provider diagnostics show needed metadata capabilities.

### 8. Behavior Insights

Use when raw app activity should become user or account intelligence.

Look for:

- login events;
- product usage;
- failed renewal/payment events;
- support contacts;
- cancellation attempts;
- feature adoption;
- repeated errors;
- positive/negative feedback.

Recommend:

- `ai-fabric-behavior`;
- raw event ingestion;
- LLM-backed insight generation;
- no deterministic fallback that hides LLM analysis failure;
- stored insight with evidence references.

Smallest POC:

1. Store raw events for one user/account.
2. Run behavior analysis.
3. Show sentiment, risk, reason, and evidence.
4. Add new events and re-run analysis using previous insight plus new events.

Tests:

- raw events remain raw;
- LLM failure surfaces visibly;
- positive events can reduce risk;
- negative events can increase risk;
- insight references the event evidence used.

### 9. Agentic UI

Use when the UI should adapt based on AI-derived user insight.

Look for:

- home dashboards;
- onboarding pages;
- upgrade/cancel flows;
- retention surfaces;
- education panels;
- support shortcuts;
- plan recommendation widgets.

Recommend:

- behavior insight as input;
- allowlisted components with short descriptions;
- LLM returns component names and reasons in structured output;
- UI renders only allowed components.

Smallest POC:

1. Define 5 to 8 allowed components with user-friendly descriptions.
2. Feed current insight and component list to the LLM.
3. Render the selected components for a user preview.
4. Re-run after new events change the insight.

Tests:

- invalid component names are rejected;
- LLM failure is visible;
- rendered components match returned names;
- no hidden deterministic fallback pretends to be AI.

### 10. Relationship Query

Use when users ask analytical questions over relational data.

Look for:

- CRM/account/order schemas;
- reporting screens;
- ad hoc filters;
- multi-table questions;
- support or sales questions that require joins.

Recommend:

- `ai-fabric-relationship-query`;
- explicit relationship schema;
- constrained query execution;
- structured failure handling.

Smallest POC:

1. Register a small relationship schema.
2. Ask one natural-language query that maps to safe filters/joins.
3. Show query plan and result.

Tests:

- valid query;
- ambiguous query;
- forbidden field/table;
- empty result;
- query plan repair if enabled.

### 11. Migration, Backfill, And Data Sync

Use when existing app data needs to become searchable or RAG-ready.

Look for:

- seed data;
- import jobs;
- admin reindex endpoints;
- external APIs;
- large existing tables;
- stale vector data.

Recommend:

- `ai-fabric-indexing`;
- `ai-fabric-migration`;
- `ai-fabric-data-sync` when HTTP/API sync is required;
- index readiness checks.

Smallest POC:

1. Backfill one entity type.
2. Verify count/index diagnostics.
3. Run one retrieval query.
4. Add cleanup/reindex path.

Tests:

- initial backfill;
- idempotent re-run;
- update/delete propagation;
- readiness state when vector provider is unavailable.

## Ranking Opportunities

Score each opportunity before recommending an implementation order.

| Factor | 1 | 3 | 5 |
| --- | --- | --- | --- |
| User value | Nice-to-have | Removes friction | Core product workflow |
| Code evidence | Weak or inferred | Several matching files | Clear service/entity/controller path |
| Implementation effort | Large unknown | Moderate | Small POC possible |
| Risk | High-risk data/action | Manageable | Read-only or low blast radius |
| Demo value | Hard to show | Useful | Easy to show live |
| Testability | Hard to test | Some tests possible | Deterministic smoke tests possible |

Suggested priority:

- **P0:** high value, strong code evidence, small POC, testable.
- **P1:** high value but needs provider, policy, or data-shaping work.
- **P2:** advanced or strategic, but not the first implementation.
- **Not now:** weak evidence, high risk, or likely to distract from a simpler proof.

## Required Output Format

Return a report in this shape.

```md
# AI Fabric Opportunity Report

## App Summary

- App type:
- Main domain objects:
- Main user workflows:
- Current AI Fabric usage, if any:
- Auth/tenant model:
- Data sources that could become AI evidence:

## Top Opportunities

| Priority | Opportunity | Why it matters | Code evidence | AI Fabric modules | Smallest POC |
| --- | --- | --- | --- | --- | --- |
| P0 | ... | ... | `path/File.java` | ... | ... |

## Recommended First POC

- User journey:
- Modules:
- Data to index or actions to expose:
- Endpoint/UI change:
- Tests:
- Needs real LLM key: yes/no
- Needs vector provider: yes/no

## Implementation Notes

- Existing code to reuse:
- Policies/access rules:
- Action confirmation rules:
- Data privacy concerns:
- Tenant/user boundary:

## Risks And Questions

- ...

## Not Recommended Yet

- Opportunity:
- Reason:
```

## Assistant Prompt To Run The Scan

Use this prompt when handing a repository to a coding assistant.

```md
You are reviewing a Java/Spring Boot application for AI Fabric opportunities.

Do not edit code yet.

First read:
- docs/llm-context/AI_FABRIC_OPPORTUNITY_SCANNER.md
- docs/llm-context/AI_FABRIC_RULES_FOR_CODING_ASSISTANTS.md
- docs/llm-context/AI_FABRIC_CAPABILITY_MAP.md
- docs/getting-started/01-choose-your-path.md
- docs/getting-started/12-real-apps-map.md

Then inspect the application source and produce an AI Fabric Opportunity Report.

For every recommended opportunity:
- cite the files/classes that prove the app has this shape;
- map the opportunity to AI Fabric modules;
- propose the smallest useful POC;
- list tests required before implementation;
- mark whether real LLM keys, embeddings, vector storage, chat memory, PII, tenant filters, or actions are needed.

Do not recommend frontend keyword matching.
Do not invent indexed evidence.
Do not hide LLM/provider failures behind fallbacks.
Do not let the LLM decide tenant, owner, account, or authorization boundaries.
```

## Implementation Prompt After The User Chooses A POC

Use this only after the user picks one opportunity.

```md
Implement the selected AI Fabric POC.

Before editing:
- restate the chosen opportunity;
- identify the AI Fabric modules involved;
- identify the application services/entities/controllers touched;
- identify the tests that will protect behavior.

During implementation:
- keep existing domain services in charge;
- use AI Fabric for orchestration, retrieval, actions, memory, privacy, or behavior;
- require confirmation for side effects;
- use backend-resolved identity/tenant/account context;
- return domain-shaped results, not raw internal JSON.

After implementation:
- run focused tests;
- run smoke profile if available;
- document any unverified real-provider behavior.
```

## Fast Heuristics

Use these when the app is large and the user needs a quick first pass.

| If you see | First opportunity to evaluate |
| --- | --- |
| `Product`, `Catalog`, `Review`, `Cart`, `Order` | Commerce RAG plus cart/checkout actions |
| `Subscription`, `Invoice`, `PaymentMethod`, `Refund` | Account resolver with read actions and confirmed writes |
| `Ticket`, `FAQ`, `Article`, `KnowledgeBase` | Support RAG plus PII guard |
| `Tenant`, `Workspace`, `Organization`, `Role` | Tenant-safe retrieval |
| `Event`, `Usage`, `Login`, `Renewal`, `Churn` | Behavior insights and agentic UI |
| `Customer`, `Contact`, `Deal`, `Interaction` | Relationship query and CRM insights |
| `Document`, `File`, `Attachment` | Document indexing and RAG ingestion |
| `Audit`, `Compliance`, `Policy` | Governed actions, policy retrieval, audit evidence |

## Evidence Quality Rules

Use these labels in the report:

- **Code-backed:** direct source files prove the opportunity.
- **Config-backed:** configuration proves a module/provider/data source exists.
- **Test-backed:** tests or real apps prove the behavior.
- **Inferred:** likely opportunity, but source evidence is incomplete.
- **Planned:** useful idea, but not supported by current app code.

High-priority opportunities should be code-backed or test-backed. Do not rank inferred ideas as P0
unless the user explicitly asks for product brainstorming rather than implementation planning.

## What A Good Scan Looks Like

Good:

- "P0: Add policy-grounded account resolver. Evidence: `SubscriptionService`, `PaymentMethodService`,
  `BillingAddressController`. Modules: RAG, `@AIAction`, chat session. POC: read current account,
  retrieve policy docs, confirm update payment method."

Not good:

- "Add an AI chatbot."

Good:

- "P1: Behavior insights. Evidence: `UserActivityEvent`, `LoginEventRepository`, cancellation events.
  Needs real LLM provider. First POC: analyze raw events for churn risk and show evidence."

Not good:

- "Use AI to improve engagement."

The scanner should make the next engineering step obvious.
