# AI Fabric External User Course Structure

This course teaches external Java/Spring Boot developers how to build real AI-enabled applications
with AI Fabric. It is designed as a public course, workshop series, or documentation-led training
path. It should stay aligned with the canonical implementation docs in `docs/getting-started` and
the assistant context pack in `docs/llm-context`.

Current baseline:

- AI Fabric version: `0.3.3`
- Java: `21`
- Spring Boot: `4.1.x`
- Maven group: `io.github.loom-ai-labs`
- Java packages: `ai.fabric.*`

## Course Promise

By the end, a developer can:

- Add AI Fabric to a Spring Boot app.
- Index app data for semantic search.
- Build RAG chat backed by retrieved evidence.
- Expose governed app actions with confirmation.
- Keep multi-turn chat state in the backend.
- Configure OpenAI through Spring AI and local ONNX embeddings.
- Use vector storage and indexing lifecycle controls.
- Apply tenant/user access policy and privacy guardrails.
- Test locally, in CI, and with live provider keys.
- Use a coding assistant safely without drifting away from AI Fabric patterns.

## Audience

Primary audience:

- Java/Spring Boot backend developers.
- Technical leads evaluating AI enablement for existing products.
- Developer advocates or solution engineers creating demos.

Secondary audience:

- Frontend developers integrating AI Fabric-backed chat or action flows.
- Coding-assistant users who want reliable, framework-aware implementation sessions.

Prerequisites:

- Java and Spring Boot basics.
- Maven basics.
- Basic REST/API testing with curl or HTTP clients.
- Optional: OpenAI API key for live-provider labs.

## Recommended Formats

| Format | Duration | Shape |
| --- | --- | --- |
| Self-paced course | 8-12 hours | Read guide, run labs, complete capstone. |
| Two-day workshop | 2 x 4 hours | Instructor-led modules plus labs. |
| Developer onboarding | 3-5 days | One or two modules per day with code review. |
| Conference workshop | 90-120 minutes | Modules 1, 2, 3, and one live demo path. |

## Course Architecture

Each module should use the same structure:

1. Concept: what problem this capability solves.
2. AI Fabric shape: modules, annotations, APIs, and runtime flow.
3. Code walk: current docs or real-app reference.
4. Lab: build or modify a small vertical slice.
5. Verification: unit test, smoke test, or live endpoint proof.
6. Reflection: common mistakes and production guardrails.

Use these source docs as the course backbone:

- `docs/getting-started/README.md`
- `docs/getting-started/00-llm-start-here.md`
- `docs/getting-started/01-choose-your-path.md`
- `docs/getting-started/02-installation.md`
- `docs/getting-started/03-first-semantic-search.md`
- `docs/getting-started/04-first-rag-chat.md`
- `docs/getting-started/05-first-governed-action.md`
- `docs/getting-started/06-chat-session-memory.md`
- `docs/getting-started/07-real-provider-openai.md`
- `docs/getting-started/08-local-onnx-embeddings.md`
- `docs/getting-started/09-vector-storage-lucene.md`
- `docs/getting-started/10-security-access-policy.md`
- `docs/getting-started/11-testing-and-verification.md`
- `docs/getting-started/12-real-apps-map.md`
- `docs/getting-started/13-production-checklist.md`

## Module 0: Orientation

Goal: understand what AI Fabric is and where it fits.

Topics:

- AI Fabric as a Java/Spring Boot AI enablement framework.
- What AI Fabric owns: orchestration, RAG, actions, policy, diagnostics, chat memory, indexing.
- What providers own: LLM calls, embedding generation, vector storage.
- Why demos are real app proofs, not just UI animations.

Lab:

- Clone the repository.
- Run the framework test command from `docs/getting-started/11-testing-and-verification.md`.
- Open `examples/real-apps/README.md` and choose one app to study.

Deliverable:

- A short written capability map for one target app.

## Module 1: Choose The Right Integration Path

Goal: choose the smallest AI Fabric module set for a product requirement.

Topics:

- Semantic search only.
- RAG chat.
- Governed actions.
- Chat memory and follow-up turns.
- Tenant-safe retrieval.
- Local vs cloud providers.

Source:

- `docs/getting-started/01-choose-your-path.md`
- `docs/llm-context/AI_FABRIC_MODULE_DECISION_TREE.md`

Lab:

- Given three product requirements, map each to required modules.
- Explain what should not be added yet.

Deliverable:

- A module selection table with reasoning.

## Module 2: Installation And First App Setup

Goal: create a minimal Spring Boot app that has AI Fabric dependencies and configuration.

Topics:

- BOM and Maven coordinates.
- Java 21 and Spring Boot 4.1.x assumptions.
- `@EnableAIInfrastructure`.
- Local no-key smoke profile.
- Where app-specific code belongs.

Source:

- `docs/getting-started/02-installation.md`

Lab:

- Add AI Fabric to a starter Spring Boot project.
- Boot the app with smoke-safe config.

Verification:

- `mvn test`
- App starts without cloud API keys when configured for local/smoke behavior.

## Module 3: Semantic Search With App Data

Goal: index domain data and retrieve by meaning.

Topics:

- `@AICapable` entity shape.
- Searchable fields and metadata.
- Vector spaces.
- Lucene vector provider for local development.
- Evidence quality and why seed data matters.

Source:

- `docs/getting-started/03-first-semantic-search.md`
- `docs/getting-started/09-vector-storage-lucene.md`

Lab:

- Add semantic search to a product, FAQ, policy, or document entity.
- Seed at least five realistic records.
- Search with wording that differs from stored text.

Verification:

- Retrieval returns relevant entity IDs and metadata.

## Module 4: RAG Chat With Evidence

Goal: answer questions using retrieved context instead of generic model guesses.

Topics:

- Retrieval before generation.
- Evidence panels and document snippets.
- Staged data readiness.
- What happens when no evidence exists.
- Why UI must not fake RAG readiness.

Source:

- `docs/getting-started/04-first-rag-chat.md`
- `examples/real-apps/chat-capabilities-demo/README.md`

Lab:

- Build a chat endpoint that answers from seeded documents.
- Clear evidence, ask the same question, then reseed and compare.

Verification:

- Response includes retrieved evidence when data is indexed.
- The app clearly reports no evidence when data is not indexed.

## Module 5: Governed Actions And Confirmations

Goal: let AI propose useful app actions while the application remains in control.

Topics:

- `@AIAction`.
- Read actions vs write actions.
- Confirmation-required flows.
- Post-action generation from trusted facts.
- Error handling and validation.
- Why actions should be domain-specific, not generic shortcuts.

Source:

- `docs/getting-started/05-first-governed-action.md`
- `docs/Framework-Dev-Guides/actions-governance/ACTIONS_AND_CONFIRMATION_INTERCEPTORS_GUIDE.md`
- `docs/Framework-Dev-Guides/actions-governance/POST_ACTION_GENERATION_FOR_ACTION_HANDLERS_GUIDE.md`

Lab:

- Add one read action and one write action.
- Require confirmation for the write action.
- Return a clean user-facing action result.

Verification:

- Confirmation is requested before execution.
- Rejected action does not mutate state.
- Confirmed action mutates state and returns trusted facts.

## Module 6: Chat Session Memory

Goal: support follow-up turns without sending all chat history from the UI.

Topics:

- Backend-owned chat session memory.
- Conversation IDs.
- Pending confirmations.
- Pinned targets and follow-up intent.
- Why UI should send the new message, not own the full reasoning history.

Source:

- `docs/getting-started/06-chat-session-memory.md`
- `examples/real-apps/ai-fabric-account-resolver/README.md`

Lab:

- Ask a question.
- Accept a suggested action with a follow-up phrase.
- Confirm the action.

Verification:

- The backend resolves the follow-up from stored session context.
- The UI does not need to resend full history.

## Module 7: Providers, OpenAI, And ONNX

Goal: understand provider posture and choose cloud or local embedding paths.

Topics:

- OpenAI through Spring AI.
- AI Fabric policy/orchestration above provider calls.
- ONNX local embeddings.
- Provider fallback and diagnostics.
- What belongs in AI Fabric vs provider layer.

Source:

- `docs/getting-started/07-real-provider-openai.md`
- `docs/getting-started/08-local-onnx-embeddings.md`
- `docs/Framework-Dev-Guides/runtime-integration/SPRING_AI_PROVIDER_INTEGRATION_GUIDE.md`

Lab:

- Run a no-key local embedding scenario.
- Optional: run the same semantic search path with OpenAI embeddings.

Verification:

- Provider diagnostics show which provider was used.
- OpenAI key failures fail clearly and are not hidden by fake fallback.

## Module 8: Indexing, Migration, And Vector Lifecycle

Goal: keep vector data accurate as application data changes.

Topics:

- Sync, async, and batch indexing.
- Backfill and migration.
- Reindexing and deletion.
- Vector lifecycle/admin checks.
- Demo readiness and reset mechanics.

Source:

- `docs/Framework-Dev-Guides/retrieval-vectorization/MIGRATION_BACKFILL_GUIDE.md`
- `docs/Framework-Dev-Guides/retrieval-vectorization/RAG_INDEXING_LIFECYCLE_GUIDE.md`
- `examples/real-apps/vector-readiness-playground/README.md`
- `examples/real-apps/migration-enabled-product-catalog/README.md`

Lab:

- Create, update, and delete an indexed entity.
- Prove search reflects the change.

Verification:

- Search finds the entity after create/update.
- Search no longer finds the entity after delete.

## Module 9: Security, Access Policy, And Privacy

Goal: make AI retrieval and actions safe for tenants, users, and sensitive data.

Topics:

- `EntityAccessPolicy`.
- Tenant/user-scoped retrieval.
- Role-aware action authorization.
- Fail-closed behavior.
- PII and transient data handling.
- Why policy text should be user-friendly while enforcement remains application-owned.

Source:

- `docs/getting-started/10-security-access-policy.md`
- `examples/real-apps/tenant-knowledge-portal/README.md`
- `examples/real-apps/privacy-first-customer-facing-support/README.md`

Lab:

- Seed records for two tenants.
- Attempt cross-tenant retrieval.
- Attempt restricted action as a low-privilege user.

Verification:

- Cross-tenant data is not returned.
- Restricted actions fail closed.

## Module 10: Real App Workshops

Goal: study complete app shapes that external users can copy.

Recommended sequence:

1. AI Shopping Experience: `examples/real-apps/chat-capabilities-demo`
2. Account Resolver: `examples/real-apps/ai-fabric-account-resolver`
3. Behavior Signals and Agentic UI: `examples/real-apps/behavior-churn-signals`
4. Tenant Guard: `examples/real-apps/tenant-knowledge-portal`

For each app, teach:

- Business problem.
- AI Fabric modules used.
- Entities and vector spaces.
- Actions and confirmation policy.
- Provider setup.
- Request/response flow.
- How to reset or seed demo data.
- What tests prove it.

Deliverable:

- A short architecture note for one real app.

## Module 11: Testing, CI, And Release Readiness

Goal: know what proof is required before shipping.

Topics:

- Unit tests.
- Real API tests.
- Manual provider matrix.
- Docker/local smoke tests.
- Health/build metadata.
- No fallback that hides LLM/provider failure.

Source:

- `docs/getting-started/11-testing-and-verification.md`
- `docs/getting-started/13-production-checklist.md`
- `docs/Framework-Dev-Guides/testing-verification/CI_PIPELINE_GUIDE.md`
- `docs/Framework-Dev-Guides/testing-verification/REALAPI_PROVIDER_MATRIX_TESTING_GUIDE.md`

Lab:

- Run unit tests for a focused module.
- Run one real-app smoke profile.
- Optional: run OpenAI-backed real API tests.

Verification:

- Test results are captured.
- Any skipped live-provider test has a documented reason.

## Capstone Project

Build one real AI Fabric vertical slice for a domain app.

Minimum requirements:

- One annotated searchable entity.
- One RAG-backed chat/query endpoint.
- One read action.
- One confirmation-required write action.
- Backend-owned chat memory.
- Access policy for at least tenant or user ownership.
- Local no-key smoke mode.
- Optional OpenAI live-provider mode.
- README with setup, env vars, test commands, and request examples.

Suggested capstone domains:

- Support knowledge assistant.
- Account resolver.
- Tenant knowledge portal.
- Product advisor with cart actions.
- Document intake and retrieval workbench.

Assessment rubric:

| Area | Pass condition |
| --- | --- |
| Framework fit | Uses AI Fabric modules directly, no duplicate mini-framework. |
| Evidence | RAG answers show or expose retrieved evidence. |
| Actions | Writes require confirmation and return trusted facts. |
| Memory | Follow-up turns work without UI-owned full history. |
| Security | Access policy fails closed. |
| Testing | Unit/smoke tests are documented and runnable. |
| Operations | README documents provider keys, local smoke mode, and health checks. |

## Dedicated Track: Using Coding Assistants With AI Fabric

This section can be taught as a standalone module or inserted throughout the course. The goal is to
help developers use AI coding assistants productively without letting them invent APIs, bypass
framework policy, or fake AI behavior.

### Assistant Setup

Before asking a coding assistant to edit code, provide this context in order:

1. `docs/getting-started/00-llm-start-here.md`
2. `docs/llm-context/AI_FABRIC_CONTEXT_INDEX.md`
3. `docs/llm-context/AI_FABRIC_RULES_FOR_CODING_ASSISTANTS.md`
4. `docs/llm-context/AI_FABRIC_CAPABILITY_MAP.md`
5. The specific task guide from `docs/getting-started`
6. The closest real app README from `examples/real-apps`

Recommended first prompt:

```text
You are helping me build an AI Fabric app.
Use AI Fabric 0.3.3, Java 21, Spring Boot 4.1.x, groupId io.github.loom-ai-labs,
and Java packages ai.fabric.*.
Read the attached AI Fabric docs first.
Do not invent framework APIs.
Do not fake AI intelligence in the UI or backend.
Prefer an existing real-app pattern from examples/real-apps.
Before code changes, identify the AI Fabric modules, entities, actions, providers,
tests, and access policy needed for this task.
```

### Assistant Task Template

Use this template for each implementation task:

```text
Goal:
Build [capability] for [domain app].

AI Fabric capabilities required:
- [semantic search/RAG/actions/chat memory/security/provider/etc.]

Existing references:
- docs/getting-started/[file].md
- examples/real-apps/[app]/README.md

Constraints:
- Use backend-owned chat session memory.
- Use app-owned action handlers.
- Writes must require confirmation.
- Return trusted facts from actions; do not show raw internal JSON.
- No fallback that hides LLM/provider failure.
- Keep smoke mode runnable without API keys unless the task explicitly needs live OpenAI.

Validation:
- Add or update tests.
- Run [commands].
- Explain any skipped live-provider tests.
```

### What To Ask The Assistant To Do

Good tasks:

- "Add semantic search to this entity using `@AICapable` and Lucene."
- "Create a read action that returns account profile facts for RAG/action reasoning."
- "Add a confirmation-required write action for updating payment method."
- "Wire chat-session memory so follow-up turns work from backend state."
- "Add an access policy that blocks cross-tenant retrieval."
- "Create smoke tests that prove indexed data appears and disappears after delete."
- "Review this app for AI Fabric philosophy violations."

Risky tasks that need extra review:

- "Make the AI smarter" without defining evidence, actions, or tests.
- "Just add a shortcut button" when the purpose is to prove natural-language AI behavior.
- "Add fallback response" if it can hide an LLM/provider failure.
- "Parse LLM text with string matching" when structured output or framework contracts exist.
- "Let the frontend send all chat history" when backend chat-session memory should own it.

### Assistant Review Checklist

After code generation, ask the assistant to verify:

- Does the code use current coordinates and packages?
- Are all AI answers backed by retrieved evidence or trusted action facts?
- Are write actions confirmation-gated?
- Does the UI show domain-specific results instead of raw JSON?
- Is user/tenant access enforced in the backend?
- Are provider failures visible instead of hidden?
- Are tests added or updated?
- Did the assistant avoid duplicate abstractions that already exist in AI Fabric?

### Assistant Debugging Prompts

Use these when behavior is wrong:

```text
Investigate this AI Fabric behavior.
Do not change framework code until you identify whether the bug is in app config,
prompt config, action registration, chat-session state, access policy, vector data,
or provider response.
Show the request path, selected mode, retrieved evidence, chosen action,
missing parameters, confirmation state, and final response.
```

```text
The UI is showing raw action result data.
Find the backend action result shape and frontend renderer.
Use domain-specific presentation fields from trusted action facts.
Do not fake or rewrite the AI answer client-side.
```

```text
The follow-up message is not using previous context.
Verify the app is wired to ai-fabric-chat-session.
The UI should send only the new message and conversationId.
The backend should supply recent session context to AI Fabric.
```

### Coding Assistant Lab

Lab goal: use a coding assistant to add a small feature safely.

Exercise:

1. Pick `smart-faq-assistant` or `ai-fabric-account-resolver`.
2. Give the assistant the setup prompt and task template.
3. Add one small action or retrieval path.
4. Ask the assistant to add tests.
5. Run the tests manually.
6. Ask the assistant for a release-readiness review.

Pass condition:

- The assistant produces code that follows AI Fabric patterns.
- The developer can explain what the assistant changed.
- Tests or smoke verification prove the change.

## Course Assets To Create

Recommended public course materials:

- Course landing page with audience, prerequisites, and outcomes.
- Module pages mirroring this structure.
- One slide deck per module.
- One lab branch or starter app per major capability.
- Instructor demo scripts for the four public demos.
- "Use AI Fabric with a coding assistant" downloadable prompt sheet.
- Capstone checklist.
- Troubleshooting FAQ.

## Publishing Plan

Suggested public navigation:

1. Start Here
2. Install AI Fabric
3. Build Semantic Search
4. Build RAG Chat
5. Add Governed Actions
6. Add Chat Memory
7. Add Security And Tenant Policy
8. Configure Providers
9. Real App Workshops
10. Use Coding Assistants
11. Production Checklist
12. Capstone

The public `aifabric` website can render this as a course hub, while this framework repo remains
the source of truth for exact implementation docs.

