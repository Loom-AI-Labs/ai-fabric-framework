# Production Course Concepts Expansion Plan

Status: in progress  
Scope: AI Fabric course theory, production lessons, learner checkpoints, website presentation, and
verification.  
Canonical course source: `docs/course` in the framework repository.  
Learner application: `Loom-AI-Labs/ai-fabric-course-support-assistant`.  

## Executive Decision

Expand the planned Production Track so a Java developer can understand and implement the parts of
AI Fabric that sit between the first working Core application and a maintainable production system:

1. provider selection and purpose-specific LLM routing;
2. orchestration modes and application positions;
3. prompt bundles, curated packs, and application overlays;
4. AI application state and storage ownership;
5. migration/backfill of existing application data;
6. continuous upsert/delete synchronization after the backfill;
7. RAG and prompt quality gates;
8. managed vector storage and release operations.

The required path must remain reproducible without paid credentials. Real-provider calls are
explicitly marked gates or extensions, use deployment/CI secrets, fail visibly, and never silently
fall back to a local or fake provider.

This document is a tracking plan. It does not mark any new lesson as published and does not change
framework runtime behavior.

## Why This Work Is Needed

The published Quickstart and Core Course prove one vertical slice:

```text
semantic search
  -> evidence-grounded RAG
  -> governed action
  -> backend-owned memory
  -> tenant/privacy policy
  -> deterministic and packaged release proof
```

The current Production Track has useful placeholders for provider profiles, indexing/backfill, RAG
quality, Qdrant, and operations. It does not yet teach several framework concepts that real adopters
must understand:

- why orchestration and answer generation can use different models;
- why a UI position is not the same thing as an orchestration mode;
- how prompt overlays extend the framework without copying or editing framework defaults;
- which state belongs in the application database, vector store, chat-session store, behavior store,
  migration/indexing queues, and ephemeral caches;
- how an existing database is initially backfilled;
- how later creates, updates, and deletes remain synchronized.

These are not optional implementation trivia. They determine correctness, cost, durability,
security, and whether retrieved evidence remains aligned with application truth.

## Existing Course Baseline

As of `ai-fabric-course-v0.3.3.1`:

- QS-01 and CORE-01 through CORE-07 are published.
- The learner repository has immutable Core checkpoints from `course-0.3.3-00-starter` through
  `course-0.3.3-06-tested-solution`.
- The current Production Track contains five planned lesson entries and no published Production
  checkpoints.
- Every new lesson must retain the existing learner contract: exact starter, exact solution, commands,
  expected output, intentional failure, done-when checks, reset instructions, troubleshooting,
  knowledge check, assistant prompt, and independent review prompt.

Production work must begin from `course-0.3.3-06-tested-solution`; it must not rewrite or move the
published Core tags.

## Goals

1. Produce a coherent theory sequence before learners configure production concerns.
2. Turn the relevant concepts into executable lessons in the continuing Support Knowledge Assistant.
3. Keep source application data, derived vector evidence, durable conversational state, and
   ephemeral runtime state visibly separate.
4. Teach initial migration and continuous synchronization as different lifecycle operations.
5. Teach purpose-specific model configuration without requiring learners to buy API access.
6. Provide an optional real OpenAI path with exact secret/configuration guidance.
7. Preserve fail-visible behavior: no local response may masquerade as a successful remote LLM call.
8. Publish immutable, independently tested Production checkpoints.
9. Render lesson availability, key requirements, commands, and theory media clearly on the website.

## Non-Goals

- Do not add a platform control plane to the learner application.
- Do not require a managed vector database for the required path.
- Do not require an OpenAI key to compile, run tests, or complete the deterministic lesson contract.
- Do not teach AI Fabric as the source of truth for business entities or raw application events.
- Do not store API keys in `application.yml`, committed `.env` files, fixtures, screenshots, reports,
  browser storage, or course progress records.
- Do not treat an optional keyed check as passed when it was skipped.
- Do not make Core orchestration trust arbitrary client mode names or backend-owned identifiers.
- Do not expand this effort into document ingestion or multi-agent orchestration; those require their
  own course decisions after the corresponding framework capabilities are ready for external users.

## Code-Backed Capability Evidence

| Concept | Current code evidence | Course implication |
| --- | --- | --- |
| Purpose-specific models | `ai-fabric-core/.../core/LlmPurpose.java`, `AIProviderConfig.java`, and `AICoreServicePurposeRoutingTest.java` | Teach `ORCHESTRATION`, `GENERATION`, `EMBEDDINGS`, and `DEFAULT`, including fallback to global provider defaults. |
| Dynamic Spring AI model resolution | `ai-fabric-provider-spring-ai/.../SpringAiModelResolver.java` | Explain model/endpoint selection and caching without requiring native provider calls in application code. |
| Orchestration modes | `OrchestrationProperties.java` and `OrchestrationPolicyResolutionStep.java` | Teach modes as server-defined capability bundles controlling retrieval, actions, planning, suggestions, and budgets. |
| Application positions | `OrchestrationContext.position` and `examples/real-apps/chat-capabilities-demo/.../CommerceModeResolver.java` | Teach position as application/UI context. Map it to an allowlisted mode in the application layer. |
| Prompt overlays | `PromptBundleProperties.java`, `PromptTemplateResolver.java`, and `ClasspathPromptTemplateStore.java` | Teach ordered overlay resolution followed by base fallback under `prompts/<family>/<version>/<name>.md`. |
| Curated packs | `ai-fabric-curated-default`, `ai-fabric-curated-commerce`, and `ai-fabric-curated-support` | Teach packs as coherent defaults plus prompt overlays, not copied application prompt folders. |
| Vector evidence | `VectorDatabaseService.java` plus memory, Lucene, Pinecone, Qdrant, Weaviate, and Milvus modules | Separate derived semantic evidence from the application source database. |
| Chat history and pending work | `ChatSessionStorageProvider.java`, `DefaultDatabaseChatSessionStorage.java`, `ChatSessionPendingActionStore.java`, and `ChatSessionActionDraftStore.java` | Teach durable backend conversation state and why browsers send only the new message. |
| Behavior state | `ExternalEventProvider.java`, `BehaviorInsightStore.java`, and `BehaviorInsightsRepository.java` | Raw events remain application-owned; AI Fabric consumes events and stores derived insights through an SPI/JPA default. |
| Action registry | `ai-fabric-actions-registry` and `RegisteredConnectorActionRepository.java` | Explain optional database-backed action registration separately from action execution state. |
| Migration jobs | `DataMigrationService.java`, `MigrationProperties.java`, `MigrationJobRepository.java`, and `@AICapable.migrationRepository` | Build a real initial backfill lesson with job status, filters, batches, pause/resume, and idempotency evidence. |
| Incremental data sync | `AIDataSyncProperties.java`, `DataSyncService.java`, and `DataSyncController.java` | Build explicit trusted upsert/delete synchronization after migration. |
| Indexing queue | `IndexingQueueService` and `IndexingQueueRepository` in `ai-fabric-indexing` | Show asynchronous work, retries, status, and why indexing is not equivalent to writing the source database. |

Paths in the final course material must be repository-relative and pinned to the course framework tag.

## Accuracy Boundaries

### Position Is Not Mode

- `position` identifies application context such as `catalog`, `checkout`, `support`, or `account`.
- `mode` selects an allowlisted server behavior bundle.
- Core currently records the position but does not automatically apply
  `OrchestrationProperties.positionRouting` as a mode decision.
- The Shopping real app performs application-owned mapping through `CommerceModeResolver`.
- The lesson must not claim that adding `position-routing` YAML alone changes Core behavior.
- Before publishing the lesson, choose and document one supported pattern:
  - keep and teach a small application resolver; or
  - add a separately reviewed app/web-layer helper to AI Fabric and test it.
- Do not change Core routing semantics as part of course-writing work without a separate framework
  design decision.

### Raw Events Are Application-Owned

- The behavior module receives events through `ExternalEventProvider`.
- AI Fabric can persist derived `BehaviorInsights` through `BehaviorInsightStore` and its JPA default.
- The course must not describe AI Fabric as a general raw event warehouse.

### Migration Is Not Live Sync

- Migration/backfill reads existing entities in bounded batches and establishes initial vector state.
- Live Data Sync handles later create/update/delete operations through a trusted runtime boundary.
- Both converge on vector evidence, but they have different authorization, progress, retry, and
  operational contracts.

### Prompt Policy Is Not Business Authorization

- Prompts guide model behavior.
- Modes, access policy, trusted context, typed schemas, validation, and confirmation enforce behavior.
- No prompt lesson may imply that prompt wording replaces server controls.

## Target Theory Media

Create one complete, self-contained NotebookLM source script per video. Do not require NotebookLM to
combine scattered repository sources.

| Video ID | Working title | Required coverage | Paired lessons |
| --- | --- | --- | --- |
| `provider-architecture-purpose-routing` | Provider Architecture And Purpose-Specific Models | LLM versus embedding versus vector providers; `LlmPurpose`; global defaults; orchestration/generation overrides; endpoint profiles; visible failure; cost/latency trade-offs | PROD-01 |
| `modes-positions-orchestration-policy` | Modes, Positions, And Orchestration Policy | profile, requested/default mode, allowlists, position context, app-owned mapping, retrieval/action gates, planning and RAG budgets | PROD-02 |
| `prompt-bundles-curated-overlays` | Prompt Bundles, Curated Packs, And Application Overlays | base version, ordered overlays, classpath resource shape, curated packs, app delta, regression tests, prompt versus policy | PROD-03 and PROD-06 |
| `ai-fabric-state-storage-map` | State And Storage In An AI Fabric Application | source DB, vectors, chat sessions, pending actions/drafts, migration jobs, indexing queue, raw events versus insights, action registry, cache/diagnostics, restart boundaries | PROD-04, PROD-05, PROD-08 |
| `migration-backfill-live-sync` | From Existing Data To Continuous AI Evidence | initial backfill, stable IDs, batching, queue/worker, upsert/delete, source-of-truth ownership, reindexing, recovery and readiness | PROD-04 and PROD-05 |

Each video script must include:

- a concrete Support Knowledge Assistant request/data flow;
- a component and ownership diagram described in narration;
- one incorrect architecture and why it fails;
- one failure path that remains visible;
- terminology matching the current release;
- a final bridge into the hands-on lesson.

## Target Production Track

The current five planned entries should be replaced before any Production lesson is published. Since
the entries are still unavailable, progress records do not yet depend on their IDs.

| Order | ID | Title | Duration | Required key posture |
| ---: | --- | --- | ---: | --- |
| 1 | PROD-01 | Provider Routing And Purpose-Specific Models | 80 min | Keyless required; OpenAI gate optional for learners and required for maintainer release evidence |
| 2 | PROD-02 | Modes, Positions, And Orchestration Policy | 75 min | Keyless required; optional OpenAI observation |
| 3 | PROD-03 | Prompt Management And Application Overlays | 80 min | Keyless regression required; optional OpenAI observation |
| 4 | PROD-04 | Backfill Existing Application Data | 95 min | No external key |
| 5 | PROD-05 | Keep Application Data Synchronized | 85 min | No external key |
| 6 | PROD-06 | RAG Quality And Prompt Regression | 75 min | Keyless deterministic gate; optional OpenAI evaluator/generation evidence |
| 7 | PROD-07 | Move To A Managed Vector Provider | 90 min | Local Docker Qdrant requires no key; Qdrant Cloud is optional and keyed |
| 8 | PROD-08 | Operations And Release Readiness | 70 min | Keyless release gate; optional deployed live-provider gate |

## Lesson Specifications

### PROD-01: Provider Routing And Purpose-Specific Models

Learner outcome:

- keep ONNX embeddings local;
- configure separate orchestration and generation model settings;
- prove purpose propagation and provider/model diagnostics;
- observe an invalid/missing credential failure without fallback;
- optionally run one real OpenAI orchestration and generation flow.

Required keyless implementation:

- recording/test providers prove calls are tagged with the expected `LlmPurpose`;
- local ONNX and Lucene remain the embedding/vector path;
- `ai.providers.enable-fallback=false` prevents a missing live provider from being hidden;
- tests assert orchestration and generation receive different configured model identities.

Optional real-provider gate:

- requires `OPENAI_API_KEY`;
- uses the `openai` Spring profile;
- may use `OPENAI_MODEL`, `AI_ORCHESTRATION_MODEL`, and `AI_GENERATION_MODEL` overrides;
- records provider, model, purpose, status, latency, and usage without logging the key, prompt, or
  sensitive response content;
- includes a deliberately invalid-key check that fails clearly and never returns a local answer.

### PROD-02: Modes, Positions, And Orchestration Policy

Learner outcome:

- define at least two allowlisted modes:
  - a retrieval-oriented `support_assistant` mode;
  - a governed `support_resolver` mode with actions enabled;
- pass an application position such as `knowledge` or `ticket`;
- map positions in an application-owned resolver only when the request did not explicitly select an
  approved mode;
- reject or safely ignore unknown modes according to strict routing configuration;
- prove mode effects through orchestration-policy metadata and action/retrieval behavior.

No external key is required. Optional OpenAI calls may demonstrate natural intent behavior, but all
mode enforcement tests must remain deterministic.

Publication prerequisite:

- resolve the position-routing accuracy boundary documented above;
- test explicit mode, default mode, mapped position, unknown mode, and unknown position separately.

### PROD-03: Prompt Management And Application Overlays

Learner outcome:

- keep the complete `v1` base prompt bundle;
- add a small `v1-course-support` application overlay under
  `src/main/resources/prompts/<family>/<version>/<name>.md`;
- configure ordered overlays through `ai.prompts.bundle.overlays`;
- prove overlay-first and base-fallback resolution;
- improve one support follow-up without hardcoding a scenario into Java;
- add prompt packaging and behavior regression tests.

No real key is required for template-resolution and regression tests. A real OpenAI call is optional
and must be labelled as model observation, not deterministic proof.

### PROD-04: Backfill Existing Application Data

Learner outcome:

- add a realistic set of existing support articles to the application database;
- declare a migration repository through the supported `@AICapable` contract;
- configure migration field selection without indexing secrets or private fields;
- start a bounded migration job;
- observe queued, running, paused/resumed, completed, cancelled, and failed states as applicable;
- rerun the same migration without duplicate vector identity;
- prove source row count, successful indexing count, skipped count, failure count, and retrieval.

Required stack:

- H2 or the learner application's existing JPA database;
- local ONNX embeddings;
- local Lucene vector storage;
- `ai-fabric-migration` and `ai-fabric-indexing`;
- no external API key.

### PROD-05: Keep Application Data Synchronized

Learner outcome:

- enable `ai.data-sync.enabled=true` only behind a trusted application boundary;
- upsert a new support article;
- update it while preserving stable entity identity;
- delete it from semantic evidence;
- prove a stale vector is not returned after delete;
- exercise batch limits, vector-space allowlists, access denial, invalid content, and partial failure;
- compare Data Sync traces with migration job evidence.

No external API key is required with ONNX and Lucene. The lesson must explicitly warn that
`allowTrustedPlatformInternalSyncBypass` stays false unless a trusted backend verifies and injects
the exact platform/system context.

### PROD-06: RAG Quality And Prompt Regression

Learner outcome:

- define golden questions and expected evidence IDs;
- test no-source, wrong-tenant, stale-source, and insufficient-context cases;
- test prompt-overlay behavior without asserting exact prose;
- expose provider and retrieval failures rather than returning generic success text;
- optionally compare live generated answers while retaining deterministic evidence assertions.

OpenAI is optional. If used, `OPENAI_API_KEY` supplies generation/evaluation only; evidence selection,
tenant filtering, and source-ID assertions remain keyless and deterministic.

### PROD-07: Move To A Managed Vector Provider

Learner outcome:

- replace Lucene with local Docker Qdrant while preserving the application retrieval contract;
- create required collection/payload-index configuration;
- prove dimensions, metadata filters, tenant isolation, upsert, delete, readiness, and visible failure;
- compare local provider diagnostics with Qdrant diagnostics;
- optionally repeat against Qdrant Cloud.

Required path:

- Docker-hosted local Qdrant;
- no Qdrant API key;
- ONNX embeddings remain keyless.

Optional cloud path:

- `AI_PROVIDERS_QDRANT_HOST`;
- `AI_PROVIDERS_QDRANT_GRPC_PORT`;
- `AI_PROVIDERS_QDRANT_API_KEY` stored as a secret;
- TLS/endpoint settings documented for the selected Qdrant deployment;
- OpenAI credentials are not required unless the learner explicitly switches embeddings.

### PROD-08: Operations And Release Readiness

Learner outcome:

- package and run the application from its built JAR/container;
- prove source-derived build metadata;
- prove database, vector, session, and migration/indexing readiness independently;
- verify startup with missing optional keys and fail-fast behavior when a live provider is explicitly
  enabled without its key;
- retain keyless and keyed evidence as separate artifacts;
- test cleanup/retention and restart behavior without erasing application-owned data accidentally.

The required release gate is keyless. A maintainer may run a separately named deployed OpenAI smoke
using deployment secrets.

## Storage And Ownership Matrix

This matrix should become a course reference and a visual website element.

| State | Source of truth | Default/available implementation | Durability expectation | Lesson proof |
| --- | --- | --- | --- | --- |
| Business entities | Application | Application JPA/database | Durable, application-owned | Migration reads without changing business ownership |
| Semantic evidence | AI Fabric vector provider | Memory, Lucene, Pinecone, Qdrant, Weaviate, Milvus | Provider-dependent, derived/rebuildable | Search, update, delete, readiness |
| Indexing work | AI Fabric indexing module | JPA `IndexingQueueRepository` | Durable when JPA-backed | Queued/retry/failure visibility |
| Migration jobs | AI Fabric migration module | JPA `MigrationJobRepository` | Durable progress/control state | Pause/resume/cancel/idempotency |
| Chat sessions/turns | AI Fabric chat-session SPI | Default JPA or custom Redis/Mongo/etc. provider | Durable for production conversations | Follow-up works after browser sends only new turn |
| Pending actions/drafts | Core or chat-session adapter | In-memory Core default; chat-session-backed durable stores | Durable when confirmation must survive restart | Confirmation survives/rejects correctly |
| Raw behavior events | Application/platform | `ExternalEventProvider` adapter | Application-defined | AI Fabric reads fresh events without becoming event warehouse |
| Behavior insights | AI Fabric behavior SPI | JPA `BehaviorInsightsRepository` default or custom store | Durable derived insight | Previous insight plus new-event analysis |
| Registered actions | Optional action registry | JPA action registry | Durable when dynamic registration is used | Discovery/approval/deregistration |
| Prompt templates | Application/framework classpath | Versioned Markdown resources | Versioned with release | Overlay resolution and packaging tests |
| Caches/debug snapshots | Runtime | In-memory/cache implementation | Ephemeral unless explicitly externalized | No correctness dependency after restart |

## Credential And Configuration Contract

### Default Rule

Every lesson must start with a keyless profile:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

The keyless profile uses local ONNX embeddings and Lucene (or local Docker Qdrant in PROD-07). It
must never claim that a cloud LLM generated a response.

### Application Configuration Shape

The learner application should reference environment variables and keep secrets out of YAML:

```yaml
ai:
  providers:
    llm-provider: ${AI_LLM_PROVIDER:openai}
    embedding-provider: ${AI_EMBEDDING_PROVIDER:onnx}
    enable-fallback: false
    orchestration:
      llm-provider: ${AI_ORCHESTRATION_PROVIDER:openai}
      model: ${AI_ORCHESTRATION_MODEL:${OPENAI_MODEL:gpt-4o-mini}}
      temperature: 0.1
    generation:
      llm-provider: ${AI_GENERATION_PROVIDER:openai}
      model: ${AI_GENERATION_MODEL:${OPENAI_MODEL:gpt-4o-mini}}
      temperature: 0.3
    openai:
      enabled: ${OPENAI_ENABLED:false}
      api-key: ${OPENAI_API_KEY:}
      base-url: ${OPENAI_BASE_URL:https://api.openai.com/v1}
      model: ${OPENAI_MODEL:gpt-4o-mini}
      embedding-model: ${OPENAI_EMBEDDING_MODEL:text-embedding-3-small}
```

The exact final variables must be validated against the learner application before publication. Do
not document both an application alias and a direct Spring property unless both are tested.

### Where Learners Provide A Key

Local terminal:

```bash
export OPENAI_ENABLED=true
export OPENAI_API_KEY="<set locally; never commit>"
export OPENAI_MODEL="gpt-4o-mini"
./mvnw spring-boot:run -Dspring-boot.run.profiles=openai
```

IDE:

- add `OPENAI_ENABLED`, `OPENAI_API_KEY`, and model overrides to the run configuration's environment
  field;
- do not add them to project files or shared run configurations.

Docker:

```bash
docker run --rm \
  -e OPENAI_ENABLED=true \
  -e OPENAI_API_KEY \
  -e OPENAI_MODEL=gpt-4o-mini \
  -p 8080:8080 \
  <course-image>
```

GitHub Actions:

```yaml
env:
  OPENAI_ENABLED: "true"
  OPENAI_API_KEY: ${{ secrets.OPENAI_API_KEY }}
  OPENAI_MODEL: gpt-4o-mini
```

Deployment platform:

- create `OPENAI_API_KEY` in the platform's encrypted secret/environment UI;
- set `OPENAI_ENABLED=true` only for the live-provider deployment/profile;
- keep keyless readiness and health endpoints functional without revealing secret presence/value;
- rotate the key through the provider and deployment secret store, not through a source commit.

If a learner chooses a local ignored `.env` file, the lesson must first prove that `.env` is ignored.
The course must not depend on implicit `.env` loading unless the learner application implements and
tests it explicitly.

### Key Requirement Matrix

| Capability | Required path | Optional live path | Secret variables |
| --- | --- | --- | --- |
| ONNX embeddings | Keyless | None | None |
| Lucene vectors | Keyless | None | None |
| Orchestration/generation | Recording provider tests | OpenAI | `OPENAI_API_KEY`; optional model/base URL overrides |
| Prompt regression | Keyless templates/fixtures | OpenAI observation | `OPENAI_API_KEY` |
| Migration/backfill | Keyless | None | Database credentials only if learner replaces H2 |
| Data Sync | Keyless | None | Application auth token if the lesson adds one; never provider key |
| Qdrant local | Keyless Docker | Qdrant Cloud | `AI_PROVIDERS_QDRANT_API_KEY` plus host/port/TLS settings |
| OpenAI embeddings | Not required | Optional extension | `OPENAI_API_KEY`, `OPENAI_EMBEDDING_MODEL`, optional dimensions |
| Other LLM providers | Not required | Optional post-course extension | Provider-specific secret such as `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, or Azure credentials |

### Secret-Safety Acceptance Tests

- [ ] Missing key with `OPENAI_ENABLED=false` leaves the keyless profile healthy.
- [ ] Missing key with `OPENAI_ENABLED=true` fails with a clear provider/configuration error.
- [ ] Invalid key fails visibly and does not return deterministic/local generated content.
- [ ] Logs, Actuator output, diagnostics, test reports, and screenshots do not contain the key.
- [ ] Prompt, completion, tool arguments, transient URLs, and PII are not added to provider diagnostic
  artifacts by default.
- [ ] Git history and generated website assets contain no credential.
- [ ] Keyed tests identify provider/model/purpose and whether the check actually ran.

## Learner Checkpoint Strategy

Use the published Core solution as the first Production starter:

```text
course-0.3.3-06-tested-solution
  -> course-0.3.3-p01-provider-routing
  -> course-0.3.3-p02-modes-positions
  -> course-0.3.3-p03-prompt-overlays
  -> course-0.3.3-p04-migration-backfill
  -> course-0.3.3-p05-live-data-sync
  -> course-0.3.3-p06-rag-quality
  -> course-0.3.3-p07-qdrant
  -> course-0.3.3-p08-production-ready
```

These are proposed names. Confirm the course patch version before publishing. Tags are immutable
after publication.

Every checkpoint must pass from a clean clone:

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

Every runnable checkpoint must also:

- boot the packaged JAR, not only `spring-boot:run`;
- execute its documented reset/seed/readiness flow;
- execute the lesson's new HTTP/API behavior;
- stop cleanly;
- retain a concise machine-readable smoke report;
- avoid external credentials on the mandatory path.

The final Production checkpoint extends `scripts/smoke-packaged.sh` to cover migration, Data Sync,
purpose diagnostics, durable session behavior, and configured vector-provider readiness.

## Lesson Artifact Contract

For each PROD lesson create:

```text
docs/course/production/<NN>-<slug>/
  lesson.md
  knowledge-check.yml
  assistant-prompt.md
  assistant-review-prompt.md
```

Each lesson must provide:

- framework version and exact starter/solution tags;
- estimated time;
- theory video ID and transcript/source link;
- exact dependencies and files to edit;
- copyable keyless commands;
- an explicitly separated optional keyed section;
- expected status codes and meaningful response fields;
- one intentional failure;
- one security/ownership boundary;
- tests to add;
- done-when checklist;
- reset and cleanup;
- troubleshooting with provider/key diagnostics;
- five to eight knowledge-check questions;
- implementation and review prompts for coding assistants.

## Website Requirements

- Show `No external key required` on every required lesson path.
- Show `Optional OpenAI exercise` or `Optional Qdrant Cloud exercise` beside the relevant section,
  not as a global course requirement.
- Provide a collapsible `Configure a real provider` panel with terminal, IDE, Docker, CI, and
  deployment guidance.
- Never render an input that asks learners to paste provider keys into the public website.
- Keep English/Arabic video selection behavior; use the English video when an Arabic version is not
  available and label that fallback.
- Show exact learner checkpoint links.
- Show whether assistant prompt validation, keyless CI, packaged smoke, and optional keyed evidence
  passed for the published lesson.
- Make storage ownership and migration-versus-sync diagrams available as developer references.

## Implementation Phases

### P0: Curriculum Contract

- [x] Update `docs/course/course.yml` from five planned Production lessons to PROD-01 through PROD-08.
- [x] Update the Production Track sections and prompt inventory in
  `AI_FABRIC_EXTERNAL_USER_COURSE.md`.
- [x] Add planned theory media and lesson dependencies.
- [x] Confirm Production checkpoint naming; retain `0.3.3-course.1-beta` while Production lessons
  remain previews.
- [x] Resolve/document the position-routing accuracy boundary.
- [x] Add the storage ownership and key-requirement matrices to canonical course material.
- [x] Run course schema/content verification.

### P1: Theory Sources

- [x] Create `PROVIDER_ARCHITECTURE_PURPOSE_ROUTING_NOTEBOOKLM_SCRIPT.md`.
- [x] Create `MODES_POSITIONS_ORCHESTRATION_POLICY_NOTEBOOKLM_SCRIPT.md`.
- [x] Create `PROMPT_BUNDLES_CURATED_OVERLAYS_NOTEBOOKLM_SCRIPT.md`.
- [x] Create `AI_FABRIC_STATE_STORAGE_MAP_NOTEBOOKLM_SCRIPT.md`.
- [x] Create `MIGRATION_BACKFILL_LIVE_SYNC_NOTEBOOKLM_SCRIPT.md`.
- [ ] Review every script against current code and the framework philosophy.
- [ ] Generate/review English videos; add Arabic versions when available.
- [ ] Register final video IDs in the course video catalog.

### P2: Provider, Mode, And Prompt Lessons

- [x] Build PROD-01 lesson, tests, assistant prompts, and checkpoint.
- [x] Run PROD-01 keyless verification: 42 tests and packaged ONNX/Lucene HTTP smoke pass.
- [ ] Run and retain PROD-01 OpenAI maintainer evidence using repository/deployment secrets.
- [x] Build PROD-02 lesson, application mode resolver, tests, and checkpoint.
- [x] Build PROD-03 overlay resources, prompt regression tests, and checkpoint.
- [x] Verify no lesson relies on prompt text for authorization.

### P3: Migration And Live Sync Lessons

- [x] Add realistic pre-existing support data and migration repository registration.
- [x] Build PROD-04 job lifecycle, idempotency, failure, and retrieval tests.
- [x] Publish PROD-04 checkpoint only after a clean packaged runtime smoke.
- [x] Build PROD-05 trusted upsert/update/delete flows.
- [x] Test stale-vector deletion, access denial, limits, and partial failure.
- [x] Publish PROD-05 checkpoint only after a clean packaged runtime smoke.

### P4: Quality, Managed Vector, And Operations

- [x] Build PROD-06 golden evidence and prompt regression scorecard.
- [ ] Run optional OpenAI answer/evaluator checks without replacing deterministic assertions.
- [ ] Build PROD-07 local Docker Qdrant profile and provider contract smoke.
- [ ] Optionally verify Qdrant Cloud through protected CI/deployment secrets.
- [ ] Build PROD-08 packaged, restart, cleanup, metadata, and readiness evidence.
- [ ] Publish final Production checkpoint.

### P5: Website And Release

- [ ] Sync canonical content into `aifabric`.
- [ ] Render all eight Production lessons and five theory videos.
- [ ] Add key-posture labels and configuration panels.
- [ ] Run desktop/mobile course E2E tests.
- [ ] Validate all external checkpoint and canonical-source links.
- [ ] Tag the canonical course source.
- [ ] Retain keyless CI and separately named keyed evidence.
- [ ] Publish release notes describing new lessons without implying optional providers are required.

## Verification Matrix

| Gate | Scope | Credentials | Blocking |
| --- | --- | --- | --- |
| Markdown/YAML schema | Framework course content | None | Yes |
| Learner `clean verify` | Every starter/solution checkpoint | None | Yes |
| Packaged HTTP smoke | Every runnable checkpoint | None | Yes |
| Local ONNX/Lucene | Search, RAG evidence, migration, sync | None | Yes |
| Local Qdrant Testcontainers/Docker | Managed vector lesson | None | Yes for PROD-07 |
| Prompt packaging/regression | Base/overlay/fallback | None | Yes |
| OpenAI live provider | Purpose routing and optional generation | `OPENAI_API_KEY` | Yes for maintainer evidence before claiming live compatibility; optional for learner completion |
| Qdrant Cloud | Optional cloud extension | Qdrant secret | No unless cloud support is advertised as verified for that course release |
| Website unit/build/E2E | Course presentation | None | Yes |
| Deployed website smoke | Served course routes/assets/links | None | Yes |

Skipped keyed checks must report `SKIPPED: credential not supplied`; they must not report `PASS`.

## Risks And Mitigations

| Risk | Mitigation |
| --- | --- |
| Production Track becomes too broad | Keep one continuing application, one outcome per checkpoint, and theory separate from lab steps. |
| Position/mode behavior is taught incorrectly | Resolve the current app-layer mapping contract before publishing PROD-02. |
| Learners believe vectors are the source database | Repeat the ownership matrix in migration, sync, and managed-vector lessons. |
| Raw behavior events are described as framework-owned | Teach `ExternalEventProvider` and derived insight storage explicitly. |
| Paid keys block completion | Keep mandatory tests and packaged runtime keyless; isolate optional real-provider work. |
| Fallback hides an invalid provider configuration | Set `ai.providers.enable-fallback=false` in live labs and assert visible failure. |
| Secrets leak into course artifacts | Use environment/secret stores, redaction tests, and repository scans before publication. |
| Model output makes tests unstable | Assert structured decisions, evidence IDs, provider purpose, and policy outcomes rather than exact prose. |
| Migration duplicates or leaves stale evidence | Use stable entity IDs, idempotency tests, update/delete tests, and readiness counts. |
| Course docs drift from code | Pin framework/course tags and run clean checkpoint plus website verification in CI. |

## Definition Of Done

This plan is complete only when:

- [ ] Five code-accurate theory scripts and reviewed videos are published.
- [ ] PROD-01 through PROD-08 are learner-ready and marked published in `course.yml`.
- [ ] Every lesson has immutable starter/solution checkpoints.
- [ ] Every checkpoint passes clean compilation, tests, packaged startup, and its documented HTTP flow.
- [ ] Keyless completion is possible for the entire required path.
- [ ] OpenAI-dependent claims have separate retained keyed evidence.
- [ ] Secret configuration is documented for terminal, IDE, Docker, CI, and deployment environments.
- [ ] No secret appears in Git history, logs, reports, screenshots, or website assets.
- [ ] Position/mode, raw-event ownership, migration/backfill, and live-sync boundaries are taught
  accurately.
- [ ] Website desktop/mobile tests pass and all source/checkpoint links resolve.
- [ ] The canonical framework course and website identify the same course/framework versions.

## Tracking Summary

| Workstream | Status |
| --- | --- |
| Curriculum contract | Complete |
| Theory scripts/videos | In progress: five complete NotebookLM source scripts are ready; recordings and catalog IDs remain |
| Provider/mode/prompt lessons | Complete: PROD-01 through PROD-03 checkpoints and canonical lessons verified |
| Migration/live-sync lessons | Complete: PROD-04 and PROD-05 checkpoints and canonical lessons verified |
| Quality/Qdrant/operations lessons | In progress: PROD-06 quality checkpoint complete; Qdrant and operations remain |
| Learner checkpoints | In progress: PROD-01 through PROD-06 checkpoints published |
| Keyless verification | In progress: PROD-01 through PROD-06 clean verification and packaged smokes pass |
| Keyed maintainer verification | Not started |
| Website course support | In progress: PROD-01 preview route and key posture implemented |
| Course release | Not started |
