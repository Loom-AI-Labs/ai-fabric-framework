# IT Support Action Bot

> One-line: a provider-only bot that turns a natural-language IT request into a single governed `@AIAction` (create/assign/close/escalate a ticket) with authorization and confirmation — no vector DB, no RAG.

## What it builds
An IT helpdesk backend where users describe a problem in plain language and the bot picks and runs the right ticket action. There is deliberately **no** embeddings/search/RAG layer — this isolates the framework's action/tool model. Key HTTP entry points: `POST /api/bot/query` (NL → action via the orchestrator), `GET /api/bot/actions` (introspect registered actions), `GET /api/tickets` and `GET /api/tickets/{ticketNumber}` (plain ticket reads), and `POST /api/demo/seed` (seed sample data).

## AI Fabric capability showcased
This is the cleanest reference for the **governed action / tool-calling model**: intent → handler selection → `@ActionAllowed` authorization → optional `@ActionConfirmation` → `@ActionExecute`, driven by `RAGOrchestrator` running in action-only mode (RAG infrastructure disabled).

## AI Fabric modules used
- `ai-fabric-provider-starter` — minimal AI infrastructure autoconfig (intent/action/orchestration) without the full RAG/indexing stack.
- `ai-fabric-provider-openai` — OpenAI LLM provider for intent extraction.

All declared under `io.github.loom-ai-labs`, version `0.2.1`.

## Configuration
From `src/main/resources/application.yml`:

```yaml
ai:
  enabled: true
  vector-db:
    type: false               # no vector store
  service:
    features:
      enable-embeddings: false # no embeddings
      enable-search: false     # no search
  infrastructure:
    rag:
      enabled: false           # action-only: RAG pipeline off
      advanced: { enabled: false }
  providers:
    llm-provider: openai
    openai:
      enabled: ${OPENAI_ENABLED:false}
      api-key: ${OPENAI_API_KEY:}
      model: ${OPENAI_MODEL:gpt-4o-mini}
```

The offline **smoke** profile (from the `smoke-support` module) switches `llm-provider` to the in-process `smoke` stub, so the bot boots and resolves actions with no API key.

## How it's wired in Java
- `@EnableAIInfrastructure` on `ItSupportActionBotApplication` bootstraps the (action-only) framework beans.
- `ai.fabric.intent.orchestration.RAGOrchestrator` — called with `OrchestrationContext`; returns `OrchestrationResult`. Here it acts as the action dispatcher.
- `ai.fabric.intent.action.AIActionRegistry` — exposes the discovered actions and metadata via `getAllMetadata()`.
- `@AIAction` / `@ActionAllowed` / `@ActionConfirmation` / `@ActionExecute` / `@Param` — define each ticket tool; handlers return `ai.fabric.intent.action.ActionResult` (built with `ActionResultContracts`).
- `ai.fabric.intent.action.{ActionAccessMode, ActionContext}` — declare WRITE_ONLY/READ access and carry the authenticated `userId` into the handler.

The bot's controller resolves both beans defensively and forwards the query:

```java
// src/main/java/com/ai/fabric/realapps/itsupport/controller/BotController.java
@PostMapping("/query")
public ResponseEntity<OrchestrationResult> query(@Valid @RequestBody QueryRequest payload) {
    RAGOrchestrator orchestrator = orchestratorProvider.getIfAvailable();
    if (orchestrator == null) {
        return ResponseEntity.ok(OrchestrationResult.builder()
            .success(false).message("Orchestrator not configured").build());
    }
    OrchestrationContext context = OrchestrationContext.builder()
        .conversationId("chat-" + sessionId)
        .userId(payload.getUserId())
        .sessionId(sessionId)
        .build();
    return ResponseEntity.ok(orchestrator.orchestrate(payload.getQuery(), context));
}
```

And a real action — note authorization, confirmation, and typed params:

```java
// src/main/java/com/ai/fabric/realapps/itsupport/action/CreateTicketActionHandler.java
@AIAction(name = "create_ticket", description = "Create a new IT support ticket",
          category = "it-support", accessMode = ActionAccessMode.WRITE_ONLY,
          requiresConfirmation = false)
public class CreateTicketActionHandler {
    @ActionAllowed
    public boolean allowed(ActionContext context) {
        String userId = context != null ? context.userId() : null;
        return userId != null && !userId.isBlank();
    }
    @ActionExecute
    public ActionResult execute(
        @Param(value = "title", required = true, description = "Short title for the issue") String title,
        @Param(value = "priority", description = "LOW|MEDIUM|HIGH|URGENT (optional)") String priority,
        ActionContext context) {
        Ticket created = ticketService.createTicket(nextTicketNumber(), title, null, parsePriority(priority));
        return ActionResult.builder().success(true).message("Ticket created")
            .data(ActionResultContracts.object(Map.of("ticketNumber", created.getTicketNumber()))).build();
    }
}
```

## Request flow
1. Client posts `{ query, userId }` to `POST /api/bot/query`.
2. `BotController` builds an `OrchestrationContext` and calls `RAGOrchestrator.orchestrate(...)`.
3. The orchestrator (LLM intent extraction) matches one registered `@AIAction` and binds `@Param` values from the request.
4. `@ActionAllowed` runs against the `ActionContext` (rejected with no `userId`); if `requiresConfirmation`, `@ActionConfirmation` returns a prompt instead of executing.
5. `@ActionExecute` performs the ticket mutation and returns an `ActionResult`, surfaced inside the `OrchestrationResult`.

## Run it
Offline (no keys):
`mvn -pl it-support-action-bot -f examples/real-apps/pom.xml spring-boot:run -Dspring-boot.run.profiles=smoke`

Sample request (default port 8082):
```bash
curl -s localhost:8082/api/demo/seed -X POST
curl -s localhost:8082/api/bot/query \
  -H 'Content-Type: application/json' \
  -d '{"query":"create a ticket: my laptop wont boot","userId":"agent-1"}'
```

For real: set `OPENAI_ENABLED=true` and `OPENAI_API_KEY=...` and drop the `smoke` profile so OpenAI handles intent extraction. No vector DB or embedding keys are needed by design.

## Take it to your own app
- For pure tool-calling (no retrieval), depend on `ai-fabric-provider-starter` and set `ai.infrastructure.rag.enabled: false` — you keep actions, skip the vector stack.
- Model each operation as one `@AIAction` with typed `@Param`s; the framework binds NL arguments to them.
- Enforce per-call authorization in `@ActionAllowed(ActionContext)` rather than in the controller — it runs for every invocation route.
- Gate destructive operations with `requiresConfirmation = true` + an `@ActionConfirmation` method that returns the user-facing prompt.
- Return `ActionResult.builder()...data(ActionResultContracts.object(...))` so structured payloads flow back through the orchestrator consistently.
