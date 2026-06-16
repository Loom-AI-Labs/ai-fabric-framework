# Privacy-First Customer Support

> One-line: detects and redacts PII in inbound support messages before they are stored or shown.

## What it builds
A customer-support intake service. When a message comes in, AI Fabric scans the subject and body for PII (emails, phone numbers, SSNs, etc.), redacts it, and stores the processed (redacted) text plus detection metadata — optionally keeping the original encrypted. The persisted `SupportMessage` exposes `piiDetected`, `modeApplied`, detection count/summary, and the redacted fields, so downstream readers never see raw PII. Key endpoints (`SupportMessagesController` `@RequestMapping("/api/support/messages")` and `DemoSamplesController` `@RequestMapping("/api/demo")`):

- `POST /api/support/messages` — submit a message; returns it redacted
- `GET /api/support/messages`, `GET /api/support/messages/{id}`
- `GET /api/demo/samples` — example PII-bearing payloads
- `POST /api/demo/seed` — seed sample messages through the redaction pipeline

## AI Fabric capability showcased
This is the reference example for **PII detection + redaction**. It shows `PIIDetectionService.detectAndProcess(...)` applied at the write boundary so personal data is scrubbed (and optionally encrypted at rest) before persistence.

## AI Fabric modules used
- `ai-fabric-pii` — PII detection + redaction (`PIIDetectionService`, `PIIDetection`, `PIIDetectionResult`).

## Configuration
```yaml
ai:
  vector-db:
    type: false
  service:
    features:
      enable-embeddings: false
      enable-search: false
      enable-rag: false
      enable-generation: false      # pure PII path, no LLM generation
  pii-detection:
    enabled: true
    mode: ${AI_PII_MODE:REDACT}                       # REDACT replaces PII in text
    detection-direction: ${AI_PII_DETECTION_DIRECTION:INPUT_OUTPUT}
    store-encrypted-original: ${AI_PII_STORE_ENCRYPTED_ORIGINAL:true}
    encryption-secret: ${AI_PII_ENCRYPTION_SECRET:}
    audit-logging-enabled: ${AI_PII_AUDIT_LOGGING_ENABLED:true}
```
No embeddings, search, RAG, or generation are enabled — PII detection runs standalone. `mode: REDACT` controls how detected PII is handled, `store-encrypted-original` keeps the original ciphertext alongside the redacted text, and `audit-logging-enabled` records detections. All are env-overridable.

## How it's wired in Java
- `@EnableAIInfrastructure` on `PrivacyFirstCustomerFacingSupportApplication` bootstraps the PII beans.
- `ai.fabric.privacy.pii.PIIDetectionService` — `detectAndProcess(text)` returns an `ai.fabric.dto.PIIDetectionResult` with the processed (redacted) query, `piiDetected`, `modeApplied`, the list of `ai.fabric.dto.PIIDetection`s, and the encrypted original + salt.
- `SupportMessageService` runs subject and body through the service and maps the result onto the `SupportMessage` entity.

```java
// src/main/java/com/ai/fabric/realapps/privacyfirst/service/SupportMessageService.java
@Service
@RequiredArgsConstructor
public class SupportMessageService {

    private final SupportMessageRepository repository;
    private final PIIDetectionService piiDetectionService;

    @Transactional
    public SupportMessage create(String customerId, String channel, String subject, String message) {
        PIIDetectionResult subjectResult = piiDetectionService.detectAndProcess(subject);
        PIIDetectionResult messageResult = piiDetectionService.detectAndProcess(message);

        SupportMessage record = new SupportMessage();
        record.setCustomerId(customerId);
        record.setChannel(channel);
        record.setProcessedSubject(subjectResult.getProcessedQuery());
        record.setProcessedMessage(messageResult.getProcessedQuery());
        record.setPiiDetected(subjectResult.isPiiDetected() || messageResult.isPiiDetected());

        List<PIIDetection> combined = combineDetections(subjectResult.getDetections(), messageResult.getDetections());
        record.setDetectionsCount(combined.size());
        record.setDetectionsSummary(summarizeDetections(combined));
        record.setMessageEncryptedOriginal(messageResult.getEncryptedOriginalQuery());
        record.setMessageEncryptionSalt(messageResult.getEncryptionSalt());
        return repository.save(record);
    }
}
```

## Request flow
1. `POST /api/support/messages` hits `SupportMessagesController`, which calls `SupportMessageService.create(...)`.
2. The service calls `PIIDetectionService.detectAndProcess(subject)` and `...(message)`.
3. Each call returns a `PIIDetectionResult`: redacted text (`processedQuery`), the detections, the mode applied, and the encrypted original + salt.
4. The service stores the redacted fields and detection metadata on `SupportMessage` (original kept only as ciphertext).
5. The controller returns the redacted record — no raw PII leaves the write boundary.

## Run it
Offline (no keys):
`mvn -pl privacy-first-customer-facing-support -f examples/real-apps/pom.xml spring-boot:run -Dspring-boot.run.profiles=smoke`

Then:
```bash
curl -s -X POST http://localhost:8093/api/support/messages \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"cust-1001","channel":"webchat","subject":"Billing update",
       "message":"my email is sara.ahmed@example.com and my phone is +1 (555) 123-4567"}'
curl -s http://localhost:8093/api/support/messages
```
The response shows `piiDetected: true`, a detections summary, and the redacted `processedMessage`.

For real: this app needs no LLM keys — PII detection is local. For production set a non-empty `AI_PII_ENCRYPTION_SECRET` (and keep `store-encrypted-original: true`) so the original text is encrypted at rest, and tune `AI_PII_MODE` / `AI_PII_DETECTION_DIRECTION` to your policy.

## Take it to your own app
- Call `PIIDetectionService.detectAndProcess(...)` at every write boundary and persist `getProcessedQuery()` instead of the raw input.
- Store `getEncryptedOriginalQuery()` + `getEncryptionSalt()` when you must retain the original — keep plaintext out of the database.
- Persist `isPiiDetected()`, `getModeApplied()`, and the `PIIDetection` list as auditable metadata on your records.
- Drive behavior from `ai.pii-detection.*` config (mode, direction, encryption, audit) rather than hard-coding redaction rules.
- Run PII detection with all other AI features (`embeddings`/`search`/`rag`/`generation`) disabled — it needs no LLM and no vector store.
