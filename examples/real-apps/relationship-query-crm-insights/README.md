# Relationship Query CRM Insights

## Scenario

This app demonstrates natural language to JPQL/relationship traversal over a realistic CRM schema.

It is fully offline by default: an in-app deterministic LLM provider returns repeatable planner output,
so the relationship-query module can be validated without external keys.

## AI Fabric Capabilities Proved

- Relationship Query discovers entity schema through the JPA metamodel and `@AICapable`.
- A single request can produce a structured plan, JPQL execution, and ID/result evidence.
- Pure relational traversal works without vector DBs or embeddings.
- Structured planner output can be parsed and bounded.
- Revenue-copilot flow can enforce entity allowlists and validate follow-up task targets.
- Summaries can cite deal/ticket/account evidence ids used by the workflow.

## Framework Surfaces

- `ai-fabric-relationship-query`
- `ai-fabric-provider-starter`
- deterministic local LLM provider
- JPA metamodel discovery
- structured output repair/parsing path

## Runtime Posture

Default runtime:

- H2 database
- deterministic offline LLM
- no embeddings
- no vector DB
- no external provider keys

Default port: `8096`.

## Run

From the repository root:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl relationship-query-crm-insights -am package
java -jar examples/real-apps/relationship-query-crm-insights/target/relationship-query-crm-insights-1.0.0-SNAPSHOT.jar
```

## Validate

Focused tests:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl relationship-query-crm-insights -am test
```

Use `requests/demo.http` to run the CRM scenario.

## Demo Flow

1. Seed CRM accounts, contacts, deals, and support tickets.
2. Inspect the discovered CRM schema.
3. Ask a natural-language CRM question.
4. Verify the structured query plan and result ids.
5. Run the revenue-copilot scenario for support-risk or follow-up evidence.

## Key Endpoints

- `POST /api/demo/seed`
- `GET /api/crm/schema`
- `POST /api/crm/query`

## What This App Does Not Cover

- RAG over account documents. Use `smart-faq-assistant` or extend this app with account notes.
- Live LLM RealAPI relationship-query behavior. Use the relationship-query integration tests.
- Tenant vector storage. Use `tenant-knowledge-portal`.
