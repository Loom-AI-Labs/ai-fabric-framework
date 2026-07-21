# NotebookLM Single-Source Production Script: Testing And Shipping AI Workflows

## Generator Instructions - Do Not Narrate

Use this file as the only source for the video. Do not supplement it with general testing-pyramid,
DevOps, provider, or deployment knowledge. Do not ask for or rely on another source.

Create a structured technical explainer titled **Testing And Shipping AI Fabric Workflows: From
Deterministic Contract To Deployed Proof**. Follow the fourteen scenes in order. Use every
**Visual** block as production direction and every **Narration** block as the spoken message.
Natural transitions are allowed, but do not omit, replace, or contradict the technical content.

This is the theoretical introduction to CORE-07, not a terminal walkthrough. Keep AI Fabric's
current test layers, automatic and manual GitHub workflows, packaged-app smoke path, provider
evidence, and deployment metadata as the subject. Do not invent CI jobs, automatic live-provider
coverage, benchmarks, endpoints, reports, test output, release status, or guarantees. Apply the
final accuracy guardrails to the complete output.

## Production Direction

- Title: **Testing And Shipping AI Fabric Workflows: From Deterministic Contract To Deployed Proof**
- Target duration: 12-15 minutes.
- Audience: Java and Spring Boot developers who have built the complete Support Knowledge Assistant
  vertical slice.
- Voice: direct, practical, calm, and technically precise. Address the developer as **you**.
- Primary objective: separate deterministic behavior, packaged runtime, container-provider parity,
  live hosted-provider behavior, and deployed artifact identity into explicit release evidence.
- Example application: the completed Spring Boot Support Knowledge Assistant.
- Visual style: use a layered verification map, CI job cards, visible failure states, artifact
  fingerprints, and one release evidence table. Avoid celebratory green-check montages without
  showing what each check proves.

## Scene 1: A Successful Chat Is Not A Release Gate

**Visual:** Show one polished chat screenshot on the left and a five-part evidence stack on the
right.

```text
one successful response
        !=
domain contract
+ framework integration
+ packaged runtime
+ provider behavior
+ deployed artifact identity
```

**Narration:**

An AI workflow can produce an impressive answer while its security denial path, confirmation
state, persistence, provider configuration, or deployed bundle is broken.

Release proof must answer several different questions. Does your domain logic behave
deterministically? Does AI Fabric discover and orchestrate the declared capabilities correctly? Does
the packaged Spring Boot application start with the expected profile and dependencies? Do the real
providers behave as configured? Is the public deployment serving the commit you tested?

No one test answers all five.

The goal of CORE-07 is not to eliminate model variability. It is to surround variable generation
with deterministic contracts, run the variable path deliberately with real credentials, and make
every failure visible enough to block a release.

## Scene 2: Use Four Evidence Classes, Not One Test Bucket

**Visual:** Build a four-layer stack.

```text
1. deterministic no-key tests
   domain, schema, retrieval lifecycle, policy, PII, memory state

2. packaged-runtime and real-app smoke
   built jars, Spring profiles, wiring, HTTP scenario scripts

3. container provider contracts
   real vector engines under Docker/Testcontainers

4. keyed live-provider tests
   hosted LLM, embedding, and SaaS vector behavior
```

**Narration:**

AI Fabric separates four evidence classes.

Deterministic tests need no cloud key. They prove action registration, parameter validation,
confirmation transitions, tenant denial, PII redaction, chat ownership, local indexing, retrieval,
and known orchestration results.

Packaged-runtime tests prove that real application artifacts start and that representative HTTP
flows work with controlled local providers. Container contracts run the shared vector lifecycle
against real Qdrant, Weaviate, and Milvus processes.

Keyed live-provider tests call hosted generation, embedding, and SaaS vector services. They prove
credentials, model names, network behavior, response parsing, quotas, and provider-specific
semantics.

Keep reports labeled by class. A deterministic stub cannot prove OpenAI works. An OpenAI response
cannot prove a tenant denial remains deterministic.

## Scene 3: Start With Domain And Registration Tests

**Visual:** Show a narrow test pyramid base.

```text
domain service tests
  -> business thresholds, authorization, persistence outcome

action/registry tests
  -> discovered name, access mode, confirmation, public schema

orchestration tests
  -> result type and no unintended side effect
```

**Narration:**

Begin below the LLM.

Test support-domain services with ordinary JUnit: authorized and denied escalation, ticket state
changes, policy thresholds, transaction results, and concise result projection. Then load the action
registry and assert the exact metadata AI Fabric exposes: names, descriptions, access modes,
confirmation flags, required parameters, allowed values, and the absence of context-owned identity
fields.

Exercise orchestration with structured intents or controlled provider doubles. A missing parameter
must yield clarification. Authorization denial must precede confirmation. A confirmed action must
invoke the expected handler, while rejection and duplicate confirmation leave state unchanged.

These tests are fast, reproducible, and precise. They catch most unsafe changes before a token is
spent or a Docker container starts.

## Scene 4: Prove Retrieval And RAG In Deterministic Profiles

**Visual:** Show a no-key retrieval laboratory.

```text
known articles
  -> deterministic/local embedding
  -> local vector provider
  -> query
  -> expected evidence IDs and scores/order constraints
  -> bounded context and controlled generated-result contract
```

**Narration:**

Retrieval quality has a deterministic core.

Index a small, known evidence set using a deterministic or local embedding provider and a local
vector implementation such as Lucene. Verify create, update, search, filtered search, delete, clear,
and stale-result behavior. For each course question, assert the expected evidence ID, allowed tenant,
and absence of forbidden records.

Test RAG context construction separately from live generation. Verify document limits, context
limits, citations, no-evidence behavior, and whether the selected vector space was allowed. Use a
controlled generation provider when you need to prove orchestration envelopes or prompt variables
without claiming model quality.

Real-provider evaluation comes later. The deterministic suite protects the evidence and policy
contract that the live model is allowed to see.

## Scene 5: Turn Failure States Into First-Class Assertions

**Visual:** Show a vertical-slice failure matrix.

| Capability | Required failure proof |
| --- | --- |
| Retrieval | empty index, no eligible vector space, provider search failure |
| Generation | provider unavailable, malformed structured output, timeout/error visible |
| Actions | missing param, denied, rejected, expired, duplicate confirmation, handler error |
| Memory | new conversation, cross-owner denial, closed or non-persisted turn |
| Tenant security | missing policy, cross-tenant evidence, unsupported required filter |
| Privacy | input PII, output PII, protected-original posture, no raw persistence |

**Narration:**

The red paths are release behavior, not troubleshooting afterthoughts.

For every capability, write the expected stopped result and the forbidden side effect. No eligible
evidence should produce an explicit no-evidence response, not invented facts. An unavailable
generation provider should remain a provider failure, not a canned answer presented as live AI.

Missing action parameters must not mutate state. Denial, rejection, expiry, and duplicate approval
must remain non-executing. A new conversation must not inherit an old pending action. Cross-tenant
records must never enter context. Matched PII must be absent from ordinary provider input,
persistence, and output.

Tests that assert only HTTP 200 or `success=true` miss the behavior that determines whether the
workflow is safe to ship.

## Scene 6: Build The Framework And A Real Consumer From Clean State

**Visual:** Show the automatic `Framework Build` workflow.

```text
release guards
  -> Java 21 framework reactor install with tests
  -> integration-suite test compilation
  -> minimal Spring Boot consumer compile
  -> real-app suite install
```

Add a visible **No test-skipping flags** guard.

**Narration:**

The repository's automatic `Framework Build` workflow runs on pushes to main, pull requests to
main, and manual dispatch.

It begins with release guard scripts that validate the provider registry, prohibit test-skipping
drift in workflows and release documentation, detect obvious production stubs, and check vector
readiness policy. The main job uses Java 21 and installs the framework reactor with tests while
excluding restored integration suites from that reactor invocation. It then test-compiles those
integration suites against the built modules.

Next, CI compiles the minimal Spring Boot consumer and installs the real-app reactor. This catches a
class of failures a module test can miss: published artifact coordinates, transitive dependencies,
auto-configuration, and consumer-facing API compatibility.

Run from a clean checkout whenever possible. A locally installed stale snapshot can make a broken
consumer appear healthy.

## Scene 7: Start The Packaged Applications, Then Exercise Scenarios

**Visual:** Split packaged verification into two stages.

```text
Stage A: smoke-boot-realapps.sh
  packaged boot jars + smoke profile
  -> deterministic embedding + local/in-memory dependencies
  -> wait for Spring "Started" marker

Stage B: deterministic scenario scripts
  -> start selected apps
  -> call HTTP APIs
  -> assert state, retrieval, deletion, action, and privacy behavior
```

**Narration:**

An application-context test is not the same as starting the packaged artifact.

AI Fabric's real-app smoke script locates each built boot jar, launches it with the offline smoke
profile on an isolated port, and waits for the Spring application-start marker. The profile uses
controlled local dependencies and no external API keys. This proves packaging and startup wiring,
not live intelligence.

Separate deterministic scenario scripts go further. They start representative real apps, call
their HTTP boundaries, and assert data synchronization, vector deletion, RAG evidence, behavior
analysis contracts, relationship queries, action authorization, confirmation, and chat action
flows.

Label both results precisely. "Jar started" does not mean every endpoint passed. "Offline scenario
passed" does not mean a hosted model answered.

## Scene 8: Use Docker For Real Vector-Engine Contracts

**Visual:** Show one shared `VectorDatabaseService` contract fanning out to four rows.

```text
Qdrant REST
Qdrant gRPC
Weaviate
Milvus
```

Show operations: store, update, exact fetch where supported, similarity search, metadata filter,
scan/admin lifecycle, count, delete, and clear.

**Narration:**

Docker can provide stronger vector evidence than an in-memory double.

The automatic vector-provider job runs a shared lifecycle contract through Testcontainers against
Qdrant REST, Qdrant gRPC, Weaviate, and Milvus. It validates the AI Fabric provider contract against
real server processes and uploads Surefire and Failsafe reports.

This is where metadata-filter behavior, lifecycle semantics, scan support, delete visibility, and
provider-specific setup become testable without hosted credentials. Image versions can be
overridden locally to evaluate an upgrade before changing the release baseline.

Docker is not proof for every provider. Pinecone is hosted, and LLM and embedding vendors are hosted
services. Their credentials, model availability, limits, and network behavior need separate live
tests.

## Scene 9: Run Hosted Providers In Explicit Keyed Workflows

**Visual:** Show two manually triggered workflows.

```text
Framework Integration Tests (Manual Trigger)
  choose module, LLM, embedding, vector DB, persistence DB, test chunk

Framework Provider Matrix Suite
  release-candidate rows based on available secrets
  provider scorecards + optional deployed readiness smoke
```

**Narration:**

The full RealAPI provider matrix does not run automatically on every pull request. It is separated
because it needs credentials, hosted services, cost, quota, and longer execution time.

The manual integration workflow lets a maintainer select AI Fabric, relationship query,
chat-session, or behavior suites; choose generation, embedding, vector, and persistence providers;
and select a test chunk. It builds the framework first, configures providers, runs the appropriate
RealAPI runner, and uploads reports and scorecards.

The provider matrix workflow assembles release-candidate rows from available secrets and includes
container vector contracts. Pinecone runs in its live gate when its key and location are configured.

A skipped row is not a pass. Record which provider, model, vector backend, and test count actually
ran, and why any expected row was absent.

## Scene 10: Never Hide A Live AI Failure With A Fake Success

**Visual:** Show an unavailable OpenAI provider branching to an explicit failure card.

```text
generation enabled + selected provider unavailable
  -> visible provider/configuration error
  -> no deterministic "helpful" answer labeled as AI
```

Show local and live profiles as explicit parallel configurations, not a hidden fallback chain.

**Narration:**

Provider posture must be explicit.

A local deterministic profile is valuable when it is selected and labeled as local. A live OpenAI
profile is valuable when it actually calls OpenAI. The dangerous behavior is silently replacing a
failed live call with a canned or local answer while the UI still claims provider-backed
intelligence.

Tests should configure an unavailable or invalid provider while generation is enabled and assert
that the error remains visible. Do the same for embedding dimension mismatches, missing vector
indexes, unsupported filters, and malformed structured output.

Fallback can be a product feature only when its policy, provider transition, user-visible posture,
and tests are explicit. It must never exist merely to keep a demo green.

## Scene 11: Embed Build Identity In The Artifact

**Visual:** Show source control flowing into the image and health response.

```text
Git commit + branch + build time + app version + AI Fabric version
  -> Docker build arguments / generated build metadata
  -> packaged application
  -> /api/demo/health
```

Show a warning over manually pinned environment values that can outlive the deployed commit.

**Narration:**

Deployment proof starts with artifact identity.

The real-app support layer can expose application status, application version, AI Fabric version,
source commit, branch, build time, and provider posture through `/api/demo/health`. Docker builds
should derive those values from the source revision being built, rather than rely on a manually
pinned environment variable that can remain unchanged across deployments.

Call the backend health endpoint after deployment and compare its commit with the intended release.
Also inspect configured provider posture. A healthy process running an old commit is not a successful
deployment. A current commit with generation disabled is not evidence of a live-LLM scenario.

Keep secrets out of health responses and build logs. Expose provider names and modes, not API keys.

## Scene 12: Verify The Served Frontend Separately

**Visual:** Show one domain with two independently deployed artifacts.

```text
public HTML
  -> referenced hashed JavaScript asset
  -> API base URL and feature code inside served bundle

backend
  -> /api/demo/health commit and provider posture
```

**Narration:**

A current backend does not prove the browser is running the current frontend.

After UI deployment, fetch the public HTML, identify the JavaScript asset it references, and inspect
that served bundle for the expected route, endpoint, release marker, or behavior. Browser caches,
CDN caches, and a stale generated bundle can preserve old code even when wrapper metadata changed.

Then call the backend health endpoint directly. Finally, run the user scenario through the public
UI and inspect the network response, not only the rendered card.

These are three different checks: the frontend asset, the backend artifact, and the end-to-end
interaction. Treat them as separate release evidence, especially when frontend and backend deploy
through different repositories or automation.

## Scene 13: Preserve Reports, Counts, And Skip Reasons

**Visual:** Show an evidence bundle.

```text
release evidence/
  unit-and-module-reports/
  vector-provider-contract-reports/
  provider-matrix-scorecards/
  packaged-smoke-summary/
  deployed-health.json
  served-ui-asset.txt
  skipped-or-not-run.md
```

**Narration:**

A release decision should be reconstructable after the terminal scrollback disappears.

Upload Surefire and Failsafe reports, vector contract artifacts, and provider scorecards. Record the
number of tests considered, succeeded, failed, and skipped. Keep the selected provider names, model
names, vector backend, profile, and source commit beside the results.

When a live test does not run, state why: missing secret, disabled matrix row, unavailable hosted
index, cost decision, or unsupported environment. Do not allow a conditional test to disappear and
leave a green job that readers interpret as provider proof.

Evidence should also record the exact command or workflow input needed to reproduce the run without
printing credentials.

## Scene 14: Make A Release Decision From A Proof Matrix

**Visual:** End with an ownership and release table.

| Proof | What it establishes | What it does not establish |
| --- | --- | --- |
| Unit/module tests | Deterministic contracts and failure states | Packaged startup or hosted provider behavior |
| Consumer compile | Public artifact/API compatibility | Runtime correctness |
| Packaged smoke | Jar and profile wiring | Live provider intelligence |
| Deterministic HTTP scenarios | Cross-module app behavior | Hosted model quality |
| Container vector contracts | Real engine adapter parity | Pinecone or LLM behavior |
| Keyed RealAPI suite | Selected hosted provider path | Every untested provider/model |
| Backend build metadata | Deployed server identity | Frontend asset identity |
| Served-bundle check | Deployed UI identity | Backend behavior by itself |

```text
Release only when:
[ ] deterministic vertical-slice and denial tests pass
[ ] framework, consumer, and real-app builds pass from clean state
[ ] packaged applications start and selected HTTP scenarios pass
[ ] required vector-provider contracts pass
[ ] required keyed provider rows ran and passed, or an explicit release exception exists
[ ] provider failures remain visible
[ ] backend commit, provider posture, and served frontend asset match the candidate
[ ] reports, counts, and skip reasons are retained
```

**Narration:**

Shipping an AI Fabric workflow is a proof-composition exercise.

Deterministic tests establish policy and state-machine behavior. Packaged and real-app tests prove
runtime assembly. Docker proves selected vector engines. Keyed suites prove selected hosted
providers. Build metadata and served-bundle inspection prove what users actually received.

You have completed the Core course when the Support Knowledge Assistant can retrieve approved
evidence, generate a grounded answer, execute a confirmed action, remember the authorized
conversation, deny tenant leakage, process PII, and expose failures honestly under this release
matrix.

That is stronger evidence than a successful chat screenshot because every claim names its owner,
test class, artifact, provider posture, and limit.

## Accuracy Guardrails - Do Not Narrate

1. Do not say one successful UI chat, unit test, or provider call proves release readiness.
2. Do not describe deterministic doubles as live LLM, embedding, or hosted-vector tests.
3. Do not describe a packaged jar startup marker as complete endpoint or scenario coverage.
4. Do not say the full RealAPI provider matrix runs automatically on every pull request. The current
   full integration and provider-matrix workflows are manually triggered.
5. Do not say Docker reproduces hosted LLM, embedding, or Pinecone services.
6. Do not invent automatic provider rows, model versions, test counts, scorecard results, or release
   status.
7. Do not use test-skipping flags as the normal course or release command.
8. Do not hide an unavailable live provider behind a deterministic answer while labeling the result
   live AI.
9. Do not claim every provider supports identical vector filtering or lifecycle behavior. Use the
   shared contract and provider-specific live gates.
10. Do not put API keys in source control, health responses, screenshots, logs, or course artifacts.
11. Do not say matching backend commit proves the frontend bundle is current, or the reverse.
12. Do not treat a skipped or conditionally absent live-provider test as a pass.
13. Do not claim health alone proves RAG data readiness, action behavior, privacy, or model quality.
14. Do not omit failed and not-run evidence from the release record.
