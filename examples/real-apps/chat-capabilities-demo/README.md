# Chat Capabilities Demo (AI Fabric)

v0 quickstart (golden path):
- `../../docs/V0_QUICKSTART.md`

This Real App demonstrates:
- **Chat sessions**: multi-turn context + turn recording + conversation lifecycle APIs (get/list/delete)
- **Product catalog + RAG**: CRUD products, index into **Lucene**, and retrieve via chat (OpenAI embeddings + LLM)
- **Actions**: `create_purchase_order` action executed from chat
- **Curated modes** (commerce pack): `navigator`, `navigator_deep`, `executor`, `cart_assistant`

## Run

```bash
mvn -f ai-infrastructure-module/pom.xml -DskipTests install
mvn -f examples/real-apps/chat-capabilities-demo/pom.xml spring-boot:run
```

## UI migration (request contract + positions)

See `Final_Documentation/Development_Guides/CHAT_CAPABILITIES_UI_MIGRATION_GUIDE.md`.

## API Docs (Swagger)

- Swagger UI: `http://localhost:8096/swagger-ui/index.html`
- OpenAPI JSON: `http://localhost:8096/v3/api-docs`

## OpenAI Setup

This app expects OpenAI for **LLM + embeddings** (required for RAG + intent extraction + actions).

```bash
export OPENAI_ENABLED=true
export OPENAI_API_KEY="..."
export OPENAI_MODEL="gpt-4o-mini"                         # optional
export OPENAI_EMBEDDING_MODEL="text-embedding-3-small"     # optional
export OPENAI_EMBEDDING_DIMENSIONS="512"                  # recommended for Lucene (max 1024)
```

Then open `requests/demo.http`.

## CORS (for https://ai-fabric.dev demo UI)

If you are calling this API from a browser-based frontend on another domain, set:

```bash
export CORS_ALLOWED_ORIGINS="https://ai-fabric.dev"
```
