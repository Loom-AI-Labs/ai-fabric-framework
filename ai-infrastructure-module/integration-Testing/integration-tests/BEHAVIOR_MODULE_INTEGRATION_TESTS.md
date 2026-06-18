# AI Behavior Module – Integration Test Expansion

> Location: `ai-infrastructure-module/integration-Testing/integration-tests`

The new ai-behavior module introduces dedicated ingestion pipelines, pluggable sinks, analyzers, and segmentation workers. To keep the integration suite production-ready we need at least ten focused test cases that exercise the end-to-end paths across storage backends, async workers, and cross-module adapters.

## Test Matrix

| ID | Title | Primary Components |
|----|-------|--------------------|
| BEH-IT-001 | Database Sink & API Roundtrip | `BehaviorIngestionController`, `DatabaseEventSink`, `BehaviorQueryController` |
| BEH-IT-002 | Kafka Sink Fan-out Verification | `KafkaEventSink`, `BehaviorMonitoringController`, embedded Kafka |
| BEH-IT-003 | Redis Hot Cache TTL Enforcement | `RedisEventSink`, RedisTemplate-backed queries |
| BEH-IT-004 | Hybrid Sink Dual-write Consistency | `HybridEventSink`, Redis + Postgres |
| BEH-IT-005 | S3 Sink Archival Contract | `S3EventSink`, Testcontainers S3 (LocalStack) |
| BEH-IT-006 | Aggregated Behavior Provider Failover | `AggregatedBehaviorProvider`, `ExternalAnalyticsBehaviorProvider` |
| BEH-IT-007 | External Analytics Adapter Contract | `RestExternalAnalyticsAdapter`, WireMock stub |
| BEH-IT-008 | Anomaly Detection Worker Alerts | `AnomalyDetectionWorker`, `AnomalyAnalyzer`, `BehaviorAlertRepository` |
| BEH-IT-009 | User Segmentation Worker Refresh | `UserSegmentationWorker`, `SegmentationAnalyzer`, `BehaviorInsightsRepository` |
| BEH-IT-010 | Pattern Analyzer Insights Quality | `BehaviorAnalysisService`, `PatternAnalyzer`, `BehaviorQueryService` |

## Detailed Scenarios

### BEH-IT-001: Database Sink & API Roundtrip
- **Goal:** Ensure baseline ingestion + query path works without alternative sinks.
- **Setup:** Use Testcontainers Postgres. Allow Spring Boot to apply the bundled Liquibase changelog (`db/changelog/db.changelog-master.yaml`).
- **Flow:** POST `/api/ai-behavior/signals` → store via `DatabaseEventSink` → GET `/api/ai-behavior/users/{id}/signals`.
- **Assertions:** Response contains schema metadata (schemaId, attributes, timestamps), persisted rows match the API payload, query ordering honors `BehaviorQuery`.

### BEH-IT-002: Kafka Sink Fan-out Verification
- **Goal:** Validate pluggable sink can publish to Kafka topic and monitoring endpoint reflects it.
- **Setup:** Embedded Kafka broker; set `ai.behavior.sink.type=kafka`.
- **Flow:** Ingest 5 purchase events. Consume from test topic.
- **Assertions:** All events present with serialized JSON, `BehaviorMonitoringController` reports sink type kafka and recent count = 5.

### BEH-IT-003: Redis Hot Cache TTL Enforcement
- **Goal:** Confirm Redis sink stores signals with TTL and expires after the configured horizon.
- **Setup:** Testcontainers Redis, `ai.behavior.sink.type=redis`, `ai.behavior.sink.redis.ttlSeconds=60`.
- **Flow:** Ingest a signal, read from Redis key `behavior:signal:{id}`, advance time (Awaitility) to verify key eviction.
- **Assertions:** Value exists immediately with JSON payload, key removed after TTL.

### BEH-IT-004: Hybrid Sink Dual-write Consistency
- **Goal:** Ensure hybrid sink writes to Redis and Postgres atomically.
- **Setup:** Redis + Postgres containers, `ai.behavior.sink.type=hybrid`.
- **Flow:** Ingest batch of 3 signals. Query Redis for hot copy, query DB for durable copy.
- **Assertions:** All IDs exist in both stores, Redis TTL matches config, DB row counts stay stable even after Redis eviction.

### BEH-IT-005: S3 Sink Archival Contract
- **Goal:** Verify S3 sink uploads gzip-compressed JSON objects into the expected schema-aware key structure.
- **Setup:** LocalStack S3, bucket `behavior-tests`, `ai.behavior.sink.s3.compress=true`.
- **Flow:** Ingest a timestamped signal → fetch object `ai-behavior/yyyy/MM/dd/{schemaId}/{signalId}.json.gz`.
- **Assertions:** Object exists, decompress contains exact JSON (schemaId, attributes, timestamps), metadata preserved.

### BEH-IT-006: Aggregated Behavior Provider Failover
- **Goal:** Confirm aggregated provider merges database + external results respecting order.
- **Setup:** Enable `ai.behavior.providers.aggregated.enabled=true`, stub external provider bean returning synthetic events.
- **Flow:** Query via `BehaviorQueryService` with `limit=50`.
- **Assertions:** Response contains union (no duplicates), sorted by timestamp desc when `ascending=false`, gracefully skips failing providers.

### BEH-IT-007: External Analytics Adapter Contract
- **Goal:** Exercise REST adapter payload + header contract.
- **Setup:** WireMock server for `/behavior/query`, configure baseUrl + apiKey.
- **Flow:** Execute `BehaviorQueryService` query for user; stub returns JSON array.
- **Assertions:** Adapter sends POST with metadata filter payload, returned events mapped to `BehaviorSignal`, provider type is `external-rest`.

### BEH-IT-008: Anomaly Detection Worker Alerts
- **Goal:** Ensure worker emits alerts for high-value & high-velocity purchases.
- **Setup:** Use in-memory `BehaviorDataProvider` spy to return crafted events. Trigger `AnomalyDetectionWorker.detect()` manually.
- **Assertions:** `BehaviorAlertRepository` contains entries with `alertType` `purchase_value_anomaly` and `velocity_anomaly`, severity matches rule, context holds amount.

### BEH-IT-009: User Segmentation Worker Refresh
- **Goal:** Validate segmentation worker updates insights when metrics change.
- **Setup:** Seed `behavior_metrics` for two users, create stale `behavior_insights`.
- **Flow:** Run `UserSegmentationWorker.refreshSegments()`.
- **Assertions:** Insights segments updated according to metrics (VIP/dormant), recommendations overwritten, `analyzedAt` refreshed.

### BEH-IT-010: Pattern Analyzer Insights Quality
- **Goal:** Protect against behavior regression in `PatternAnalyzer`.
- **Setup:** Insert deterministic sequence of events via repository (views, carts, purchases).
- **Flow:** Invoke `BehaviorAnalysisService.analyze(userId)`.
- **Assertions:** Patterns include `frequent_buyer`, segment `VIP`, recommendations list loyalty program and referral actions, scores within expected ranges.

---

**Next Steps**
1. Add skeletal test classes under `src/test/java/ai/fabric/it/behavior/...`.
2. Extend `integration-tests/pom.xml` with LocalStack + Kafka testcontainers dependencies.
3. Update CI workflows to allow selective execution (e.g., `mvn -Dtest=Behavior*IntegrationTest test`).
