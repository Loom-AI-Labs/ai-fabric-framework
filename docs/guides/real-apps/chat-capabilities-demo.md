# Chat Capabilities Demo

> One-line: a conversational commerce assistant with persistent chat-session memory, RAG orchestration, and governed tool-calling that pauses for human confirmation.

## What it builds
A commerce backend (products, carts, orders, returns, shipments, support tickets, reviews, policies) fronted by a single chat endpoint that turns natural-language requests into retrieval answers or governed actions. The AI feature is a conversation-aware orchestrator: it remembers the thread, retrieves relevant catalog/policy context, and calls `@AIAction` handlers (add to cart, checkout, cancel order, create ticket) with confirmation gates. Key HTTP entry points: `POST /api/chat/query`, `POST /api/chat/suggestions`, and conversation CRUD under `/api/chat/conversations` (`GET /api/chat/conversations`, `GET|DELETE /api/chat/conversations/{conversationId}`). The plain commerce REST surface lives at `/api/products`, `/api/carts`, `/api/orders`, `/api/tickets`, and friends.

## AI Fabric capability showcased
This is the reference example for **conversational orchestration with chat-session memory plus human-in-the-loop confirmation interceptors** — RAG retrieval and governed actions combined behind a stateful chat thread, with app-level policy that intercepts a pending confirmation before it executes.

## AI Fabric modules used
- `ai-fabric-starter` — autoconfig, core services, `@EnableAIInfrastructure`.
- `ai-fabric-chat-session` — persistent conversation/turn storage + confirmation interception.
- `ai-fabric-rag` — RAG orchestration pipeline.
- `ai-fabric-indexing` — async embedding/indexing of entities.
- `ai-fabric-governance` — access policy + compliance hooks for actions.
- `ai-fabric-vector-lucene` — local Lucene vector store.
- `ai-fabric-provider-openai` — OpenAI LLM + embeddings provider.
- `ai-fabric-curated-commerce` — packaged commerce action/entity pack.

All declared under `io.github.loom-ai-labs`, version `0.2.1`.

## Configuration
From `src/main/resources/application.yml`:

```yaml
ai:
  curated:
    pack: commerce            # load the curated commerce action/entity pack
  chat:
    enabled: true             # turn on chat-session storage
    window-size: 12           # turns of history fed back into context
    max-context-chars: 8000   # context budget per turn
    auto-create-sessions: true
  providers:
    llm-provider: openai
    embedding-provider: openai
  vector-db:
    type: lucene              # local on-disk vector index
  indexing:
    enabled: true
    async-worker: { enabled: true, fixed-delay: PT0.5S, batch-size: 25 }
  service:
    features:
      enable-generation: ${OPENAI_ENABLED:false}
      enable-embeddings: ${OPENAI_ENABLED:false}
      enable-search: true
      enable-rag: true
```

The offline **smoke** profile is contributed by the `smoke-support` module (`application-smoke.yml`): it overrides `llm-provider`/`embedding-provider` to `smoke` and `vector-db.type` to `memory`, so the app boots with no API keys.

## How it's wired in Java
- `@EnableAIInfrastructure` on `ChatCapabilitiesDemoApplication` bootstraps the framework beans.
- `ai.fabric.intent.orchestration.RAGOrchestrator` — the single entry point that classifies intent, retrieves context, and dispatches actions.
- `ai.fabric.chat.service.ChatSessionService` + `ai.fabric.chat.domain.{ChatSession,ChatTurn}` — persist and replay conversation history.
- `ai.fabric.core.AICoreService` — direct generation/search calls (used by `/suggestions`).
- `ai.fabric.intent.action.AIActionRegistry` — discovered `@AIAction` handlers and their metadata.
- `@AIAction` / `@ActionExecute` / `@ActionConfirmation` / `@Param` on handlers under `*/action/` — the governed tool definitions.
- `@AIConfirmationInterceptors` + `@OnPendingActionConfirmation` — app policy that intercepts a pending action's confirmation.
- `@AICapable` / `@AISearchable` / `@AIContext` on entities (e.g. `Product`) — declares what gets embedded and searched.

The interceptor below runs when a user confirms cancelling an order: instead of executing immediately, it offers a retention discount first.

```java
// src/main/java/com/ai/fabric/realapps/chat/orders/resolver/CancellationRetentionOfferResolver.java
@AIConfirmationInterceptors
public class CancellationRetentionOfferResolver {

    private static final String INTERCEPT_ACTION = "cancel_purchase_order";
    private static final String OFFER_ACTION = OfferOrderDiscountActionHandler.ACTION_NAME;
    private static final String PARAM_RETENTION_OFFERED = "_retentionOfferOffered";

    @OnPendingActionConfirmation(
        pendingActions = {INTERCEPT_ACTION},
        confirmation = IntentType.CONFIRMATION_POSITIVE,
        onceParam = PARAM_RETENTION_OFFERED)
    public InterceptionDecision offerDiscount(ConfirmationInterceptionContext ctx) {
        return ctx.promptAction(OFFER_ACTION, buildOfferParams(ctx.pending()));
    }
}
```

## Request flow
1. Client posts `{ query, conversationId?, userId? }` to `POST /api/chat/query`.
2. `ChatController` builds an `OrchestrationContext` (reusing or minting a `conversationId`) and calls `RAGOrchestrator.orchestrate(query, context)`.
3. The orchestrator replays history via `ChatSessionService`, classifies intent, and either retrieves catalog/policy context (RAG) or selects an `@AIAction`.
4. If the action `requiresConfirmation`, an `@OnPendingActionConfirmation` interceptor can re-route it (e.g. offer a discount) before `@ActionExecute` runs.
5. The `OrchestrationResult` (answer, pending confirmation, or action result) is returned and the turn is persisted.

## Run it
Offline (no keys):
`mvn -pl chat-capabilities-demo -f examples/real-apps/pom.xml spring-boot:run -Dspring-boot.run.profiles=smoke`

Sample request (default port 8096):
```bash
curl -s localhost:8096/api/chat/query \
  -H 'Content-Type: application/json' \
  -d '{"query":"show me laptops under $1000","userId":"u-1"}'
```

For real: set `OPENAI_ENABLED=true` and `OPENAI_API_KEY=...` (optionally `OPENAI_MODEL`, `OPENAI_EMBEDDING_DIMENSIONS`) and drop the `smoke` profile so OpenAI + Lucene are used for live generation, embeddings, and search.

## Take it to your own app
- Treat `RAGOrchestrator.orchestrate(query, context)` as your one chat entry point — pass a stable `conversationId` and let `ai-fabric-chat-session` handle memory.
- Tune conversation budget with `ai.chat.window-size` and `ai.chat.max-context-chars` instead of hand-rolling history truncation.
- Define tools as `@AIAction` handlers with `@ActionExecute`/`@Param`; set `requiresConfirmation = true` for anything destructive.
- Insert business policy between confirmation and execution with `@AIConfirmationInterceptors` + `@OnPendingActionConfirmation` — no orchestrator forking required.
- Annotate domain entities with `@AICapable`/`@AISearchable`/`@AIContext` and let async indexing keep the vector store current.
