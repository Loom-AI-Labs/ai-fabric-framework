# Real-App Walkthroughs

Eleven runnable Spring Boot apps, each showing **how to use AI Fabric to build a smart feature** —
written for Java developers who want AI in their apps without leaving the JVM. Every app boots
offline with the `smoke` profile (no API keys), so you can run the code while you read.

```bash
# build once
mvn -f ai-infrastructure-module/pom.xml -q -DskipTests install
mvn -f examples/real-apps/pom.xml      -q -DskipTests install
# then run any app offline
mvn -pl <app> -f examples/real-apps/pom.xml spring-boot:run -Dspring-boot.run.profiles=smoke
```

## Suggested reading path

If you're evaluating AI Fabric, read these three first — they cover the core patterns and all run
offline:

1. **[smart-faq-assistant](smart-faq-assistant.md)** — semantic search + RAG. The "hello world" of
   embeddings and vector search.
2. **[it-support-action-bot](it-support-action-bot.md)** — governed AI actions (`@AIAction` with
   authorization + human-in-the-loop confirmation). The cleanest look at tool-calling done the
   enterprise way.
3. **[relationship-query-crm-insights](relationship-query-crm-insights.md)** — natural language →
   JPQL over your own JPA entities. The framework's flagship differentiator.

## All apps by capability

### Search, RAG & retrieval
| App | What it shows |
|-----|---------------|
| [smart-faq-assistant](smart-faq-assistant.md) | Offline semantic search over FAQ articles, optional RAG answer. |
| [cloud-qdrant-openai-vector-search](cloud-qdrant-openai-vector-search.md) | Production-like search (Postgres + Qdrant + OpenAI) — and how `@AICapable`/`@AISearchable` drive indexing. Swap local→cloud by config. |
| [migration-enabled-product-catalog](migration-enabled-product-catalog.md) | Bulk-backfill an existing catalog into the vector store with async migration jobs. |

### Natural language → your data
| App | What it shows |
|-----|---------------|
| [relationship-query-crm-insights](relationship-query-crm-insights.md) | Plain-English questions translated to JPQL over CRM entities. |

### Governed actions & conversation
| App | What it shows |
|-----|---------------|
| [it-support-action-bot](it-support-action-bot.md) | `@AIAction` tool-calling with `@ActionAllowed` (authz) and `@ActionConfirmation` (confirm-before-execute). No vector DB. |
| [chat-capabilities-demo](chat-capabilities-demo.md) | Chat-session memory + RAG orchestration + governed actions with confirmation interceptors. |

### Trust, safety & insights
| App | What it shows |
|-----|---------------|
| [privacy-first-customer-facing-support](privacy-first-customer-facing-support.md) | Detect & redact PII in customer messages. |
| [behavior-churn-signals](behavior-churn-signals.md) | Derive churn/sentiment insights from user event streams. |

### Putting it all together
| App | What it shows |
|-----|---------------|
| [sub-management-hub](sub-management-hub.md) | The "kitchen-sink" app: search + NL→query + actions + async indexing + behavior + PII. |
| [sub-management-hub-simple](sub-management-hub-simple.md) | A trimmed-down subscription app — the "start here" variant of the hub. |
| [ecommerce-store](ecommerce-store.md) | The pure-domain Spring Boot baseline (no AI Fabric) — the canvas the AI demos build on, and where you'd plug AI Fabric in. |

## How to read each doc

Every walkthrough follows the same shape: what the app builds, the AI Fabric capability it showcases,
the modules and `ai.*` configuration it uses, **how it's wired in Java** (with a real code excerpt
from the app), the request flow, how to run it, and the reusable techniques to copy into your own app.

See also the conceptual guides: [Understanding AI Fabric](../02-understanding-ai-fabric.md),
[Modules Reference](../03-modules.md), [Configuration Reference](../04-configuration.md).
