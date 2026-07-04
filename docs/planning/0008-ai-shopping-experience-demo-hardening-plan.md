# ADR 0008 - AI Shopping Experience demo hardening plan

- **Status:** Implemented locally; live UI verification pending deploy
- **Date:** 2026-07-04
- **Decision owner:** AI Fabric framework and public demo UI
- **Applies to backend:** `examples/real-apps/chat-capabilities-demo`
- **Applies to frontend:** `/Users/mahmoudashraf/Downloads/Projects/aifabric`
- **Depends on:** ADR 0005 real app coverage, ADR 0006 capability priority map, AI Fabric LLM session lessons learned

## Context

The public AI Shopping Experience demo is now backed by the real `chat-capabilities-demo` app and
the public frontend route:

```text
https://ai-fabric.dev/demos/ai-shopping-experience
```

The live capability smoke on 2026-07-04 proved the backend can support the intended story after data
is seeded:

- health returned `UP`;
- products, policies, reviews, coupons, and tickets were seeded through the same mechanics used by
  the current UI migration controls;
- semantic product search for `gaming laptop` returned real product entities;
- runtime vector search returned product evidence when called with the backend contract parameter
  `q`;
- chat/RAG returned a grounded answer with retrieved `product` and `review` evidence;
- smart suggestions generated product-specific next actions;
- `add_to_cart` returned `CONFIRMATION_REQUIRED`, confirmation returned `ACTION_EXECUTED`, and the
  live cart contained the selected SKU.

The same smoke also exposed demo-product gaps. The backend was empty before seeding, the public UI
still needs better staged data/RAG UX, action result rendering should be pluggable rather than
hardcoded per demo, position/mode behavior should be clearer, and deploy verification needs a served
bundle/backend version proof.

## Lessons-Learned Alignment

This plan intentionally follows the constraints in
`docs/Framework-Dev-Guides/LLM-guides/AI_FABRIC_LLM_SESSION_LESSONS_LEARNED.md`:

- RAG is only real if evidence is indexed.
- A UI shortcut is not AI Fabric intelligence.
- `position` describes UI/context, while `mode` controls orchestration policy.
- Backend action results should stay structured; user-facing UI should project the useful fields.
- Public write-action demos need session thinking and a cleanup lifecycle, but this demo also has a
  shared RAG knowledge-base story where full user cloning may be heavier than the immediate value.
- Do not trust only a pushed commit; verify the served JS bundle and backend version/commit.
- Chat history belongs in `ai-fabric-chat-session`; the browser should send the new turn and stable
  identifiers, not prior prompt history.

## Current Code Evidence

### Backend

| Area | Evidence |
| --- | --- |
| Commerce curated pack | `examples/real-apps/chat-capabilities-demo/src/main/resources/application.yml` has `ai.curated.pack: commerce`. |
| Chat session | `application.yml` has `ai.chat.enabled: true`, `auto-create-sessions: true`, and the app depends on `ai-fabric-chat-session`. |
| RAG and vector | The app depends on `ai-fabric-rag` and `ai-fabric-vector-lucene`; `ai-entity-config.yml` registers `product`, `policy`, and `review`. |
| Data sync and indexing | The app depends on `ai-fabric-data-sync` and `ai-fabric-indexing`; indexing worker is enabled in `application.yml`. |
| Natural-language endpoint | `ChatController.query(...)` creates/passes `conversationId`, `userId`, `sessionId`, `position`, `mode`, and attachments into `RAGOrchestrator`. |
| Runtime vector probe | `RuntimeVectorSearchController` expects `q`, not `query`. |
| Admin reset | `MigrationAdminController` exposes `/api/admin/migration/clear` behind `X-ADMIN-API-KEY`. |
| Cart write action | `AddToCartActionHandler` exposes `items` to the model and resolves the active cart from `ActionContext.userId()`. |
| Cart read action | `ViewCartActionHandler` returns structured cart summary and items. |

### Frontend

| Area | Evidence |
| --- | --- |
| Backend target | `src/pages/demos/AIFabricFramework/constants/index.ts` points to `https://ai-fabric-chat-capabilities-demo.46.224.145.148.sslip.io`. |
| Generated demo data | The frontend imports generated products, policies, reviews, coupons, and tickets, then POSTs them through normal app APIs. |
| Shared identity | `DEFAULT_USER_ID = "demo-user"` and `DEFAULT_SESSION_ID = "demo-session"` are global. |
| Chat session contract | `useChat` sends `conversationId` and does not send browser-built `historyMessages`. |
| RAG document extraction gap | `useChat.extractDocuments` reads `result.sanitizedPayload.data.documents` and top-level `ragResponse`, but live responses also include `result.data.documents`. |
| Verification probe fix | The Runtime Vector Probe should use `q=wireless%20headphones`. This has been patched in the frontend repo but requires UI redeploy. |
| Renderer shape | `ActionResultRenderer` has Account Resolver-specific summaries and generic fallbacks, but no flexible demo/domain-specific plugin contract. |

## Decision

Harden the AI Shopping Experience as a staged AI Fabric product demo instead of only making it appear
fully ready after hidden seeding.

The demo should let users see the AI improve as evidence is added:

```text
No demo data
  -> AI can answer only generally and should be transparent that no indexed evidence exists

Products indexed
  -> semantic catalog search and product-grounded RAG work

Products + reviews indexed
  -> AI can analyze product quality and compare based on customer evidence

Products + reviews + policies indexed
  -> AI can answer shopping policy questions with evidence

Products + reviews + policies + coupons indexed
  -> AI can suggest/apply discount opportunities and explain constraints

Full demo data
  -> cart/order/support/ticket scenarios become realistic
```

Keep explicit seeding and clearing mechanics because they are part of the demo story. Make them
safe, obvious, staged, and auditable instead of hidden or accidental.

Do not implement full per-browser dataset cloning as P0. Use browser-scoped commerce user/session
identity first so carts, orders, and conversations do not collide. Treat full per-session data clones
and TTL cleanup as a later hardening phase if concurrent public usage proves it is needed.

## Product Goals

1. Show AI Fabric before and after RAG evidence exists.
2. Make data migration/indexing visible as a first-class demo workflow.
3. Keep natural-language requests going through AI Fabric orchestration, not frontend keyword routing.
4. Make product, cart, policy, coupon, and support pages feed realistic `position` context.
5. Let backend map positions to safe commerce modes unless the user explicitly chooses a mode.
6. Render action results with reusable, demo-injected projections instead of one-off hardcoding.
7. Prove live deployment state with backend health version and served-bundle markers.

## Non-Goals

- Do not change AI Fabric framework contracts unless a framework bug is proven.
- Do not route natural-language scenario buttons directly to manual action endpoints.
- Do not move chat memory into the frontend.
- Do not make the model collect sensitive payment data.
- Do not remove structured backend action result fields to simplify the UI.
- Do not fully clone the RAG knowledge base per browser session in P0.

## Proposed Experience

### 1. Demo Control Center

Add an explicit "Demo Data" or "Evidence" panel to the Shopping Experience.

It should show:

- backend health/version;
- current dataset counts: products, reviews, policies, coupons, tickets;
- vector-space status: known spaces and indexed count/search proof where available;
- current RAG stage;
- last seed/reset operation result;
- safe buttons for staged seed/clear actions.

Recommended stages:

| Stage | Label | Backend state | User-facing proof |
| --- | --- | --- | --- |
| 0 | No evidence | counts are zero; vector search empty | AI gives transparent non-RAG answer. |
| 1 | Catalog only | products indexed | product semantic search and product RAG. |
| 2 | Catalog + reviews | products/reviews indexed | comparison with review evidence. |
| 3 | Policies added | products/reviews/policies indexed | policy Q&A with citations. |
| 4 | Coupons added | coupons available plus product evidence | discount/coupon suggestions. |
| 5 | Full commerce | tickets/orders/support fixtures available | support/cart/order action scenarios. |

The UI should encourage users to run the same prompt at different stages, for example:

```text
Find high performance laptops for gaming and analyze results.
```

Expected behavior:

- Stage 0: answer explains no indexed product evidence is available.
- Stage 1: answer cites product specs.
- Stage 2: answer adds review quality/sentiment evidence.
- Stage 3: answer can include policy constraints.
- Stage 4: answer can include coupon opportunities.

### 2. Seeding And Clearing Mechanics

Keep seed/clear mechanics, but move the public demo toward backend-owned staged operations.

Current behavior:

- frontend imports generated JSON files and POSTs many entities to normal CRUD endpoints;
- admin clear exists behind `X-ADMIN-API-KEY`;
- public UI currently has enough material to clear or duplicate live data if exposed carelessly.

Recommended backend API:

```text
GET  /api/demo/readiness
POST /api/demo/stages/products
POST /api/demo/stages/reviews
POST /api/demo/stages/policies
POST /api/demo/stages/coupons
POST /api/demo/stages/tickets
POST /api/demo/stages/full
POST /api/demo/reset
```

Properties:

```text
APP_DEMO_CONTROLS_ENABLED=true
APP_DEMO_CONTROLS_API_KEY=<server-side only or restricted demo admin key>
APP_DEMO_AUTO_BOOTSTRAP=false
APP_DEMO_MIN_READY_PRODUCTS=100
APP_DEMO_MIN_READY_POLICIES=20
APP_DEMO_MIN_READY_REVIEWS=200
APP_DEMO_MIN_READY_COUPONS=20
```

Rules:

- seed operations should be idempotent;
- products should upsert by SKU, not create duplicates;
- policies/coupons/tickets should upsert by stable natural key where available;
- reviews may be replaced for the demo dataset or deduped by a stable seed id;
- reset should clear demo data and related vectors/indexing queue, but must be visibly labeled and
  protected;
- readiness should report both DB counts and a lightweight retrieval proof.

This keeps the demo truthful while making data state recoverable without SSH/manual scripts.

### 3. Browser-Scoped Commerce Identity

The user pushed back on full per-session cloning because this is primarily a RAG/AI demo. That is
reasonable for P0.

P0 compromise:

- generate a browser-scoped `shoppingDemoUserId` and `shoppingDemoSessionId`;
- store them in browser storage;
- send them through all chat/cart/order/conversation calls;
- keep shared catalog/RAG evidence global;
- keep cart/order/chat writes isolated per browser user.

Example:

```text
shopping-demo-user-<uuid>
shopping-demo-session-<uuid>
```

This avoids visitor collisions for real write actions while preserving the shared RAG dataset.

P2 hardening:

- add TTL cleanup for carts/orders/chat sessions with user id prefix `shopping-demo-user-`;
- keep seeded fixture users protected from cleanup;
- if needed, add per-session scenario fixtures later.

### 4. Generic AI Component With Demo-Specific Projections

The user-facing chat component should stay generic, but demos need custom projections.

Proposed design:

```ts
type DemoActionProjection = {
  canRender(input: ActionProjectionInput): boolean;
  render(input: ActionProjectionInput): ReactNode;
};

type DemoDocumentProjection = {
  canRender(document: Document): boolean;
  render(document: Document): ReactNode;
};

type DemoChatAdapter = {
  actionProjections?: DemoActionProjection[];
  documentProjections?: DemoDocumentProjection[];
  scenarioPrompts?: ScenarioPrompt[];
  positionMap?: Record<string, string>;
};
```

The shared chat panel would:

1. render framework-standard cards by default;
2. let the active demo inject projection plugins;
3. pass action result data, action name, result type, and message metadata to projections;
4. keep raw JSON only in debug/API panels.

Shopping projections should cover:

- `add_to_cart`: added item count, cart total, currency, pinned active cart;
- `view_cart`: item rows, subtotal, discount, total;
- `apply_coupon`: coupon code, discount, new total;
- `checkout`: order number, total, payment status, shipment/tracking if present;
- `create_support_ticket`: ticket id/status/reason;
- product search arrays: product cards with price, stock, SKU, rating/review count where available.

This directly applies the lessons-learned rule: backend results stay structured, UI projects the best
fields for the workflow.

### 5. Position To Commerce Mode Mapping

The frontend should show real pages/positions:

- Product Catalog page -> `position=catalog`
- Product Search page -> `position=search`
- Product Detail page -> `position=product_detail`
- Cart page -> `position=cart`
- Checkout page -> `position=checkout`
- Orders page -> `position=orders`
- Support page -> `position=support`

Backend should map positions to commerce modes when the UI does not explicitly choose a mode:

| Position | Default backend mode | Rationale |
| --- | --- | --- |
| `landing` | `navigator` | broad discovery |
| `catalog` | `navigator` | browse and compare |
| `search` | `navigator_deep` | stronger retrieval and analysis |
| `product_detail` | `navigator_deep` | product-specific evidence |
| `cart` | `cart_assistant` | cart context and cart read/write actions |
| `checkout` | `executor` or guarded commerce write mode | confirmation-heavy workflow |
| `orders` | `cart_assistant` or support/order mode | order read/actions |
| `support` | support mode if available, otherwise navigator | ticket/support actions |

Important rule:

- If the user explicitly chooses a mode in the UI, honor it.
- If the UI only sends `position`, backend applies a safe default mode for that position.
- The UI should explain the active position/mode in a compact developer/debug panel, not hidden
  marketing copy.

This preserves the user's guidance: do not force mode selection unless the user explicitly chooses it.

### 6. Product And Cart Pages

To make positions real, add or improve these Shopping Experience views:

1. **Products**
   - catalog grid, semantic search, product detail route;
   - product cards can attach product context to chat;
   - position: `catalog` or `search`.

2. **Product Detail**
   - details, reviews, related policies/coupons if available;
   - chat asks product-specific questions;
   - position: `product_detail`.

3. **Cart**
   - active cart for the browser-scoped user;
   - add/remove items, apply coupon, checkout;
   - chat sees cart position and can run cart actions through AI Fabric;
   - position: `cart`.

4. **Checkout**
   - demo-safe shipping/payment method inputs;
   - confirmation-required action path remains through AI Fabric for AI-triggered checkout;
   - position: `checkout`.

5. **Orders/Support**
   - recent orders and support ticket fixtures once full data is loaded;
   - position: `orders` or `support`.

### 7. Deployment Version Proof

Backend:

Add a public, non-sensitive health/info endpoint or actuator info enrichment:

```text
GET /api/demo/health
```

Return:

```json
{
  "app": "chat-capabilities-demo",
  "version": "1.0.0-SNAPSHOT",
  "aiFabricVersion": "0.3.2",
  "commit": "...",
  "buildTime": "...",
  "ragProviderPresent": true,
  "chatSessionEnabled": true,
  "vectorSpaces": ["product", "policy", "review"]
}
```

Frontend:

- keep a hidden build marker in the Shopping route bundle;
- verification should fetch the served JS bundle and check for that marker after deployment;
- demo control center should show backend commit/build time.

This prevents repeating the issue where `main` was pushed but `ai-fabric.dev` still served an older
JS bundle.

## Implementation Plan

### P0 - Make The Public Demo Truthful And Recoverable

Backend:

1. Add `DemoReadinessService`.
   - Count products, reviews, policies, coupons, tickets.
   - Check vector spaces.
   - Run one lightweight retrieval proof per loaded vector space where possible.
   - Return `stage`, `ready`, `warnings`, and `nextRecommendedStep`.

2. Add backend-owned staged seed endpoints.
   - Implement idempotent stage operations.
   - Prefer stable upserts over blind creates.
   - Keep `/api/admin/migration/clear` for admin reset, but expose public demo reset only if controls
     are explicitly enabled and protected.

3. Add tests.
   - readiness returns stage 0 when empty;
   - product stage moves to stage 1 and does not duplicate on second run;
   - full stage populates expected counts;
   - reset clears DB data, vectors, and indexing queue when requested;
   - vector probe endpoint contract uses `q`.

Frontend:

1. Add Demo Data/Evidence panel.
   - Show stage and counts.
   - Let users run staged seed/reset.
   - Provide "try this prompt now" examples per stage.

2. Fix RAG document extraction.
   - Include `result.data.documents`.
   - Include `result.data.ragResponse.documents`.
   - Keep existing sanitized/smart-suggestion paths.

3. Add browser-scoped commerce identity.
   - Replace shared `demo-user`/`demo-session` usage in Shopping page with generated/stored IDs.
   - Pass the same IDs to cart APIs, conversations, suggestions, and chat query.

4. Build and smoke.
   - `npm run build`.
   - Live backend smoke for empty/stage/full.
   - Browser/API smoke for chat RAG after each stage.

### P1 - Make The Demo Feel Like A Real Commerce App

Backend:

1. Add position-to-mode mapping in `ChatController` or a small `CommerceModeResolver`.
   - Only apply when `request.mode` is blank.
   - Unit-test each position mapping.

2. Make action result messages self-explaining.
   - `add_to_cart` should include item count/cart total.
   - `view_cart` already carries strong data; review if message needs total.
   - `checkout` should explain order/payment/shipment outcome.
   - Support/refund/order actions should explain policy decisions in the backend result when applicable.

Frontend:

1. Introduce generic projection injection for chat/action/document cards.
   - Keep shared AI component generic.
   - Inject Shopping-specific projections from the Shopping demo route.

2. Add/improve product/cart/checkout/orders/support pages.
   - Wire page to `position`.
   - Do not force mode selection except when user explicitly chooses deep/executor/cart assistant.

3. Convert scenario chips/quick tools to natural language.
   - Example: "Show my active cart" instead of `Action: view_cart`.
   - Keep manual action developer tools separate and clearly labeled if retained.

4. Add tests/smokes.
   - Component-level test for action projection selection.
   - API smoke for add-to-cart confirmation then cart view.
   - API smoke for staged RAG answer showing retrieved docs.

### P2 - Security, Cleanup, And Release Hardening

Backend:

1. Narrow demo `EntityAccessPolicy`.
   - Replace unconditional `true` with explicit demo resource/action allowances.
   - Keep fail-closed behavior for unknown resources.

2. Add cleanup job for browser-scoped demo writes.
   - Delete old carts/orders/chat sessions for user ids starting with `shopping-demo-user-`.
   - Make TTL/cron configurable.

3. Add deployment health/version endpoint.
   - Include commit/build time if env vars are present.
   - Include component flags for RAG/chat/vector provider presence.

4. Add regression tests.
   - policy denies unknown resources;
   - cleanup deletes only old demo users/carts/orders/conversations;
   - health endpoint exposes non-sensitive build metadata.

Frontend:

1. Show backend version and frontend build marker in the developer/verification panel.
2. Add a "copy smoke report" action for live demo verification.
3. Keep raw JSON behind debug/details affordances, not primary chat cards.

## Suggested Test Matrix

### Backend Unit/Controller Tests

- `DemoReadinessControllerTest`
- `DemoStageSeedControllerTest`
- `DemoStageSeedServiceTest`
- `CommerceModeResolverTest`
- `ShoppingActionResultContractTest`
- `DemoAccessAndComplianceConfigurationTest`
- `DemoCleanupServiceTest` (P2)

### Frontend Tests Or Build Checks

- `npm run build`
- RAG document extraction helper test, if the project has a test runner available.
- Manual browser/API smoke until frontend test infra is added.

### Live Smoke

Use direct API checks before browser checks:

```text
GET  /actuator/health
GET  /api/demo/readiness
POST /api/demo/reset
GET  /api/demo/readiness
POST /api/chat/query                 # no data: transparent non-RAG answer
POST /api/demo/stages/products
POST /api/chat/query                 # product-grounded answer
POST /api/demo/stages/reviews
POST /api/chat/query                 # product + review evidence
POST /api/demo/stages/full
POST /api/chat/query                 # full RAG evidence
POST /api/chat/query                 # add-to-cart confirmation
POST /api/chat/query                 # confirmation executes action
GET  /api/carts/active?userId=...
```

Then verify:

- CORS from `https://ai-fabric.dev`;
- served JS bundle contains the Shopping build marker;
- frontend route shows the same backend version/commit as live API.

## Risks And Guardrails

| Risk | Guardrail |
| --- | --- |
| Public users clear data during a demo | Gate clear/reset behind explicit demo controls and auth; show warnings and current stage. |
| Seeding duplicates data | Use backend idempotent upserts and tests. |
| UI looks smart by shortcutting orchestration | Scenario chips send natural text to `/api/chat/query`; manual controls are visibly separate. |
| RAG claims are misleading | Readiness panel must show counts and vector/retrieval proof. |
| Shared carts/conversations collide | Browser-scoped user/session IDs for writes and chat. |
| Full per-user cloning is too heavy | Keep shared RAG dataset global in P0; isolate writes only. |
| Action cards become demo-specific spaghetti | Add projection plugin interface; keep shared component generic. |
| Mode behavior becomes hidden | Backend maps position to mode only when UI did not specify mode; developer panel shows effective mode if returned/available. |
| Deployed code mismatch | Verify served JS marker and backend health commit/build time. |

## Implementation Snapshot

Local implementation completed on 2026-07-04:

- backend-owned staged demo APIs were added under `/api/demo`;
- readiness/health now report data counts, vector-space status, and build/runtime flags;
- readiness now includes a lightweight scan-based retrieval proof per populated vector space;
- the chat app maps UI `position` to commerce mode when the UI does not explicitly choose one;
- cart action results now include concise, user-facing totals;
- demo access control is explicit rather than blanket-allow;
- browser-scoped demo writes now have a scheduled, configurable TTL cleanup path for carts, orders,
  support tickets, and AI Fabric chat sessions;
- frontend seeding moved from bundled JSON to backend-owned stage operations;
- frontend chat uses browser-scoped user/session IDs and still sends only the new turn;
- Shopping UI now has Evidence, Cart, and Support surfaces;
- shared chat rendering accepts demo-specific action projections, with Shopping projections for cart,
  checkout/order, coupon, and support ticket results;
- quick tools send natural-language requests instead of action syntax.

Verification completed locally:

- `mvn test -DskipITs` from `examples/real-apps/chat-capabilities-demo`;
- focused backend tests for demo-controls auth, health metadata, access policy, readiness retrieval
  proof, staged seeding idempotency, and demo-user cleanup;
- `npm run build` from `/Users/mahmoudashraf/Downloads/Projects/aifabric`;
- `git diff --check` in both repos.

Live browser/API verification is intentionally deferred until the updated frontend bundle is deployed.

## Recommendation

Proceed with P0 first.

The highest-value change is not to hide empty data. It is to make empty data, partial RAG, and full
RAG visible as a staged AI Fabric story. That makes the demo more educational and more honest:

```text
AI Fabric does not magically know your business.
It becomes useful when your Java app indexes trusted domain evidence,
then routes user intent through governed RAG and confirmed actions.
```

After P0, implement the generic projection mechanism and product/cart pages so the demo feels like a
real commerce application rather than a chat-only showcase.
