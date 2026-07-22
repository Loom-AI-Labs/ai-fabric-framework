# CORE-06 Coding-Assistant Implementation Prompt

Status: Planned. Do not use this prompt as a validated implementation handoff until the published
starter checkpoint replaces `planned`.

```text
You are implementing AI Fabric course lesson CORE-06: Tenant Security And Privacy.

Use AI Fabric 0.3.3 / ai-fabric-framework-v0.3.3, Java 21, and Spring Boot 4.1.x. Work only from the
published CORE-06 starter checkpoint when available. Do not copy a solution checkpoint.

Read first:
- docs/course/core/06-tenant-security-and-privacy/lesson.md
- docs/getting-started/10-security-access-policy.md
- current OrchestrationContextMetadataKeys, OrchestrationAuthContextResolver, EntityAccessPolicy,
  AIAccessControlService, AccessControlStep, VectorMetadataFilterSupport, PIIDetectionProperties,
  PIIDetectionStep, and ResponseSanitizationStep APIs
- the starter's authentication, indexing, retrieval, action, chat-session, and persistence paths

Goal:
Derive canonical identity from verified authentication, fail closed at request entry, restrict
evidence by trusted tenant and positive visibility metadata before generation, authorize action
targets before confirmation, and keep matched PII out of ordinary provider, index, response, and
conversation paths.

Before editing:
1. Verify the starter ref, worktree, auth model, local vector provider, tests, and provider posture.
2. Draw the current data flow from HTTP input to retrieval/action, persistence, generation, response, and chat.
3. Identify every place tenant or user scope currently comes from request data.
4. Inspect current access and PII APIs; do not invent automatic tenant isolation or compliance guarantees.
5. Produce a threat/boundary table and a concise implementation and test plan.

Required behavior:
1. Add ai-fabric-pii with explicit REDACT and INPUT_OUTPUT configuration.
2. Build canonical subject, tenant, session, and scopes from verified server authentication.
3. Register a narrow EntityAccessPolicy that denies missing identity, unknown operations/resources,
   missing scope, and exceptions.
4. Add trusted tenant, source, and positive visibility metadata during indexing.
5. Build exact-match AISearchRequest metadata filters from canonical context.
6. Stop when required filters are unsupported; never retry without them.
7. Verify returned hit metadata before prompt context, citations, or chat working-set construction.
8. Authorize action role, tenant, object, and state before pending confirmation.
9. Process PII before application persistence, indexing, embedding, retrieval, or generation.
10. Sanitize nested output before client response and conversation recording.
11. Reject, quarantine, or use NEVER_PERSIST when privacy-critical redaction cannot be proved.
12. Keep protected-original encryption/hash semantics and secrets explicit.

Testing:
- missing policy, missing identity, denied policy, and throwing policy tests;
- canonical-auth construction test that ignores untrusted tenant request data;
- index metadata completeness test;
- portable exact-match filter validation test;
- Tenant A/Tenant B overlapping-term retrieval tests;
- provider-filter unsupported test with no unfiltered retry;
- returned-hit defense-in-depth mismatch test;
- cross-tenant action denial before pending confirmation and mutation;
- REDACT and DETECT_ONLY safe-downstream tests for email, phone, SSN, and product-specific patterns;
- repository, vector, embedding, provider, API, log-capture, and chat assertions with no raw PII;
- output/nested-action-result sanitization test;
- detector failure and NEVER_PERSIST tests;
- packaged local HTTP smoke;
- clean ./mvnw clean verify.

Do not:
- trust tenant, owner, user, role, or scope from public JSON or unverified headers;
- claim EntityAccessPolicy automatically filters vectors or authorizes every action;
- use inequality filters as the portable metadata contract;
- widen a required filtered search;
- filter forbidden evidence only in the UI or after generation;
- treat confirmation as authorization;
- expose full card data, CVV, passwords, or API keys through chat/actions;
- return raw content when privacy-critical processing fails;
- call a HASH record encrypted or claim regex detection proves compliance;
- hide provider failures with deterministic output;
- use -DskipTests, commit secrets, or discard unrelated changes;
- commit, push, or deploy.

Stop and report when the starter checkpoint is missing, verified identity cannot be established,
the selected provider cannot preserve required filters, a PII boundary cannot fail closed, pinned
APIs contradict the lesson, or requested live credentials are unavailable.

Finish with changed files, exact command outcomes, canonical identity proof, access decisions,
indexed metadata, retrieval filters, complete allowed/denied responses, action pending/mutation
counts, PII values used and every inspected sink, encryption/hash posture, packaged smoke status,
unexecuted checks, and the final protected data flow.
```

