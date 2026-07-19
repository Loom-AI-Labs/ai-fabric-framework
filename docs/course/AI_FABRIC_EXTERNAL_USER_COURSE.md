# Build Production-Oriented AI Workflows with Java and Spring Boot

> Master curriculum and implementation specification for the public AI Fabric course.

## Document Status

| Field | Value |
| --- | --- |
| Status | Ready for course asset implementation after the release blockers in this document are fixed |
| Course baseline | AI Fabric `0.3.3` |
| Framework release tag | `ai-fabric-framework-v0.3.3` |
| Course content version | `0.3.3-course.1` |
| Planned course source tag | `ai-fabric-course-v0.3.3.1` |
| Java | `21` |
| Spring Boot | `4.1.x` |
| Maven group | `io.github.loom-ai-labs` |
| Framework packages | `ai.fabric.*` |
| Public course title | Build Production-Oriented AI Workflows with Java and Spring Boot |
| Public subtitle | Semantic search, evidence-grounded RAG, governed actions, chat memory, and tenant security with AI Fabric |

This file is the curriculum source of truth. It defines what must be built in the framework
repository, the learner repository, the `aifabric` website, and the NotebookLM production workflow.
It is not itself a learner lesson.

Do not publicly label the course "AI Fabric External User Course." That phrase is an internal
description. Use the public title above.

## Course Promise

The course teaches a Java developer to add useful AI workflows to an existing Spring Boot
application while keeping domain rules, authorization, state changes, and failure handling under
application control.

By the end of the core course, a learner can:

- Install AI Fabric from Maven Central in a standalone Spring Boot app.
- Model and index application data for semantic retrieval.
- Produce answers grounded in retrieved evidence.
- Register read and write actions with typed parameters.
- Require confirmation before side effects.
- Continue a conversation using backend-owned chat session state.
- Apply tenant, user, and role policy before exposing evidence or actions.
- Test success, failure, and no-evidence behavior without hiding provider failures.

The production and elective tracks add:

- Local ONNX and OpenAI provider profiles.
- Indexing, migration, update, and delete lifecycle coverage.
- Provider and vector diagnostics.
- PII-safe application patterns.
- RAG quality evaluation.
- Real application architecture studies.
- Safe use of coding assistants with AI Fabric.

## Course Philosophy

The course must consistently demonstrate these principles:

1. AI Fabric supplies orchestration, retrieval, governed action, memory, policy, and diagnostics.
2. The application owns domain truth, authorization, persistence, and side effects.
3. The LLM supplies interpretation and generation. UI code must not fake intelligence.
4. Retrieved evidence and trusted action facts must remain visible and inspectable.
5. Write actions require explicit confirmation.
6. Security and tenant boundaries fail closed.
7. Provider failures stay visible. A deterministic fallback must never masquerade as live AI.
8. Tests preserve behavior before refactoring or release.
9. Learners use published artifacts. Course success must not depend on framework source being
   installed locally.

The framework philosophy remains authoritative:

- `docs/Framework-Dev-Guides/LLM-guides/AI_FABRIC_FRAMEWORK_PHILOSOPHY.md`
- `docs/Framework-Dev-Guides/LLM-guides/AI_FABRIC_LLM_SESSION_LESSONS_LEARNED.md`

## Audience

Primary audience:

- Java and Spring Boot backend developers.
- Technical leads evaluating AI enablement for an existing product.
- Solution engineers building secure enterprise AI workflows.
- Developer advocates teaching AI Fabric through working applications.

Secondary audience:

- Frontend developers consuming AI Fabric-backed chat and action APIs.
- Engineers using coding assistants to add AI Fabric to an application.
- Contributors who first want to understand the external-user experience.

Prerequisites:

- Comfortable reading and editing a small Spring Boot application.
- Basic Java, Maven, REST, JSON, and JUnit knowledge.
- Java 21 and Maven 3.9 or later.
- Git and a command-line HTTP client.
- Optional OpenAI API key for explicitly marked live-provider labs.

Not required for the quickstart or core local path:

- Cloning or building the AI Fabric framework reactor.
- Docker.
- A cloud vector database.
- An LLM API key.

## Definition Of Learner-Ready

A lesson is not ready merely because its topic outline exists. Every public lesson must provide:

- An estimated duration.
- Prerequisite lessons and software.
- A pinned framework version.
- A starter checkpoint and a solution checkpoint.
- Exact files to inspect and edit.
- Copyable build, run, and request commands.
- Expected status codes and important response fields.
- One intentional failure or incomplete-state exercise.
- A clear "done when" checklist.
- Reset and cleanup instructions.
- Troubleshooting for likely errors.
- Focused tests that run from a clean checkout.
- A NotebookLM source pack and reviewed video brief.

No lesson may be marked ready until a clean-environment CI job proves its checkpoint.

## Publishing Architecture

The course is one product published through four connected surfaces.

| Surface | Responsibility | Planned location |
| --- | --- | --- |
| Framework repository | Canonical curriculum, lesson Markdown, manifests, API evidence, release compatibility | `AI-Fabric-Framework/docs/course` |
| Learner repository | Standalone Spring Boot app, Maven wrapper, fixtures, checkpoints, tests | `Loom-AI-Labs/ai-fabric-course-support-assistant` |
| Website repository | Human-readable course hub, lesson renderer, progress, video embeds, downloads | `aifabric` under `/course` |
| NotebookLM | Source-grounded explanation videos, study guides, quizzes, and supporting artifacts | One notebook per lesson or tightly coupled lesson pair |

The learner repository must consume AI Fabric only through Maven Central. It must not use relative
paths into the framework source tree or depend on unpublished `examples/real-apps` artifacts.

### Canonical Content Rule

Course Markdown is authored in this framework repository. The website receives a generated,
version-pinned copy. Website developers must not edit generated course lesson text directly.

Planned canonical authoring structure:

```text
docs/course/
  README.md
  AI_FABRIC_EXTERNAL_USER_COURSE.md
  course.yml
  shared/
    glossary.md
    troubleshooting-index.md
    architecture/
  quickstart/
    01-first-useful-result/
      lesson.md
      lab.md
      expected-results.md
      troubleshooting.md
      quiz.md
      notebooklm/
  core/
    01-mental-model/
    02-model-and-index/
    03-evidence-grounded-rag/
    04-governed-actions/
    05-chat-memory/
    06-security-and-privacy/
    07-test-and-ship/
  production/
    01-provider-profiles/
    02-indexing-lifecycle/
    03-rag-quality/
    04-managed-vector-provider/
    05-release-readiness/
  case-studies/
  coding-assistants/
  capstone/
```

The synchronization process must:

1. Read the course manifest from the immutable course source tag.
2. Copy only declared public course assets.
3. Record the course source tag, framework compatibility tag, and source commit in a generated manifest.
4. Fail when generated website content differs from canonical source.
5. Preserve website-only presentation components outside the generated content directory.

Suggested website generated directory:

```text
src/content/course/0.3.3/
  course.yml
  quickstart/
  core/
  production/
  case-studies/
  coding-assistants/
  capstone/
  source-manifest.json
```

### Versioning Rule

Every published course release is pinned to a framework release. Course content receives its own
immutable source tag because documentation may be completed after the framework release tag was
created. Do not make old lessons silently follow `main` or a moving `latest` version.

For this course release:

- Framework API compatibility: `ai-fabric-framework-v0.3.3`.
- Course content source: `ai-fabric-course-v0.3.3.1` after Phase 0 is complete.
- Learner checkpoint tags: `course-0.3.3-*` in the standalone learner repository.

Checkpoint naming convention:

```text
course-0.3.3-00-starter
course-0.3.3-01-first-search
course-0.3.3-02-rag
course-0.3.3-03-actions
course-0.3.3-04-memory
course-0.3.3-05-security
course-0.3.3-06-tested-solution
```

Checkpoint tags are immutable. A course correction creates a patch checkpoint or a new course
release rather than moving an existing tag.

## Course Product Structure

The complete course is a set of selectable tracks, not an eight-hour promise for every topic.

| Track | Duration | Outcome |
| --- | --- | --- |
| Quickstart | 60-90 minutes | Run a standalone app and prove a first semantic search |
| Core course | 7-9 hours | Build search, RAG, actions, memory, security, and tests in one app |
| Production track | 6-8 hours | Add providers, lifecycle, quality, diagnostics, and release checks |
| Real-app case studies | 2-4 hours | Study five deployed application shapes |
| Coding-assistant track | 1-2 hours | Use an assistant without inventing APIs or bypassing policy |
| Capstone | 4-8 hours | Build and defend a complete AI Fabric vertical slice |

Expected total for every track and capstone: approximately 21-33 hours.

The website must let learners distinguish:

- Required core lessons.
- Optional live-provider work.
- Production lessons.
- Case studies.
- Capstone work.

## Continuing Application

All quickstart and core lessons evolve one application:

> **Support Knowledge Assistant**

The application starts as a normal support knowledge service and grows into an evidence-grounded,
policy-aware assistant. This domain naturally supports retrieval, governed actions, memory,
privacy, and tenant safety without changing business context between lessons.

### Business Scenario

A software company stores support articles and customer tickets. Customers ask questions, retrieve
approved knowledge, inspect their own tickets, create or escalate a ticket, and continue follow-up
conversations. Support agents can access additional tenant-approved evidence, but no user can see
another tenant's private records.

### Domain Model

| Type | Purpose | AI Fabric role |
| --- | --- | --- |
| `KnowledgeArticle` | Approved support content | Searchable and embeddable evidence |
| `SupportPolicy` | Return, escalation, privacy, and service rules | RAG evidence |
| `SupportTicket` | Application-owned ticket state | Read and governed write actions |
| `CustomerAccount` | Current user, tenant, plan, and entitlements | Trusted action input and access context |
| `ChatSession` | Conversation and pending confirmation state | `ai-fabric-chat-session` persistence |

### AI Surfaces

| Surface | Contract |
| --- | --- |
| Semantic search | Search articles by meaning and return IDs, scores, and metadata |
| RAG query | Answer only from retrieved article or policy evidence when the lesson requires grounding |
| Read action | Read current user's ticket status without asking for an ID already known by the app |
| Write action | Create or escalate a ticket after confirmation |
| Chat memory | Resolve follow-ups from backend conversation state |
| Access policy | Scope evidence and actions to the current user and tenant |
| PII policy | Prevent raw sensitive data from being logged or indexed in privacy exercises |

### Provider Profiles

| Profile | Purpose | Expected dependency |
| --- | --- | --- |
| `local` | No-key semantic retrieval using real local embeddings | `ai-fabric-onnx-starter` plus Lucene |
| `test` | Fast deterministic contract tests with app-owned test providers | Test-scoped fixtures plus memory or Lucene |
| `openai` | Optional live generation and embeddings | `ai-fabric-provider-spring-ai` plus Lucene |

The local course path uses ONNX for real semantic behavior. Deterministic hash or fixture embeddings
may be used in unit tests, but lessons must label them as test wiring and must not present them as
semantic intelligence.

### Planned Learner Repository Shape

```text
ai-fabric-course-support-assistant/
  .mvn/
  mvnw
  mvnw.cmd
  pom.xml
  README.md
  scripts/
    download-onnx-model.sh
    reset-course.sh
  requests/
    quickstart.http
    rag.http
    actions.http
    memory.http
    security.http
  src/main/java/dev/aifabric/course/support/
    SupportAssistantApplication.java
    account/
    actions/
    chat/
    knowledge/
    policy/
    security/
    web/
  src/main/resources/
    application.yml
    application-local.yml
    application-openai.yml
    ai-entity-config.yml
  src/test/java/dev/aifabric/course/support/
```

### Stable Course API

The course app should keep a small API stable across checkpoints:

| Endpoint | Purpose |
| --- | --- |
| `POST /api/demo/reset` | Clear learner-owned fixtures, vectors, and sessions |
| `POST /api/demo/seed` | Seed the lesson's domain data |
| `POST /api/demo/index` | Index current course evidence |
| `GET /api/demo/readiness` | Report data, vector, provider, and build readiness |
| `GET /api/knowledge/search?q=...` | Prove semantic retrieval directly |
| `POST /api/assistant/query` | Run RAG, action, and memory scenarios |
| `GET /actuator/health` | Prove application health |

Confirmation should use the same conversation endpoint and backend chat-session state. The learner
must be able to send a short follow-up such as `yes` with the same conversation ID. Do not teach a
frontend-owned confirmation or history protocol.

## Lesson Contract

Each lesson directory must include this front matter in `lesson.md`:

```yaml
---
id: core-03
slug: governed-actions
title: Governed Actions and Confirmation
track: core
order: 3
durationMinutes: 75
courseVersion: 0.3.3-course.1
frameworkVersion: 0.3.3
frameworkTag: ai-fabric-framework-v0.3.3
courseSourceTag: ai-fabric-course-v0.3.3.1
starterRef: course-0.3.3-02-rag
solutionRef: course-0.3.3-03-actions
requiresOpenAi: false
requiresDocker: false
sourcePaths:
  - docs/getting-started/05-first-governed-action.md
  - examples/real-apps/it-support-action-bot/README.md
video:
  status: planned
  generator: notebooklm
  notebookSource: notebooklm-source.md
  publicUrl: null
  transcript: null
  reviewedBy: null
  reviewedAt: null
---
```

Every `lesson.md` follows this order:

1. Outcome.
2. Why this matters.
3. Starting state.
4. Architecture and request flow.
5. Files to inspect.
6. Build steps.
7. Run and request commands.
8. Expected result.
9. Intentional failure exercise.
10. Tests.
11. Done when.
12. Reset and cleanup.
13. Troubleshooting.
14. What changed from the previous checkpoint.
15. Next lesson.

## Quickstart

### QS-01: First Useful Result

**Duration:** 60-90 minutes

**Checkpoint:** `course-0.3.3-00-starter` to `course-0.3.3-01-first-search`

**Keys required:** No

Outcome:

- Start a standalone Spring Boot app that resolves AI Fabric from Maven Central.
- Seed and index five support articles.
- Search using wording different from the stored article title.
- Inspect entity ID, score, content, and metadata in the response.

The learner does not clone or build the framework reactor.

Files to edit:

- `pom.xml`
- `src/main/resources/application-local.yml`
- `src/main/resources/ai-entity-config.yml`
- `knowledge/KnowledgeArticle.java`
- `knowledge/KnowledgeArticleService.java`
- `web/KnowledgeSearchController.java`

Required sequence:

1. Verify Java 21.
2. Check out the starter tag.
3. Download the local ONNX assets with the supplied script.
4. Run tests.
5. Start the app with the `local` profile.
6. Call reset, seed, and index.
7. Run a semantic query.

Intentional failure:

- Search before indexing.
- Observe a successful request with no evidence rather than a fabricated answer.
- Index the records and repeat the same query.

Done when:

- Dependencies resolve from a clean Maven repository.
- The app starts without cloud keys.
- Search returns the expected article ID for the golden quickstart query.
- The response exposes evidence metadata.
- The learner can explain the difference between domain storage and vector storage.

Quickstart source material:

- `docs/getting-started/01-choose-your-path.md`
- `docs/getting-started/02-installation.md`
- `docs/getting-started/03-first-semantic-search.md`
- `docs/getting-started/08-local-onnx-embeddings.md`
- `docs/getting-started/09-vector-storage-lucene.md`

## Core Course

QS-01 and CORE-02 share the first-search solution checkpoint. The quickstart is the compressed build
path; CORE-02 supplies the deeper data-modeling, metadata, update, and delete explanation. A learner
who completed QS-01 inspects and extends the same code instead of rebuilding it from scratch.

### CORE-01: AI Fabric Mental Model

**Duration:** 35-45 minutes

**Code checkpoint:** no code change

Outcome:

- Distinguish application responsibilities, AI Fabric responsibilities, and provider
  responsibilities.
- Select the smallest module set for a feature.
- Trace a request from REST input through retrieval or action execution.

Lab:

- Map three requirements to AI Fabric modules.
- Identify one requirement that should remain ordinary application code.
- Draw the Support Knowledge Assistant request flow.

Intentional failure:

- Review a proposed design that lets an LLM write directly to a repository.
- Replace it with a registered, confirmation-gated application action.

Done when:

- The module-selection table names required and deliberately excluded modules.
- The learner can explain why AI Fabric is not another persistence layer.

Sources:

- `docs/getting-started/00-llm-start-here.md`
- `docs/getting-started/01-choose-your-path.md`
- `docs/llm-context/AI_FABRIC_MODULE_DECISION_TREE.md`
- `docs/llm-context/AI_FABRIC_CAPABILITY_MAP.md`

### CORE-02: Model And Index Application Data

**Duration:** 60 minutes

**Checkpoint:** `course-0.3.3-00-starter` to `course-0.3.3-01-first-search`

Outcome:

- Define searchable, embeddable, context, and metadata fields.
- Index a `KnowledgeArticle` into a dedicated vector space.
- Preserve stable entity and tenant metadata.

Lab changes:

- Add `KnowledgeArticle` and repository fixtures.
- Add annotation or YAML-driven AI entity configuration.
- Add explicit seed and indexing services.
- Add a golden retrieval test.

Intentional failure:

- Index an article without the expected tenant/category metadata.
- Observe the failing test, correct the metadata, and reindex.

Done when:

- Create, update, and delete are reflected in retrieval.
- Metadata required by later security lessons is present.
- A test proves the expected article is retrieved by a semantically related query.

Sources:

- `docs/getting-started/03-first-semantic-search.md`
- `docs/getting-started/09-vector-storage-lucene.md`
- `examples/real-apps/smart-faq-assistant/README.md`

### CORE-03: Evidence-Grounded RAG

**Duration:** 75 minutes

**Checkpoint:** `course-0.3.3-01-first-search` to `course-0.3.3-02-rag`

Outcome:

- Retrieve approved evidence before answer generation.
- Expose evidence IDs and snippets with the answer.
- Report no-evidence state without replacing it with generic model knowledge.

Lab changes:

- Add `SupportPolicy` evidence.
- Add `ai-fabric-rag`.
- Add the assistant query endpoint.
- Add response projection for answer, evidence, mode, and diagnostics.

Intentional failure:

- Clear the vector index and ask a policy question.
- Verify the app reports that no indexed evidence is available.
- Reseed, reindex, and repeat the query.

Done when:

- The answer is supported by returned article or policy IDs.
- The UI/API never labels database rows as retrieved evidence before indexing.
- A test fails if an expected source is absent.

Sources:

- `docs/getting-started/04-first-rag-chat.md`
- `examples/real-apps/smart-faq-assistant/README.md`
- `examples/real-apps/chat-capabilities-demo/README.md`

### CORE-04: Governed Actions And Confirmation

**Duration:** 75-90 minutes

**Checkpoint:** `course-0.3.3-02-rag` to `course-0.3.3-03-actions`

Outcome:

- Register one read action and one write action.
- Extract typed parameters through `@Param`.
- Require confirmation before changing ticket state.
- Return an `ActionResult` containing concise trusted facts.

Required action shape:

```java
@AIAction(
    name = "create_support_ticket",
    description = "Create a support ticket for the current customer.",
    category = "support",
    accessMode = ActionAccessMode.WRITE_ONLY,
    requiresConfirmation = true
)
public class CreateSupportTicketActionHandler {

    @ActionExecute
    public ActionResult execute(
        @Param(value = "subject", description = "Short ticket subject", required = true)
        String subject,
        @Param(value = "description", description = "Issue details", required = true)
        String description,
        ActionContext context
    ) {
        // Application service owns authorization and persistence.
        return ticketService.createForCurrentCustomer(subject, description, context);
    }
}
```

Lab changes:

- Add `get_my_ticket_status` as a `READ` action.
- Add `create_support_ticket` as a `WRITE_ONLY` action.
- Add `@ActionAllowed` where application authorization is required.
- Add confirmation, rejection, and duplicate-confirmation tests.

Intentional failure:

- Submit a write request with a missing required parameter.
- Verify clarification is returned and no ticket is created.
- Reject a complete action and verify state remains unchanged.

Done when:

- Registration tests prove both actions are discoverable.
- The write action cannot execute before confirmation.
- Confirm executes once.
- Reject does not mutate state.
- The response does not dump raw persistence objects.

Sources:

- `docs/getting-started/05-first-governed-action.md`
- `docs/Framework-Dev-Guides/actions-governance/ACTIONS_AND_CONFIRMATION_INTERCEPTORS_GUIDE.md`
- `examples/real-apps/it-support-action-bot/README.md`

### CORE-05: Backend-Owned Conversation Memory

**Duration:** 60 minutes

**Checkpoint:** `course-0.3.3-03-actions` to `course-0.3.3-04-memory`

Outcome:

- Create and reuse a stable conversation ID.
- Store recent turns and pending confirmation in the backend.
- Resolve a short follow-up without the client rebuilding a history prompt.

Lab sequence:

1. Ask why a ticket remains unresolved.
2. Receive a grounded answer and suggested escalation.
3. Reply `escalate it` using the same conversation ID.
4. Reply `yes` to confirm.

Intentional failure:

- Send the follow-up with a new conversation ID.
- Observe that prior context is unavailable.
- Repeat with the original ID.

Done when:

- The client request contains only the new message and stable identity fields.
- Backend session state supplies recent context.
- Pending action state survives the confirmation turn.
- Tests prove sessions cannot cross users.

Sources:

- `docs/getting-started/06-chat-session-memory.md`
- `examples/real-apps/ai-fabric-account-resolver/README.md`

### CORE-06: Tenant Security And Privacy

**Duration:** 75-90 minutes

**Checkpoint:** `course-0.3.3-04-memory` to `course-0.3.3-05-security`

Outcome:

- Apply tenant and user scope before evidence reaches generation.
- Authorize actions against application identity.
- Redact sensitive support text before storage or indexing.

Lab changes:

- Seed two tenants with overlapping article and ticket names.
- Add an `EntityAccessPolicy`.
- Add action authorization for customer and support-agent roles.
- Add a PII-safe intake exercise.

Intentional failure:

- Attempt cross-tenant search.
- Attempt escalation as an unauthenticated or unauthorized user.
- Submit a message containing an email, phone number, or test SSN.

Done when:

- Cross-tenant evidence is absent rather than filtered in the UI.
- Unauthorized actions fail closed.
- Raw sensitive values are not returned, logged, or indexed in the privacy path.
- Security tests run without an LLM key.

Sources:

- `docs/getting-started/10-security-access-policy.md`
- `examples/real-apps/tenant-knowledge-portal/README.md`
- `examples/real-apps/privacy-first-customer-facing-support/README.md`

### CORE-07: Test And Ship The Vertical Slice

**Duration:** 60-75 minutes

**Checkpoint:** `course-0.3.3-05-security` to `course-0.3.3-06-tested-solution`

Outcome:

- Separate deterministic tests from live-provider tests.
- Prove retrieval, RAG, actions, memory, security, and failure behavior.
- Publish health and build metadata.

Required test layers:

- Domain and action unit tests.
- Application context and action-registration tests.
- Local retrieval/index lifecycle tests.
- Chat-session integration tests.
- Tenant and PII regression tests.
- Docker or packaged-jar smoke test.
- Optional keyed OpenAI smoke test.

Intentional failure:

- Configure an unavailable provider while generation is enabled.
- Verify the failure is explicit and no deterministic answer is presented as live AI.

Done when:

- `./mvnw clean verify` passes from a clean checkout.
- The packaged app starts in the local profile.
- Health includes application status and deployed source metadata.
- Skipped live-provider tests state exactly why they were skipped.

Sources:

- `docs/getting-started/11-testing-and-verification.md`
- `docs/getting-started/13-production-checklist.md`
- `docs/Framework-Dev-Guides/testing-verification/CI_PIPELINE_GUIDE.md`

## Production Track

### PROD-01: OpenAI And Local ONNX Provider Profiles

**Duration:** 75 minutes

**Keys required:** OpenAI portion only

Build:

- Keep local ONNX embeddings available without cloud keys.
- Add OpenAI generation and embedding configuration through Spring AI.
- Externalize model, base URL, key, and embedding dimensions.
- Expose provider diagnostics without exposing credentials.

Failure exercise:

- Use an invalid key or endpoint and confirm the provider error remains visible.

Done when:

- Local and OpenAI profiles are explicitly distinguishable.
- Tests prove secret redaction.
- The selected provider appears in diagnostics.

Sources:

- `docs/getting-started/07-real-provider-openai.md`
- `docs/getting-started/08-local-onnx-embeddings.md`
- `docs/Framework-Dev-Guides/runtime-integration/SPRING_AI_PROVIDER_INTEGRATION_GUIDE.md`

### PROD-02: Indexing, Backfill, And Vector Lifecycle

**Duration:** 90 minutes

Build:

- Index new records.
- Update indexed content.
- Delete records and associated vectors.
- Backfill existing data.
- Report vector readiness and counts.

Failure exercise:

- Change embedding dimensions without changing the Lucene index path.
- Observe the mismatch and apply the documented migration/reset behavior.

Done when:

- Search reflects create, update, and delete.
- Backfill is repeatable or safely resumable.
- Readiness proves expected evidence is retrievable, not merely present in the database.

Sources:

- `docs/Framework-Dev-Guides/retrieval-vectorization/MIGRATION_BACKFILL_GUIDE.md`
- `docs/Framework-Dev-Guides/retrieval-vectorization/RAG_INDEXING_LIFECYCLE_GUIDE.md`
- `examples/real-apps/vector-readiness-playground/README.md`
- `examples/real-apps/migration-enabled-product-catalog/README.md`

### PROD-03: RAG Quality And Prompt Regression

**Duration:** 60-75 minutes

Build:

- Define golden support questions and expected evidence IDs.
- Record retrieval scores and missing-evidence failures.
- Add optional evaluator-backed checks.
- Preserve prompt and mode identifiers in diagnostics.

Failure exercise:

- Remove a required article and run the quality gate.
- Verify the gate fails rather than grading an unsupported answer as acceptable.

Done when:

- Golden questions pass with expected sources.
- No-evidence cases are explicit.
- Prompt changes can be regression-tested before release.

Sources:

- `examples/real-apps/smart-faq-assistant/README.md`
- `docs/Framework-Dev-Guides/testing-verification/REALAPI_PROVIDER_MATRIX_TESTING_GUIDE.md`

### PROD-04: Move From Local Lucene To A Managed Vector Provider

**Duration:** 75-90 minutes

**Docker required:** Yes for the recommended Qdrant lab

Build:

- Keep the AI Fabric retrieval contract unchanged while selecting a different vector provider.
- Run Qdrant locally with Docker.
- Configure collection dimensions to match the selected embedding provider.
- Preserve tenant and entity metadata filters.
- Compare provider readiness, lifecycle, and admin diagnostics with the Lucene profile.

Failure exercise:

- Start with an unavailable Qdrant endpoint or a dimension mismatch.
- Verify readiness and search diagnostics identify the real provider problem.
- Correct provider configuration without changing application retrieval code.

Done when:

- The same golden retrieval tests pass against Lucene and Qdrant profiles.
- Tenant metadata remains enforced.
- Provider-specific failure is visible and is not replaced by another provider silently.

Sources:

- `examples/real-apps/vector-readiness-playground/README.md`
- `examples/real-apps/cloud-qdrant-openai-vector-search/README.md`
- `docs/Framework-Dev-Guides/retrieval-vectorization/RAG_INDEXING_LIFECYCLE_GUIDE.md`

### PROD-05: Operations And Release Readiness

**Duration:** 60 minutes

Build:

- Package the app with Docker.
- Include source commit, course version, and build time in readiness output.
- Document required and optional environment variables.
- Run local smoke and optional live-provider gates.

Done when:

- Deployment metadata identifies the running commit.
- Reset and seed operations are explicit and protected appropriately.
- Logs and diagnostics contain no API keys or raw PII.
- The release checklist records every executed test layer.

Sources:

- `docs/getting-started/13-production-checklist.md`
- `docs/Framework-Dev-Guides/testing-verification/VERIFICATION_PLAYBOOK.md`
- `docs/Framework-Dev-Guides/testing-verification/CI_PIPELINE_GUIDE.md`

## Real-App Case Studies

Case studies are not substitutes for the continuing lab app. They show how the same framework
contracts adapt to different domains.

### CASE-01: AI Shopping Experience

Reference: `examples/real-apps/chat-capabilities-demo`

Teach:

- Staged RAG readiness.
- Product and policy evidence.
- Attachments and target resolution.
- Cart and checkout actions.
- Confirmation and domain-specific action results.

### CASE-02: AI Fabric Account Resolver

Reference: `examples/real-apps/ai-fabric-account-resolver`

Teach:

- Resolver mode.
- Reading current-account facts.
- Policy-grounded blocker explanation.
- Payment, address, cancellation, plan, and refund actions.
- Backend-owned follow-up context.

### CASE-03: AI Fabric Behavior Signals

Reference: `examples/real-apps/behavior-churn-signals`

Teach:

- Raw application events as evidence.
- Previous insight plus newly recorded events.
- Structured behavior insight.
- LLM-selected allowlisted UI components.
- Explicit AI failure without hidden fallback.

### CASE-04: AI Fabric Tenant Guard

Reference: `examples/real-apps/tenant-knowledge-portal`

Teach:

- Tenant metadata and retrieval boundaries.
- Role-aware evidence.
- Governed writes.
- Denied cross-tenant scenarios.

### CASE-05: AI Fabric Privacy Shield

Reference: `examples/real-apps/privacy-first-customer-facing-support`

Teach:

- PII detection and redaction.
- Safe support-message storage.
- Sanitized indexing and retrieval.
- Difference between privacy capability proof and live LLM generation.

Each case-study page must include:

- Business problem.
- AI Fabric modules.
- Annotated or configured entities.
- Provider and vector choices.
- Request and data flow.
- Actions and policy boundaries.
- Test evidence.
- Live demo link.
- Backend source link.
- A statement identifying which behavior is live AI, deterministic framework behavior, or ordinary
  application logic.

## Coding-Assistant Track

### ASSIST-01: Give An Assistant Correct Context

**Duration:** 30 minutes

Provide context in this order:

1. `docs/getting-started/00-llm-start-here.md`
2. `docs/llm-context/AI_FABRIC_CONTEXT_INDEX.md`
3. `docs/llm-context/AI_FABRIC_RULES_FOR_CODING_ASSISTANTS.md`
4. `docs/llm-context/AI_FABRIC_CAPABILITY_MAP.md`
5. The relevant Getting Started task guide.
6. The nearest real-app README.

Starter prompt:

```text
You are helping me build an AI Fabric application.
Use AI Fabric 0.3.3, Java 21, Spring Boot 4.1.x,
Maven group io.github.loom-ai-labs, and framework packages ai.fabric.*.

Read the supplied AI Fabric documents before proposing code.
Do not invent framework APIs.
Do not fake AI intelligence in the UI or backend.
Keep domain state, authorization, and side effects in application services.
Require confirmation for writes.
Use backend-owned chat-session memory.
Before editing, identify modules, entities, providers, actions, policies, and tests.
```

### ASSIST-02: Implement A Small Capability Safely

**Duration:** 45-60 minutes

Task template:

```text
Goal:
Build [capability] for [domain application].

AI Fabric capabilities:
- [semantic search/RAG/actions/chat memory/security/provider]

References:
- docs/getting-started/[task].md
- examples/real-apps/[closest-app]/README.md

Constraints:
- Use application-owned handlers and policies.
- Writes require confirmation.
- Return trusted, domain-shaped action facts.
- Do not display raw internal JSON.
- Do not hide LLM or provider failures.
- Preserve a local no-key test path.

Validation:
- Add tests before or with the change.
- Run [exact commands].
- Explain any unexecuted live-provider checks.
```

Lab:

- Ask an assistant to add one action or retrieval field to the course app.
- Require it to identify the current framework API from code or canonical docs.
- Review its diff manually.
- Run tests independently.
- Ask it for a release-readiness review.

Done when:

- The generated code follows current AI Fabric contracts.
- The learner can explain every change.
- Tests prove the behavior.
- No duplicate orchestration, history, or policy layer was introduced.

### Assistant Review Checklist

- Are coordinates, release version, and packages current?
- Does every annotated action have exactly one `@ActionExecute` method?
- Are non-context action parameters annotated with `@Param`?
- Do write actions use `WRITE_ONLY` or `READ_WRITE` and require confirmation?
- Are answers grounded by evidence or trusted action facts?
- Is access enforced before evidence or execution?
- Does the UI avoid raw action JSON?
- Are provider failures visible?
- Does backend chat-session state own conversation history?
- Were focused tests added and run?

## Capstone

### Goal

Build a production-oriented AI Fabric vertical slice for a domain application. The capstone may use
the Support Knowledge Assistant or a learner-owned domain.

### Minimum Requirements

- One searchable application entity.
- One explicit vector space with stable metadata.
- One evidence-grounded RAG endpoint.
- One read action.
- One confirmation-required write action.
- Backend-owned chat memory.
- Tenant or user access policy.
- One privacy or sensitive-data rule.
- Local no-key profile.
- Optional OpenAI profile.
- Index create, update, and delete proof.
- Unit, integration, and packaged-app smoke tests.
- README with setup, environment variables, requests, reset, and troubleshooting.

### Suggested Domains

- Support knowledge and ticket resolution.
- Account repair.
- Tenant knowledge portal.
- Product advisor with governed cart actions.
- Document intake and retrieval workbench.

### Assessment Rubric

| Area | Weight | Pass condition |
| --- | ---: | --- |
| Framework fit | 15% | Uses AI Fabric directly without a duplicate mini-framework |
| Retrieval | 15% | Indexed evidence is relevant, visible, and lifecycle-tested |
| RAG | 10% | Answers distinguish grounded evidence from no-evidence state |
| Actions | 15% | Writes require confirmation and return trusted facts |
| Memory | 10% | Follow-ups work from backend state |
| Security and privacy | 15% | Access fails closed and sensitive data follows declared policy |
| Testing | 15% | Deterministic and optional live-provider gates are documented |
| Operations | 5% | Health, build metadata, configuration, and reset are clear |

A capstone passes at 75% only when every security, action-confirmation, and testing pass condition is
also satisfied. A high total cannot compensate for unsafe writes or cross-tenant leakage.

## NotebookLM Production Contract

NotebookLM is a production aid for explanations, not the source of framework truth. Create one
notebook per lesson or tightly coupled lesson pair so unrelated modules do not contaminate the
explanation.

Each lesson may publish two different media artifacts:

1. A short NotebookLM concept explainer grounded in the reviewed lesson source pack.
2. An optional maintainer-recorded lab walkthrough when exact terminal output, IDE edits, or live
   application behavior needs to be visible.

The website lesson, checkpoint, and tests remain authoritative. A generated video must never be the
only place where a command, API contract, expected result, or security requirement is documented.

### Required Source Pack

Each lesson's `notebooklm/` directory contains:

```text
notebooklm/
  00-lesson-brief.md
  01-concepts-and-request-flow.md
  02-reviewed-code-walk.md
  03-lab-and-expected-results.md
  04-failure-and-troubleshooting.md
  05-glossary.md
  06-video-steering-prompt.md
  source-manifest.yml
```

Source rules:

- Include only the framework release and checkpoint used by the lesson.
- Copy reviewed code excerpts rather than uploading the entire repository.
- Include exact source paths and commit or tag references.
- Explain which output comes from an LLM, AI Fabric, a provider, or application code.
- Remove secrets, private operational notes, and live customer data.
- Keep expected LLM wording outcome-based. Do not require a model to reproduce one exact paragraph.

### Video Steering Prompt Template

```text
Create a concise technical explainer for Java and Spring Boot developers.
Focus only on lesson [ID]: [TITLE].

Use the supplied AI Fabric 0.3.3 sources as authoritative.
Explain the business problem first, then the request flow, then the code changes,
then the verification and intentional failure case.

Clearly distinguish:
- application-owned domain logic
- AI Fabric orchestration and policy
- LLM or embedding provider behavior
- vector storage behavior

Do not invent classes, annotations, configuration properties, endpoints, performance numbers,
compliance claims, or expected LLM wording.
Use Java 21 and Spring Boot 4.1.x terminology.
End with the lesson's "done when" checks.
```

### Video Review Gate

Every generated video must be reviewed against:

- Current framework enum values and annotations.
- Current Maven coordinates and release version.
- Correct action handler and confirmation flow.
- Correct provider and vector responsibility.
- Correct test commands.
- No unsupported performance, uptime, accuracy, compliance, or production-readiness claims.
- No implication that deterministic fixtures are live AI.
- No raw secrets, PII, or private documentation.

Reject and regenerate a video when any code, API, or security statement is wrong. Editing the lesson
source is preferable when NotebookLM repeatedly misinterprets an ambiguous source pack.

After review, record the following in lesson front matter:

- Public or embedded video URL.
- Transcript path.
- Course source tag.
- Reviewer and review date.
- Output language.
- Any correction note that learners must see.

## Website Course Experience

### Routes

```text
/course
/course/quickstart
/course/core/:lessonSlug
/course/production/:lessonSlug
/course/case-studies/:caseSlug
/course/coding-assistants
/course/capstone
```

### Course Hub

The hub shows:

- Public title and outcome.
- Current pinned framework version.
- Quickstart call to action.
- Track cards with honest duration.
- Prerequisites.
- Continuing application architecture.
- Progress summary stored locally unless accounts are introduced later.
- Links to the learner repository, Maven Central, live demos, and community.

### Lesson Page

Each lesson page renders:

- Track, lesson number, duration, and version badge.
- Learning outcome.
- Embedded reviewed video when available.
- Architecture/request-flow visual.
- Exact files and code excerpts.
- Copyable commands and requests.
- Expected output and evidence fields.
- Intentional failure exercise.
- Test and completion checklist.
- Starter and solution checkpoint links.
- Reset and troubleshooting sections.
- Previous and next lesson navigation.
- Downloadable NotebookLM source pack for transparent source review.

The website must not execute fake AI responses. Interactive results must come from a deployed course
backend or be clearly labeled static expected-output examples.

### Website Content Sync

Add a repeatable command in `aifabric`, for example:

```bash
npm run course:sync -- --course-ref ai-fabric-course-v0.3.3.1
npm run course:verify
```

The exact implementation may clone the tagged framework repository or download the tagged archive.
The generated `source-manifest.json` must contain:

- Framework repository.
- Course source tag.
- Framework compatibility tag.
- Source commit.
- Course schema version.
- Synced file list and checksums.
- Generation timestamp.

## Course Manifest

Create `docs/course/course.yml` before website implementation. It is the machine-readable navigation
and release contract.

Minimum shape:

```yaml
schemaVersion: 1
courseId: ai-fabric-production-oriented-java
courseVersion: 0.3.3-course.1
title: Build Production-Oriented AI Workflows with Java and Spring Boot
subtitle: Semantic search, evidence-grounded RAG, governed actions, chat memory, and tenant security with AI Fabric
frameworkVersion: 0.3.3
frameworkTag: ai-fabric-framework-v0.3.3
courseSourceTag: ai-fabric-course-v0.3.3.1
javaVersion: 21
springBootVersion: 4.1.x
learnerRepository: https://github.com/Loom-AI-Labs/ai-fabric-course-support-assistant
tracks:
  - id: quickstart
    title: Quickstart
    required: true
    lessons:
      - id: qs-01
        slug: first-useful-result
        source: quickstart/01-first-useful-result/lesson.md
  - id: core
    title: Core Course
    required: true
    lessons:
      - id: core-01
        slug: mental-model
        source: core/01-mental-model/lesson.md
```

## Course CI And Release Gates

### Learner Checkpoint CI

Every starter and solution checkpoint must run in a clean environment:

1. Checkout only the learner repository.
2. Use Java 21.
3. Use an empty temporary Maven repository.
4. Resolve AI Fabric from Maven Central.
5. Run `./mvnw clean verify`.
6. Start the packaged app in the local profile.
7. Reset, seed, index, and execute the lesson smoke request.
8. Stop the app and upload test reports.

Do not install the framework reactor before this job. Doing so would hide a missing Maven Central
artifact or dependency.

### Documentation CI

The framework repository must validate:

- Course manifest schema.
- Every declared lesson and source path exists.
- Internal links resolve.
- Framework version, compatibility tag, and course source tag agree with the manifest.
- Code-backed annotation and enum references match current source.
- No stale `ActionAccessMode.WRITE` example exists.
- Real-app Maven commands use the reactor when sibling modules are required.
- Public demo count and names match the current real-app map.

### Website CI

The website must validate:

- Generated course checksums match the framework tag.
- Every manifest route renders.
- Previous and next navigation is complete.
- Code blocks and long paths fit desktop and mobile layouts.
- Videos have titles, captions when available, and source/version labels.
- Starter and solution links resolve.
- No unpublished lesson is visible in public navigation.

### Optional Live-Provider Gate

Live OpenAI tests remain a separate keyed job. It must:

- Run only lessons marked `requiresOpenAi: true`.
- Use real credentials from secret storage.
- Record provider/model diagnostics without printing credentials.
- Fail when the provider call fails.
- Never replace failure with a local canned answer.

## Release Blockers Before Recording

These issues are verified against the `0.3.3` source and must be fixed before course recording:

### Blocker 1: Governed Action Example

`docs/getting-started/05-first-governed-action.md` currently uses the nonexistent
`ActionAccessMode.WRITE` and omits the required `@ActionExecute` and `@Param` shape.

Required fix:

- Use `WRITE_ONLY` for the example write action.
- Add exactly one `@ActionExecute` method.
- Add typed `@Param` parameters.
- Return a current domain-shaped `ActionResult`.
- Add a documentation or example compilation test.

### Blocker 2: Standalone No-Key Installation

`docs/getting-started/02-installation.md` selects `memory` and `smoke`, but the listed minimal
dependencies do not provide the memory vector module or the example-only smoke providers.

Required fix:

- Make the public quickstart use ONNX plus Lucene, with a tested model-download step.
- Keep deterministic providers in learner tests only, clearly labeled test fixtures.
- Remove any suggestion that external applications receive `smoke-support` from Maven Central.

### Blocker 3: Clean Real-App Commands

`docs/getting-started/11-testing-and-verification.md` runs individual app POMs that depend on the
sibling `smoke-support` module.

Required command shape:

```bash
mvn -B -V --no-transfer-progress \
  -f examples/real-apps/pom.xml \
  -pl chat-capabilities-demo \
  -am test
```

Apply the same reactor form to smoke runtime instructions or explicitly install/package the reactor
first.

### Blocker 4: Website Documentation Drift

The framework and website copies of Getting Started content already differ. Implement the pinned
course synchronization contract before adding another manually copied content tree.

### Blocker 5: Public Demo Count

The previous curriculum mentioned four public demos. The case-study track now includes five:

1. AI Shopping Experience.
2. AI Fabric Account Resolver.
3. AI Fabric Behavior Signals.
4. AI Fabric Tenant Guard.
5. AI Fabric Privacy Shield.

## Implementation Backlog

### Phase 0: Correctness Gate

- [ ] Fix the governed-action guide and add compile-backed coverage.
- [ ] Replace the broken standalone smoke instructions.
- [ ] Correct real-app reactor commands.
- [ ] Align the framework and website real-app maps.
- [ ] Verify all Getting Started source links against `0.3.3`.

### Phase 1: Course Foundation

- [ ] Create `docs/course/course.yml`.
- [ ] Create the lesson directory structure.
- [ ] Create the standalone learner repository.
- [ ] Add Maven wrapper, local ONNX setup, reset, seed, and readiness contracts.
- [ ] Add clean Maven Central consumer CI.
- [ ] Publish immutable starter and solution checkpoints.

### Phase 2: Quickstart Beta

- [ ] Write QS-01 completely.
- [ ] Build the quickstart website page.
- [ ] Create the first NotebookLM source pack.
- [ ] Generate and technically review the first video.
- [ ] Run the quickstart with at least three external learners.
- [ ] Record setup time, failure points, and completion rate.
- [ ] Fix every reproducible blocker before recording the core track.

### Phase 3: Core Course

- [ ] Implement CORE-01 through CORE-07.
- [ ] Create and test every checkpoint.
- [ ] Publish lesson pages and reviewed videos incrementally.
- [ ] Add quizzes and completion checks.
- [ ] Run a second external learner beta.

### Phase 4: Production And Case Studies

- [ ] Implement PROD-01 through PROD-05.
- [ ] Publish all five code-backed case studies.
- [ ] Add optional live-provider CI.
- [ ] Add production and security review exercises.

### Phase 5: Coding Assistants And Capstone

- [ ] Publish the assistant context lesson and prompt sheet.
- [ ] Publish the capstone starter and rubric.
- [ ] Add project submission guidance.
- [ ] Collect learner projects, corrections, and testimonials with permission.

## Course Readiness Scorecard

| Area | Ready when |
| --- | --- |
| Technical correctness | Every snippet and checkpoint is compiled or exercised against the pinned release |
| Reproducibility | A clean learner machine can complete the quickstart without framework source |
| Lab quality | Every lesson satisfies the lesson contract |
| Website | Course manifest renders complete routes and navigation |
| Video | Every NotebookLM output passes technical review |
| Security | Unsafe writes, cross-tenant access, and hidden provider failures are tested |
| Versioning | Website, checkpoints, videos, and docs identify the same framework release |
| External proof | Beta learners complete the quickstart without maintainer intervention |

The course is ready for full public launch only when all eight areas pass. Until then, individual
lessons may be published as clearly versioned beta material.

## Authoritative References

Start with:

- `docs/getting-started/README.md`
- `docs/llm-context/AI_FABRIC_CONTEXT_INDEX.md`
- `docs/Framework-Dev-Guides/LLM-guides/AI_FABRIC_FRAMEWORK_PHILOSOPHY.md`
- `docs/Framework-Dev-Guides/LLM-guides/AI_FABRIC_LLM_SESSION_LESSONS_LEARNED.md`
- `docs/release-notes/0.3.3.md`
- `examples/real-apps/README.md`

NotebookLM product references:

- `https://support.google.com/notebooklm/answer/16215270`
- `https://support.google.com/notebooklm/answer/16454555`
