# AI Fabric Framework Capability Priority Map

Status: draft for release planning  
Date: 2026-06-20  
Sources: `docs/Framework-Dev-Guides/**`, `docs/guides/03-modules.md`, and current real apps under `examples/real-apps`

## Purpose

This document captures the release-facing capability inventory for AI Fabric and prioritizes what
must be proven with tests, real apps, or manual verification before release.

It is intentionally broader than the real-app list. The real apps prove many framework paths, but
the framework documentation includes additional capabilities around actions, relay, connectors,
managed vector providers, marketplace plugins, runtime security, deployment verification, and UI
integration.

## Priority Key

| Priority | Meaning |
| --- | --- |
| P0 | Release gate. Must be proven with automated tests and/or deterministic real-app smoke flows. |
| P1 | Important framework capability. Should be covered before release if feasible, or immediately after as a focused hardening track. |
| P2 | Advanced provider, platform, or deployment capability. Good fit for nightly/manual/provider-matrix verification. |
| P3 | Operator, marketplace, documentation, or ecosystem workflow. Important for product maturity but not a core framework runtime gate. |

## Capability Inventory

### P0 Release Gates

Current status: P0 is closed when the automatic CI gate passes all release guards, framework unit
tests, Docker-backed vector provider contracts, integration-suite test compilation, minimal consumer
compile, real-app package/tests, real-app boot smoke, and the ecommerce-to-chat data-sync runtime
smoke. The final cross-app smoke is `.github/scripts/smoke-ecommerce-chat-datasync.sh`.

| Capability | Guide Evidence | Current Proof Target |
| --- | --- | --- |
| Core Spring Boot starter and auto-configuration | `03-modules.md`, `CI_PIPELINE_GUIDE.md` | all real apps package/tests |
| Orchestration pipeline: security, access, PII, compliance, intent, action/RAG handling | `Orchestrator_User_Guide.md`, `NORMALIZATION_AND_ORCHESTRATION_GUIDE.md` | core tests, chat app |
| Provider-agnostic result normalization | `NORMALIZATION_AND_ORCHESTRATION_GUIDE.md`, ADR-0006/0007 | core/orchestration tests |
| Progressive intent extraction | `CONFIGURATION_AND_OPTIMIZATION_GUIDE.md`, ADR-0006 | core tests, chat app |
| Standard multi-message LLM request contract | `LLM_STANDARD_CHAT_PROMPTING_GUIDE.md` | provider/core tests |
| Local action discovery/execution with `@AIAction` and `@ActionExecute` | `ACTIONS_AND_CONFIRMATION_INTERCEPTORS_GUIDE.md` | `chat-capabilities-demo`, `it-support-action-bot`, sub-management apps |
| Action access modes: `READ`, `WRITE_ONLY`, `READ_WRITE` | `ACTIONS_AND_CONFIRMATION_INTERCEPTORS_GUIDE.md` | chat and action-bot handlers |
| Action authorization with `@ActionAllowed` | `ACTIONS_AND_CONFIRMATION_INTERCEPTORS_GUIDE.md` | action-bot, sub-management apps |
| Action confirmation with `@ActionConfirmation` | `ACTIONS_AND_CONFIRMATION_INTERCEPTORS_GUIDE.md` | chat app, action-bot, sub-management apps |
| Pending action store and yes/no confirmation flow | `ACTIONS_AND_CONFIRMATION_INTERCEPTORS_GUIDE.md`, chat-session docs | chat-session tests, chat app |
| Confirmation interceptors | `ACTIONS_AND_CONFIRMATION_INTERCEPTORS_GUIDE.md` | `CancellationRetentionOfferResolver` in chat app |
| Chat sessions: turn recording, memory, pending action stack | `ACTIONS_AND_CONFIRMATION_INTERCEPTORS_GUIDE.md`, `CHAT_CAPABILITIES_UI_MIGRATION_GUIDE.md` | `chat-capabilities-demo` |
| RAG indexing lifecycle: extract, embed, store, retrieve | `RAG_INDEXING_LIFECYCLE_GUIDE.md` | `smart-faq-assistant`, chat runtime |
| Vector lifecycle: store, search, update, delete, clear, count, scan | `RAG_INDEXING_LIFECYCLE_GUIDE.md`, vector provider guides | core tests, chat/ecommerce proof |
| Docker-backed vector provider parity: Qdrant REST, Qdrant gRPC, Weaviate, Milvus | `CI_PIPELINE_GUIDE.md`, `VERIFICATION_PLAYBOOK.md` | automatic `Vector Provider Container Contracts` CI job |
| Data Sync push API: vector spaces, upsert, delete, batch | `DATA_SYNC_PUSH_API_GUIDE.md` | `ecommerce-store -> chat-capabilities-demo` |
| Data Sync verified auth context and fail-closed authorization | `DATA_SYNC_PUSH_API_GUIDE.md`, `RUNTIME_AUTHORIZATION_AND_ACCESS_CONTROL_GUIDE.md` | ecommerce data-sync client tests, runtime proof |
| Embedding query composition and retrieval query hints | `RAG_EMBEDDING_QUERY_COMPOSITION_GUIDE.md`, ADR-0009 | core tests, chat app deep retrieval |
| PII detection/redaction in orchestration | `Orchestrator_User_Guide.md`, PII module docs | `privacy-first-customer-facing-support` |
| Governance deletion orchestration | `GOVERNANCE_AND_COMPLIANCE_GUIDE.md` | privacy app, governance tests |
| Index catalog modes: `AUTO`, `VECTOR`, `SQL`, `DISABLED` | `GOVERNANCE_AND_COMPLIANCE_GUIDE.md`, ADR-0004 | governance tests |
| Stable vector metadata timestamps | `GOVERNANCE_AND_COMPLIANCE_GUIDE.md`, ADR-0004 | vector management tests |
| Runtime authorization through `EntityAccessPolicy` | `RUNTIME_AUTHORIZATION_AND_ACCESS_CONTROL_GUIDE.md` | core/data-sync/governance tests |
| Runtime/admin endpoint protection | `RUNTIME_AUTHORIZATION_AND_ACCESS_CONTROL_GUIDE.md` | app/admin smoke where available |
| Deterministic real-app smoke profile | `CI_PIPELINE_GUIDE.md`, `examples/real-apps/README.md` | real-app package and smoke flows |

### P1 Hardening Track

| Capability | Guide Evidence | Recommended Proof |
| --- | --- | --- |
| Connector-backed actions through Customer Connector API | `ACTIONS_CONNECTOR_AND_RELAY_GUIDE.md`, `CUSTOMER_CONNECTOR_IMPLEMENTATION_GUIDE.md` | add real-app or harness using `ai-fabric-actions-connector` |
| File-based connector action catalog loading and validation | `ACTIONS_CONNECTOR_AND_RELAY_GUIDE.md` | connector module tests plus app smoke |
| Connector action idempotency, retry, and error contract | `ACTIONS_CONNECTOR_AND_RELAY_GUIDE.md` | connector module contract tests |
| DB-backed action registry | `ACTIONS_CONNECTOR_AND_RELAY_GUIDE.md` | action-registry tests and boot smoke |
| Action registry Liquibase helper | `ACTIONS_CONNECTOR_AND_RELAY_GUIDE.md` | registry-liquibase tests |
| Packaged action confirmation/interceptor HTTP smoke | `ACTIONS_AND_CONFIRMATION_INTERCEPTORS_GUIDE.md`, chat-session docs | `chat-capabilities-demo` smoke-profile script that triggers a confirmable cancel action, verifies the retention-offer confirmation interceptor, and proves final action execution/cancel behavior |
| AI Fabric Relay `/actions/execute` | `ACTIONS_CONNECTOR_AND_RELAY_GUIDE.md`, `RELAY_IMPLEMENTATION_AND_DEPLOYMENT_GUIDE.md` | relay module tests plus local relay smoke |
| Relay auth, replay protection, rate limiting, idempotency | `RELAY_IMPLEMENTATION_AND_DEPLOYMENT_GUIDE.md` | relay unit/contract tests |
| Retrieval connector `/retrieval/search` documents-only boundary | `RETRIEVAL_CONNECTOR_GUIDE.md` | retrieval connector tests plus local stub connector |
| Spring AI guarded action tool-calling bridge | `ACTIONS_AND_CONFIRMATION_INTERCEPTORS_GUIDE.md`, `SPRING_AI_PROVIDER_INTEGRATION_GUIDE.md` | provider tests and app-level read-only tool smoke |
| Request-scoped Spring AI advisors | `SPRING_AI_PROVIDER_INTEGRATION_GUIDE.md` | provider tests |
| Redacted Spring AI observation diagnostics | `SPRING_AI_PROVIDER_INTEGRATION_GUIDE.md` | provider tests and ops endpoint smoke |
| Post-action generation from handler-shaped facts | `POST_ACTION_GENERATION_FOR_ACTION_HANDLERS_GUIDE.md` | action handler test/app scenario |
| Relationship query: natural language to JPQL/traversal | relationship guides, ADR-0007 | `relationship-query-crm-insights` |
| Packaged relationship-query scenario smoke | relationship guides, ADR-0007 | deterministic `relationship-query-crm-insights` HTTP smoke for natural language question -> structured relationship result |
| Relationship query plan repair and structured failures | ADR-0007 | relationship-query tests |
| Migration/backfill lifecycle | `MIGRATION_BACKFILL_GUIDE.md` | `migration-enabled-product-catalog` |
| Packaged migration/backfill smoke | `MIGRATION_BACKFILL_GUIDE.md` | deterministic `migration-enabled-product-catalog` HTTP smoke that seeds catalog data, runs/retries backfill, and proves indexed counts/status evidence |
| Indexing queue, retry, dead-letter, worker behavior | `RAG_INDEXING_LIFECYCLE_GUIDE.md` | indexing module tests |
| Spring AI document reader/chunker ingestion bridge | `RAG_INDEXING_LIFECYCLE_GUIDE.md` | indexing Spring AI document tests |
| Advanced RAG expansion, reranking, context optimization | `RAG_INDEXING_LIFECYCLE_GUIDE.md` | rag tests, smart-faq quality gate |
| Packaged RAG golden-answer HTTP smoke | `RAG_INDEXING_LIFECYCLE_GUIDE.md` | `smart-faq-assistant` smoke-profile script that loads the golden FAQ set and fails closed when expected source/evidence is missing |
| Smart suggestions and next-step enrichment | `Orchestrator_User_Guide.md` | core/chat tests |
| Behavior signals: churn, sentiment, context provider | `Orchestrator_User_Guide.md`, behavior docs | `behavior-churn-signals` |
| Packaged behavior signal scenario smoke | behavior docs | deterministic `behavior-churn-signals` HTTP smoke for event ingestion -> sentiment/churn/next-action result evidence |
| Compliance checks and content filtering | `GOVERNANCE_AND_COMPLIANCE_GUIDE.md` | governance tests |
| Retention cleanup | `GOVERNANCE_AND_COMPLIANCE_GUIDE.md` | governance tests |
| Packaged privacy/governance deletion smoke | `GOVERNANCE_AND_COMPLIANCE_GUIDE.md`, PII module docs | `privacy-first-customer-facing-support` smoke-profile script that stores masked support data, requests deletion/retention cleanup, and proves no searchable/indexed residue |
| Private runtime customer integration | `PRIVATE_RUNTIME_CUSTOMER_INTEGRATION_GUIDE.md` | runtime integration smoke |
| Public runtime browser-token integration | `PUBLIC_RUNTIME_BROWSER_TOKEN_INTEGRATION_GUIDE.md` | security/auth tests or product smoke |
| Public anonymous action policy gates | `PUBLIC_ANONYMOUS_ACTION_POLICY_GUIDE.md` | policy tests and chat app smoke |
| Curated modes and packs: default, commerce, support | `MODES_AND_CURATED_PACKS_GUIDE.md`, `CURATED_MODES_PACKS_GUIDE.md` | chat app and curated module tests |
| Mode selector and policy capability flags | ADR-0010 | core orchestration tests |
| Chat UI request contract, attachments, pinned targets | `CHAT_CAPABILITIES_UI_MIGRATION_GUIDE.md` | chat app controller tests |

### P2 Provider, Vector, Platform, and Deployment Track

| Capability | Guide Evidence | Recommended Proof |
| --- | --- | --- |
| Real LLM provider matrix: OpenAI, Azure OpenAI, Anthropic, Gemini, Cohere | `REALAPI_PROVIDER_MATRIX_TESTING_GUIDE.md`, `SPRING_AI_PROVIDER_INTEGRATION_GUIDE.md` | `run-provider-matrix-tests.sh` through manual/keyed CI with scorecard thresholds |
| Spring AI embeddings: OpenAI, Azure OpenAI, Gemini, Spring AI ONNX | `SPRING_AI_PROVIDER_INTEGRATION_GUIDE.md` | provider matrix and ONNX smoke |
| Native/local ONNX embeddings | install/config guides | local model smoke where assets exist |
| RealAPI chat-session smoke: conversation, actions, confirmations, PII, deletion | chat-session docs, `REALAPI_PROVIDER_MATRIX_TESTING_GUIDE.md` | `run-chat-session-realapi-tests.sh` across selected LLM/embedding/vector combinations |
| RealAPI action and confirmation scenarios | `ACTIONS_AND_CONFIRMATION_INTERCEPTORS_GUIDE.md` | `ChatSessionConfirmableActionsRealApiIntegrationTest`, `ChatSessionSafeActionConfirmationRealApiIntegrationTest`, `ChatSessionActionPlusInfoCompoundRealApiIntegrationTest`, and core action RealAPI tests |
| RealAPI relationship-query domain scenarios | relationship guides, ADR-0007 | `run-relationship-query-realapi-tests.sh` for ecommerce, fraud, law-firm, query-param, summarization, and access-policy scenarios |
| RealAPI behavior analytics scenarios | behavior docs | `run-behavior-realapi-tests.sh` for analytics, processing, sentiment/churn, trend boundary, and LLM error-resilience scenarios |
| RealAPI RAG/vector lifecycle scenarios | `RAG_INDEXING_LIFECYCLE_GUIDE.md`, vector provider guides | `RealAPIVectorLifecycleIntegrationTest`, `RealAPIHybridRetrievalToggleIntegrationTest`, and provider matrix `vector` chunk |
| RealAPI intent/orchestration scenarios | `NORMALIZATION_AND_ORCHESTRATION_GUIDE.md`, ADR-0006/0007 | `RealAPIIntentGenerationRoutingIntegrationTest`, progressive extraction diagnostics, smart validation, smart suggestions, and multi-step complex scenarios |
| Transient provider file URL inputs with fail-closed safety | `RUNTIME_TRANSIENT_PROVIDER_FILE_URL_INPUTS_GUIDE.md` | runtime integration tests |
| Live/managed vector provider parity: Pinecone and hosted provider control planes | `VECTOR_DATABASE_CONFIGURATION_AUTH_AND_DEPLOYMENT_GUIDE.md` | live provider matrix and managed verification |
| Managed vector DB provisioning/admin | `MANAGED_VECTOR_DATABASE_ADMINISTRATION_GUIDE.md` | platform/manual provider verification |
| Qdrant/Pinecone/Zilliz credential and lifecycle flows | provider/vector guides | managed provider verification |
| Tenant/shared vector storage verification | `CUSTOMER_TENANT_SHARED_VECTOR_STORAGE_GUIDE.md`, `PLATFORM_VECTORIZATION_AND_TENANT_VERIFICATION_GUIDE.md` | platform vectorization suite |
| Vector provider readiness and drift evidence | provider/vector guides | live admin/provider verification |
| Generic REST connector pattern | `GENERIC_REST_API_CONNECTOR_GUIDE.md` | connector smoke once service exists |
| Relay deployment packaging, Docker, Helm | `RELAY_IMPLEMENTATION_AND_DEPLOYMENT_GUIDE.md` | relay packaging tests/manual deploy |
| Export/import sealed backup/restore | `DEPLOYMENT_EXPORT_IMPORT_SEALED_BACKUP_RESTORE_GUIDE.md` | platform verification |
| Deployment chat benchmarking | `DEPLOYMENT_CHAT_BENCHMARKING_GUIDE.md` | manual/nightly benchmark |
| Chat latency optimization and stage timing | `DEPLOYMENT_CHAT_LATENCY_OPTIMIZATION_PLAN.md` | benchmark evidence |
| Platform-hosted deployment verification | `PLATFORM_HOSTED_DEPLOYMENT_VERIFICATION_GUIDE.md` | platform verification workflow |
| Live admin/platform regression verification | `PLATFORM_REGRESSION_AND_LIVE_ADMIN_VERIFICATION_GUIDE.md` | manual/nightly live regression |

### P3 Ecosystem, Marketplace, and Workflow Track

| Capability | Guide Evidence | Recommended Proof |
| --- | --- | --- |
| Marketplace plugin authoring | marketplace plugin guides | manifest validation and docs examples |
| Marketplace plugin manifest validation | `MARKETPLACE_PLUGIN_MANIFEST_REFERENCE.md` | marketplace validation tests |
| Marketplace `TEMPLATE` contributions | marketplace plugin guides | platform/plugin verification |
| Marketplace `ACTION` contributions | marketplace plugin guides | action contribution verification |
| Marketplace `DATA` plugins and knowledge sources | `MARKETPLACE_DATA_PLUGIN_GUIDE.md` | data plugin verification |
| Marketplace `INFERENCE_PROFILE` plugins and BYOK | `MARKETPLACE_INFERENCE_PROFILE_GUIDE.md` | inference profile verification |
| Marketplace install form, permissions, compatibility, secret refs | marketplace plugin guides | control-plane tests |
| Public API client provisioning, apply, status, discovery | `PUBLIC_API_CLIENT_USER_GUIDE.md` | client contract tests/manual |
| GitHub Actions provider matrix suite | CI/testing guides | workflow evidence |
| CI/release pipeline documentation | `CI_PIPELINE_GUIDE.md` | CI green |
| Agentic app patterns | `AGENTIC_APP_GUIDE.md` | docs/examples |
| Multi-agent patterns/workflows | `MULTI_AGENT_PATTERNS_GUIDE.md`, `MULTI_AGENT_WORKFLOW_EXAMPLES.md` | docs/examples |
| Custom pipeline-step/action/SPI agent patterns | application pattern docs | docs/examples |
| Developer workflow prompts and code review prompts | developer workflow docs | documentation only |

## Recommended Release Sequencing

1. Make the P0 list the release gate. It proves the framework foundation: orchestration, actions,
   confirmations, RAG, vector lifecycle, data sync, PII, governance, auth, and deterministic app
   smoke.
2. Use `chat-capabilities-demo` as the P0 action proof app because it already contains many local
   actions, confirmation-required write actions, read actions, and the cancellation retention
   confirmation interceptor.
3. Use `ecommerce-store -> chat-capabilities-demo` as the P0 external runtime proof for data-sync,
   vector search, delete propagation, and stale-result cache eviction.
4. Use `smart-faq-assistant` as the P0 RAG quality proof.
5. Use `privacy-first-customer-facing-support` as the P0 privacy/governance proof.
6. Promote deterministic packaged real-app HTTP smokes into the P1 verification lane for actions,
   relationship-query, migration/backfill, RAG quality, behavior signals, and privacy deletion. The
   first packaged suite now runs without provider secrets in automatic CI through
   `.github/scripts/smoke-p1-realapp-scenarios.sh`.
7. Move connector actions, DB action registry, relay, retrieval connector, and Spring AI tool-calling
   into the same P1 verification lane.
8. Keep secret-backed RealAPI provider smokes, managed vector provisioning, platform workflows,
   marketplace plugins, and benchmarking as P2/P3 manual, nightly, keyed-CI, or platform-level
   verification.

## Real Scenario Smoke Additions

These are the concrete scenario smokes that should be added or promoted next. They are separate from
the P0 unit/module proof because they validate the framework in product-shaped flows.

### P1 Packaged Real-App Smokes

P1 smokes should run the packaged application jars under the deterministic `smoke` profile, use local
H2/local deterministic providers, and expose pass/fail evidence through HTTP responses or a script
summary. The initial packaged suite is wired into automatic CI after the P0 boot/data-sync smokes.

| Scenario | App / Existing Code Evidence | Smoke Proof |
| --- | --- | --- |
| Action confirmation plus confirmation interceptor | `chat-capabilities-demo` has confirmable order/review actions and `CancellationRetentionOfferResolver` | HTTP script starts the app, triggers `cancel_purchase_order`, verifies pending confirmation, verifies the retention-offer interceptor path, then proves accept/reject clears or executes the expected action |
| Support/action bot authorization | `it-support-action-bot`, `sub-management-hub`, `sub-management-hub-simple` contain action handlers and authorization hooks | HTTP or controller smoke proves read action, write action denial/confirmation, and `@ActionAllowed` behavior under a known user context |
| RAG golden-answer quality | `smart-faq-assistant` quality service and golden FAQ test coverage | Script runs the quality endpoint and fails closed if expected article/source evidence is absent or retrieval throws |
| Privacy/governance deletion | `privacy-first-customer-facing-support` PII masking and governance deletion tests | Script creates masked support data, verifies searchable evidence, requests deletion/retention cleanup, then proves no indexed/searchable residue remains |
| Relationship-query business question | `relationship-query-crm-insights` deterministic relationship query app | Script asks a representative CRM/account question and verifies structured result shape, relationship evidence, and a bounded failure response for impossible queries |
| Behavior signal analysis | `behavior-churn-signals` deterministic behavior app | Script ingests sample events and verifies sentiment/churn/next-action evidence, including a boundary case with insufficient history |
| Migration/backfill lifecycle | `migration-enabled-product-catalog` migration/backfill app | Script seeds products, runs backfill, verifies indexed counts/status evidence, retries idempotently, and checks clear/rebuild behavior |

### P2 Secret-Backed RealAPI Smokes

P2 RealAPI smokes use live provider credentials and should publish scorecards, selected provider
combinations, and thresholds. They can run in the manual integration workflow, the keys-only provider
suite, or a nightly/keyed CI lane.

| Scenario | Existing Runner / Tests | Smoke Proof |
| --- | --- | --- |
| Provider matrix baseline | `integration-tests/run-provider-matrix-tests.sh`, `RealAPIProviderMatrixIntegrationTest` | Run selected LLM/embedding/vector combinations and publish scorecards with success-rate and considered-test thresholds |
| Core orchestration, RAG, vector, intent/action chunks | provider matrix chunks `core`, `vector`, `intent-actions`, `advanced` | Use chunked matrix runs after large changes to localize failures while preserving live-provider evidence |
| Chat-session RealAPI flows | `chat-session-integration-tests/run-chat-session-realapi-tests.sh` | Prove conversation, anonymous sessions, clarification, context windowing, confirmable actions, safe action confirmation, PII redaction, owner mismatch, and deletion with live LLM behavior |
| Action confirmation RealAPI proof | `ChatSessionConfirmableActionsRealApiIntegrationTest`, `ChatSessionSafeActionConfirmationRealApiIntegrationTest`, `ChatSessionActionPlusInfoCompoundRealApiIntegrationTest`; core `RealAPIActionFlowIntegrationTest`, `RealAPIActionErrorRecoveryIntegrationTest`, `RealAPIActionPostActionGenerationIntegrationTest` | Prove live models extract action intents, ask for confirmation, execute/cancel safely, recover from action errors, and generate post-action responses from bounded facts |
| Relationship-query RealAPI domains | `relationship-query-integration-tests/run-relationship-query-realapi-tests.sh` | Prove ecommerce, financial fraud, law firm, provider scorecard, query-param, summarization, and access-policy cases against live model output |
| Behavior RealAPI analytics | `behavior-integration-tests/run-behavior-realapi-tests.sh` | Prove analytics, processing, sentiment/churn, trend-boundary, and LLM error-resilience behavior with live model calls |
| ONNX/provider fallback | `RealAPIONNXFallbackIntegrationTest`, provider matrix `openai:onnx` combinations | Prove local embeddings can pair with live LLM providers and fallback behavior remains release-compatible |
| Live vector/provider lifecycle | `RealAPIVectorLifecycleIntegrationTest`, provider matrix vector chunk, Pinecone/live vector lanes | Prove live-provider vector upsert/search/update/delete and hybrid retrieval behavior beyond Docker-backed container providers |

## P0 Progress Evidence

### 2026-06-20: Action Confirmation Interceptor Hardening

Status: completed for this slice; included in the closed automatic P0 gate.

Code evidence:

- `ai-fabric-chat-session/src/main/java/ai/fabric/chat/resolver/AnnotatedConfirmationInterceptorsResolver.java`
  now preserves unrelated non-confirmation intents when an annotated confirmation interceptor handles
  a compound confirmation turn.
- The same resolver now suppresses correlated duplicate action intents so an LLM turn like "yes and
  show orders" can keep the unrelated follow-up while avoiding duplicate execution of the pending or
  replacement action.
- Annotated interceptor action matching now uses the shared `AIActionNames.normalize(...)` utility,
  matching the framework-wide action-name contract.
- Annotated interceptor `onceParam` handling now uses the shared
  `ConfirmationInterceptorParamSupport` helper, so guard keys are case-insensitive like the configured
  catalog path.

Test evidence:

- `AnnotatedConfirmationInterceptorsResolverTest` covers compound-turn preservation, correlated action
  suppression, shared action-name normalization, and case-insensitive once guards.
- `ConfiguredConfirmationInterceptorsResolverTest` remains green, proving the existing catalog path
  behavior was preserved.
- Verification command run without `-DskipTests`:
  `mvn -pl ai-fabric-chat-session test`
- Result: 44 tests, 0 failures, 0 errors, 0 skipped.
- Local install command run without `-DskipTests` so the real app consumed the changed framework
  artifacts:
  `mvn -pl ai-fabric-chat-session -am install`
- Result: core ran 560 tests, chat-session ran 44 tests, 0 failures, 0 errors, 0 skipped.
- Real-app verification command run without `-DskipTests`:
  `mvn -pl smoke-support,chat-capabilities-demo -am test`
- Result: smoke-support ran 8 tests and chat-capabilities-demo ran 20 tests, 0 failures, 0 errors,
  0 skipped.

Philosophy check:

- Supports "respect intelligence": the framework preserves unrelated LLM-extracted intents instead of
  collapsing a compound turn into a single backend decision.
- Supports fail-closed action safety: correlated action suppression prevents duplicate execution when
  a confirmation interceptor already replaced or confirmed an action.
- Supports no magic/ambiguous code: action-name and once-guard normalization now reuse shared framework
  helpers instead of local string rules.

### 2026-06-20: Data Sync Verified Auth Fail-Closed Hardening

Status: completed for this slice; included in the closed automatic P0 gate.

Code evidence:

- `ai-fabric-data-sync/src/main/java/ai/fabric/datasync/AIDataSyncProperties.java` now exposes
  `allowTrustedPlatformInternalSyncBypass`, default `false`.
- `ai-fabric-data-sync/src/main/java/ai/fabric/datasync/service/DataSyncService.java` only uses the
  trusted platform-internal policy bypass when that property is explicitly enabled and the verified
  auth context has the required platform/system shape and scope.
- Default behavior now routes platform-shaped request-body auth context through
  `AIAccessControlService`, so the request DTO cannot silently become a policy bypass.
- `DATA_SYNC_PUSH_API_GUIDE.md` and `docs/guides/04-configuration.md` document the safer default and
  the conditions for enabling the bypass behind a trusted backend/runtime boundary.

Test evidence:

- `DataSyncServiceTest` covers:
  - explicit trusted platform bypass when enabled;
  - default no-bypass behavior for platform-shaped auth context;
  - missing platform scope;
  - missing verified auth subject;
  - batch preflight fail-closed with no embedding/vector side effects;
  - access-service exception fail-closed metadata.
- `AIDataSyncAutoConfigurationTest` locks the bypass default to `false`.
- Verification command run with clean compile and without `-DskipTests`:
  `mvn -pl ai-fabric-data-sync clean test`
- Result: 34 tests, 0 failures, 0 errors, 0 skipped.
- Local install command run without `-DskipTests`:
  `mvn -pl ai-fabric-data-sync install`
- Result: 34 tests, 0 failures, 0 errors, 0 skipped.
- Real-app verification command run without `-DskipTests`:
  `mvn -pl smoke-support,chat-capabilities-demo,ecommerce-store -am test`
- Result: smoke-support ran 8 tests, chat-capabilities-demo ran 20 tests, ecommerce-store ran 20
  tests, 0 failures, 0 errors, 0 skipped.

Philosophy check:

- Supports fail-closed security: request-body auth context does not grant a platform bypass unless
  the application explicitly enables that deployment posture.
- Supports explicit capability flags: a powerful shortcut is named and configured instead of hidden
  behind inferred auth metadata.
- Supports modularity: demos can continue using normal `EntityAccessPolicy`; platform runtimes can
  opt into the bypass only when the surrounding trusted boundary is real.

### 2026-06-20: Governance Deletion And Retention Outcome Hardening

Status: completed for this slice; included in the closed automatic P0 gate.

Code evidence:

- `ai-fabric-governance/src/main/java/ai/fabric/deletion/UserDataDeletionService.java` now treats a
  referenced indexed vector returning `false` from `VectorDatabaseService.removeVector(...)` as a
  surfaced partial deletion failure instead of allowing the user deletion workflow to report
  `COMPLETED` with zero vectors removed.
- The deletion workflow still attempts SQL catalog cleanup after the failed vector removal result, so
  stale catalog evidence can be cleaned while the user-facing result remains `PARTIAL`.
- `ai-fabric-governance/src/main/java/ai/fabric/retention/RetentionCleanupScheduler.java` now applies
  the same fail-visible rule for eligible retention cleanup entries: an eligible catalog entry whose
  vector provider removes no record becomes a `PARTIAL` cleanup result with a concrete failure
  message.

Test evidence:

- `UserDataDeletionServiceTest.shouldReportPartialWhenIndexedVectorIsNotFoundDuringDeletion` covers
  the deletion workflow when a provider or catalog finds an indexed entity but the vector provider
  returns `false`.
- `RetentionCleanupSchedulerTest.reportsPartialWhenEligibleVectorIsNotFoundDuringCleanup` covers the
  retention workflow when an old eligible catalog entry cannot be removed from the vector provider.
- Governance verification command run without `-DskipTests`:
  `mvn -pl ai-fabric-governance test`
- Result: 51 tests, 0 failures, 0 errors, 0 skipped.
- Local install command run without `-DskipTests`:
  `mvn -pl ai-fabric-governance install`
- Result: 51 tests, 0 failures, 0 errors, 0 skipped.
- Privacy real-app verification command run with clean compile and without `-DskipTests`:
  `mvn -pl privacy-first-customer-facing-support -am clean test`
- Result: smoke-support ran 8 tests and privacy-first-customer-facing-support ran 6 tests, 0 failures,
  0 errors, 0 skipped.

Philosophy check:

- Supports fail-closed privacy/governance: a known indexed reference that cannot be removed no longer
  disappears into a successful result.
- Supports "code is communication": the result status and failure messages now tell operators exactly
  which entity reference did not get removed.
- Supports example-driven verification: the privacy-first real app still compiles and tests against
  the tightened governance artifact.

### 2026-06-20: Privacy App PII Boundary Hardening

Status: completed for this slice; included in the closed automatic P0 gate.

Code evidence:

- `examples/real-apps/privacy-first-customer-facing-support/src/main/java/.../SupportMessageService.java`
  now masks detected PII before persistence even when `PIIDetectionService` is running in
  `DETECT_ONLY` mode and returns the original payload as `processedQuery`.
- The same service now masks detected PII before direct semantic-search calls to `AICoreService`, so
  the privacy app does not bypass the orchestration pipeline's PII masking guarantee when it uses
  search as an app service.
- The app still stores only processed subject/message fields plus encrypted/hash original payload
  evidence from the detector; raw original payload fields are not added to the entity.

Test evidence:

- `SupportMessageServiceTest.createMasksDetectedPiiBeforePersistenceEvenWhenDetectorIsDetectOnly`
  covers the app persistence/indexing boundary when the detector reports PII but leaves
  `processedQuery` unchanged.
- `SupportMessageServiceTest.semanticSearchMasksPiiBeforeSendingQueryToAiFabricSearch` covers direct
  search calls and verifies that `AISearchRequest.query` contains the masked value.
- Privacy real-app verification command run with clean compile and without `-DskipTests`:
  `mvn -pl privacy-first-customer-facing-support -am clean test`
- Result: smoke-support ran 8 tests and privacy-first-customer-facing-support ran 8 tests, 0 failures,
  0 errors, 0 skipped.
- PII module verification command run with clean compile and without `-DskipTests`:
  `mvn -pl ai-fabric-pii clean test`
- Result: 25 tests, 0 failures, 0 errors, 0 skipped.

Verification note:

- A non-clean `mvn -pl ai-fabric-pii test` run surfaced stale target classes with unresolved
  compilation-problem bytecode. The clean run recompiled the module and passed, so P0/release gates
  should prefer clean module/app runs when target directories may contain stale IDE artifacts.

Philosophy check:

- Supports privacy-first defaults: demo overrides like `AI_PII_MODE=DETECT_ONLY` cannot silently turn
  a privacy example into raw PII storage/indexing.
- Supports layered safety: the app boundary now mirrors the orchestration pipeline's guarantee that
  detections imply masked downstream payloads.
- Supports example-driven release quality: the real app demonstrates the pattern users should copy
  when they call AI Fabric services directly.

### 2026-06-20: Runtime Admin Endpoint Protection Hardening

Status: completed for this slice; included in the closed automatic P0 gate.

Code evidence:

- `examples/real-apps/ecommerce-store/src/main/java/.../DemoResetAdminController.java` now protects
  destructive `/api/admin/demo/reset`, `/api/admin/demo/clear`, and `/api/admin/migration/clear`
  endpoints with the shared admin API-key check before confirmation or service execution.
- Ecommerce admin auth is enabled by default through `app.admin.*`. The existing
  `CONNECTOR_ADMIN_*` variables remain accepted as compatibility aliases for older deployment
  scripts.
- `examples/real-apps/chat-capabilities-demo/src/main/java/.../MigrationAdminController.java` now
  protects `/api/admin/migration/clear` with the same fail-closed admin guard.
- The app configs now deny admin requests when auth is enabled but no admin key is configured. A
  no-key posture requires an explicit local-only opt-out with `APP_ADMIN_AUTH_ENABLED=false`.
- The runtime/security docs, ecommerce README, product REST guide, verification playbook, and request
  examples now document the protected-by-default posture.

Test evidence:

- `DemoResetAdminControllerTest` covers missing key, wrong key, matching key, and confirm-after-auth
  ordering for ecommerce reset endpoints.
- `MigrationAdminControllerTest` covers missing key, wrong key, matching key, and confirm-after-auth
  ordering for chat-capabilities runtime reset.
- Real-app verification command run with clean compile and without `-DskipTests`:
  `mvn -pl smoke-support,chat-capabilities-demo,ecommerce-store -am clean test`
- Result: smoke-support ran 8 tests, chat-capabilities-demo ran 24 tests, ecommerce-store ran 24
  tests, 0 failures, 0 errors, 0 skipped.

Philosophy check:

- Supports fail-closed operations: destructive admin endpoints are not public because a key was
  forgotten.
- Supports explicit local demo posture: no-key operation is still possible, but it is a named opt-out
  instead of an accidental default.
- Supports example-driven safety: real apps now demonstrate the admin protection pattern users should
  copy.

### 2026-06-20: Cache Namespace Hardening

Status: completed for this slice; included in the closed automatic P0 gate.

Code evidence:

- `ai-fabric-core/src/main/java/ai/fabric/cache/AICacheNames.java` centralizes framework-owned cache
  region names.
- Runtime cache names now use the `ai-fabric-` prefix:
  `ai-fabric-embeddings`, `ai-fabric-vector-search`, `ai-fabric-text-search`,
  `ai-fabric-generation`, `ai-fabric-validation`, `ai-fabric-retention-status`,
  `ai-fabric-behavior-retention`, and `ai-fabric-access-decisions`.
- `AICacheConfig`, `AIEmbeddingService`, `VectorSearchService`, `VectorManagementService`, and
  `AIAccessControlService` now resolve shared cache regions through `AICacheNames`.
- Core and integration tests now create/read the same named cache regions through the shared
  constants instead of repeating bare literals.

Test evidence:

- `AIEmbeddingServiceTest` verifies embedding cache reuse and connection-override cache separation
  against `AICacheNames.EMBEDDINGS`.
- `VectorSearchServiceTest` and `VectorManagementServiceTest` verify stale vector-search cache
  eviction against `AICacheNames.VECTOR_SEARCH`.
- `AIAccessControlServiceTest` verifies access-decision caching and failure non-caching against
  `AICacheNames.ACCESS_DECISIONS`.
- Latest full core verification after later P0 slices:
  `mvn -pl ai-fabric-core test`
- Result: 581 tests, 0 failures, 0 errors, 0 skipped.

Philosophy check:

- Supports "code is communication": framework-owned cache state is visible in logs, metrics, and
  shared cache managers as AI Fabric state.
- Supports modular host applications: generic names like `vectorSearch` or `embeddings` are no longer
  framework runtime cache regions that can collide with an app's own cache names.
- Supports removing magic strings: services and tests now share one cache-name contract.

### 2026-06-20: Cache Auto-Configuration Backoff And Helper Cache Cleanup

Status: completed for this slice; included in the closed automatic P0 gate.

Code evidence:

- `ai-fabric-core/src/main/java/ai/fabric/cache/AICacheConfig.java` now creates AI Fabric's
  `CacheManager` only when the host application has not already provided one, using
  `@ConditionalOnMissingBean(CacheManager.class)`.
- The old generic native Caffeine helper beans named `embeddingCache`, `searchCache`, and
  `generationCache` were removed from the Spring context. They were not the cache regions consumed by
  AI Fabric services and could collide with application bean names.
- The specialized TTLs that those helper beans implied are now registered on the actual framework
  cache regions:
  `ai-fabric-embeddings`, `ai-fabric-vector-search`, `ai-fabric-text-search`, and
  `ai-fabric-generation`.
- The shared cache regions remain static and namespaced; legacy unprefixed names such as
  `embeddings`, `vectorSearch`, `textSearch`, `aiGeneration`, `aiValidation`,
  `retentionStatus`, `behaviorRetention`, and `accessDecisions` are not exposed by the default AI
  Fabric cache manager.

Test evidence:

- `AICacheConfigTest` verifies that the default framework cache manager exposes namespaced cache
  regions, does not expose the old generic helper bean names, rejects legacy unprefixed cache names,
  and backs off when an application supplies its own `CacheManager`.
- Focused verification:
  `mvn -f ai-infrastructure-module/pom.xml -pl ai-fabric-core -Dtest=AICacheConfigTest test`
- Result: 2 tests, 0 failures, 0 errors, 0 skipped.
- Full core verification:
  `mvn -f ai-infrastructure-module/pom.xml -pl ai-fabric-core test`
- Result: 586 tests, 0 failures, 0 errors, 0 skipped.
- Installed changed reactor without skipping tests:
  `mvn -f ai-infrastructure-module/pom.xml -pl ai-fabric-core -am install`
- Result: curated-default ran 3 tests and core ran 586 tests; 0 failures, 0 errors, 0 skipped.
- Real-app compatibility verification:
  `mvn -f examples/real-apps/pom.xml -pl smoke-support,chat-capabilities-demo,ecommerce-store -am clean test`
- Result: smoke-support ran 8 tests, chat-capabilities-demo ran 24 tests, and ecommerce-store ran 24
  tests; 0 failures, 0 errors, 0 skipped.

Philosophy check:

- Supports modular host applications: AI Fabric no longer unconditionally claims the host
  application's `CacheManager` slot.
- Supports "code is communication": the cache regions that appear in logs, metrics, and external
  cache backends are clearly AI Fabric-owned.
- Supports production cleanliness: cache TTL behavior now lives on the real runtime cache regions
  instead of unused helper beans with generic names.

### 2026-06-20: Stable Vector Lifecycle Metadata Hardening

Status: completed for this slice; included in the closed automatic P0 gate.

Code evidence:

- `ai-fabric-core/src/main/java/ai/fabric/util/VectorRecordLifecycleMetadata.java` now repairs an
  invalid or blank internal `_indexedCreatedAt` value during enrichment, using the existing vector's
  `createdAt` hint when available.
- Valid existing `_indexedCreatedAt` values are preserved, and `_indexedUpdatedAt` is refreshed on
  every store/update enrichment.
- `ai-fabric-core/src/main/java/ai/fabric/service/VectorManagementService.java` now delegates vector
  lifecycle timestamp enrichment to `VectorRecordLifecycleMetadata` instead of carrying a duplicate
  local implementation.
- Batch store/update paths in `VectorManagementService` use the same helper, so bulk mutations and
  single-record mutations share one lifecycle metadata contract.
- Native vector adapters for Pinecone, Weaviate, Qdrant, and Milvus already use
  `VectorRecordLifecycleMetadata`; this helper fix therefore applies consistently across provider
  payload serialization and record rehydration.

Test evidence:

- `VectorRecordLifecycleMetadataTest.enrichForUpdateRepairsInvalidCreatedAtUsingExistingRecordHint`
  proves invalid internal creation metadata is repaired from the existing record hint.
- `VectorManagementServiceTest.updateVectorRepairsInvalidCreatedAtMetadataFromExistingRecord` proves
  the public management update path passes repaired lifecycle metadata to the provider update call.
- Focused verification command run without `-DskipTests`:
  `mvn -pl ai-fabric-core -Dtest=VectorRecordLifecycleMetadataTest,VectorManagementServiceTest test`
- Result: 10 tests, 0 failures, 0 errors, 0 skipped.
- Full vector provider reactor verification command run without `-DskipTests`:
  `mvn -pl victor-databases/ai-fabric-vector-memory,victor-databases/ai-fabric-vector-lucene,victor-databases/ai-fabric-vector-qdrant,victor-databases/ai-fabric-vector-pinecone,victor-databases/ai-fabric-vector-weaviate,victor-databases/ai-fabric-vector-milvus -am test`
- Result: curated-default ran 3 tests, core ran 581 tests, memory ran 16 tests, Lucene ran 9 tests,
  Pinecone ran 31 tests, Weaviate ran 25 tests, Qdrant ran 34 tests, and Milvus ran 24 tests; 0
  failures, 0 errors, 0 skipped.
- Local install command run without `-DskipTests`:
  `mvn -pl ai-fabric-core install`
- Result: 581 tests, 0 failures, 0 errors, 0 skipped; `ai-fabric-core-0.2.1.jar` installed locally.

Container provider contract evidence:

- `.github/scripts/run-vector-container-contracts.sh` runs the Docker/Testcontainers-backed
  `VectorDatabaseService` lifecycle/admin contract as one local command.
- `.github/workflows/framework-verify.yml` now runs that script as the automatic
  `Vector Provider Container Contracts` job after release guards pass.
- The job covers Qdrant REST, Qdrant gRPC, Weaviate, and Milvus containers, including scoped-provider
  isolation against the same backend.
- Pinecone remains in the live SaaS provider gate because it does not have a local Docker equivalent
  with release-parity behavior.

Philosophy check:

- Supports audit correctness: vector updates cannot silently erase or corrupt creation evidence
  because a malformed internal lifecycle key was present in metadata.
- Supports "code is communication": lifecycle timestamp rules now live in one named helper used by
  core management and native vector providers.
- Supports release-grade provider parity: the same lifecycle metadata repair behavior is compiled and
  tested across memory, Lucene, Pinecone, Weaviate, Qdrant, and Milvus modules.

### 2026-06-20: Smart FAQ RAG Quality Gate Fail-Closed Hardening

Status: completed for this slice; included in the closed automatic P0 gate.

Code evidence:

- `examples/real-apps/smart-faq-assistant/src/main/java/.../FaqQualityService.java` now catches
  retrieval failures per golden question and returns a failed `QualityReport` instead of bubbling an
  opaque runtime exception from the quality endpoint.
- Failed retrieval questions now include actionable feedback naming the expected FAQ article and the
  concise retrieval error.
- If Spring AI RAG evaluation was requested, the failed question marks Spring evaluation as
  `UNAVAILABLE` with retrieval-failure context rather than attempting evaluator calls with missing
  evidence.
- Null retrieval results are normalized to an empty evidence list, so provider or test doubles cannot
  accidentally crash the release gate.

Test evidence:

- `FaqQualityServiceTest.goldenSetFailsClosedWithQuestionEvidenceWhenRetrievalThrows` covers a vector
  retrieval failure and verifies that the report fails closed with question-level feedback and
  evaluator status evidence.
- Existing Smart FAQ tests continue to cover golden-set pass behavior, top-result strictness,
  unavailable Spring AI evaluator reporting, entity config, search evidence mapping, and controller
  delegation.
- Focused verification command run without skipping unit tests:
  `mvn -pl smart-faq-assistant -am -Dtest=FaqQualityServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`
- Result: `FaqQualityServiceTest` ran 4 tests, 0 failures, 0 errors, 0 skipped.
- Real-app verification command run with clean compile and without `-DskipTests`:
  `mvn -pl smoke-support,smart-faq-assistant -am clean test`
- Result: smoke-support ran 8 tests and smart-faq-assistant ran 12 tests, 0 failures, 0 errors,
  0 skipped.

Philosophy check:

- Supports fail-closed release gates: a broken vector/search path produces `pass=false` with evidence
  instead of an HTTP 500 or missing report.
- Supports "frameworks teach": the example app now shows users how to expose RAG quality failures as
  inspectable artifacts.
- Supports correctness before convenience: evaluator-backed checks do not run on missing retrieval
  evidence, and local retrieval failure remains visible in the report.

### 2026-06-20: Retrieval Query Hint Safety Hardening

Status: completed for this slice; included in the closed automatic P0 gate.

Code evidence:

- `ai-fabric-core/src/main/java/.../RetrievalQueryHintSupport.java` now applies LLM-produced
  `retrievalQueryHint` only for the current retrieval intent, and still requires exactly one retrieval
  intent in the turn.
- Safe hints are restricted to a bounded identifier/product-name character set: letters, digits,
  single spaces, and conservative punctuation (`-`, `_`, `.`, `#`, `/`, `'`).
- Prompt/markup/control-like artifacts such as `@`, `:`, braces, angle brackets, quotes, backticks,
  tabs, newlines, leading/trailing whitespace, and consecutive whitespace are rejected.
- Applying a valid hint to a blank base query now returns the hint itself instead of producing
  accidental `"null ..."` query text.
- `RAG_EMBEDDING_QUERY_COMPOSITION_GUIDE.md` and ADR-0009 now document the stricter safety contract
  and current `ai.fabric.*` implementation package.

Test evidence:

- `RetrievalQueryHintSupportTest` now covers:
  - valid single retrieval hint application;
  - rejection when multiple retrieval intents exist;
  - rejection when the current intent does not require retrieval;
  - safe blank-base application;
  - unsafe email, newline, tab, consecutive whitespace, prompt prefix, braces, markup, padded, and
    over-length hints.
- `IntentHandlingStepRetrievalQueryHintTest`, `InformationRetrievalQuerySupportTest`,
  `EmbeddingQueryComposerTest`, and `IntentHandlingStepEmbeddingQueryExpansionTest` remain green,
  proving the stricter helper still feeds retrieval and embedding query composition correctly.
- Focused verification command run without `-DskipTests`:
  `mvn -pl ai-fabric-core -Dtest=RetrievalQueryHintSupportTest,IntentHandlingStepRetrievalQueryHintTest,InformationRetrievalQuerySupportTest,EmbeddingQueryComposerTest,IntentHandlingStepEmbeddingQueryExpansionTest test`
- Result: 16 tests, 0 failures, 0 errors, 0 skipped.
- Full core verification command run without `-DskipTests`:
  `mvn -pl ai-fabric-core test`
- Result: 562 tests, 0 failures, 0 errors, 0 skipped.

Philosophy check:

- Supports fail-closed LLM boundaries: optional model-produced retrieval hints are treated as
  untrusted input and are dropped unless they match a narrow, explainable contract.
- Supports "code is communication": the allowed hint shape is centralized in one helper and reflected
  in tests and docs.
- Supports deterministic retrieval: hints are only applied when they cannot contaminate multiple
  retrieval intents or non-retrieval intent handling.

### 2026-06-20: Action Authorization Annotation Contract Hardening

Status: completed for this slice; included in the closed automatic P0 gate.

Code evidence:

- `ai-fabric-core/src/main/java/ai/fabric/intent/action/AIActionRegistry.java` now validates annotated
  `@ActionAllowed` methods during registry refresh, before handlers are exposed.
- `@ActionAllowed` methods must return `boolean` or `Boolean`; non-boolean permission hooks now fail
  application startup instead of silently becoming a runtime denial.
- `@ActionAllowed` methods may only declare framework context parameters such as `ActionContext`,
  `OrchestrationContext`, or `PipelineContext`. Action parameters with `@Param` are rejected because
  authorization is evaluated before user action parameters are bound.
- Existing real-app action handlers remain compatible because their permission hooks already use
  `boolean allowed(ActionContext context)`.

Test evidence:

- `AIActionRegistryTest.shouldInvokeActionAllowedWithActionContext` proves a valid context-based
  permission hook is still invoked and can allow/deny by current user context.
- `AIActionRegistryTest.shouldFailFastWhenActionAllowedReturnsNonBoolean` proves invalid return types
  fail during Spring context refresh.
- `AIActionRegistryTest.shouldFailFastWhenActionAllowedDeclaresActionParameter` proves action-parameter
  authorization hooks fail during Spring context refresh instead of being hidden by runtime binder
  failure.
- Focused verification command run without `-DskipTests`:
  `mvn -pl ai-fabric-core -Dtest=AIActionRegistryTest test`
- Result: 7 tests, 0 failures, 0 errors, 0 skipped.
- Full core verification command run without `-DskipTests`:
  `mvn -pl ai-fabric-core test`
- Result: 565 tests, 0 failures, 0 errors, 0 skipped.
- Local install command run without `-DskipTests` so real apps consumed the changed core artifact:
  `mvn -pl ai-fabric-core install`
- Result: 565 tests, 0 failures, 0 errors, 0 skipped; `ai-fabric-core-0.2.1.jar` installed locally.
- Action real-app compatibility command run with clean compile and without `-DskipTests`:
  `mvn -pl smoke-support,it-support-action-bot,sub-management-hub,sub-management-hub-simple -am clean test`
- Result: smoke-support ran 8 tests, it-support-action-bot compiled 15 app sources with no test
  sources, sub-management-hub ran 6 tests, and sub-management-hub-simple ran 3 tests; 0 failures,
  0 errors, 0 skipped.

Philosophy check:

- Supports fail-closed action safety: invalid authorization hooks cannot create ambiguous runtime
  behavior or depend on unavailable action parameters.
- Supports "code is communication": the annotation contract is enforced at the registry boundary with
  explicit error messages.
- Supports production-level examples: real apps keep the simple, auditable
  `boolean allowed(ActionContext context)` pattern.

### 2026-06-20: Action Confirmation Annotation Contract Hardening

Status: completed for this slice; included in the closed automatic P0 gate.

Code evidence:

- `ai-fabric-core/src/main/java/ai/fabric/intent/action/AIActionRegistry.java` now validates annotated
  `@ActionConfirmation` methods during registry refresh, before handlers are exposed.
- `@ActionConfirmation` methods must return text. Non-text confirmation hooks now fail application
  startup instead of becoming confusing prompts such as `"true"` or silently falling back to the
  generic default message.
- `@ActionConfirmation` method parameters may use framework context parameters, and every action
  parameter must be explicitly annotated with `@Param` and have a resolvable name.
- Existing real-app confirmation handlers remain compatible because they already follow the
  documented `String confirm(@Param(...) ...)` shape.

Test evidence:

- `AIActionRegistryTest.shouldInvokeActionConfirmationWithActionParameter` proves a valid
  parameterized confirmation hook is still invoked with bound action parameters.
- `AIActionRegistryTest.shouldFailFastWhenActionConfirmationReturnsNonText` proves invalid
  confirmation return types fail during Spring context refresh.
- `AIActionRegistryTest.shouldFailFastWhenActionConfirmationParameterMissingParamAnnotation` proves
  ambiguous confirmation parameters fail during Spring context refresh instead of being hidden by
  runtime fallback behavior.
- Focused verification command run without `-DskipTests`:
  `mvn -pl ai-fabric-core -Dtest=AIActionRegistryTest test`
- Result: 10 tests, 0 failures, 0 errors, 0 skipped.
- Full core verification command run without `-DskipTests`:
  `mvn -pl ai-fabric-core test`
- Result: 568 tests, 0 failures, 0 errors, 0 skipped.
- Local install command run without `-DskipTests` so real apps consumed the changed core artifact:
  `mvn -pl ai-fabric-core install`
- Result: 568 tests, 0 failures, 0 errors, 0 skipped; `ai-fabric-core-0.2.1.jar` installed locally.
- Confirmation-heavy real-app compatibility command run with clean compile and without `-DskipTests`:
  `mvn -pl smoke-support,chat-capabilities-demo,it-support-action-bot,sub-management-hub,sub-management-hub-simple -am clean test`
- Result: smoke-support ran 8 tests, chat-capabilities-demo ran 24 tests, it-support-action-bot
  compiled 15 app sources with no test sources, sub-management-hub ran 6 tests, and
  sub-management-hub-simple ran 3 tests; 0 failures, 0 errors, 0 skipped.

Philosophy check:

- Supports explicit consent before side effects: custom confirmation prompts are treated as a
  contract, not best-effort formatting.
- Supports fail-loud required SPI behavior: a broken confirmation method stops startup instead of
  weakening the user-facing confirmation experience.
- Supports example-driven production quality: real apps keep the documented text-returning
  confirmation pattern under stricter startup validation.

### 2026-06-20: Action Facts Annotation Contract Hardening

Status: completed for this slice; included in the closed automatic P0 gate.

Code evidence:

- `ai-fabric-core/src/main/java/ai/fabric/intent/action/AIActionRegistry.java` now validates annotated
  `@ActionFacts` methods during registry refresh, before handlers are exposed.
- `@ActionFacts` methods must return `Map<String,Object>` or `Optional<Map<String,Object>>`; invalid
  return types now fail application startup instead of being silently ignored at runtime.
- `@ActionFacts` methods may declare no parameters, or exactly `(ActionResult, ActionContext)`.
  Unsupported parameter shapes now fail during Spring context refresh.
- Declared non-string facts-map key types now fail startup when generics are visible, keeping
  post-action LLM facts aligned with the framework `Map<String,Object>` contract.
- `AnnotatedAIActionHandler` now normalizes both direct `Map` and `Optional<Map>` facts through the
  same string-key map conversion before downstream post-action generation sees them.
- `ai-fabric-core/src/main/java/ai/fabric/intent/action/annotation/ActionFacts.java` now documents
  the supported return and parameter shapes.
- Existing production handler compatibility was checked against
  `ai-fabric-relationship-query/src/main/java/ai/fabric/relationship/action/RelationshipQueryActionHandler.java`,
  which already uses `Map<String,Object> buildFacts(ActionResult, ActionContext)`.

Test evidence:

- `AIActionRegistryTest.shouldBuildPostActionFactsWithActionResultAndContext` proves the
  `(ActionResult, ActionContext)` facts hook still works.
- `AIActionRegistryTest.shouldBuildPostActionFactsWithoutArguments` proves the no-argument facts hook
  still works.
- `AIActionRegistryTest.shouldFailFastWhenActionFactsReturnsNonMap` proves invalid direct return
  types fail during Spring context refresh.
- `AIActionRegistryTest.shouldFailFastWhenActionFactsOptionalValueIsNonMap` proves invalid
  `Optional` payload types fail during Spring context refresh.
- `AIActionRegistryTest.shouldFailFastWhenActionFactsMapKeysAreNotString` proves declared non-string
  facts keys fail during Spring context refresh.
- `AIActionRegistryTest.shouldFailFastWhenActionFactsDeclaresUnsupportedParameters` proves unsupported
  facts hook parameter shapes fail during Spring context refresh.
- Focused verification command run without `-DskipTests`:
  `mvn -pl ai-fabric-core -Dtest=AIActionRegistryTest test`
- Result: 16 tests, 0 failures, 0 errors, 0 skipped.
- Full core verification command run without `-DskipTests`:
  `mvn -pl ai-fabric-core test`
- Result: 574 tests, 0 failures, 0 errors, 0 skipped.
- Local install command run without `-DskipTests`:
  `mvn -pl ai-fabric-core install`
- Result: 574 tests, 0 failures, 0 errors, 0 skipped; `ai-fabric-core-0.2.1.jar` installed locally.
- Relationship-query compatibility command run without `-DskipTests`:
  `mvn -pl ai-fabric-relationship-query -am test`
- Result: curated-default ran 3 tests, core ran 574 tests, ONNX starter ran 10 tests, Lucene vector
  ran 9 tests, and relationship-query ran 82 tests; 0 failures, 0 errors, 0 skipped.

Philosophy check:

- Supports bounded LLM context: post-action facts are explicitly shaped before they are allowed into
  generation prompts.
- Supports fail-loud framework contracts: broken facts hooks stop startup instead of disappearing
  into empty post-action evidence.
- Supports "code is communication": the annotation Javadoc, registry validation, and runtime
  normalization all describe the same contract.

### 2026-06-20: Standard Multi-Message LLM Request Contract Hardening

Status: completed for this slice; included in the closed automatic P0 gate.

Code evidence:

- `ai-fabric-core/src/main/java/ai/fabric/dto/AIGenerationRequestContracts.java` centralizes the
  standard chat prompting contract for `AIGenerationRequest`.
- Requests that include `messages` now reject null history messages, missing roles, blank history
  content, and `SYSTEM` history messages. System instructions must use
  `AIGenerationRequest.systemPrompt`.
- Requests that include prior `messages` must also provide the current user input through
  `prompt` or `inputParts`, keeping history separate from the final user turn.
- `AIProviderManager` validates the contract before provider selection or provider calls, so managed
  providers share one fail-fast boundary.
- `SpringAiChatProvider` validates the same contract before transient input checks, model
  availability checks, and model resolution, so direct provider use cannot bypass the framework
  boundary.
- `SpringAiPromptMapper` validates before mapping to Spring AI messages and no longer maps history
  `SYSTEM` messages to Spring AI `SystemMessage`.
- `AIChatMessage` Javadoc now mirrors the guide rule: `messages` is for prior `USER` and
  `ASSISTANT` turns only; `systemPrompt` carries system instructions.

Test evidence:

- `AIProviderManagerTest.rejectsSystemHistoryMessagesBeforeCallingProvider` proves `SYSTEM` history
  is rejected before any provider is called.
- `AIProviderManagerTest.rejectsHistoryWithoutCurrentUserInputBeforeCallingProvider` proves prior
  history without a final user input fails before provider selection side effects.
- `AIProviderManagerTest.rejectsBlankHistoryMessagesBeforeCallingProvider` proves blank history
  content fails before provider calls.
- `SpringAiPromptMapperTest.rejectsSystemHistoryMessagesInsteadOfMappingThemAsSystemAuthority`
  proves Spring AI prompt mapping cannot convert history into provider system authority.
- `SpringAiPromptMapperTest.rejectsHistoryMessagesWithoutCurrentUserInput` proves the Spring AI
  mapper enforces the same final-user-input boundary.
- `SpringAiProviderAdapterTest.chatProviderRejectsSystemHistoryBeforeResolvingModel` proves direct
  Spring AI provider calls reject invalid history before model resolution.
- Focused core verification command run without `-DskipTests`:
  `mvn -pl ai-fabric-core -Dtest=AIProviderManagerTest test`
- Result: `AIProviderManagerTest` ran 8 tests, 0 failures, 0 errors, 0 skipped.
- Focused Spring AI verification command run with a clean reactor and without skipping unit tests:
  `mvn -pl providers/ai-fabric-provider-spring-ai -am clean test -Dtest=SpringAiPromptMapperTest,SpringAiProviderAdapterTest -Dsurefire.failIfNoSpecifiedTests=false`
- Result: `SpringAiPromptMapperTest` ran 5 tests and `SpringAiProviderAdapterTest` ran 29 tests; 0
  failures, 0 errors, 0 skipped.
- Full core verification command run without `-DskipTests`:
  `mvn -pl ai-fabric-core test`
- Result: 577 tests, 0 failures, 0 errors, 0 skipped.
- Local install command run without `-DskipTests`:
  `mvn -pl ai-fabric-core install`
- Result: 577 tests, 0 failures, 0 errors, 0 skipped; `ai-fabric-core-0.2.1.jar` installed
  locally.
- Full Spring AI provider reactor verification command run without `-DskipTests`:
  `mvn -pl providers/ai-fabric-provider-spring-ai -am test`
- Result: curated-default ran 3 tests, core ran 577 tests, and Spring AI provider ran 40 tests; 0
  failures, 0 errors, 0 skipped.

Philosophy check:

- Supports respecting LLM context: prior conversation history cannot silently become system
  authority.
- Supports fail-loud provider boundaries: invalid multi-message requests fail before provider
  selection, model resolution, prompt mapping, or fallback behavior.
- Supports the no-giant-prompt direction in the prompting guide: history, system instructions, and
  current user input remain distinct fields instead of being concatenated into an ambiguous string.
- Supports "code is communication": the DTO Javadoc, shared validator, provider manager, Spring AI
  adapter, and tests now describe the same request contract.

### 2026-06-20: Action Access Mode Contract Hardening

Status: completed for this slice; included in the closed automatic P0 gate.

Code evidence:

- `ai-fabric-core/src/main/java/ai/fabric/intent/action/ActionAccessMode.java` now centralizes the
  default read-only/grounding semantics. Only `READ` is grounding-eligible by default.
- `ai-fabric-core/src/main/java/ai/fabric/intent/action/AIActionRegistry.java` now treats
  `READ_WRITE` actions as side-effecting by default and rejects non-`READ` actions that opt into
  `readActionResolutionEligible`.
- `ai-fabric-actions-connector/src/main/java/ai/fabric/intent/action/connector/ConnectorActionCatalogLoader.java`
  and `ConnectorActionMetadataMapper.java` enforce the same file-catalog and direct-mapper contract.
- `ai-fabric-actions-registry/src/main/java/ai/fabric/entity/RegisteredConnectorAction.java` now
  rehydrates `READ_WRITE` DB actions as non-grounding by default.
- `ai-fabric-actions-registry/src/main/java/ai/fabric/intent/action/connector/registry/service/ConnectorActionDefinitionValidator.java`
  rejects DB-registered non-`READ` actions that request read-action resolution.
- `docs/Framework-Dev-Guides/actions-governance/ACTIONS_AND_CONFIRMATION_INTERCEPTORS_GUIDE.md`
  and `ACTIONS_CONNECTOR_AND_RELAY_GUIDE.md` now document the stricter rule: planner-driven
  read-action resolution is for `READ` actions only.

Test evidence:

- `AIActionRegistryTest` covers `READ_WRITE` as side-effecting/non-grounding by default and fail-fast
  rejection for `READ_WRITE + readActionResolutionEligible`.
- `ConnectorActionCatalogLoaderTest` covers file-catalog `READ_WRITE` default grounding behavior and
  invalid planner eligibility rejection.
- `RegisteredConnectorActionMappingTest` covers DB rehydration of stored `READ_WRITE` actions.
- `ConnectorActionDefinitionValidatorTest` covers direct DB registration validation.
- Focused core verification command run without `-DskipTests`:
  `mvn -pl ai-fabric-core -Dtest=AIActionRegistryTest test`
- Result: `AIActionRegistryTest` ran 18 tests, 0 failures, 0 errors, 0 skipped.
- Clean focused connector verification command run without `-DskipTests`:
  `mvn -pl ai-fabric-actions-connector -am clean test -Dtest=ConnectorActionCatalogLoaderTest -Dsurefire.failIfNoSpecifiedTests=false`
- Result: `ConnectorActionCatalogLoaderTest` ran 15 tests, 0 failures, 0 errors, 0 skipped.
- Clean focused registry verification command run without `-DskipTests`:
  `mvn -pl ai-fabric-actions-registry -am clean test -Dtest=RegisteredConnectorActionMappingTest,ConnectorActionDefinitionValidatorTest -Dsurefire.failIfNoSpecifiedTests=false`
- Result: 17 tests, 0 failures, 0 errors, 0 skipped.
- Full actions registry reactor verification command run without `-DskipTests`:
  `mvn -pl ai-fabric-actions-registry -am test`
- Result: curated-default ran 3 tests, core ran 579 tests, actions-connector ran 50 tests, and
  actions-registry ran 25 tests; 0 failures, 0 errors, 0 skipped.
- Local install command run without `-DskipTests` so real apps consumed fresh artifacts:
  `mvn -pl ai-fabric-actions-registry -am install`
- Result: curated-default ran 3 tests, core ran 579 tests, actions-connector ran 50 tests, and
  actions-registry ran 25 tests; 0 failures, 0 errors, 0 skipped.
- Real-app compatibility command run cleanly without `-DskipTests`:
  `mvn -pl smoke-support,chat-capabilities-demo,it-support-action-bot,sub-management-hub,sub-management-hub-simple -am clean test`
- Result: smoke-support ran 8 tests, chat-capabilities-demo ran 24 tests, it-support-action-bot
  compiled cleanly with no tests, sub-management-hub ran 6 tests, and sub-management-hub-simple ran
  3 tests; 0 failures, 0 errors, 0 skipped.

Philosophy check:

- Supports fail-closed action safety: mutating or partially mutating actions cannot silently become
  planner-selected read helpers.
- Supports "code is communication": the access-mode enum now exposes the semantic predicate used by
  annotation, connector, and DB registry paths.
- Supports explicit business boundaries: `READ_WRITE` is still supported, but it must be treated as
  side-effecting unless a future explicit policy says otherwise.

### 2026-06-20: AI Fabric Cache Namespace Confirmation

Status: superseded by the follow-up cache auto-configuration hardening slice above.

Code evidence:

- `ai-fabric-core/src/main/java/ai/fabric/cache/AICacheNames.java` defines all shared Spring cache
  names with the `ai-fabric-` prefix: `ai-fabric-embeddings`, `ai-fabric-vector-search`,
  `ai-fabric-text-search`, `ai-fabric-generation`, `ai-fabric-validation`,
  `ai-fabric-retention-status`, `ai-fabric-behavior-retention`, and `ai-fabric-access-decisions`.
- `AICacheConfig`, `AIEmbeddingService`, `VectorSearchService`, `VectorManagementService`, and
  `AIAccessControlService` consume those constants instead of bare shared cache-name literals.
- Remaining cache-like fields found in providers, such as ONNX classpath-resource caches and Lucene
  index maps, are private in-memory implementation details, not shared Spring cache names. Follow-up
  work in `AICacheConfig` also removed generic native helper cache beans from the Spring context.

Philosophy check:

- Supports "code is communication": framework-owned cache state is visibly namespaced in shared
  cache managers, logs, metrics, and external cache backends.
- Reduces accidental collisions with host applications or neighboring services that may also use
  generic names like `vectorSearch`, `embeddingCache`, or `searchCache`.

### 2026-06-20: Release Guard And Real-App Smoke Verification

Status: completed for this slice; no code change required.

Release guard evidence:

- Release guard bundle run without `-DskipTests`:
  `.github/scripts/validate-framework-release-guards.sh`
- Result: passed provider registry validation, workflow test-policy validation, release-doc policy
  tests, release-doc policy validation, production stub marker validation, and vector readiness
  health script tests.
- Production marker scan covered Java sources under `ai-infrastructure-module` and `examples` for
  release-risk markers such as TODO, FIXME, stub, dummy, not implemented, placeholder, and mock
  implementation. Remaining matches were prompt-template terminology or the guard script itself, not
  production stub implementations.

Real-app package evidence:

- Full real-app suite package command run without `-DskipTests`:
  `mvn -f examples/real-apps/pom.xml clean package`
- Result: all 13 reactor entries succeeded, including smoke support, 11 runnable real apps, and the
  suite aggregator.
- Generated Surefire report total after the package run: 41 report files, 101 tests, 0 failures,
  0 errors, 0 skipped.

Runtime smoke evidence:

- Deterministic packaged-jar smoke command:
  `.github/scripts/smoke-boot-realapps.sh`
- Result: all 11 real apps started successfully with `--spring.profiles.active=smoke` on ports
  19001 through 19011.
- The smoke run includes `cloud-qdrant-openai-vector-search`, proving the smoke profile boots without
  leaking a live external Qdrant/OpenAI dependency into the deterministic release gate.

Cross-app data-sync runtime evidence:

- Deterministic packaged-jar cross-app smoke command:
  `.github/scripts/smoke-ecommerce-chat-datasync.sh`
- The smoke starts `chat-capabilities-demo` and `ecommerce-store` as separate packaged JVMs under the
  offline `smoke` profile, enables connector indexing in ecommerce, and points it at the chat runtime
  data-sync API.
- The smoke creates a unique product through ecommerce, waits until chat runtime vector search returns
  that SKU, deletes the product through ecommerce, then repeats the exact same runtime vector search
  until the SKU disappears.
- This closes the `ecommerce-store -> chat-capabilities-demo` P0 proof for data-sync upsert, verified
  auth context propagation, runtime vector search, delete propagation, and stale-result cache eviction.
- The script is wired into `.github/workflows/framework-verify.yml` immediately after the real-app
  boot smoke, so this proof runs in automatic PR/push CI after the real-app suite is packaged.

Philosophy check:

- Supports "frameworks teach": every runnable real app must start from its packaged artifact under a
  deterministic profile, so examples teach repeatable release verification rather than manual demos.
- Supports correctness before convenience: the release gate uses real unit tests, generated Surefire
  reports, and packaged-jar smoke boots instead of skipped tests or compile-only confidence.
- Supports fail-closed release hygiene: production stub markers and workflow test-policy drift are
  checked by scripts that CI can run the same way.

### 2026-06-20: Orchestration Normalization Contract And Snapshot Boundary

Status: completed for this slice; included in the closed automatic P0 gate.

Code evidence:

- `ai-fabric-core/src/test/java/ai/fabric/intent/orchestration/OrchestrationResultDebugSnapshotStoreTest.java`
  now proves debug snapshots capture only safe provider-agnostic diagnostics and do not include result
  message/data content such as PII-like values or raw provider payloads.
- `ai-fabric-core/src/test/java/ai/fabric/intent/orchestration/pipeline/steps/OrchestrationResultNormalizationStepTest.java`
  now proves the pipeline normalization step records the normalized top-level contract in debug
  snapshots when snapshots are enabled, and records nothing when they are disabled.
- The tests strengthen the P0 provider-agnostic normalization row without changing runtime behavior:
  `OrchestrationResultNormalizer`, `OrchestrationResultNormalizationStep`, and
  `OrchestrationResultDebugSnapshotStore` continue to enforce deterministic type/success/error-code
  evidence.

Documentation evidence:

- Added the missing orchestration contract docs referenced by
  `NORMALIZATION_AND_ORCHESTRATION_GUIDE.md`:
  `ORCHESTRATION_RESULT_NORMALIZATION.md`, `PIPELINE_ARCHITECTURE.md`,
  `PIPELINE_STEPS_REFERENCE.md`, and `PROGRESSIVE_INTENT_EXTRACTION_FALLBACK_PLAN.md`.
- The new docs tie the release-facing contract to current code paths, step ordering, progressive
  intent extraction budget/fallback behavior, and root-runnable verification commands.

Test evidence:

- Focused verification command run without `-DskipTests`:
  `mvn -f ai-infrastructure-module/pom.xml -pl ai-fabric-core -Dtest=OrchestrationResultNormalizerTest,OrchestrationResultNormalizationStepTest,OrchestrationResultDebugSnapshotStoreTest test`
- Result: 10 tests, 0 failures, 0 errors, 0 skipped.
- Full core verification command run without `-DskipTests`:
  `mvn -f ai-infrastructure-module/pom.xml -pl ai-fabric-core test`
- Result: 584 tests, 0 failures, 0 errors, 0 skipped.
- Documentation reference check confirmed all four guide-referenced orchestration docs exist under
  `docs/Framework-Dev-Guides/architecture/orchestration`.

Philosophy check:

- Supports "code is communication": the normalization and progressive extraction contracts now have
  explicit release docs instead of broken guide references.
- Supports fail-closed and provider-agnostic behavior: debug snapshots expose canonical outcome
  evidence for CI without leaking user text, provider output, retrieved content, or result payloads.
- Supports respecting intelligence without trusting raw LLM output: progressive extraction documents
  the bounded ladder and validation gates that constrain provider variability.

### 2026-06-20: Chat Session Expired Confirmation Fail-Closed Hardening

Status: completed for this slice; included in the closed automatic P0 gate.

Code evidence:

- `SingleConfirmationPositiveResolver` now refuses to convert an expired pending action into an
  executable action, including defensive direct `resolve(...)` calls.
- `CompoundConfirmationResolver` now applies the same expiration guard for compound turns such as
  "yes and also ...".
- `AnnotatedConfirmationInterceptorsResolver` and `ConfiguredConfirmationInterceptorsResolver` now
  refuse expired pending actions before invoking app-level or catalog-level confirmation
  interceptors.
- The existing `ExpiredConfirmationResolver` remains responsible for cleanup/popping; executable
  resolvers no longer rely on resolver ordering alone for this safety rule.
- `MigrationAdminControllerTest` no longer mocks `DemoDataResetService` directly. It uses a recording
  subclass so the admin test does not depend on Mockito inline instrumentation of optional indexing
  repository generics.

Documentation evidence:

- `ACTIONS_AND_CONFIRMATION_INTERCEPTORS_GUIDE.md` now states that expired pending confirmations may
  be cleaned up but must not become executable ACTION intents, and custom resolvers should enforce
  the same guard.

Test evidence:

- Focused chat-session verification command run without `-DskipTests`:
  `mvn -f ai-infrastructure-module/pom.xml -pl ai-fabric-chat-session -Dtest=ConfirmationResolutionStepTest,AnnotatedConfirmationInterceptorsResolverTest,ConfiguredConfirmationInterceptorsResolverTest test`
- Result: 16 tests, 0 failures, 0 errors, 0 skipped.
- Clean chat-session verification command run without `-DskipTests`:
  `mvn -f ai-infrastructure-module/pom.xml -pl ai-fabric-chat-session clean test`
- Result: 48 tests, 0 failures, 0 errors, 0 skipped.
- Local install command run without `-DskipTests`:
  `mvn -f ai-infrastructure-module/pom.xml -pl ai-fabric-chat-session -am install`
- Result: curated-default ran 3 tests, core ran 584 tests, chat-session ran 48 tests; 0 failures,
  0 errors, 0 skipped.
- Real-app compatibility command run without `-DskipTests`:
  `mvn -f examples/real-apps/pom.xml -pl smoke-support,chat-capabilities-demo -am test`
- Result: smoke-support ran 8 tests and chat-capabilities-demo ran 24 tests; 0 failures, 0 errors,
  0 skipped.

Verification note:

- A non-clean targeted chat app test initially reused stale compiled classes and failed while mocking
  `DemoDataResetService` because Mockito attempted to instrument a type hierarchy containing optional
  `IndexingQueueRepository` generics. A clean targeted rebuild passed after the test was changed to a
  recording subclass. This reinforces the existing release-gate preference for clean app/module runs
  when target directories may contain stale IDE/compiler artifacts.

Philosophy check:

- Supports fail-closed action safety: a stale confirmation cannot execute a pending action simply
  because resolver ordering or app customization is incomplete.
- Supports "code is communication": every action-producing resolver now carries the expiration rule
  locally instead of depending on an implicit cleanup side effect.
- Supports framework examples as teaching material: the chat app admin test now avoids fragile
  mocking of optional framework internals and keeps its assertion focused on the public admin
  boundary.

## P1 Progress Evidence

### 2026-06-21: Packaged Real-App P1 Scenario Smoke Lane

Status: completed for the deterministic packaged real-app P1 lane and wired into automatic CI. This
closes the packaged smoke rows for RAG quality, privacy/governance deletion, relationship-query,
behavior signals, support/action bot authorization, migration/backfill, and action confirmation plus
confirmation interceptors. It now also gives `chat-capabilities-demo` proof for public anonymous
write-action policy gates and the attachment-aware chat suggestions request contract.
Connector-backed actions, DB action registry, relay, retrieval connector, and Spring AI bridge rows
are handled by the follow-up framework module/relay P1 slice below.

Code evidence:

- `.github/scripts/smoke-p1-realapp-scenarios.sh` starts packaged real-app jars under the offline
  `smoke` profile and asserts concrete HTTP JSON evidence for seven product-shaped scenarios.
- `.github/workflows/framework-verify.yml` now runs the P1 smoke after the real-app boot smoke and
  ecommerce-to-chat data-sync smoke, so the lane is part of automatic PR/push CI.
- `docs/Framework-Dev-Guides/testing-verification/CI_PIPELINE_GUIDE.md` documents the new CI step,
  dependencies, failure modes, and local command.
- `chat-capabilities-demo` now has `ChatLocalLlmProvider`, a deterministic smoke-profile-capable
  provider for action extraction, yes/no confirmation turns, and suggestion generation. It keeps the
  app flow offline while still exercising the real chat-session/action pipeline and suggestions
  endpoint.
- `ChatLocalLlmProviderTest` preserves cancel-action extraction, `list_orders` READ-action
  extraction, support-ticket WRITE-action extraction for anonymous policy smoke, attachment-grounded
  suggestion JSON, negative confirmation detection, and deterministic embedding behavior.
- `CartItem` now ignores its parent `Cart` during JSON serialization, and `CartSerializationTest`
  prevents recursive cart/item responses from breaking the real checkout smoke.
- `DataMigrationService`, `IndexingRequest`, and `SpringAiDocumentIndexingOptions` now schedule
  indexing work with the framework UTC clock boundary. This prevents local timezone drift from
  delaying migration/backfill smoke indexing.
- `DataMigrationServiceTest` asserts the migration queue uses the injected clock when setting
  `scheduledFor`.
- `it-support-action-bot` now exposes a smoke-profile-only `SmokeActionController` that exercises the
  real `AIActionRegistry` handlers directly, proving action metadata, `@ActionAllowed`, confirmation
  message generation, and confirmed execution without live LLM credentials.
- `SmokeActionControllerTest` covers missing-identity denial, allowed non-confirmable ticket
  creation, confirmable assignment gating, and confirmed assignment execution against the app's H2
  state.

Scenario evidence:

- Smart FAQ golden-answer quality: seeds the FAQ demo set and fails closed when expected quality
  evidence is missing.
- Privacy/governance deletion: stores masked support data, verifies it is searchable, deletes the
  customer inventory, and proves the deleted customer no longer appears in search.
- Relationship-query CRM: seeds accounts/deals, verifies a structured successful relationship answer,
  and verifies an impossible query remains bounded.
- Behavior signal analysis: seeds user events and verifies churn, sentiment, trend, and next-action
  evidence.
- Support/action bot authorization: verifies discovered write-action contracts, denies a write action
  without identity, executes an allowed non-confirmable create action, gates assignment behind
  confirmation, then executes the confirmed assignment and verifies ticket state.
- Migration/backfill lifecycle: seeds products, runs migration, waits for completion, checks progress,
  and verifies indexed search results.
- Action confirmation and confirmation interceptor: creates real carts/orders, executes the
  `list_orders` READ action without confirmation, triggers `cancel_purchase_order`, verifies the
  first confirmation, verifies the retention-offer interceptor, proves rejecting the offer executes
  cancellation, and proves accepting the offer keeps the order active through the discount action.
- Public anonymous action policy gates: the same packaged chat app asks for a support-ticket WRITE
  action without `userId`, receives the framework `ACTION_DENIED` result, and proves the denial
  happens before confirmation or handler execution.
- Smart suggestions and chat UI request contract: the packaged chat app calls `/api/chat/suggestions`
  with an attached product card, receives exactly three suggestions, and proves at least one
  suggestion is grounded in the attachment text.

Test evidence:

- Framework UTC scheduling focused verification command run without `-DskipTests`:
  `mvn -B -V --no-transfer-progress -f ai-infrastructure-module/pom.xml -pl ai-fabric-indexing,ai-fabric-migration -am test`
- Result: core ran 586 tests, indexing and migration ran their focused suites, migration ran 30
  tests; 0 failures, 0 errors, 0 skipped.
- Framework local install command run without `-DskipTests`:
  `mvn -B -V --no-transfer-progress -f ai-infrastructure-module/pom.xml -pl ai-fabric-indexing,ai-fabric-migration -am install`
- Result: curated-default ran 3 tests, core ran 586 tests, indexing ran 49 tests, migration ran 30
  tests; 0 failures, 0 errors, 0 skipped.
- Focused chat deterministic provider verification command run without `-DskipTests`:
  `mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl smoke-support,chat-capabilities-demo -am -Dtest=ChatLocalLlmProviderTest -Dsurefire.failIfNoSpecifiedTests=false test`
- Result: chat-capabilities-demo ran `ChatLocalLlmProviderTest` with 4 tests; 0 failures, 0 errors,
  0 skipped.
- Selected P1 real-app package command run without `-DskipTests`:
  `mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl smoke-support,smart-faq-assistant,privacy-first-customer-facing-support,relationship-query-crm-insights,behavior-churn-signals,migration-enabled-product-catalog,chat-capabilities-demo,it-support-action-bot -am clean package`
- Result: eight selected modules rebuilt and packaged, 76 tests ran; 0 failures, 0 errors,
  0 skipped.
- P1 packaged real-app HTTP smoke command:
  `.github/scripts/smoke-p1-realapp-scenarios.sh`
- Result: all seven P1 product-shaped scenarios passed against the freshly packaged jars, including
  support action authorization/confirmation, chat READ-action execution, and chat anonymous
  WRITE-action denial, and attachment-aware chat suggestions.
- Release guard commands run after adding the CI step:
  `bash -n .github/scripts/smoke-p1-realapp-scenarios.sh && .github/scripts/validate-workflow-test-policy.sh && .github/scripts/validate-release-doc-policy.sh`
- Result: script syntax, workflow test policy, and release documentation policy all passed.

Philosophy check:

- Supports "frameworks teach": the examples now prove real product flows from packaged artifacts
  rather than only unit-level fragments.
- Supports fail-closed release hygiene: the new lane has deterministic pass/fail JSON assertions and
  does not require provider secrets.
- Supports modularity: the packaged real-app lane covers scenarios already represented by real apps,
  while connector, relay, registry, and Spring AI bridge work have explicit module and packaged-relay
  proof instead of being hidden inside the app smoke.

### 2026-06-21: Framework Module And Relay P1 Hardening

Status: completed for the remaining framework-owned P1 module rows that do not require live provider
credentials. No LLM API keys were needed for this slice because Spring AI provider behavior is covered
through deterministic model/test doubles and the relay/connector paths use local HTTP stubs.

P1 implementation checklist:

| P1 Area | Release Proof | Status |
| --- | --- | --- |
| Connector-backed actions through Customer Connector API | `ActionConnectorExecutorTest`, `ConnectorAIActionHandlerTest`, `ConnectorActionsRegistryContributorTest` | Closed by module tests |
| File-based connector action catalog loading and validation | `ConnectorActionCatalogLoaderTest` and YAML fixture matrix | Closed by module tests |
| Connector action idempotency, retry, and error contract | `ActionConnectorExecutorTest` retry/error assertions plus relay idempotency smoke | Closed by module tests and packaged relay smoke |
| DB-backed action registry | `RegisteredConnectorActionMappingTest`, `ConnectorActionRegistryServiceTest`, `DbConnectorActionsRegistryContributorTest`, `ConnectorActionDefinitionValidatorTest` | Closed by module tests |
| Action registry Liquibase helper | `AIActionDbRegistryLiquibaseEnvironmentPostProcessorTest` | Closed by module tests |
| AI Fabric Relay `/actions/execute` | relay service/controller/OpenAPI tests plus `.github/scripts/smoke-p1-relay-local.sh` | Closed by packaged relay smoke |
| Relay auth, replay protection, rate limiting, idempotency | `RelayAuthenticatorTest`, `FixedWindowRateLimiterTest`, `IdempotencyStoreTest`, relay smoke API-key/idempotency assertions | Closed by module tests and packaged relay smoke |
| Retrieval connector `/retrieval/search` documents-only boundary | `RetrievalConnectorRAGProviderTest`, `AIRetrievalConnectorAutoConfigurationTest`, relay smoke documents-only rejection | Closed by module tests and packaged relay smoke |
| Spring AI guarded action tool-calling bridge | `SpringAiProviderAdapterTest`, `SpringAiReadOnlyActionToolExampleTest`, `AIActionToolCallbackFactoryTest` | Closed by provider/core tests |
| Request-scoped Spring AI advisors | `SpringAiProviderAdapterTest` advisor bridge coverage | Closed by provider tests |
| Redacted Spring AI observation diagnostics | `SpringAiObservationDiagnosticsTest` | Closed by provider tests |
| Indexing queue, retry, dead-letter, worker behavior | `IndexingQueueServiceTest`, `IndexingWorkerRunnerTest`, `IndexingWorkProcessorTest`, cleanup/strategy tests | Closed by indexing tests |
| Spring AI document reader/chunker ingestion bridge | `SpringAiDocumentReaderFactoryTest`, `SpringAiDocumentIndexingAdapterTest` | Closed by indexing tests |
| Packaged real-app scenario rows | `.github/scripts/smoke-p1-realapp-scenarios.sh` | Closed by automatic CI smoke |
| Public anonymous action policy gates | `IntentHandlingStepAnonymousActionPolicyTest` plus `.github/scripts/smoke-p1-realapp-scenarios.sh` chat denial assertion | Closed by core tests and packaged chat smoke |
| Smart suggestions and chat UI request contract, attachments, pinned targets | `ChatControllerSuggestionsTest`, core attachment/target tests, and `.github/scripts/smoke-p1-realapp-scenarios.sh` suggestions assertion | Closed by controller/core tests and packaged chat smoke |
| Runtime/public auth, curated modes, compliance/retention rows | Existing P0 release gates and real-app/controller tests listed above | Carried by the P0/P1 automatic gate; no additional live-provider proof required for P1 |

Code evidence:

- `ai-fabric-relay/pom.xml` now binds `spring-boot:repackage`, producing an executable relay boot jar
  for Docker, local smoke, and customer-side deployment.
- `ai-fabric-relay/Dockerfile` now builds the relay package without `-DskipTests`, so container builds
  run the relay unit/contract tests before copying the artifact.
- `RelayContainerPackagingTest` now verifies the relay POM keeps the executable-jar repackage contract
  and the Docker package command does not skip tests.
- `.github/scripts/smoke-p1-relay-local.sh` starts a packaged relay jar and a local internal stub,
  then proves API-key auth, action forwarding, idempotency replay/conflict, retrieval forwarding, and
  documents-only retrieval rejection.
- `.github/workflows/framework-verify.yml` runs the relay smoke immediately after the framework
  reactor build/install, before integration-suite compilation and real-app smokes.
- `CI_PIPELINE_GUIDE.md` documents the relay smoke dependencies, command, and failure modes.

Test evidence:

- Clean connector/registry verification command run without `-DskipTests`:
  `mvn -B -V --no-transfer-progress -f ai-infrastructure-module/pom.xml -pl ai-fabric-actions-registry -am clean test`
- Result: curated-default ran 3 tests, core ran 586 tests, actions-connector ran 50 tests, and
  actions-registry ran 25 tests; 0 failures, 0 errors, 0 skipped.
- Clean P1 module verification command run without `-DskipTests`:
  `mvn -B -V --no-transfer-progress -f ai-infrastructure-module/pom.xml -pl ai-fabric-actions-registry-liquibase,ai-fabric-relay,ai-fabric-retrieval-connector,providers/ai-fabric-provider-spring-ai,ai-fabric-indexing -am clean test`
- Result: curated-default ran 3 tests, core ran 586 tests,
  actions-connector ran 50 tests, actions-registry ran 25 tests, actions-registry-liquibase ran 9
  tests, retrieval-connector ran 17 tests, relay ran 35 tests, indexing ran 49 tests, and Spring AI
  provider ran 40 tests; 0 failures, 0 errors, 0 skipped.
- Focused relay clean package command run without `-DskipTests` after the packaging fix:
  `mvn -B -V --no-transfer-progress -f ai-infrastructure-module/pom.xml -pl ai-fabric-relay -am clean package`
- Result: relay ran 35 tests, produced an executable Spring Boot jar, and repackaged
  `ai-fabric-relay-0.2.1.jar`; 0 failures, 0 errors, 0 skipped.
- Packaged relay local smoke command:
  `.github/scripts/smoke-p1-relay-local.sh`
- Result: relay booted from the packaged jar and passed API-key rejection, action forwarding,
  idempotent replay, idempotency conflict, retrieval forwarding, and generated-response rejection.

Verification note:

- A non-clean local P1 reactor run initially exposed stale target classes in
  `ai-fabric-actions-registry`. Clean verification passed. The release-facing command for local P1
  module proof therefore uses `clean test`, and automatic CI starts from a fresh checkout.
- The first packaged relay smoke exposed that the relay jar was not executable. Binding
  `spring-boot:repackage` in the relay module fixed the deployability gap and is now guarded by both
  packaging tests and the packaged relay smoke.

Philosophy check:

- Supports "frameworks teach": the relay is no longer only a documented component; it boots as a
  packaged jar and demonstrates the exact Customer Connector API shape.
- Supports fail-closed boundaries: generated retrieval responses are rejected at the relay boundary,
  and idempotency conflicts return deterministic failure evidence.
- Supports correctness before convenience: the Docker package build now runs tests instead of using
  skipped-test packaging.

## Philosophy Alignment Check

Source checked: `docs/Framework-Dev-Guides/LLM-guides/AI_FABRIC_FRAMEWORK_PHILOSOPHY.md`.

The capability prioritization supports the framework philosophy.

### Strong Alignment

- The P0 gate centers fail-closed paths: authorization, data-sync preflight, PII, compliance,
  governance deletion, confirmation flows, and admin protection.
- The inventory separates framework runtime capabilities from platform/operator capabilities, which
  respects the philosophy scope note and keeps core clean.
- Prioritizing real-app proofs matches the "frameworks teach" principle: examples become patterns,
  so the examples must demonstrate safe action, RAG, auth, and privacy behavior.
- The P0/P1 split supports modularity. Apps prove what they use; optional connector, relay, registry,
  marketplace, and provider-matrix capabilities stay outside the minimal runtime gate.
- The cache rename to `ai-fabric-vector-search` supports the "code is communication" principle by
  making framework-owned cache state visibly namespaced in logs, metrics, and shared cache managers.
- The test-first release gate supports correctness before convenience and avoids relying on
  optimistic manual demos alone.

### Tensions To Watch

- Platform, marketplace, managed deployment, and operator flows are documented under
  `Framework-Dev-Guides`, but the philosophy file says framework/core philosophy does not govern
  product/admin/operator workflow design. This document therefore marks those as P2/P3 unless they
  affect embedded framework runtime correctness.
- Deterministic smoke providers are good for repeatable P0 tests, but they do not replace real
  provider/vector matrix verification. Real provider behavior stays P2/manual or nightly.
- The philosophy rejects magic strings. Cache names, cache bean exposure, and action names should be
  centralized or explicitly namespaced where practical, especially when shared by services, config,
  tests, and operational docs.
- The philosophy says required SPIs should fail loudly when they protect security/business rules.
  Any demo-only permissive defaults must stay explicit, profile-scoped, and impossible to mistake for
  production posture.

### Conclusion

The analysis does not contradict the framework philosophy. It supports it by making the release gate
security-first, correctness-first, modular, and example-driven.

The main adjustment from the philosophy is implementation hygiene: shared operational names, such as
cache names and auto-configured infrastructure bean exposure, should use named constants,
namespacing, and conditional backoff so the code teaches the pattern we want users to copy.
