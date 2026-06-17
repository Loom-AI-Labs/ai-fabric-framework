# Chat Capabilities Demo — UI Migration Guide (V5 auth-aware request contract)

This guide documents the **request payload** and **position list** for the Real App:
- `Real_Apps/chat-capabilities-demo`

It is intended for frontend/UI clients calling the runtime chat surface.

Preferred verified-caller surface:
- `POST /api/chat/me/query`

---

## 1) Endpoint

### Preferred: `POST /api/chat/me/query`

Use `/api/chat/me/query` when the caller already conveys verified auth context through runtime auth headers or bearer tokens.

**Request body (JSON)**

```json
{
  "query": "string (required)",
  "conversationId": "string (recommended; stable per chat thread)",
  "position": "string (recommended; drives orchestration mode via routing)",
  "mode": "string (optional; only used if position is missing or not routed)",
  "attachments": [
    {
      "id": "string (optional; stable entity id when available)",
      "vectorSpace": "string (optional; e.g. \"product\")",
      "contentText": "string (optional; bounded, best-effort grounding text)",
      "metadata": { "anyScalar": "..." },
      "source": "string (optional)",
      "url": "string (optional)",
      "imageUrl": "string (optional)"
    }
  ]
}
```

Notes:
- do not send request `userId` in verified-caller mode
- do not send request `sessionId` in verified-caller mode
- rely on response `authContext` as the source of truth for the effective actor
- When enabled by orchestration mode/policy, the backend may add a `result.metadata.readProbe` object (debug visibility) when a READ action returns an empty successful payload and the orchestrator falls back to RAG.

---

## 2) Positions to use (Chat Capabilities Demo)

This app uses the curated pack:
- `ai.curated.pack: commerce`

That pack defines **position routing**:

| UI position | Send `"position"` | Routed mode | Intended behavior |
|---|---|---|---|
| Landing / catalog browsing | `"landing"` | `navigator` | RAG-first discovery (deterministic retrieval + generation) |
| Cart / checkout flows | `"cart"` | `cart_assistant` | Action-oriented flows (purchase order, add-to-cart, etc.) |

Notes:
- **Position routing wins over `mode`.** If you send both, the routed mode for the position is applied.
- If you send a `position` that is not configured, the request falls back to normal mode resolution (unless the app enables strict routing).
 - To force a mode (e.g. `navigator_deep` / `executor`) while still sending a UI `position`,
   use a position that is **not routed** in the active pack (or omit `position`) so `mode` is honored.

---

## 3) When to send attachments

Send `attachments` when the user is interacting with **specific UI objects** (e.g., selected product cards) and the user’s message is ambiguous:
- “Add it to my cart”
- “Buy this”
- “Compare these”

This enables deterministic resolution without relying on the LLM to “guess” the target.

Recommended UI pattern:
- Send `attachments` as the list of pinned/selected cards/items the assistant should consider for this turn.
- If the user changes selection, send a new `attachments` list reflecting the updated pinned set.
- Set `vectorSpace` to the entity type configured in your `ai-entity-config.yml` (for the demo catalog: `product`) when known.
- Include `contentText` when available so the LLM can answer from pinned context without extra retrieval.

---

## 4) Conversation persistence (UI expectations)

If you want the backend to store and later return chat history:
- Send a stable `conversationId` for the thread (example: `chat-<userId>` or a generated UUID stored client-side).
- use `/api/chat/me/conversations`
- do not send `ownerId`
- do not treat request `userId` as authoritative

---

## 5) Examples (copy/paste)

### A) Landing (RAG) query

```json
{
  "conversationId": "chat-user-1",
  "position": "landing",
  "query": "I'm looking for wireless headphones under $250. Recommend options from the catalog."
}
```

### A2) Deep navigator query (explicit mode)

```json
{
  "conversationId": "chat-user-1",
  "mode": "navigator_deep",
  "query": "Go deep: show alternatives, common complaints, and any relevant policies for returns."
}
```

### B) Cart (action) request

```json
{
  "conversationId": "chat-user-1",
  "position": "cart",
  "query": "Create a purchase order for sku SKU-0001 quantity 2, ship to 1 Market St, SF, email alice@example.com."
}
```

### B2) Executor query (explicit mode)

```json
{
  "conversationId": "chat-user-1",
  "mode": "executor",
  "query": "What is the refund policy?"
}
```

### C) Cart (action) using attachments (deterministic target)

```json
{
  "conversationId": "chat-user-1",
  "position": "cart",
  "query": "Add it to my cart",
  "attachments": [
    {
      "id": "SKU-0002",
      "vectorSpace": "product",
      "contentText": "Compact Mechanical Keyboard - 65% mechanical keyboard with Bluetooth connectivity.",
      "metadata": { "sku": "SKU-0002", "category": "Keyboards" },
      "source": "ui-card"
    }
  ]
}
```

### D) More runnable examples

For more runnable examples, see:
- `Real_Apps/chat-capabilities-demo/requests/demo.http`

---

## 6) Related endpoints (UI)

- `GET /api/chat/me/conversations`
- `GET /api/chat/me/conversations/<conversationId>`
- `DELETE /api/chat/me/conversations/<conversationId>`

---

## 7) CORS + Deployment notes

If your UI is hosted on a different domain than the API:
- Set `CORS_ALLOWED_ORIGINS` (exact origin) or `CORS_ALLOWED_ORIGIN_PATTERNS` (patterns) in the backend environment.

Local default port:
- `http://localhost:8096` (the app uses `PORT` when deployed)
