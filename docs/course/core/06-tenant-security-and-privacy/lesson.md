---
id: core-06
slug: tenant-security-and-privacy
title: Tenant Security And Privacy
track: core
order: 6
durationMinutes: 85
availability: published
courseVersion: 0.4.0-course.4-beta
frameworkVersion: 0.4.0
frameworkTag: ai-fabric-framework-v0.4.0
courseSourceTag: ai-fabric-course-v0.4.0.4
starterRef: course-0.4.0-04-memory
solutionRef: course-0.4.0-05-security
requiresOpenAi: false
requiresDocker: false
sourcePaths:
  - docs/course/core/06-tenant-security-and-privacy/notebooklm/AI_FABRIC_TENANT_SECURITY_PRIVACY_NOTEBOOKLM_SCRIPT.md
  - docs/getting-started/10-security-access-policy.md
  - docs/course/labs/AI_FABRIC_CHAT_UI_LAB.md
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/orchestration/OrchestrationContextMetadataKeys.java
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/orchestration/OrchestrationAuthContextResolver.java
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/access/policy/EntityAccessPolicy.java
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/access/AIAccessControlService.java
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/orchestration/pipeline/steps/AccessControlStep.java
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/util/VectorMetadataFilterSupport.java
  - ai-infrastructure-module/ai-fabric-pii/src/main/java/ai/fabric/config/PIIDetectionProperties.java
  - ai-infrastructure-module/ai-fabric-pii/src/main/java/ai/fabric/intent/orchestration/pipeline/steps/PIIDetectionStep.java
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/orchestration/pipeline/steps/ResponseSanitizationStep.java
  - examples/real-apps/tenant-knowledge-portal/src/main/java/com/ai/fabric/realapps/tenantportal/service/TenantKnowledgeService.java
  - examples/real-apps/privacy-first-customer-facing-support/src/main/java/com/ai/fabric/realapps/privacyfirst/service/SupportMessageService.java
  - examples/real-apps/privacy-first-customer-facing-support/src/test/java/com/ai/fabric/realapps/privacyfirst/service/SupportMessageServiceTest.java
theoryVideoIds:
  - tenant-security-and-privacy
assistant:
  mode: implement
  implementationPrompt: assistant-prompt.md
  reviewPrompt: assistant-review-prompt.md
  validationStatus: passed
knowledgeCheck:
  source: knowledge-check.yml
  required: true
  passingScorePercent: 80
---

# Tenant Security And Privacy

## Start Here

An LLM cannot repair a boundary after forbidden evidence has entered its prompt. In this lesson,
you will protect the Support Knowledge Assistant before retrieval, generation, action confirmation,
storage, and indexing.

You will seed two tenants with overlapping support content, derive tenant identity from verified
server authentication, apply exact-match metadata filters before vector results are accepted, deny
cross-tenant actions, and redact representative PII before ordinary persistence or AI processing.

> **Verified checkpoints:** start from `course-0.4.0-04-memory` and finish at
> `course-0.4.0-05-security`. The required path uses deterministic policy, local vector, and PII
> tests, so it needs no LLM key. A live-provider run is additional evidence, not a substitute.

## The Protected Request Flow

Use this order:

```text
verified authentication
  -> canonical subject and tenant context
  -> request security and access policy
  -> PII input processing
  -> tenant-filtered retrieval or action authorization
  -> generation from allowed evidence only
  -> response sanitization
  -> sanitized conversation persistence and client projection
```

Do not retrieve broadly, generate an answer, and then hide another tenant's citations in React. At
that point, the backend and possibly the model provider have already received forbidden evidence.

## Step 1: Write The Security Contract

Create `security-contract.md` before editing code:

| Boundary | Trusted input | Deny condition | Required proof |
| --- | --- | --- | --- |
| request entry | verified principal and claims | missing identity, unknown resource, missing scope | fail-closed access test |
| evidence indexing | source-owned tenant and visibility | missing tenant metadata | indexing test rejects record |
| retrieval | canonical tenant and positive visibility flag | filter unsupported or hit metadata mismatches | no forbidden hit before generation |
| action | current subject, tenant, role, object state | cross-tenant or insufficient role | denied before confirmation |
| PII intake | configured detector and app policy | redaction cannot be proved | reject or quarantine before save/index |
| response | sanitizer and explicit result DTO | unsafe nested data remains | API and conversation record contain no raw PII |

This table separates request access from evidence authorization and action authorization. One policy
does not automatically implement all three.

## Step 2: Add And Configure PII Processing

Add the privacy module:

```xml
<dependency>
  <groupId>io.github.loom-ai-labs</groupId>
  <artifactId>ai-fabric-pii</artifactId>
</dependency>
```

Choose an explicit posture:

```yaml
ai:
  pii-detection:
    enabled: true
    mode: REDACT
    detection-direction: INPUT_OUTPUT
    expose-original-payload-in-result: false
    store-encrypted-original: false
```

PII detection is disabled by default, and its default mode is `PASS_THROUGH`. Never describe the
application as privacy-protected merely because the dependency is present.

The built-in patterns include card-shaped numbers, email, phone, and US SSN. IBAN detection is
disabled by default. These regular-expression safeguards do not find every sensitive value and do
not establish regulatory compliance.

## Step 3: Build Canonical Identity From Authentication

At the HTTP boundary, derive AI Fabric metadata from a verified principal or validated token:

```java
Map<String, Object> authMetadata = new LinkedHashMap<>();
authMetadata.put(OrchestrationContextMetadataKeys.SUBJECT_ID, principal.getName());
authMetadata.put(OrchestrationContextMetadataKeys.SUBJECT_TYPE, "END_USER");
authMetadata.put(OrchestrationContextMetadataKeys.AUTH_MODE, "BEARER");
authMetadata.put(OrchestrationContextMetadataKeys.TENANT_ID, claims.tenantId());
authMetadata.put(OrchestrationContextMetadataKeys.GRANTED_SCOPES, claims.scopes());

OrchestrationContext context = OrchestrationContext.builder()
    .userId(principal.getName())
    .sessionId(serverSessionId)
    .conversationId(request.conversationId())
    .metadata(authMetadata)
    .position("support")
    .build();
```

`OrchestrationAuthContextResolver` converts those trusted values into
`AIAccessSubjectContext`. A tenant ID in public JSON or an unverified `X-Tenant-Id` header is not
canonical identity.

Anonymous session identity can enter a deliberately public workflow, but it is not an authenticated
user. Give it a narrower policy.

## Step 4: Implement A Fail-Closed Entry Policy

Register an application-owned `EntityAccessPolicy`:

```java
@Bean
EntityAccessPolicy supportAccessPolicy() {
    return (auth, entity) -> {
        if (auth == null
            || auth.getSubjectId() == null
            || auth.getSubjectId().isBlank()
            || auth.getTenantId() == null
            || auth.getTenantId().isBlank()) {
            return false;
        }

        String resource = Objects.toString(entity.get("resourceId"), "");
        String operation = Objects.toString(entity.get("operationType"), "");

        return "rag:intent".equals(resource)
            && "READ".equalsIgnoreCase(operation)
            && auth.getGrantedScopes() != null
            && auth.getGrantedScopes().contains("support:read");
    };
}
```

`AccessControlStep` currently evaluates the orchestration resource `rag:intent` with operation
`READ`. If the policy is missing, identity is missing, the hook throws, or the policy returns
false, the request must not proceed as an implicit grant.

This policy answers whether the subject may enter this AI workflow. It does not automatically add a
tenant filter to vector search or authorize a ticket mutation.

### Intentional Missing-Policy Failure

Temporarily remove the bean and run the application-context or orchestration test. Record the
fail-closed result. Restore the policy and prove only known resource, operation, identity, tenant,
and scope combinations are granted.

## Step 5: Index Tenant And Visibility Metadata

Seed records with overlapping terms so a weak filter is easy to detect:

| ID | Tenant | Visibility | Title |
| --- | --- | --- | --- |
| `vpn-a` | `tenant-a` | `internal` | VPN recovery |
| `vpn-b` | `tenant-b` | `internal` | VPN recovery |
| `payroll-b` | `tenant-b` | `restricted` | Payroll escalation |

Project source-owned metadata when indexing:

```java
Map<String, Object> metadata = Map.of(
    "tenantId", document.tenantId(),
    "visibility", document.visibility(),
    "visibleToUser", document.isVisibleToUser(),
    "documentId", document.id(),
    "sourceId", document.sourceId()
);
```

Use a positive reviewed flag such as `visibleToUser=true`. AI Fabric's portable metadata subset is
exact-match oriented; `visibility != restricted` is not the portable contract.

Reject or quarantine records that lack tenant metadata. Reindexing old unscoped records into the
same tenant-aware space without repair creates a security ambiguity.

## Step 6: Build Retrieval Filters From Trusted Context

Construct `AISearchRequest.metadata` on the server:

```java
Map<String, Object> filter = Map.of(
    "tenantId", canonicalAuth.getTenantId(),
    "visibleToUser", true
);

AISearchRequest searchRequest = AISearchRequest.builder()
    .query(safeQuery)
    .entityType("support-article")
    .limit(5)
    .threshold(0.0d)
    .metadata(filter)
    .build();
```

Validate the portable filter before sending it:

```java
VectorMetadataFilterSupport.ValidationResult validation =
    VectorMetadataFilterSupport.validatePortableEquals(filter);

if (validation.hasRejectedFilters()) {
    throw new IllegalStateException("Required tenant filter is not portable");
}
```

The selected vector provider must preserve the required filter. If it cannot, stop the request or
choose a provider that can. Never retry a required tenant search without metadata constraints.

## Step 7: Verify Hits Before Building Generation Context

Treat provider-side filtering as the primary boundary and perform an application-side verification
before context construction:

```java
List<Map<String, Object>> allowedHits = response.getResults().stream()
    .filter(hit -> canonicalAuth.getTenantId().equals(metadataText(hit, "tenantId")))
    .filter(hit -> Boolean.TRUE.equals(metadataValue(hit, "visibleToUser")))
    .toList();

if (allowedHits.size() != response.getResults().size()) {
    throw new EvidenceBoundaryException("Vector results crossed the required tenant boundary");
}
```

The exact helper shape is application-specific. The invariant is not: a mismatched hit must never
reach prompt context, citations, chat working-set metadata, logs returned to the client, or a hidden
UI field.

Add direct API tests that search the shared term `VPN` as each tenant. Assert the other tenant's
IDs and content are absent from the complete backend response.

## Step 8: Authorize Actions Before Confirmation

Request access, retrieval scope, and action authorization answer different questions:

```text
May this caller enter the workflow?
Which evidence may this caller see?
May this caller perform this operation on this target now?
```

For a governed `escalate_support_ticket` action:

1. Keep current tenant and user out of model-visible `@Param` values.
2. Use `@ActionAllowed` as an early action gate.
3. Load the target through the current tenant in the domain service.
4. Recheck role, object ownership, and state inside the transaction.
5. Create pending confirmation only after authorization succeeds.

Confirmation records user approval; it does not grant permission. Test that a forged Tenant B
ticket number submitted by Tenant A is denied before pending state or mutation exists.

## Step 9: Redact Before Storage And Indexing

`PIIDetectionStep` protects the ordinary orchestration path before intent extraction, embedding,
retrieval, or generation. Application intake endpoints outside that pipeline must call
`PIIDetectionService` themselves before persistence:

```java
PIIDetectionResult result = piiDetectionService.detectAndProcess(rawMessage);

String safeMessage = requireProvenProcessedText(rawMessage, result);
SupportMessage saved = repository.save(
    SupportMessage.fromSafeContent(customerId, safeMessage, result.getDetections())
);
IndexingOutcome outcome = indexingGateway.upsert(
    saved,
    AIProcessOperation.CREATE,
    IndexingStrategy.SYNC
);
requireCompletedIndexing(outcome);
```

`requireProvenProcessedText` must mask detected spans or reject/quarantine the record when safe
processing cannot be proved. In privacy-critical paths, do not return raw input when the detector is
null, throws, or reports PII without usable positions and masked values.

The example calls the public `AIEntityIndexingGateway` from the application-owned transaction
boundary. A service method annotated with
`@AIProcess(operation = AIProcessOperation.CREATE)` is the equivalent AOP path. The removed
`processEntityForAI` lifecycle must not be used in a 0.4 application.

Use this test input:

```text
My email is alex@example.com and my SSN is 123-45-6789.
```

Assert that raw email and SSN are absent from the saved entity, vector content, embedding query,
ordinary logs, API response, and chat turns.

## Step 10: Handle Protected Originals Precisely

Keep `expose-original-payload-in-result=false`. If a reviewed auditing requirement needs protected
original storage, enable it deliberately and supply a strong runtime secret:

```yaml
ai:
  pii-detection:
    store-encrypted-original: true
    encryption-secret: ${AI_PII_ENCRYPTION_SECRET}
```

With a usable secret, the current service uses AES-GCM protected content. Without one, it records a
salted `HASH:` marker. A hash marker is not encrypted and cannot recover the original.

Encryption does not authorize access. Key rotation, retention, deletion, audit, and recovery remain
application and operational responsibilities. Do not commit the secret.

Full payment-card data, CVV, passwords, and API keys belong in specialized secure-capture flows,
not ordinary chat or action parameters.

## Step 11: Sanitize Output Before Conversation Recording

The late response path is:

```text
generated, RAG, or action result
  -> ResponseSanitizationStep
  -> sanitized message, nested data, suggestions, and action/RAG result
  -> ConversationRecordingStep
  -> client and backend chat memory
```

Add a test where an action result or generated response contains the sample email. Assert that the
API response and stored assistant turn contain only the configured masked value.

The current conversation recorder attempts a second PII pass over the query, but preserves the
original query if that helper throws. A privacy-critical application therefore needs an explicit
fail-closed persistence boundary or `NEVER_PERSIST`; do not claim the generic recorder alone
guarantees fail-closed storage during detector failure.

### Optional Chat UI Checkpoint

Run the cross-user, cross-tenant, and PII scenarios through the
[AI Fabric Chat UI lab](../../labs/AI_FABRIC_CHAT_UI_LAB.md). The component uses same-origin
authentication and prefers `sanitizedPayload`, but it is not a security boundary. Identity, tenant,
roles, authorization, and redaction remain backend-owned, and forbidden data must never reach the
browser response.

## Step 12: Run The Boundary Regression Matrix

Keep these tests deterministic and keyless:

| Scenario | Required backend result |
| --- | --- |
| policy bean missing | startup/request fails; no implicit grant |
| canonical identity missing | access denied |
| policy hook throws | denied and hook failure visible |
| Tenant A searches `VPN` | only Tenant A allowed evidence |
| restricted record matches strongly | absent before generation and citations |
| required filter is unsupported | request fails; no unfiltered retry |
| Tenant A targets Tenant B action | denied before confirmation and mutation |
| email, phone, or SSN enters intake | provider/index/storage receive masked content |
| PII appears in output | response and chat recording are sanitized |
| transient sensitive request | no chat, log, or index persistence |

Capture arguments sent to the vector, embedding, generation, repository, and action services. This
proves what crossed each boundary without relying on model prose.

## Step 13: Add Optional Packaged And Live Proof

Start the packaged application with the same local profile used by the tests and call both tenant
paths through HTTP. Verify health, provider posture, and the complete response payload.

If you also run a hosted LLM, repeat an allowed and denied tenant query. A successful live answer is
additional integration evidence. It does not replace deterministic proof that forbidden material
was absent before provider invocation.

Provider failure must remain visible. Do not replace a failed generation call with a deterministic
answer labeled as live AI.

## Commands And Requests

```bash
./mvnw clean verify
./scripts/download-onnx-model.sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Open `requests/05-tenant-security-privacy.http` for missing-identity, tenant-isolation,
cross-tenant-action, PII-redaction, and readiness proof against the standalone application.

## Common Mistakes

| Mistake | Consequence | Correct approach |
| --- | --- | --- |
| Trusting a tenant header or request field | Caller proposes its own security scope | Derive canonical tenant from verified auth |
| Treating `EntityAccessPolicy` as vector row policy | Entry access is mistaken for evidence filtering | Build required metadata filters separately |
| Using `visibility != restricted` | Filter cannot use the portable equality contract | Index and require `visibleToUser=true` |
| Retrying without filters | Cross-tenant evidence can enter context | Fail or choose a capable provider |
| Hiding denied rows in the UI | Forbidden data already left the backend | Exclude before generation and response |
| Treating confirmation as authorization | Approval can execute an unauthorized target | Authorize before pending confirmation |
| Adding the PII dependency without configuration | Detection remains disabled or pass-through | Enable and choose a reviewed mode |
| Returning raw text when redaction fails | Storage and index receive sensitive values | Reject, quarantine, or use `NEVER_PERSIST` |
| Calling a hash encrypted | Recovery and audit assumptions become false | Label `HASH:` records accurately |
| Claiming compliance from regexes | Product guarantees exceed implementation proof | State and test the exact safeguard |

## Troubleshooting

| Symptom | Inspect |
| --- | --- |
| Pipeline reports `AccessControl` failure | canonical identity, policy bean, resource/operation allowlist, and scopes |
| Tenant filter appears empty | auth metadata construction and server-derived tenant |
| Cross-tenant result appears | indexed metadata, request metadata filter, provider capability, and hit verification |
| Restricted content is retrieved | positive visibility field and exact-match filter |
| Action reaches confirmation for wrong tenant | action gate, context-owned tenant, and domain lookup |
| Raw PII reaches repository or index | intake service boundary and detector failure behavior |
| Input is masked but output leaks | response sanitization configuration and nested payload tests |
| Chat contains a transient value | `NEVER_PERSIST` metadata and conversation recording |
| Protected original begins with `HASH:` | missing/unusable encryption secret; this is not recoverable encryption |
| UI looks safe but API includes denied IDs | backend evidence projection and direct API test |

## Done When

You are done with this lesson when:

- subject, tenant, scopes, and session come from verified server context;
- missing identity, missing policy, denial, and policy exceptions fail closed;
- every indexed support record carries trusted tenant and positive visibility metadata;
- required exact-match filters execute before context construction;
- cross-tenant and restricted evidence is absent from the complete backend result;
- unauthorized actions stop before confirmation and mutation;
- matched PII is absent from ordinary provider, index, response, and memory paths;
- protected-original and transient-request behavior is described and tested accurately;
- security proof is deterministic and does not depend on an LLM refusal or UI masking;
- you score at least 80 percent on the knowledge check.

## Next Lesson

CORE-07 turns the complete vertical slice into a release gate with deterministic, packaged-runtime,
and explicitly keyed live-provider evidence.
