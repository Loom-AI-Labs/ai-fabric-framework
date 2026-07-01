# Privacy-First Customer-Facing Support

## Scenario

This app demonstrates privacy-first handling for customer support messages. A user can submit text
containing PII, and the app stores only redacted content while preserving safe evidence about the
original message according to configuration.

It intentionally does not require vector search, indexing, or RAG. The point is to prove the privacy
surface independently.

## AI Fabric Capabilities Proved

- PII detection activates through configuration.
- Redaction mode stores safe support-message content.
- Original payload storage can be hash-based or AES-GCM encrypted depending on configuration.
- Plain-text PII is not required for the support workflow.
- Governance/deletion services can be layered onto privacy-safe support data.
- The app can run locally without model keys or vector infrastructure.

## Framework Surfaces

- `ai-fabric-starter`
- PII detection/redaction configuration
- `ai-fabric-governance`
- optional local vector provider dependency for governance-compatible wiring
- Spring Boot/JPA support-message persistence

## Runtime Posture

Default runtime:

- H2 database
- local deterministic smoke support available
- no external LLM
- no RAG
- no external vector service

Default port: `8093`.

## Run

From the repository root:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl privacy-first-customer-facing-support -am package
java -jar examples/real-apps/privacy-first-customer-facing-support/target/privacy-first-customer-facing-support-1.0.0-SNAPSHOT.jar
```

## Validate

Focused tests:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl privacy-first-customer-facing-support -am test
```

Use `requests/demo.http` to run the full demo flow.

## Key Configuration

`src/main/resources/application.yml` enables PII handling:

- `ai.pii-detection.enabled=true`
- `ai.pii-detection.mode=REDACT`
- `AI_PII_MODE`: override redaction mode.
- `AI_PII_STORE_ENCRYPTED_ORIGINAL`: controls encrypted/hash original storage.
- `AI_PII_ENCRYPTION_SECRET`: when set, AES-GCM is used; when blank, original evidence is stored as
  `HASH:*`.

## Demo Flow

1. Inspect sample support messages.
2. Seed demo messages.
3. Submit a support message containing PII.
4. Verify stored content is redacted.
5. Retrieve support messages and confirm no plain-text PII is exposed.

## Key Endpoints

- `GET /api/demo/samples`
- `POST /api/demo/seed`
- `POST /api/support/messages`
- `GET /api/support/messages`
- `GET /api/support/messages/{id}`

## What This App Does Not Cover

- Semantic search over support messages.
- Chat-session action flow.
- Live provider privacy behavior.

Use `chat-capabilities-demo`, `smart-faq-assistant`, or provider RealAPI tests for those paths.
