# Framework Development Guides

This folder is the framework-repo copy of framework-related documentation from `Final_Documentation`.

`Final_Documentation` is intentionally left untouched. These files are copied here so the separated framework repository has a focused, navigable documentation set without dragging in Shopify, ProdUS, partner-program, launch, and business-specific material.

## Selection Rules

Copied here:

- framework architecture and ADRs
- orchestration, RAG, retrieval, vectorization, and provider docs
- runtime modes, curated packs, prompting, and configuration docs
- action, confirmation, governance, compliance, and post-action docs
- connector, relay, REST, and public client integration docs
- Marketplace plugin authoring and manifest docs
- runtime auth, public/private runtime integration, and secret-boundary docs
- deployment verification, benchmarking, export/import, and CI verification docs
- developer workflow prompts useful for framework maintenance
- generic application pattern docs such as agentic and multi-agent patterns

Not copied here:

- Shopify Companion product, launch, app review, and merchant docs
- ProdUS staging handoff or customer-specific operational material
- private LLM handoff files and private production credential notes
- partner-program, GTM, pricing, and business strategy docs
- platform admin/operator user manuals that are not framework development guides
- `.DS_Store` and other local filesystem artifacts

## Folder Map

| Folder | Use it for |
| --- | --- |
| `architecture/adrs` | Architecture decision records copied from `Final_Documentation/ADRs`. |
| `architecture/framework` | Framework philosophy and agentic framework compliance. |
| `architecture/orchestration` | Normalization, orchestration, and orchestrator behavior. |
| `architecture/rag` | RAG architecture plans and deterministic answer generation. |
| `architecture/actions-governance` | Architecture-level Thinker/Resolver and read-only action mode plans. |
| `actions-governance` | Action handlers, confirmation interceptors, governance, compliance, and post-action generation. |
| `application-patterns` | Agentic app and multi-agent implementation patterns. |
| `connectors` | Generic REST connector, customer connector, relay, and REST branch guides. |
| `deployment-operations` | Deployment verification, benchmarking, latency, export/import, and hosted verification. |
| `developer-workflows` | LLM coding and code-review prompts for maintainers. |
| `LLM-guides` | LLM session reference material and framework philosophy. |
| `marketplace-plugins` | Marketplace plugin authoring, manifest, data plugins, inference profiles, and troubleshooting. |
| `providers-vector-db` | LLM/embedding/vector provider and managed vector database guidance. |
| `retrieval-vectorization` | Retrieval connector, data sync, RAG query composition, and tenant vectorization docs. |
| `runtime-integration` | Runtime integration behavior that is not primarily auth-related. |
| `runtime-modes-prompts` | Modes, curated packs, prompt management, standard chat prompting, and runtime optimization. |
| `security-auth` | Runtime auth, public/browser tokens, private runtime auth, anonymous action policy, and secret boundaries. |
| `testing-verification` | GitHub Actions verification, provider-matrix tests, regression verification, and verification playbooks. |
| `ui-clients` | Public API client and chat capability UI migration guidance. |

## Fast Reading Paths

For a new framework maintainer:

1. `architecture/framework/AI_FABRIC_FRAMEWORK_PHILOSOPHY.md`
2. `architecture/orchestration/NORMALIZATION_AND_ORCHESTRATION_GUIDE.md`
3. `runtime-modes-prompts/MODES_AND_CURATED_PACKS_GUIDE.md`
4. `retrieval-vectorization/RAG_EMBEDDING_QUERY_COMPOSITION_GUIDE.md`
5. `actions-governance/ACTIONS_AND_CONFIRMATION_INTERCEPTORS_GUIDE.md`
6. `security-auth/RUNTIME_AUTHORIZATION_AND_ACCESS_CONTROL_GUIDE.md`

For connector and action work:

1. `connectors/GENERIC_REST_API_CONNECTOR_GUIDE.md`
2. `connectors/CUSTOMER_CONNECTOR_IMPLEMENTATION_GUIDE.md`
3. `actions-governance/ACTIONS_CONNECTOR_AND_RELAY_GUIDE.md`
4. `actions-governance/POST_ACTION_GENERATION_FOR_ACTION_HANDLERS_GUIDE.md`
5. `security-auth/PUBLIC_ANONYMOUS_ACTION_POLICY_GUIDE.md`

For specialist execution and manifest authoring:

1. `application-patterns/AGENTIC_APP_GUIDE.md`
2. `application-patterns/SPECIALIST_MANIFEST_AUTHORING_GUIDE.md`
3. `application-patterns/INTERACTIVE_DIALOGUE_OWNERSHIP.md`
4. `application-patterns/ONE_LEVEL_SPECIALIST_DELEGATION.md`
5. `application-patterns/EXPLICIT_SPECIALIST_HANDOFF.md`
6. `application-patterns/BOUNDED_READ_ONLY_PARALLEL_PLANS.md`
7. `actions-governance/GOVERNED_SPECIALIST_WRITES_AND_RECEIPTS.md`
8. `security-auth/RUNTIME_AUTHORIZATION_AND_ACCESS_CONTROL_GUIDE.md`
9. `testing-verification/VERIFICATION_PLAYBOOK.md`

For RAG, vector, and provider work:

1. `retrieval-vectorization/RAG_EMBEDDING_QUERY_COMPOSITION_GUIDE.md`
2. `retrieval-vectorization/RAG_INDEXING_LIFECYCLE_GUIDE.md`
3. `retrieval-vectorization/RETRIEVAL_CONNECTOR_GUIDE.md`
4. `retrieval-vectorization/MIGRATION_BACKFILL_GUIDE.md`
5. `providers-vector-db/PLATFORM_PROVIDER_AND_VECTOR_DEPLOYMENT_GUIDE.md`
6. `runtime-integration/SPRING_AI_PROVIDER_INTEGRATION_GUIDE.md`
7. `providers-vector-db/VECTOR_DATABASE_CONFIGURATION_AUTH_AND_DEPLOYMENT_GUIDE.md`
8. `testing-verification/REALAPI_PROVIDER_MATRIX_TESTING_GUIDE.md`

For release and verification:

1. `testing-verification/VERIFICATION_PLAYBOOK.md`
2. `testing-verification/CI_PIPELINE_GUIDE.md`
3. `testing-verification/GITHUB_ACTIONS_VERIFICATION_SUITE_GUIDE.md`
4. `testing-verification/PLATFORM_REGRESSION_AND_LIVE_ADMIN_VERIFICATION_GUIDE.md`
5. `deployment-operations/PLATFORM_HOSTED_DEPLOYMENT_VERIFICATION_GUIDE.md`

For LLM-assisted framework debugging sessions:

1. `LLM-guides/AI_FABRIC_FRAMEWORK_PHILOSOPHY.md`
2. `LLM-guides/AI_FABRIC_LLM_SESSION_LESSONS_LEARNED.md`
3. `developer-workflows/AI_LLM_CODE_GENERATION_GUIDE.md`

## Copy Policy

These are copies, not symlinks. If a source guide changes in `Final_Documentation`, refresh only the relevant copied file and keep this folder framework-focused. Do not copy customer-private, product-launch, or business strategy docs into this tree unless they are rewritten as framework development guidance.
