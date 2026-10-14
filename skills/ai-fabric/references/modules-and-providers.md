# Modules And Providers

## Supported Baseline

- AI Fabric release: `0.8.32`
- Public source baseline reviewed: `3c3be868`
- Java: `21`
- Spring Boot: `4.1.0`
- Spring AI: `2.0.0`
- Maven group: `io.github.loom-ai-labs`

Import `ai-fabric-bom` in dependency management, then declare only the modules the application needs. Confirm the current values in [`ai-infrastructure-module/pom.xml`](../../../ai-infrastructure-module/pom.xml) before changing a project to a later release.

## Capability Modules

| Artifact | Use it for |
| --- | --- |
| `ai-fabric-starter` | Core Spring Boot activation and common orchestration wiring. |
| `ai-fabric-execution` | Structured orchestration, execution plans, specialists, and declarative chains. |
| `ai-fabric-provider-starter` | Provider selection and provider-neutral bootstrap. |
| `ai-fabric-provider-spring-ai` | Spring AI-backed generation and embedding providers. |
| `ai-fabric-indexing` | Entity projection and indexing lifecycle. |
| `ai-fabric-data-sync` | Push ingestion and durable indexing work contracts. |
| `ai-fabric-rag` | Retrieval-augmented generation and evidence flow. |
| `ai-fabric-retrieval-connector` | Application-supplied external document retrieval. |
| `ai-fabric-actions-connector` | Connector-backed action execution. |
| `ai-fabric-actions-registry` | Database-backed action catalog support. |
| `ai-fabric-actions-registry-liquibase` | Liquibase ownership for the action registry schema. |
| `ai-fabric-chat-session` | Backend conversation history, ownership, control, and handover contracts. |
| `ai-fabric-behavior` | Behavior signals and event-oriented analysis. |
| `ai-fabric-pii` | Configured PII detection/redaction boundaries. |
| `ai-fabric-governance` | Optional governance checks and policy boundaries. |
| `ai-fabric-web` | Reusable HTTP-facing framework contracts where the application chooses to expose them. |

Curated modules (`ai-fabric-curated-default`, `ai-fabric-curated-commerce`, and `ai-fabric-curated-support`) provide reviewed capability composition and prompt/catalog defaults. They do not own application business policy.

## Vector Providers

Select one provider that matches the deployment's durability, filtering, and operations requirements:

- `ai-fabric-vector-lucene`: local persistent index for bounded single-instance applications and demos.
- `ai-fabric-vector-memory`: non-durable development/test use.
- `ai-fabric-vector-qdrant`, `ai-fabric-vector-pinecone`, `ai-fabric-vector-weaviate`, or `ai-fabric-vector-milvus`: external vector services with provider-specific configuration and operational ownership.

Do not claim provider capabilities that its contract tests do not prove. In particular, do not widen tenant filters when a provider cannot enforce the required metadata predicate.

## Reference Applications

- Minimal bootstrap: [`examples/minimal-spring-boot`](../../../examples/minimal-spring-boot)
- Indexed live data and durable work: [`ai-fabric-live-data-sync`](../../../examples/real-apps/ai-fabric-live-data-sync)
- Governed reads/writes plus session state: [`ai-fabric-account-resolver`](../../../examples/real-apps/ai-fabric-account-resolver)
- Generic chat capabilities: [`chat-capabilities-demo`](../../../examples/real-apps/chat-capabilities-demo)
- Conversation authority and handover: [`conversation-handover-demo`](../../../examples/real-apps/conversation-handover-demo)
- Document lifecycle: [`document-ingestion-workbench`](../../../examples/real-apps/document-ingestion-workbench)

Treat examples as maintained evidence, not libraries to copy wholesale. Preserve the target application's established architecture and add only the required contracts.
