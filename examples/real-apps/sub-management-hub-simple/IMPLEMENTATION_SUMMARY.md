# Subscription Management Hub Simple - Implementation Summary

## Status

This application is a small subscription-management example built with Java 21, Spring Boot 4.1,
and AI Fabric 0.4.0.

It demonstrates:

- subscription-plan semantic retrieval;
- governed subscription actions with confirmation;
- natural-language orchestration;
- explicit plan reindexing through the AI Fabric indexing gateway;
- H2 development storage and optional PostgreSQL runtime storage.

It does not claim that every JPA entity write is synchronized automatically.

## AI Entity Contract

`SubscriptionPlan` is the only AI-indexed entity in this application:

- `@AICapable(entityType = "subscription-plan", indexingStrategy = ASYNC)` defines the entity and
  default lifecycle strategy;
- `@AIIdentity` marks the stable vector identity;
- `@AISearchable` selects the plan name and description as embedding text;
- `@AIContext` selects approved price, tier, feature, user-limit, and storage metadata.

`Subscription` and `Address` remain application-domain records. They are not annotated as searchable
entities and are not silently indexed by repository writes.

## Indexing Boundary

AI Fabric 0.4.0 keeps persistence and AI indexing as explicit application boundaries:

- startup seeding and demo reindex endpoints submit plans through `AIEntityIndexingGateway`;
- the gateway projects only approved fields into a durable `AIIndexDocument`;
- provider work follows the configured queue, retry, ordering, and dead-letter semantics;
- `@AICapable` alone is not a JPA entity listener.

The service methods in `SubscriptionService` are ordinary transactional domain methods. They do not
carry `@AIProcess`, because subscriptions and addresses are not indexed by this example.

## Governed Actions

The application registers five framework actions:

1. subscribe;
2. cancel a subscription;
3. upgrade a subscription;
4. downgrade a subscription;
5. update an address.

The action handlers own validation, application context, confirmation text, and execution. AI Fabric
performs intent/action orchestration, but the application remains responsible for business rules and
authorized domain writes.

## Main HTTP Surfaces

- `POST /api/subscriptions/query` - natural-language orchestration;
- `POST /api/subscriptions/query/actions/execute` - governed action execution;
- `GET /api/subscriptions/plans` - list plans;
- `GET /api/subscriptions/plans/{id}` - read a plan;
- `POST /api/subscriptions/plans/search` - plan search;
- `POST /api/demo/indexing/reindex/plans` - explicit plan reindex;
- `GET /api/ai/debug/indexing/queue` - queue diagnostics;
- `POST /api/ai/debug/indexing/queue/run-once` - manually process queued demo work.

## Running And Testing

From the real-app reactor:

```bash
mvn -pl sub-management-hub-simple -am clean verify
```

Run the application with its offline smoke providers:

```bash
mvn -pl sub-management-hub-simple -am spring-boot:run \
  -Dspring-boot.run.profiles=smoke
```

For an unreleased local AI Fabric version, first run the infrastructure reactor's normal
`clean install`; do not bypass its tests.

## Current Scope

The application is intentionally smaller than the deployed Account Resolver demo. Behavior
analytics, account-readiness analysis, refund policy, and richer resolver workflows live in
`examples/real-apps/ai-fabric-account-resolver`.
