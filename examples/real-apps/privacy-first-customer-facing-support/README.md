# Privacy-First Customer-Facing Support (Real_App)

Scenario: demonstrate **PII detection + redaction** for customer support requests, using **configuration-driven auto-config** (no manual bean wiring).

## What this app proves

- The app can accept customer messages containing PII and **store only redacted content**
- Optional storage of the original payload as **HASH/AES-GCM** (never plain text)
- No vector DB, no indexing, no RAG required for this scenario

## Run

1) Build framework artifacts:

`cd ai-infrastructure-module && mvn -DskipTests install`

2) Run the app:

`cd examples/real-apps/privacy-first-customer-facing-support && mvn -DskipTests package && java -jar target/*.jar`

App port: `8093`

## Key config

`src/main/resources/application.yml` enables the PII module:

- `ai.pii-detection.enabled=true`
- `ai.pii-detection.mode=REDACT` (override with `AI_PII_MODE`)
- `ai.pii-detection.store-encrypted-original=true` (override with `AI_PII_STORE_ENCRYPTED_ORIGINAL`)
- `ai.pii-detection.encryption-secret=${AI_PII_ENCRYPTION_SECRET:}` (blank => store `HASH:*` instead of AES)

## Demo endpoints

- `GET /api/demo/samples`
- `POST /api/demo/seed`
- `POST /api/support/messages`
- `GET /api/support/messages`
- `GET /api/support/messages/{id}`

Use `requests/demo.http` to run the full demo flow.
