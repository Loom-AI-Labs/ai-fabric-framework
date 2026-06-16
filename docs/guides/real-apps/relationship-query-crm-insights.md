# Relationship Query CRM Insights

> One-line: answers natural-language CRM questions by turning them into governed queries over your JPA entities.

## What it builds
A CRM-style service with `CrmAccount`, `CrmContact`, `CrmDeal`, and `CrmSupportTicket` JPA entities. A user posts a plain-English question ("Acme's open deals", "high priority tickets for Globex") and AI Fabric plans and executes a relationship query across those entities, returning matching records as a RAG response. Your entities are exposed to the engine by annotation, not by hand-written queries. Key endpoints (`CrmQueryController` `@RequestMapping("/api/crm")` and `DemoSeedController` `@RequestMapping("/api/demo")`):

- `POST /api/crm/query` — natural-language query → results
- `GET /api/crm/schema` — the entity schema the engine sees
- `POST /api/demo/seed` — seed demo accounts/contacts/deals/tickets

## AI Fabric capability showcased
This is the flagship reference for **natural language → query (NL→query)** over your own JPA model. The LLM produces a structured relationship plan; `ReliableRelationshipQueryService` validates and executes it against the entities marked `@AICapable`, so free-text never becomes raw SQL.

## AI Fabric modules used
- `ai-fabric-relationship-query` — NL→query planning + execution (`ReliableRelationshipQueryService`, `RelationshipSchemaProvider`).
- `ai-fabric-provider-starter` — LLM provider wiring + `@EnableAIInfrastructure`.

## Configuration
```yaml
ai:
  vector-db:
    type: false
  service:
    features:
      enable-embeddings: false
      enable-search: false
      enable-generation: true       # LLM plans the query
  providers:
    llm-provider: crm-stub           # local stub bean
  infrastructure:
    relationship:
      enabled: true
      enable-vector-search: false    # pure relational path, no embeddings
      fallback-to-vector-search: false
      fallback-to-simple-search: false
      schema:
        log-schema: false
```
The relationship engine runs in its deterministic relational mode: vector search and the soft fallbacks are off, so a query either resolves to a structured plan or fails cleanly. `llm-provider: crm-stub` selects the in-app provider so it runs keyless. The shared `smoke` profile (from the `smoke-support` dependency) overrides `llm-provider` to `smoke` and `vector-db.type` to `memory`.

## How it's wired in Java
- `@EnableAIInfrastructure` on `RelationshipQueryCrmInsightsApplication` bootstraps the engine.
- `@AICapable(entityType = "...")` on each entity (`CrmAccount`, `CrmContact`, `CrmDeal`, `CrmSupportTicket`) registers it with the relationship engine and names the type used in queries.
- `ai.fabric.relationship.service.RelationshipSchemaProvider` — `getSchemaDescription(types)` returns the schema the LLM is shown.
- `ai.fabric.relationship.service.ReliableRelationshipQueryService` — `execute(query, entityTypes, ...)` runs the NL query and returns `ai.fabric.dto.RAGResponse`.
- `ai.fabric.provider.AIProvider` — `CrmStubLlmProvider` returns deterministic query plans offline.

```java
// src/main/java/com/ai/fabric/realapps/crm/web/CrmQueryController.java
@RestController
@RequestMapping("/api/crm")
@RequiredArgsConstructor
public class CrmQueryController {

    private final ReliableRelationshipQueryService relationshipQueryService;
    private final RelationshipSchemaProvider schemaProvider;

    @GetMapping("/schema")
    public String schema() {
        return schemaProvider.getSchemaDescription(List.of("account", "contact", "deal", "support-ticket"));
    }

    @PostMapping("/query")
    public RAGResponse query(@Valid @RequestBody QueryRequest request) {
        List<String> entityTypes = request.entityTypes() == null || request.entityTypes().isEmpty()
            ? List.of("account", "contact", "deal", "support-ticket")
            : request.entityTypes();
        return relationshipQueryService.execute(request.query(), entityTypes, null);
    }

    public record QueryRequest(@NotBlank String query, List<String> entityTypes) {}
}
```

## Request flow
1. `POST /api/crm/query` with `{"query": "Acme's open deals"}` hits `CrmQueryController`.
2. The controller calls `ReliableRelationshipQueryService.execute(query, entityTypes, null)`.
3. The service shows the LLM the schema for the `@AICapable` entities and asks it to produce a structured relationship plan (primary entity, relationship paths, filters).
4. The service validates and executes that plan as a query against your JPA entities.
5. Matching records are returned as a `RAGResponse` and serialized to the HTTP response.

## Run it
Offline (no keys):
`mvn -pl relationship-query-crm-insights -f examples/real-apps/pom.xml spring-boot:run -Dspring-boot.run.profiles=smoke`

Then:
```bash
curl -s -X POST http://localhost:8096/api/demo/seed
curl -s -X POST http://localhost:8096/api/crm/query \
  -H 'Content-Type: application/json' \
  -d '{"query":"Acme open deals"}'
curl -s http://localhost:8096/api/crm/schema
```

For real: drop the `smoke` profile and either keep `llm-provider: crm-stub` or point `ai.providers.llm-provider` at a real LLM provider and supply its API key, so the planner uses a real model instead of the canned plans.

## Take it to your own app
- Annotate your existing JPA entities with `@AICapable(entityType = "...")` to expose them for NL querying — no new query layer.
- Call `ReliableRelationshipQueryService.execute(...)` from a thin controller and return the `RAGResponse` directly.
- Constrain results by passing an explicit `entityTypes` list so a query only touches the entities you intend.
- Keep `enable-vector-search` and the fallbacks off for a deterministic relational-only path when you don't want fuzzy semantic matching.
- Use a local `AIProvider` stub to test the NL→plan→execute pipeline deterministically before wiring a real model.
