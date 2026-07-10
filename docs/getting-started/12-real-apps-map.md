# Real Apps Map

Use real apps as templates. They are the best evidence of how AI Fabric modules fit together.

## Public Demo Apps

| App | Path | Shows |
| --- | --- | --- |
| AI Shopping Experience | `examples/real-apps/chat-capabilities-demo` | RAG stages, commerce modes, governed cart/order actions, chat session memory. |
| AI Fabric Account Resolver | `examples/real-apps/ai-fabric-account-resolver` | Resolver-mode account repair, policy RAG, current-user actions, confirmations. |
| AI Fabric Behavior Signals | `examples/real-apps/behavior-churn-signals` | Raw events to LLM behavior insights, agentic UI recommendations. |
| AI Fabric Tenant Guard | `examples/real-apps/tenant-knowledge-portal` | Tenant-safe retrieval, metadata boundaries, policy-aware answers. |

## Smaller Capability Apps

| App | Path | Shows |
| --- | --- | --- |
| Smart FAQ Assistant | `examples/real-apps/smart-faq-assistant` | Minimal searchable knowledge app. |
| IT Support Action Bot | `examples/real-apps/it-support-action-bot` | Local `@AIAction` discovery and execution. |
| DB Action Registry Lab | `examples/real-apps/db-action-registry-lab` | Database-backed connector actions. |
| MCP Operations Assistant | `examples/real-apps/mcp-operations-assistant` | MCP-oriented operations assistant pattern. |
| Migration Enabled Product Catalog | `examples/real-apps/migration-enabled-product-catalog` | Backfill/migration indexing. |
| Privacy First Support | `examples/real-apps/privacy-first-customer-facing-support` | PII-aware support flows. |
| Provider Failover Lab | `examples/real-apps/provider-failover-lab` | Provider behavior and failover. |
| Relationship Query CRM Insights | `examples/real-apps/relationship-query-crm-insights` | Relationship/query capability patterns. |
| Cloud Qdrant OpenAI Vector Search | `examples/real-apps/cloud-qdrant-openai-vector-search` | Managed vector provider with OpenAI. |

## Which App Should I Copy?

- Need search only: `smart-faq-assistant`.
- Need chat over products/policies/reviews/actions: `chat-capabilities-demo`.
- Need account resolver or support automation: `ai-fabric-account-resolver`.
- Need behavior analytics: `behavior-churn-signals`.
- Need tenant isolation: `tenant-knowledge-portal`.
- Need action registry: `db-action-registry-lab`.

## Verification Rule

When a guide says a capability is supported, prefer citing a real app README or test that uses that
capability. If no real app proves it yet, mark it as planned or smoke-only.

