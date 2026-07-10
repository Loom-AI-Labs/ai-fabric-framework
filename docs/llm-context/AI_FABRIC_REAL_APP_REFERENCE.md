# Real App Reference

Use these apps as implementation evidence.

## AI Shopping Experience

Path: `examples/real-apps/chat-capabilities-demo`

Capabilities:

- commerce curated prompts/modes
- product, policy, review indexing
- staged RAG evidence seeding
- cart/order/support actions
- chat session memory
- real OpenAI profile and smoke profile

## AI Fabric Account Resolver

Path: `examples/real-apps/ai-fabric-account-resolver`

Capabilities:

- account profile read action
- payment/address/subscription/refund actions
- confirmation-required side effects
- policy RAG
- current-user action context
- per-session public demo users

## AI Fabric Behavior Signals

Path: `examples/real-apps/behavior-churn-signals`

Capabilities:

- raw event ingestion
- LLM behavior analysis
- insight refresh from previous insight plus new events
- agentic UI component recommendation
- no deterministic fallback hiding failed LLM analysis

## AI Fabric Tenant Guard

Path: `examples/real-apps/tenant-knowledge-portal`

Capabilities:

- tenant-scoped documents
- metadata-safe retrieval
- cross-tenant leak checks
- policy-aware RAG
- admin/demo reset flows

## Smaller Apps

- `smart-faq-assistant`: minimal semantic search.
- `it-support-action-bot`: local action handlers.
- `db-action-registry-lab`: DB action registry.
- `migration-enabled-product-catalog`: migration/backfill.
- `privacy-first-customer-facing-support`: PII-aware support.
- `cloud-qdrant-openai-vector-search`: managed vector provider.

