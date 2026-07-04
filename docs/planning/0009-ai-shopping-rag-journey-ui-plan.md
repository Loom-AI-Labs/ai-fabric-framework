# ADR 0009 - AI Shopping RAG Journey UI plan

- **Status:** Implemented locally; live UI verification pending deploy
- **Date:** 2026-07-05
- **Decision owner:** AI Fabric public demo UI and `chat-capabilities-demo`
- **Applies to backend:** `examples/real-apps/chat-capabilities-demo`
- **Applies to frontend:** `/Users/mahmoudashraf/Downloads/Projects/aifabric`
- **Depends on:** ADR 0008 AI Shopping Experience demo hardening plan

## Context

The AI Shopping Experience demo now has the backend foundation needed to show staged evidence:

- `GET /api/demo/readiness` reports current demo stage, entity counts, vector counts, and vector
  retrieval proof.
- `POST /api/demo/reset` clears demo data, vectors, and indexing queue.
- `POST /api/demo/stages/products`, `/reviews`, `/policies`, `/coupons`, `/tickets`, and `/full`
  seed data in controlled stages.
- The public UI has an `Evidence` tab at
  `/Users/mahmoudashraf/Downloads/Projects/aifabric/src/pages/demos/AIFabricFramework/components/DemoEvidencePanel.tsx`.
- The current UI can reset/seed stages, but it presents this mostly as an operational control panel.

The target product story is stronger:

```text
Ask the same question before RAG evidence exists.
Seed product evidence.
Ask again and see product grounding.
Seed reviews.
Ask again and see customer evidence.
Seed policies.
Ask policy questions and see policy grounding.
Seed coupons/tickets/full data.
Ask workflow questions and see actions plus richer business context.
```

The UI should make that before/after journey obvious without faking intelligence. Scenario buttons
must send natural-language prompts to AI Fabric. They must not shortcut into manual action endpoints
or frontend keyword routing.

## Current Code Evidence

### Backend

| Capability | Current evidence |
| --- | --- |
| Staged data controls | `DemoController` exposes `/api/demo/stages/{stage}`, `/api/demo/reset`, `/api/demo/readiness`, and `/api/demo/health`. |
| Readiness model | `DemoReadinessService` reports counts, stage number, warnings, vector status, and scan-based retrieval proof. |
| Seed idempotency | `DemoStageSeedService` skips unchanged products, policies, and coupons and deduplicates reviews/tickets. |
| RAG vector spaces | `ai-entity-config.yml` registers `product`, `policy`, and `review` as embedding/search entities. |
| Non-vector staged data | Coupons and tickets are seeded app data today; they are not first-class RAG vector spaces unless added later. |
| Natural AI path | `ChatController.query(...)` sends user text, identifiers, position, optional mode, and attachments into AI Fabric orchestration. |

### Frontend

| Capability | Current evidence |
| --- | --- |
| Evidence panel | `DemoEvidencePanel.tsx` shows counts, stage badge, vector spaces, runtime metadata, seed buttons, reset, and last operation JSON. |
| Stage API hook | `useMigration.ts` calls `api.seedDemoStage(...)`, `api.resetDemoData()`, `api.fetchDemoReadiness()`, and `api.fetchDemoHealth()`. |
| API client | `utils/api.ts` has `seedDemoStage`, `resetDemoData`, `fetchDemoReadiness`, and `fetchDemoHealth`. |
| Chat request contract | `utils/api.ts` sends natural query text to `/api/chat/query` with `userId`, `sessionId`, `conversationId`, `position`, optional explicit `mode`, and attachments. |
| Gap | The UI does not yet guide users through repeated prompts, before/after answer comparison, stage-specific lessons, or retrieved-evidence deltas. |

## Decision

Add a first-class **RAG Journey** experience to the AI Shopping demo.

This can replace or sit beside the current `Evidence` tab. The goal is to make staged RAG visible as
the main demo narrative, not a hidden admin function.

The RAG Journey must:

1. Show the current evidence stage.
2. Show which data exists and which vector spaces have retrieval proof.
3. Let users reset to no evidence.
4. Let users seed the next stage.
5. Let users run the same natural-language prompt at each stage.
6. Capture the AI answer, retrieved document count, and vector-space evidence after each run.
7. Compare answers across stages.
8. Keep all AI behavior routed through `/api/chat/query`.

## Stage Model

Use this stage sequence in the UI:

| Stage | Backend state | RAG/vector expectation | Primary prompt | User-visible lesson |
| --- | --- | --- | --- | --- |
| 0 - No Evidence | No products/reviews/policies/coupons/tickets | No vector retrieval proof | `Find high performance laptops for gaming and analyze results.` | The AI should be transparent that indexed catalog evidence is unavailable. |
| 1 - Products | Products seeded | `product` vectors present and retrievable | Same gaming-laptop prompt | The AI can ground recommendations in product specs. |
| 2 - Reviews | Products + reviews seeded | `product` and `review` vectors present | Same gaming-laptop prompt | The AI can add customer sentiment/review evidence. |
| 3 - Policies | Products + reviews + policies seeded | `product`, `review`, and `policy` vectors present | `Can I return a gaming laptop if I opened it?` | The AI can answer with policy evidence. |
| 4 - Coupons | Coupons seeded in app data | Coupons available for commerce flows; not a vector space today | `Find a laptop and any discount I can use.` | The AI can combine product evidence with coupon/action context if action handlers expose it. |
| 5 - Full Commerce | Tickets and full fixtures seeded | Full seeded app scenario, plus product/review/policy RAG | `Add the best gaming laptop to my cart and checkout.` | RAG plus confirmed actions produce an end-to-end commerce workflow. |

Important nuance:

- Today, only `product`, `review`, and `policy` are configured as RAG vector spaces.
- Coupons and tickets are still valuable demo stages, but they prove app workflow readiness more than
  vector-backed RAG unless we add coupon/ticket indexing later.

## Proposed UI

### 1. Replace Admin Feel With A Journey

Rename the current `Evidence` tab to **RAG Journey** or add **RAG Journey** before the existing
developer-style Evidence panel.

Recommended layout:

```text
AI Shopping Experience

[Products] [Cart] [Checkout] [Support] [RAG Journey] [API]

RAG Journey
------------------------------------------------------------
Stage timeline:
[0 No Evidence] -> [1 Products] -> [2 Reviews] -> [3 Policies] -> [4 Coupons] -> [5 Full]

Current stage card:
- stage name
- entity counts
- vector retrieval proof
- indexing queue status
- warnings

Guided test card:
- selected prompt
- Run prompt
- Seed next stage
- Reset to no evidence

Comparison:
- answer at stage 0
- answer at current stage
- retrieved docs count
- vector spaces used
- action result, if any
```

### 2. Stage Cards

Each stage card should include:

- icon;
- status: `locked`, `available`, `current`, `complete`;
- entity counts required for that stage;
- vector proof status where applicable;
- one recommended natural-language prompt;
- buttons:
  - `Run prompt`;
  - `Seed this stage` or `Seed next`;
  - `View evidence`.

Do not show raw JSON by default. Keep raw response details behind a debug/details affordance.

### 3. Before/After Answer Capture

Introduce a UI-only comparison state:

```ts
type RagJourneyRun = {
  id: string;
  stage: string;
  stageNumber: number;
  prompt: string;
  answer: string;
  documents: Array<{
    type?: string;
    id?: string;
    vectorSpace?: string;
    score?: number;
    title?: string;
  }>;
  actionType?: string;
  resultType?: string;
  createdAt: string;
};
```

Store this in component state or browser storage for the current visitor. It is demo comparison state,
not AI memory. AI memory still belongs to `ai-fabric-chat-session`.

Comparison display:

- stage badge;
- answer excerpt;
- retrieved docs count;
- vector spaces used;
- top evidence rows;
- action card summary when an action was produced.

### 4. Prompt Set

Use a small curated prompt set that always goes through `/api/chat/query`:

| Prompt key | Text | Best stages |
| --- | --- | --- |
| `gaming_laptop_analysis` | `Find high performance laptops for gaming and analyze results.` | 0, 1, 2 |
| `return_policy` | `Can I return a gaming laptop if I opened it?` | 3 |
| `discount_search` | `Find a strong laptop and any discount I can use.` | 4 |
| `cart_action` | `Add the best gaming laptop to my cart.` | 5 |
| `checkout_action` | `Checkout my cart and place the order.` | 5 |
| `support_ticket` | `I had a delivery issue. Help me create a support request.` | 5 |

The UI can recommend prompts, but it must not route these prompts to manual endpoints. It should call
the same chat send function used by the chat panel.

### 5. Evidence Delta

For each run, extract and display:

- answer text;
- retrieved docs;
- retrieved doc count;
- vector spaces represented in docs;
- top score if available;
- whether an action was suggested, required confirmation, or executed.

This turns the demo into a visible proof:

```text
Before products: 0 docs, no vector spaces, generic answer.
After products: 3 product docs, product vector space, product-grounded answer.
After reviews: product + review docs, richer comparison.
After policies: policy docs, governed answer.
```

### 6. Position And Mode

The RAG Journey should set position based on the active stage/prompt:

| Prompt | Position | Explicit mode |
| --- | --- | --- |
| gaming laptop analysis | `search` | none; let backend resolve |
| return policy | `support` or `product_detail` | none |
| discount search | `cart` or `catalog` | none |
| add to cart | `cart` | none |
| checkout | `checkout` | none |
| support request | `support` | none |

Only send explicit mode if the user toggles a developer/debug control. Default demo usage should rely
on backend position-to-mode mapping.

## Backend Needs

P0 backend is mostly complete. Remaining optional backend improvements:

1. **Readiness stage descriptions**
   - Add `stageLabel`, `stageDescription`, and `nextStage` to `ReadinessReport`, or keep these in UI
     constants.
   - Recommendation: keep labels in UI unless backend needs to support multiple demo products.

2. **Coupon/ticket RAG indexing**
   - Today coupons/tickets are app data stages, not RAG vector spaces.
   - If the demo should honestly say coupons/tickets are RAG-grounded, add `coupon` and
     `supportTicket` to `ai-entity-config.yml` and annotate relevant fields.
   - Otherwise label them as workflow/action scenario data, not vector evidence.

3. **Stage reset variants**
   - Current reset clears all data.
   - The journey can use full reset plus incremental stage seeding.
   - Later, add `POST /api/demo/reset?toStage=products` only if the UI needs arbitrary stage rewind.

## Frontend Implementation Plan

### P0 - RAG Journey View

1. Add a new component:

```text
/Users/mahmoudashraf/Downloads/Projects/aifabric/src/pages/demos/AIFabricFramework/components/RagJourneyPanel.tsx
```

Responsibilities:

- render stage timeline;
- render stage counts and vector proof;
- expose reset/seed/run-prompt controls;
- capture run summaries;
- show before/after comparison.

2. Add stage constants:

```text
/Users/mahmoudashraf/Downloads/Projects/aifabric/src/pages/demos/AIFabricFramework/constants/ragJourney.ts
```

Include:

- stage definitions;
- required counts;
- expected vector spaces;
- prompt recommendations;
- stage-to-position mapping.

3. Wire into `index.tsx`.

- Add a `rag-journey` tab.
- Pass readiness/health/migration actions into `RagJourneyPanel`.
- Pass a `runChatPrompt` callback that reuses the existing chat send flow.

4. Add answer extraction helper.

Create or reuse helper for:

- answer text;
- documents from all known response shapes;
- action type/result type;
- vector space names;
- scores.

This should reuse existing `useChat.extractDocuments` behavior rather than forking response parsing
in multiple places.

5. UI polish.

- Make the next action obvious: `Reset`, `Seed next stage`, `Run prompt again`.
- Keep controls compact and operational, not marketing-heavy.
- Avoid raw JSON in primary cards.
- Show raw payload only behind `Details`.

### P1 - Better Comparison And Smoke Report

1. Persist journey runs in browser storage per visitor.
2. Add `Clear comparison` control.
3. Add `Copy smoke report`.
4. Include backend health/readiness snapshot in the smoke report.
5. Include frontend build marker and backend `checkedAt`.

### P2 - Optional Coupon/Ticket RAG Expansion

Only do this if we want to claim coupons/tickets are RAG-backed:

1. Add `coupon` to `ai-entity-config.yml`.
2. Add `supportTicket` or `support-ticket` to `ai-entity-config.yml`.
3. Ensure entity fields have `@AISearchable` and `@AIContext`.
4. Extend `DemoReadinessService.VECTOR_SPACES`.
5. Extend readiness tests to require coupon/ticket retrieval proof.
6. Update the journey labels from "workflow data" to "RAG evidence" for those stages.

## Acceptance Criteria

### Product Acceptance

- A user can reset the demo to stage 0 from the UI.
- The UI clearly shows that stage 0 has no indexed evidence.
- A user can run the gaming laptop prompt at stage 0.
- A user can seed products and rerun the same prompt.
- The UI shows more evidence after products are seeded.
- A user can seed reviews and see review evidence appear in later answers.
- A user can seed policies and see policy evidence for policy questions.
- A user can seed coupons/full data and run cart/action scenarios.
- The UI never pretends a frontend shortcut is AI behavior.

### Technical Acceptance

- All stage buttons call backend stage APIs.
- All prompt buttons call `/api/chat/query`.
- No manual action endpoint is used for scenario prompt execution.
- The comparison panel uses actual AI Fabric response payloads.
- Vector proof comes from `/api/demo/readiness`, not hardcoded frontend assumptions.
- `npm run build` passes in `/Users/mahmoudashraf/Downloads/Projects/aifabric`.

### Live Smoke Acceptance

Run this after deployment:

```text
1. Open https://ai-fabric.dev/demos/ai-shopping-experience
2. Open RAG Journey
3. Reset to no evidence
4. Run gaming laptop prompt
5. Seed products
6. Run the same prompt
7. Seed reviews
8. Run the same prompt
9. Seed policies
10. Run return-policy prompt
11. Seed full
12. Run add-to-cart prompt and confirm
13. Copy smoke report
```

Expected proof:

- stage 0 answer has no retrieved docs;
- stage 1 answer has product evidence;
- stage 2 answer has review evidence;
- stage 3 policy prompt has policy evidence;
- full stage can produce confirmed cart/order action flow;
- readiness reports vector retrieval proof for product/review/policy.

## Risks

| Risk | Mitigation |
| --- | --- |
| Users think coupon/ticket stages are vector-backed RAG | Label them as workflow data unless coupon/ticket indexing is added. |
| Scenario buttons become fake shortcuts | Route every prompt through `/api/chat/query`; keep manual tools separate. |
| Reset disrupts other live users | Keep browser-scoped user/session for writes; shared RAG dataset reset is acceptable only for demo/admin use. |
| The journey becomes too much UI | Keep the main flow to one prompt, one seed button, one comparison card. Put debug details behind collapsible controls. |
| Backend deploy version is unclear | Continue using `/api/demo/health`; configure `APP_BUILD_COMMIT` and `APP_BUILD_TIME` in deployment when possible. |

## Recommendation

Implement P0 first.

This gives the demo the intended educational shape without changing AI Fabric framework contracts:

```text
AI Fabric does not magically know your business.
It becomes useful when the Java app indexes trusted domain evidence,
then routes user intent through RAG, policies, and confirmed actions.
```

## Implementation Snapshot

Local implementation completed on 2026-07-05:

- added a `RAG Journey` tab to the AI Shopping Experience route;
- added a typed stage model for no evidence, products, reviews, policies, coupons, and full commerce;
- added stage-aware prompt recommendations that call only `/api/chat/query`;
- updated the chat hook to return the actual AI Fabric message it already appends to the chat window;
- added before/after run capture using real answer text, result type, retrieved documents, vector
  spaces, and action metadata from the AI Fabric response;
- added browser-local comparison history, clear comparison, and copy smoke report controls;
- surfaced real readiness counts and backend scan-based vector retrieval proof in the journey view;
- kept coupon/ticket stages labeled as commerce workflow data, not vector-backed RAG evidence.

Verification completed locally:

- `npm run build` from `/Users/mahmoudashraf/Downloads/Projects/aifabric`.
