---
id: case-05
slug: privacy-shield
title: AI Fabric Privacy Shield
track: case-studies
order: 5
durationMinutes: 55
availability: published
courseVersion: 0.4.0-course.4-beta
frameworkVersion: 0.4.0
frameworkTag: ai-fabric-framework-v0.4.0
courseSourceTag: ai-fabric-course-v0.4.0.4
starterRef: course-0.4.0-p08-production-ready
solutionRef: course-0.4.0-p08-production-ready
requiresOpenAi: false
requiresDocker: false
optionalProviderExercises: []
sourcePaths:
  - examples/real-apps/privacy-first-customer-facing-support/README.md
  - examples/real-apps/privacy-first-customer-facing-support/src/main/java/com/ai/fabric/realapps/privacyfirst/domain/SupportMessage.java
  - examples/real-apps/privacy-first-customer-facing-support/src/main/java/com/ai/fabric/realapps/privacyfirst/service/SupportMessageService.java
  - examples/real-apps/privacy-first-customer-facing-support/src/main/java/com/ai/fabric/realapps/privacyfirst/service/PrivacyDemoService.java
  - examples/real-apps/privacy-first-customer-facing-support/src/test/java/com/ai/fabric/realapps/privacyfirst/service/SupportMessageProjectionTest.java
  - examples/real-apps/privacy-first-customer-facing-support/src/test/java/com/ai/fabric/realapps/privacyfirst/service/PrivacyDemoServiceTest.java
theoryVideoIds:
  - case-privacy-shield-walkthrough
assistant:
  mode: reproduce
  implementationPrompt: assistant-prompt.md
  reviewPrompt: assistant-review-prompt.md
  validationStatus: passed
knowledgeCheck:
  source: knowledge-check.yml
  required: true
  passingScorePercent: 80
---

# Reproduce Privacy-Safe Indexing And Search

## Start Here

Privacy Shield processes sensitive customer support text before approved content is stored or
indexed. It intentionally does not claim live LLM generation. The proof is PII detection, redaction,
safe persistence, sanitized retrieval, and isolated deletion.

Open:

- live UI: `https://ai-fabric.dev/demos/ai-fabric-privacy-shield`
- backend health: `https://ai-fabric-privacy-shield.46.224.145.148.sslip.io/api/demo/health`
- source: `examples/real-apps/privacy-first-customer-facing-support`

## Architecture To Recognize

```text
customer text -> AI Fabric PII detection -> redacted projection
                                      |          |
                               protected proof  +-> H2 safe row
                                                  -> Lucene vector

search text -> PII detection/redaction -> vector query -> session-owned safe hits
```

The public API never returns raw submitted PII. If encrypted-original retention is enabled, that
storage is a protected backend concern and still does not belong in public responses or vector
content.

## Step 1: Create An Isolated Session

Reset the live demo and create a browser session. Seed the provided safe support examples. Inspect
the dashboard count and confirm that another session cannot read them.

## Step 2: Submit Sensitive Content

Submit a message containing an email address, phone number, and SSN-like value. Expected:

- detection count and categories are visible;
- public subject/body fields are redacted;
- raw input is absent from response JSON;
- the indexed content is the safe projection;
- original-evidence policy is reported without exposing the original.

Inspect `SupportMessageService` and `SupportMessageProjectionTest` for the backend boundary.

## Step 3: Search With Sensitive Query Text

Search using an email or phone number in the query. The query must be sanitized before vector
search. Returned hits must contain only approved, session-owned redacted data.

This demo uses the local `privacy-simple` embedding provider and Lucene. A configured OpenAI key does
not mean a model generated the result.

## Step 4: Inspect And Delete Privacy Inventory

Use the inventory operation to inspect safe counts for the current customer/session, then run the
governed deletion path. Verify both source rows and derived vectors are removed for that scope.

## Intentional Failure

Search the browser network log and response body for the exact raw values you submitted. Any
plain-text occurrence outside the request itself is a failure. Also verify that the UI does not label
deterministic search as an LLM answer.

## Run Locally

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml \
  -pl privacy-first-customer-facing-support -am test
```

No external provider key is required. Configure secrets only through the environment:

```bash
AI_PII_MODE=REDACT \
AI_PII_STORE_ENCRYPTED_ORIGINAL=true \
AI_PII_ENCRYPTION_SECRET="$AI_PII_ENCRYPTION_SECRET" \
mvn -f examples/real-apps/privacy-first-customer-facing-support/pom.xml spring-boot:run
```

## Done When

- raw PII is absent from public persistence/search responses;
- redacted content is what reaches the vector index;
- sensitive query text is sanitized before retrieval;
- sessions cannot see each other's support records;
- deletion removes both source and vector state in scope;
- provider posture is described honestly as no-LLM local search.

## Next Lesson

CASE-06 closes the track by proving that annotation-driven source changes converge on current vector
evidence across multiple entity types.
