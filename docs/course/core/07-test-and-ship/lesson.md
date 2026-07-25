---
id: core-07
slug: test-and-ship
title: Test And Ship The Vertical Slice
track: core
order: 7
durationMinutes: 70
availability: published
courseVersion: 0.4.0-course.2-beta
frameworkVersion: 0.4.0
frameworkTag: ai-fabric-framework-v0.4.0
courseSourceTag: ai-fabric-course-v0.4.0.2
starterRef: course-0.4.0-05-security
solutionRef: course-0.4.0-06-tested-solution
requiresOpenAi: false
requiresDocker: false
sourcePaths:
  - docs/course/core/07-test-and-ship/notebooklm/AI_FABRIC_TESTING_SHIPPING_WORKFLOWS_NOTEBOOKLM_SCRIPT.md
  - docs/getting-started/11-testing-and-verification.md
  - docs/getting-started/13-production-checklist.md
  - docs/course/labs/AI_FABRIC_CHAT_UI_LAB.md
  - docs/Framework-Dev-Guides/testing-verification/CI_PIPELINE_GUIDE.md
  - .github/workflows/framework-verify.yml
  - .github/workflows/integration-tests-manual.yml
  - .github/workflows/provider-suite-keys-only.yml
  - .github/scripts/validate-framework-release-guards.sh
  - .github/scripts/validate-workflow-test-policy.sh
  - .github/scripts/validate-no-production-stubs.sh
  - .github/scripts/smoke-boot-realapps.sh
  - .github/scripts/smoke-p1-realapp-scenarios.sh
  - .github/scripts/run-vector-container-contracts.sh
  - examples/real-apps/smoke-support/src/main/java/com/ai/fabric/examples/smoke/health/DemoDeploymentInfoService.java
theoryVideoIds:
  - testing-and-shipping
assistant:
  mode: verify
  implementationPrompt: assistant-prompt.md
  reviewPrompt: assistant-review-prompt.md
  validationStatus: passed
knowledgeCheck:
  source: knowledge-check.yml
  required: true
  passingScorePercent: 80
---

# Test And Ship The Vertical Slice

## Start Here

You have built the complete Support Knowledge Assistant:

- indexed support evidence with stable metadata;
- evidence-grounded RAG with explicit no-evidence behavior;
- typed actions with authorization and confirmation;
- backend-owned conversation memory;
- tenant-scoped retrieval and PII-safe boundaries.

Now turn those capabilities into release evidence. A successful chat screenshot is not a release
gate. You will prove deterministic behavior, start the packaged application, exercise HTTP
scenarios, make provider posture explicit, expose build identity, and record what did and did not
run.

> **Verified checkpoints:** start from `course-0.4.0-05-security` and finish at
> `course-0.4.0-06-tested-solution`. The required completion path is keyless. Docker and
> hosted-provider runs remain additional, explicitly labelled evidence when your release claim
> includes them.

## The Evidence Classes

Keep each result labeled:

| Evidence class | What it proves | What it does not prove |
| --- | --- | --- |
| deterministic tests | domain, schema, state, policy, PII, local retrieval | packaged startup or hosted providers |
| packaged runtime | built jar, profile, auto-configuration, HTTP wiring | live LLM quality |
| container contracts | real selected vector-engine behavior | hosted LLM, embedding, or Pinecone |
| keyed RealAPI | selected hosted credentials, model, network, parsing | untested providers or deterministic denial paths |
| deployment identity | backend artifact and served frontend revision | application correctness by itself |

Do not merge these into a single green label called "AI tests."

## Step 1: Build A Requirement-To-Proof Matrix

Create `release-evidence.md` and map every Core capability:

| Capability | Success proof | Failure proof | Forbidden side effect |
| --- | --- | --- | --- |
| indexing | create/update/search/delete expected IDs | empty/stale index | stale evidence returned |
| RAG | allowed source IDs and grounded answer contract | no evidence/provider failure visible | unsupported facts generated |
| actions | registered schema and one confirmed mutation | clarify/deny/reject/expire/duplicate | mutation before valid confirmation |
| memory | same-conversation follow-up | new conversation/cross-owner denial | state leaks between owners |
| tenant | only allowed hit metadata enters context | unsupported filter/cross-tenant hit | forbidden evidence reaches provider |
| privacy | masked input, output, index, and chat | detector failure is explicit | raw PII persists |

Every row needs an executable test or an explicitly recorded gap. A heading in documentation is not
test evidence.

## Step 2: Separate Test Profiles

Define clear runtime profiles:

```text
test/local
  deterministic embedding
  local vector provider
  controlled generation or structured-intent doubles
  H2 or test database
  no cloud credentials

smoke
  packaged application
  controlled local providers
  representative HTTP scenarios
  no cloud credentials

openai
  real OpenAI generation/embedding as configured
  no canned-success fallback
  credentials supplied only at runtime
```

The UI and health endpoint must identify the effective posture. A deterministic profile is useful
when it is labeled deterministic. It is not a failed imitation of a live profile.

## Step 3: Lock Domain And Registration Contracts

Keep the fastest tests below the model:

```text
TicketServiceTest
  authorized and denied escalation
  transaction and status transition

ActionRegistryTest
  exact name, description, access mode, confirmation flag
  required/optional parameters
  no userId, tenantId, conversationId, or sessionId parameters

OrchestrationStateTest
  clarification before pending state
  denial before confirmation
  rejection and duplicate approval do not mutate
  confirmed action executes once
```

Use controlled structured intent in deterministic orchestration tests. Assert result type, action,
pending state, handler calls, and database counts rather than exact generated prose.

## Step 4: Lock Retrieval, RAG, Memory, And Security

Build one known evidence fixture for both tenants. The keyless suite must prove:

1. Index create, update, filtered search, delete, and clear.
2. Expected evidence IDs for known queries.
3. Explicit no-evidence result when the index is empty.
4. Bounded RAG context and visible citation IDs.
5. Same-conversation history without browser replay.
6. New-conversation and cross-owner isolation.
7. Required tenant filters and post-provider hit verification.
8. PII redaction before ordinary persistence, index, provider input, response, and chat storage.

Capture provider arguments where necessary. A generated refusal does not prove that forbidden
evidence or raw PII was absent from the provider request.

## Step 5: Make Failure States First-Class

Add this intentional failure matrix:

| Failure | Expected result |
| --- | --- |
| vector index empty | explicit no-evidence result |
| generation provider unavailable | visible provider/configuration error |
| malformed structured output | parse/contract failure, no action |
| action parameter missing | clarification, no pending complete action |
| action unauthorized | denied before confirmation |
| pending action rejected/expired/already consumed | no mutation |
| conversation ID changed | prior target and pending state unavailable |
| access policy missing/throws | fail closed |
| required metadata filter unsupported | stop, no unfiltered retry |
| PII processing cannot be proved | reject, quarantine, or `NEVER_PERSIST` |

Do not make these tests pass by adding message keyword rules or deterministic success responses to a
profile labeled live.

## Step 6: Run The Clean Application Build

From the standalone course project:

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

Do not use `-DskipTests` or `-Dmaven.test.skip`. Save the Surefire/Failsafe reports, not only the
last terminal line.

For framework maintainers validating the source repository, the automatic `Framework Build`
workflow separately runs release guards, the Java 21 framework reactor, integration-suite test
compilation, a minimal consumer compile, the real-app reactor, packaged boots, deterministic HTTP
scenarios, and container vector contracts. A framework build is valuable upstream proof; it does
not replace your application's own test gate.

## Step 7: Start The Packaged Artifact

Download the local model and run the packaged smoke gate, not the IDE classpath:

```bash
./scripts/download-onnx-model.sh
COURSE_SMOKE_USE_EXISTING_JAR=true ./scripts/smoke-packaged.sh
jq . target/course-release-evidence/packaged-smoke-summary.json
```

The script starts `ai-fabric-course-support-assistant-*.jar` with the `local` profile on an isolated
port, waits for health, and executes representative HTTP scenarios:

```text
seed/index known articles
search and inspect allowed evidence
ask a grounded question
request escalation
reject once
request again and confirm
continue with a short follow-up
attempt cross-tenant retrieval/action
submit representative PII
```

Assert HTTP status, structured result type, evidence IDs, pending transitions, mutation counts, and
sanitized fields. A Spring `Started` marker proves startup wiring only; the scenario calls prove
application behavior.

Stop the process in test cleanup and retain its logs without secrets or raw PII.

## Step 8: Understand The Upstream Docker Gate

When your release depends on a managed vector adapter, run that adapter's real-engine contract.
AI Fabric's source CI uses:

```bash
.github/scripts/run-vector-container-contracts.sh
```

The current contract runs Qdrant REST, Qdrant gRPC, Weaviate, and Milvus through Docker and
Testcontainers. It covers shared lifecycle behavior such as storage, search, metadata filtering,
scan/admin operations, counts, deletion, and clearing where supported.

Docker does not reproduce hosted OpenAI, Cohere, Anthropic, Gemini, or Pinecone. Do not label vector
container results as hosted-provider proof.

The Core application can complete with the local provider. Run Docker only when the provider under
release requires that evidence.

## Step 9: Run A Keyed Provider Smoke Deliberately

When an OpenAI path is part of the release claim, supply secrets at runtime:

```bash
OPENAI_API_KEY=<set-locally> \
OPENAI_MODEL=gpt-4o-mini \
./mvnw --batch-mode --no-transfer-progress spring-boot:run \
  -Dspring-boot.run.profiles=openai
```

Run at least:

1. grounded question with expected allowed evidence;
2. short follow-up using the same backend conversation;
3. action request, confirmation, and result projection;
4. no-evidence request;
5. provider-unavailable or invalid-model failure in a non-production test environment.

Record provider, model, profile, source commit, tests considered, successes, failures, skips, and
the exact command shape without the key.

AI Fabric's full RealAPI integration and provider-matrix workflows are manually triggered because
they need credentials, hosted resources, quota, and time. A conditional row that did not run is not
a pass.

## Step 10: Prove Live Failure Visibility

In a protected test profile, select an unavailable provider or invalid model while generation is
enabled.

Expected proof:

```text
provider/configuration error is visible
response is not labeled successful live AI
no canned answer replaces the provider result
no write action executes
health reports the configured/effective posture honestly
```

Fallback can be an explicit product feature only when provider transition, policy, user-visible
posture, and tests are designed for it. A hidden fallback that keeps a demo green is a release
failure.

## Step 11: Embed Build Identity

Expose a safe health payload:

```json
{
  "status": "UP",
  "service": "ai-fabric-course-support-assistant",
  "version": "0.1.0-SNAPSHOT",
  "aiFabricVersion": "0.4.0",
  "commit": "candidate-source-sha",
  "branch": "main",
  "builtAt": "build timestamp",
  "checkpoint": "course-0.4.0-06-tested-solution",
  "provider": {
    "mode": "live-openai",
    "generationEnabled": true,
    "generation": "openai",
    "embedding": "onnx",
    "vector": "lucene",
    "fallbackEnabled": false
  }
}
```

Derive build values from the source revision being built. Do not manually pin a commit environment
value that can survive later deployments. Never include credentials.

Health proves artifact identity and configured posture. It does not prove the index is populated,
actions work, tenant policy is correct, or provider responses are high quality.

## Step 12: Verify Frontend And Backend Independently

If you deploy a UI:

1. Fetch public HTML.
2. Identify the hashed JavaScript asset it references.
3. Fetch that asset and verify the expected route, endpoint, or release marker.
4. Call backend health directly and compare its source commit.
5. Run the public browser scenario and inspect the network response.

A current backend commit does not prove the browser received the current bundle. A current bundle
does not prove the backend action or provider behavior.

Use Playwright for visible workflows and viewport checks. Keep direct HTTP assertions for backend
security and privacy boundaries.

### Optional Chat UI Release Gate

Adopt the browser matrix in the
[AI Fabric Chat UI lab](../../labs/AI_FABRIC_CHAT_UI_LAB.md): evidence, clarification,
confirmation, safe action projection, authorized history, failed reset, provider outage, malformed
contract, accessibility, and mobile framing. Pin the UI release and verify the served asset; do not
load a moving branch in a production application.

## Step 13: Assemble The Release Evidence

Store or link:

```text
release-evidence/
  deterministic-test-summary.md
  surefire-and-failsafe-reports/
  packaged-smoke-summary.md
  vector-contract-summary.md
  keyed-provider-scorecard.md
  deployed-health.json
  served-ui-asset.txt
  skipped-not-run-and-exceptions.md
```

Every skipped live test needs a reason such as missing secret, unavailable hosted index, cost
decision, disabled row, or unsupported environment. Record required exceptions explicitly with an
owner and release decision.

Do not commit API keys, raw PII, private prompts, or confidential application data into evidence.

## Step 14: Make The Release Decision

Complete this gate:

```text
[ ] deterministic vertical-slice and denial tests pass
[ ] clean application build passes with no test-skipping flags
[ ] packaged jar starts under the intended local/smoke profile
[ ] representative HTTP scenarios pass
[ ] required vector-engine contracts pass, when applicable
[ ] required keyed provider rows actually ran and passed, or an exception is recorded
[ ] provider failures remain visible
[ ] backend commit and provider posture match the candidate
[ ] served frontend asset matches the candidate, when applicable
[ ] reports, counts, skips, and exceptions are retained
```

Release only from the evidence you actually have. Do not turn optional proof into an unspoken claim.

## AI Fabric Repository Reference Commands

These commands are for framework maintainers working from the AI Fabric source repository, not for
a Maven Central application consumer:

```bash
.github/scripts/validate-framework-release-guards.sh

mvn -B -V --no-transfer-progress \
  -f ai-infrastructure-module/pom.xml \
  -Dai.vector-db.lucene.cleanup-on-close=true \
  -pl '!integration-Testing/testcontainers-support,!integration-Testing/integration-tests,!integration-Testing/relationship-query-integration-tests,!integration-Testing/chat-session-integration-tests,!integration-Testing/behavior-integration-tests' \
  install

mvn -B -V --no-transfer-progress \
  -f examples/real-apps/pom.xml \
  install

.github/scripts/smoke-boot-realapps.sh
.github/scripts/smoke-p1-realapp-scenarios.sh
```

The automatic workflow also runs vector container contracts in a separate job. Full hosted RealAPI
suites remain manual.

## Common Mistakes

| Mistake | Consequence | Correct approach |
| --- | --- | --- |
| Treating one chat as release proof | Failure and boundary paths remain unknown | Use a requirement-to-proof matrix |
| Using only mocks | Packaged wiring and providers remain unproved | Add packaged and selected real-provider evidence |
| Calling jar startup an endpoint smoke | Business scenarios were never exercised | Run direct HTTP assertions |
| Saying Docker proves OpenAI or Pinecone | Evidence class is mislabeled | Name the exact engine and contract |
| Letting live failures return canned success | Broken provider path appears healthy | Assert explicit provider failure |
| Treating skipped RealAPI rows as green | Claimed provider path did not run | Record considered/pass/fail/skip counts |
| Trusting health as complete readiness | Data, policy, and workflow can still be broken | Test each capability separately |
| Matching only backend commit | Frontend can remain stale | Inspect the served hashed asset |
| Printing secrets in evidence | Release process creates a security incident | Mask and omit secret values |
| Running with `-DskipTests` | Release bypasses its primary evidence | Run normal tests and preserve reports |

## Troubleshooting

| Symptom | Inspect |
| --- | --- |
| Clean build fails but IDE works | stale local artifacts, dependency management, Java version, and clean checkout |
| Module tests pass but jar fails | runtime dependencies, auto-configuration, profile, and packaging |
| Jar starts but scenario fails | endpoint contract, seed/readiness, provider posture, and database state |
| Deterministic test is flaky | hidden clock/randomness, shared state, nondeterministic provider, and exact prose assertions |
| Vector container contract fails | Docker, image version, payload indexes, dimensions, and provider capability |
| RealAPI reports zero tests | profile, runner selection, conditional assumptions, and credentials |
| Provider error becomes success | fallback path, exception mapping, and UI status label |
| Health commit is old | build args/source metadata and deployment source revision |
| Backend is current but UI acts old | public HTML, hashed asset, CDN/browser cache, and API base URL |
| Green job omitted a provider | matrix resolution, missing secrets, disabled row, and recorded skip reason |

## Done When

You have completed the Core track when:

- every Core capability has success, failure, and forbidden-side-effect proof;
- the deterministic suite passes from a clean application checkout;
- the packaged jar starts and representative HTTP scenarios pass;
- selected provider evidence is labeled by its actual class;
- a failed live provider remains a visible failure;
- build identity and provider posture are exposed without secrets;
- deployed frontend and backend identity are verified independently when both exist;
- reports include counts, skips, not-run rows, and release exceptions;
- you score at least 80 percent on the knowledge check.

## Continue From Here

The Production track adds provider profiles, indexing/backfill operations, RAG evaluation, managed
vector readiness, and deployment workflows. The Core vertical slice is now complete enough to
evaluate, test, and explain without pretending that one successful model response proves the
system.
