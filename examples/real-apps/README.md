# AI Fabric Real Apps

These standalone Spring Boot applications are public examples used to validate AI Fabric Framework capabilities in realistic product shapes.

The apps are intentionally scenario-focused:

- `smart-faq-assistant`: offline FAQ search using a deterministic local embedding provider and optional RAG.
- `migration-enabled-product-catalog`: migration/backfill indexing with local H2, hash embeddings, and Lucene.
- `privacy-first-customer-facing-support`: PII detection and redaction workflow.
- `relationship-query-crm-insights`: natural language relationship query with an offline stub LLM.
- `behavior-churn-signals`: behavior analytics and churn/sentiment insight flow with an offline stub LLM.
- `chat-capabilities-demo`: chat-session storage and conversation-aware orchestration.
- `it-support-action-bot`: provider-only action orchestration path.
- `sub-management-hub-simple`: config-driven indexing setup using local deterministic embeddings by default.
- `sub-management-hub`: annotation-assisted indexing setup using local deterministic embeddings by default.
- `ecommerce-store`: domain API fixture for connector/runtime examples.
- `cloud-qdrant-openai-vector-search`: cloud vector search shape using OpenAI, Postgres, and Qdrant.

## Build

Install the framework artifacts from the local checkout first:

```bash
mvn -B -V --no-transfer-progress -f ai-infrastructure-module/pom.xml -DskipTests install
```

Then compile all real apps:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -DskipTests compile
```

Each app declares `ai-fabric.version=0.2.0`, so it resolves the framework artifacts from the local Maven install produced by this repository.

## Runtime Notes

Most apps can boot without external API keys because they use local H2 storage and either local/stub AI providers, deterministic local embeddings, or disabled cloud providers by default.

`cloud-qdrant-openai-vector-search` is compile-verified by default and requires Postgres, Qdrant, and OpenAI configuration before runtime smoke testing.
