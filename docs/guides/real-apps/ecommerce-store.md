# Ecommerce Store (Domain Baseline)

> One-line: the pure-domain Spring Boot ecommerce app that the AI demos are built on — no AI Fabric, by design.

## What it builds
A complete, realistic ecommerce backend: products, carts, orders, payments, shipments, returns, reviews, coupons, policies, support tickets, accounts, and addresses — 14 REST controllers over 15 JPA entities, persisted in H2. It deliberately contains **no AI** so it can serve as the clean domain "canvas" the AI examples reuse. The rich API surface includes `@RequestMapping("/api/products")` (`GET /`, `GET /{id}`, `GET /search`, `GET /similar`, `GET /trending`, `POST`, `PUT /{id}`, `DELETE /{id}`), `@RequestMapping("/api/carts")` (`GET /active`, `POST /active/items`, `POST /active/checkout`), `@RequestMapping("/api/orders")`, `@RequestMapping("/api/payments")`, `@RequestMapping("/api/reviews")`, `@RequestMapping("/api/returns")`, `@RequestMapping("/api/shipments")`, `@RequestMapping("/api/coupons")`, `@RequestMapping("/api/policies")`, `@RequestMapping("/api/tickets")`, `@RequestMapping("/api/accounts")`, and `@RequestMapping("/api/addresses")`.

The domain entities are: `Product`, `Cart`, `CartItem`, `PurchaseOrder`, `OrderItem`, `Payment`, `Review`, `Shipment`, `ReturnRequest`, `Coupon`, `SupportTicket`, `Policy`, `Account`, and `Address` — a realistic spread of catalog, checkout, fulfillment, and support data with status enums (e.g. `Cart.Status`, `Payment.Status`, `Shipment.Status`) and stable identifiers (`sku`, `orderNumber`, `userId`).

## AI Fabric capability showcased
**None — and that's the point.** This app is the reference for *what a clean, AI-ready domain model looks like before AI Fabric is added*. It shows the starting point the annotation-driven examples (e.g. `cloud-qdrant-openai-vector-search`, `chat-capabilities-demo`) begin from. A well-factored domain — entities with clear text fields and stable identifiers — is exactly what makes adding `@AICapable`/`@AISearchable` a few-line change rather than a rewrite.

## AI Fabric modules used
**None.** Its `pom.xml` declares zero `ai-fabric-*` artifacts and the source contains no `ai.fabric.*` imports. Its role is to be the framework-free baseline. (It does optionally talk to an external AI Fabric *Runtime* over HTTP via a `connector.*` config block and an `/api/authz` hook, but that is a separate deployed service, not an embedded `ai-fabric-*` library.)

## Configuration
There is **no `ai.*` section**. The relevant config is plain Spring + an optional outbound connector:
```yaml
spring:
  datasource:
    url: jdbc:h2:file:./data/ecommerce-store.db   # file-backed H2, survives restarts
    driver-class-name: org.h2.Driver
  jpa:
    hibernate:
      ddl-auto: update
connector:                                         # optional: push data to an external AI Fabric Runtime
  indexing:
    enabled: ${CONNECTOR_INDEXING_ENABLED:false}
    runtime-base-url: ${CONNECTOR_INDEXING_RUNTIME_BASE_URL:http://rest-connector:8082}
```
There is no `application-smoke.yml`; the app runs offline out of the box because it has no AI dependencies or external model calls.

## How you'd add AI Fabric to this domain
This section adapts the template: there are no AI Fabric classes to document here, so instead here is how a Java dev would AI-enable this exact domain. Take an existing entity such as `Product` (`com.ai.fabric.realapps.chat.catalog.domain.Product`, fields `sku`, `name`, `description`, `category`, `price`) and add the starter + a vector module, then annotate:

```java
// illustrative — how Product would look after AI-enabling (see cloud-qdrant-openai-vector-search for the real thing)
@Entity
@Table(name = "product")
@AICapable(entityType = "product")
public class Product {
    @Id private Long id;
    private String sku;

    @AISearchable(weight = 2.0)
    private String name;

    @AISearchable(weight = 1.6)
    private String description;

    @AIContext(description = "Product category for filtering")
    private String category;
}
```
With `@EnableAIInfrastructure` on the application class and `ai-fabric-starter` + a vector module on the classpath, those annotations make products semantically searchable — no change to the controllers or persistence layer.

## Request flow
1. A client calls a domain endpoint, e.g. `GET /api/products/search?q=...`.
2. The request is handled by `ProductController` → service → Spring Data repository (plain SQL/`LIKE` matching today).
3. JSON is returned. **No AI Fabric is involved.** In the AI-enabled siblings, the same call routes through `AICoreService.performSearch(AISearchRequest...)` against a vector store instead.

## Run it
Offline (no keys):
`mvn -pl ecommerce-store -f examples/real-apps/pom.xml spring-boot:run -Dspring-boot.run.profiles=smoke`
(The `smoke` profile is harmless here — the app has no AI dependencies, so it boots identically with or without it.)

Sample request:
```bash
curl -s "http://localhost:8096/api/products?limit=10"
curl -s -X POST http://localhost:8096/api/admin/demo/reset
```

For real: there are no AI keys to supply. To make this domain searchable with AI, see `cloud-qdrant-openai-vector-search` (Postgres + Qdrant + OpenAI) or `chat-capabilities-demo`, which add `ai-fabric-starter` + a vector module and annotate the entities as shown above.

The default port is `8096` (override with `PORT`). The `@RequestMapping("/api/admin")` controller exposes `POST /demo/reset`, `POST /demo/clear`, and `POST /migration/clear` to rebuild or wipe the demo dataset between runs, which is handy when comparing this baseline against its AI-enabled siblings.

## Take it to your own app
- Keep your domain model AI-agnostic first: clean entities with meaningful text fields and stable IDs make AI enablement additive, not invasive.
- When you adopt AI Fabric, you should not have to restructure controllers or repositories — add `@EnableAIInfrastructure`, the starter, and a vector module, then annotate entities.
- Mark human-readable fields (`name`, `description`) `@AISearchable` and identifiers/categories `@AIContext`; leave the rest untouched.
- Use this app as a contract test of the "pure domain" surface, then compare against its AI-enabled siblings to see exactly which lines AI Fabric adds.
- Treat the external `connector.*` / `/api/authz` integration as the alternative path when you want AI handled by a separate AI Fabric Runtime rather than embedded libraries.
