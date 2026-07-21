# NotebookLM Single-Source Production Script: Tenant Security And Privacy

## Generator Instructions - Do Not Narrate

Use this file as the only source for the video. Do not supplement it with general zero-trust,
compliance, vector-database, or privacy knowledge. Do not ask for or rely on another source.

Create a structured technical explainer titled **Tenant Security And Privacy With AI Fabric: Apply
Policy Before Evidence Or Execution**. Follow the fourteen scenes in order. Use every **Visual**
block as production direction and every **Narration** block as the spoken message. Natural
transitions are allowed, but do not omit, replace, or contradict the technical content.

This is the theoretical introduction to CORE-06, not a code-along. Keep AI Fabric's current request
security, access policy, metadata-filtering contract, action authorization, PII processing, and
response sanitization as the subject. Do not invent automatic tenant isolation, compliance status,
PII accuracy, provider behavior, endpoints, test output, or guarantees. Apply the final accuracy
guardrails to the complete output.

## Production Direction

- Title: **Tenant Security And Privacy With AI Fabric: Apply Policy Before Evidence Or Execution**
- Target duration: 12-15 minutes.
- Audience: Java and Spring Boot developers who understand AI Fabric RAG, governed actions, and
  backend conversation memory.
- Voice: direct, practical, calm, and technically precise. Address the developer as **you**.
- Primary objective: show where identity, tenant scope, evidence filters, action policy, PII
  processing, and final response projection must be enforced to prevent cross-boundary leakage.
- Example application: a multi-tenant Support Knowledge Assistant serving Tenant A and Tenant B.
- Example sensitive input: **My email is alex@example.com and my SSN is 123-45-6789.**
- Visual style: use one persistent data-boundary diagram, two tenant lanes, security gates before the
  model, and sanitized proof artifacts. Avoid locks floating over generic AI imagery.

## Scene 1: Security Must Precede Intelligence

**Visual:** Show the complete protected flow.

```text
authenticated request
  -> security analysis
  -> request access policy
  -> derive trusted tenant/user scope
  -> PII processing
  -> tenant-filtered retrieval or authorized action
  -> generation from allowed evidence only
  -> response sanitization and safe projection
```

Cross out a reversed flow that filters Tenant B evidence after generation.

**Narration:**

An LLM cannot repair a security boundary after private evidence has already entered its context.

Tenant and privacy controls must run before retrieval, generation, or action execution. The server
authenticates the caller, derives trusted subject and tenant context, evaluates request access,
processes sensitive input, and constrains the evidence or action available to this request. Only
allowed, sanitized material may reach a generation provider.

The final response is sanitized again before it reaches the client or conversation memory.

This ordering is more important than a polished refusal prompt. A prompt can ask the model not to
mention another tenant. It cannot make exposure safe once another tenant's document was supplied as
context. Security lives in deterministic application and framework boundaries around the model.

## Scene 2: Build Canonical Identity On The Server

**Visual:** Show a Spring Security principal or verified token claims feeding canonical metadata.

```text
verified authentication
  -> subjectId
  -> sessionId
  -> tenantId / customerId
  -> subjectType and authMode
  -> issuer, audiences, granted scopes, expiry
  -> OrchestrationContext metadata
```

Show client-supplied `X-Tenant-Id` as a demo input that is not inherently trusted.

**Narration:**

AI Fabric carries a canonical `AIAccessSubjectContext`, but your application establishes the truth
of that context.

At the HTTP boundary, resolve identity from verified authentication: a Spring Security principal,
a validated token, or another trusted service identity. Populate subject, tenant, customer, scopes,
issuer, audiences, and session metadata from that source.

Do not trust a tenant ID merely because a browser sent it in JSON or a header. A public demo may use
a tenant selector to illustrate behavior, but production code must bind that selector to authorized
identity or ignore it in favor of server-derived claims.

When subject identity is absent, AI Fabric can represent an anonymous session. That is not an
authenticated user and must remain restricted by request, chat, action, and domain policies.

## Scene 3: Request Security And Access Are Separate Gates

**Visual:** Place the first two core pipeline steps side by side.

```text
SecurityAnalysisStep, order 10
  content + auth + request metadata
  -> SecurityAnalysisPolicy
  -> block suspicious request or continue

AccessControlStep, order 20
  canonical auth + resource rag:intent + operation READ
  -> EntityAccessPolicy
  -> grant or terminate
```

**Narration:**

The core orchestration pipeline begins with two separate gates.

`SecurityAnalysisStep` asks `AISecurityService` to analyze the incoming request, authentication
context, operation, IP address, user agent, and metadata. If policy says block, orchestration stops.

`AccessControlStep` then creates an access request for the orchestration resource and delegates to
the application-provided `EntityAccessPolicy`. The access service requires canonical subject or
session identity. If no policy bean exists, it throws instead of silently granting access. If the
policy throws, the service records a failed hook and denies access.

Request access answers, "May this caller enter this AI workflow?" It does not automatically answer,
"Which tenant documents may search return?" or "May this action change ticket T-1042?" Those narrower
boundaries still require evidence and action policy.

## Scene 4: `EntityAccessPolicy` Is Application-Owned And Fail-Closed

**Visual:** Show the policy inputs.

```text
EntityAccessPolicy.canAccess(
    AIAccessSubjectContext auth,
    immutable entity/request metadata
)
```

Show decisions for known subject and resource, missing identity, unknown resource, expired or
insufficient scope, and cross-tenant context.

**Narration:**

`EntityAccessPolicy` is a narrow application SPI. It receives canonical authentication context and
an immutable metadata view containing resource, operation, timestamp, request context, purpose, and
selected attributes.

Implement it as an allow policy, not an optimistic default. Grant only known operations for known
subjects and required scopes. Deny missing identity, unknown resources, invalid tenant relationships,
and policy exceptions. Override the denial callback when you need application audit records.

AI Fabric can cache successful or denied decisions through the configured cache, and the cache key
includes identity, tenant, scopes, resource, operation, context, attributes, and timestamp. A hook
failure is not cached as a grant.

This policy protects entry to orchestration. Continue to authorize retrieval records and action
targets at their own boundaries.

## Scene 5: Attach Security Metadata When Evidence Is Indexed

**Visual:** Show two documents entering the vector index.

```text
Document A
  id: vpn-a
  tenantId: tenant-a
  visibility: internal
  visibleToUser: true
  sourceId: article-101

Document B
  id: payroll-b
  tenantId: tenant-b
  visibility: restricted
  visibleToUser: false
  sourceId: article-202
```

**Narration:**

Tenant-safe retrieval begins during indexing. Every evidence record needs stable metadata that can
express its ownership and visibility boundary.

Store the trusted tenant or customer ID, visibility classification, source identity, and any
positive access flags your portable filter contract needs. Those values must come from the source
record and application policy, not from generated text.

AI Fabric's portable vector metadata filtering is exact-match oriented. Instead of requesting an
inequality such as `visibility != restricted`, store and query a reviewed positive flag such as
`visibleToUser = true`. This keeps the filter representable across providers that support the
portable equality subset.

Metadata is part of the security contract. If old records lack tenant tags, do not mix them into a
tenant-scoped index and hope the answer layer will infer ownership.

## Scene 6: Build Retrieval Filters From Trusted Context

**Visual:** Show an application service constructing a search request.

```text
current authenticated context
  sessionId = demo-session-42
  tenantId = tenant-a
  role = USER

AISearchRequest.metadata = {
  sessionId: demo-session-42,
  tenantId: tenant-a,
  visibleToUser: true
}
```

Then show those filters reaching vector search before any documents return.

**Narration:**

AI Fabric does not invent the application's tenant filter automatically. The application builds it
from canonical request identity and passes it with the search or RAG request.

For a tenant user, the filter can require the current tenant, current demo or data session, and a
positive visibility flag. A platform administrator may use a different reviewed filter and policy,
but that is an explicit privileged path.

The selected vector provider must advertise and prove metadata-filtered search behavior compatible
with the filter shape. AI Fabric's provider contract says a requested filter must not be widened
silently when it cannot be represented safely. Deployment tests must verify the actual provider,
index configuration, namespace, and payload-index requirements.

If tenant filtering is required and the provider cannot preserve it, stop the request or choose a
safe provider. Never retry as an unfiltered similarity search.

## Scene 7: Verify Returned Evidence Before Generation

**Visual:** Show a defense-in-depth check after provider search and before context construction.

```text
provider hits
  -> verify session/tenant/visibility metadata
  -> allowed hit list
  -> generation context
```

Show a Tenant B hit being dropped and raising a boundary failure rather than appearing in a hidden
UI field.

**Narration:**

Provider-side filtering is the primary retrieval boundary, but the application can verify returned
metadata before context construction as defense in depth.

Every hit should belong to the expected tenant and session and satisfy the caller's visibility
policy. Denied records must never reach the prompt, citations, diagnostics returned to the user, or
conversation working set.

Do not treat frontend filtering as evidence security. If the backend response contains Tenant B IDs
but the React component hides them, the leak has already occurred. Likewise, a generated answer with
no visible citation does not prove that forbidden evidence was absent from model context.

Return backend boundary diagnostics suitable for testing, but keep them sanitized. Direct API tests
should assert that denied tenant IDs and content are absent from both evidence and generated output.

## Scene 8: Actions Need Operation-Specific Authorization Too

**Visual:** Show request access, retrieval scope, and action authorization as three layers.

```text
request policy: may enter AI workflow
retrieval filter: may see these evidence records
action policy: may perform this operation on this target now
```

Show `@ActionAllowed` and the domain service checking canonical tenant and object ownership.

**Narration:**

A user allowed to ask support questions is not automatically allowed to archive a document, delete
a tenant, escalate another customer's ticket, or issue a refund.

Use `@ActionAllowed` for the action-level pre-confirmation gate and pass `ActionContext` into the
handler. The application resolves the target from trusted identity and verifies tenant ownership,
role, scopes, and current object state. The domain service should re-check state-sensitive rules in
the transaction.

Confirmation records user approval; it does not grant permission. An unauthorized action must be
denied before the framework creates a pending confirmation. A forged target in model parameters or
an old pinned reference must fail current authorization.

Keep tenant and current-account identifiers out of model-visible parameters when the backend
already owns them.

## Scene 9: Enable PII Processing Explicitly

**Visual:** Show the configuration posture.

```text
ai.pii-detection.enabled: true
ai.pii-detection.mode: REDACT
ai.pii-detection.detection-direction: INPUT_OUTPUT

modes:
PASS_THROUGH | DETECT_ONLY | REDACT
```

Show default enabled patterns: credit card, email, phone, SSN. Mark IBAN as disabled by default.

**Narration:**

The PII module is explicit. Detection is disabled by default, and its default mode is
`PASS_THROUGH`. A privacy-sensitive application must enable it and choose a reviewed mode and
direction.

The current modes are `PASS_THROUGH`, `DETECT_ONLY`, and `REDACT`. Default configurable patterns
cover payment-card-shaped numbers, email, phone, and US SSN. The included IBAN pattern is disabled
by default. Applications can add or replace patterns.

These regular-expression detectors are useful application safeguards, not proof that every form of
personal or regulated data will be detected. Test the data formats your product accepts, add
domain-specific detectors where needed, and use specialized secure capture for secrets such as full
payment details.

Never claim regulatory compliance from enabling one module or one pattern set.

## Scene 10: Process Input Before It Reaches LLM Or Retrieval

**Visual:** Place `PIIDetectionStep` at order 30 and transform the sample input.

```text
raw input:
"My email is alex@example.com and my SSN is 123-45-6789."

processed downstream query:
"My email is ***@***.*** and my SSN is ***-**-****."
```

Show detected types stored separately as `EMAIL` and `SSN`.

**Narration:**

`PIIDetectionStep` runs after request access control and before intent extraction, embedding,
retrieval, or generation.

When input detection is enabled, the PII service returns structured detections and a processed
query. In `REDACT` mode, the service masks matched spans. The pipeline adds another safety invariant:
if PII was found but the processed query still equals the raw input, as can happen in
`DETECT_ONLY`, the step constructs a masked downstream query from the detections.

Detected type names are retained as diagnostics, while downstream AI work receives the processed
query. This prevents the ordinary orchestration path from forwarding matched raw values to the LLM
or query embedding provider.

Input processing must also happen before application storage or indexing outside orchestration. A
privacy intake endpoint should call the backend service before it persists or vectorizes content.

## Scene 11: Protected Original Storage Is Optional And Precise

**Visual:** Show three storage choices.

```text
storeEncryptedOriginal = false
  -> no protected original field

storeEncryptedOriginal = true + encryptionSecret
  -> AES-GCM encrypted payload + IV metadata

storeEncryptedOriginal = true + no usable secret
  -> salted SHA-256 HASH record, not recoverable encryption
```

Show `exposeOriginalPayloadInResult = false` as the default.

**Narration:**

By default, detection results do not expose the raw original when PII is found. Keep
`exposeOriginalPayloadInResult` false for user-facing and routinely logged results.

If auditing requirements demand protected original storage, enable it deliberately and provide a
strong encryption secret through secure runtime configuration. The current service derives an AES
key and uses AES-GCM with a random initialization vector.

If no secret is configured, or encryption cannot be completed, the service stores a salted SHA-256
hash marker instead. That proves equality only through controlled processing; it is not recoverable
encrypted content. Monitoring must distinguish encrypted records from `HASH:` records.

Encryption does not authorize retrieval of the original. Key management, rotation, retention,
audited access, and deletion remain application and operational responsibilities.

## Scene 12: Sanitize Output And Conversation Persistence

**Visual:** Show the late response path.

```text
generated/action/RAG result
  -> ResponseSanitizationStep, order 90
  -> message + nested data + suggestions sanitized
  -> ConversationRecordingStep, order 95
  -> client and backend memory receive sanitized text
```

Show `NEVER_PERSIST` bypassing chat storage.

**Narration:**

Input protection does not remove the need to inspect output. A provider or action result can still
return sensitive text.

`ResponseSanitizationStep` processes the message, nested result data, suggestions, smart
suggestions, RAG responses, and action results through `ResponseSanitizer`. Response sanitization is
enabled by default, and force-redaction is enabled by default when the PII service detects output.
The sanitized message and answer are mirrored back into the orchestration result before later
conversation recording.

The chat module records after this step and asks the PII service to redact the query again. The
current recorder preserves the original query if that secondary analysis throws, so a
privacy-critical application must not treat this helper as a universal fail-closed persistence
guarantee. Use `NEVER_PERSIST` for sensitive transient workflows, monitor detector failures, and add
an application persistence boundary that rejects or quarantines a turn when redaction cannot be
proved.

Sanitization is a final boundary, not permission to retrieve forbidden evidence earlier.

## Scene 13: Make Denial And Leakage Tests Release Gates

**Visual:** Present a failure matrix.

| Scenario | Expected backend proof |
| --- | --- |
| Missing `EntityAccessPolicy` | Startup/request fails; no implicit grant |
| Missing canonical subject/session | Access request rejected |
| Policy hook throws | Access denied and hook failure visible |
| Tenant A searches shared terms | Only Tenant A allowed evidence returned |
| Restricted record | Absent before generation and citations |
| Provider cannot preserve filter | Request fails; no unfiltered retry |
| Unauthorized action target | Denied before confirmation and mutation |
| Email, phone, or SSN in input | Downstream query and stored proof are redacted |
| PII in generated/action output | Sanitized before client and chat recording |
| Transient request | Raw input absent from chat, logs, and index |

**Narration:**

Security claims need negative tests against backend boundaries.

Seed two tenants with overlapping names and deliberately attractive cross-tenant content. Search as
each caller and assert the other tenant's IDs and text never appear in provider results accepted by
the application, generation context, citations, response payload, or chat working set.

Test missing policy, missing identity, policy exception, unsupported filter capability, restricted
visibility, and forged action targets. Submit representative PII and inspect downstream provider
requests, persistence, index records, logs captured by the test, and response projection.

These tests run largely without an LLM key. A live provider test can prove that the complete path
preserves the boundary, but deterministic denial and redaction tests remain the primary release
gate.

## Scene 14: The Ownership Map And Completion Proof

**Visual:** End with an ownership table followed by a checklist.

| Concern | Owner |
| --- | --- |
| Authentication and trusted claims | Application security integration |
| Request threat analysis contract | AI Fabric plus application `SecurityAnalysisPolicy` |
| Entry authorization | AI Fabric plus application `EntityAccessPolicy` |
| Tenant metadata and filter construction | Application source/indexing and retrieval service |
| Filter execution and capability reporting | Selected AI Fabric vector provider |
| Action authorization and domain policy | Application handler and services |
| PII patterns, mode, secret, and retention | Application configuration and operations |
| Input processing and final response sanitization | AI Fabric pipeline |
| Proof of no cross-boundary leakage | Backend tests and deployment verification |

```text
Done when:
[ ] identity and tenant come from verified server context
[ ] missing or failing policy denies access
[ ] tenant and visibility metadata exist on indexed evidence
[ ] required exact-match filters execute before context construction
[ ] forbidden evidence is absent from backend results, not hidden by UI
[ ] unauthorized writes stop before confirmation
[ ] raw PII does not enter ordinary provider, index, response, or memory paths
[ ] encryption, hashing, retention, and transient behavior are described accurately
```

**Narration:**

AI Fabric supplies policy hooks, ordered pipeline gates, provider filter contracts, PII processing,
and response sanitization. Your application supplies trusted identity, tenant semantics, source
metadata, authorization, and retention rules.

You have completed the tenant and privacy foundation when forbidden evidence is excluded before
generation, unauthorized actions cannot reach confirmation, and matched sensitive values are
processed before ordinary AI, persistence, and response paths. The proof comes from backend denial
and leakage tests, not from tenant-colored cards or privacy labels in the UI.

The next lesson turns those controls into a release discipline that distinguishes deterministic
proof, packaged-runtime proof, container-provider proof, and live hosted-provider proof.

## Accuracy Guardrails - Do Not Narrate

1. Do not say AI Fabric authenticates users or automatically trusts tenant headers. The application
   must populate canonical context from verified authentication.
2. Do not say `EntityAccessPolicy` automatically filters every vector record or authorizes every
   action. It is the request-access hook; narrower policies remain necessary.
3. Do not move tenant filtering after generation or into frontend-only rendering.
4. Do not claim AI Fabric automatically derives tenant filters. The application constructs filters
   from trusted context.
5. Do not use `visibility != restricted` as the portable filter example. The current portable
   metadata contract is exact-match oriented; use a positive reviewed field such as
   `visibleToUser=true`.
6. Do not claim every provider has identical metadata filtering. Inspect capability diagnostics and
   run provider-specific boundary tests.
7. Do not retry a required filtered search without filters when the provider cannot preserve them.
8. Do not treat confirmation as authorization.
9. Do not claim PII detection is enabled by default. It is disabled by default and defaults to
   `PASS_THROUGH`.
10. Do not claim regular-expression detection finds every sensitive value or proves compliance.
11. Do not say `DETECT_ONLY` forwards matched raw PII through the orchestration pipeline; the input
    step masks detected spans before downstream work.
12. Do not describe a `HASH:` protected-original record as encrypted or recoverable.
13. Do not expose full payment-card data, CVV, passwords, API keys, or other secure-capture secrets
    through ordinary chat parameters.
14. Do not claim conversation recording always fails closed when its secondary PII analysis throws;
    use `NEVER_PERSIST` or an application fail-closed persistence boundary for privacy-critical
    flows.
15. Do not present UI masking, screenshots, or a successful generated refusal as proof that forbidden
    evidence never reached the backend or model.
