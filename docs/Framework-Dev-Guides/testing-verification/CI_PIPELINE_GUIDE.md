# Framework CI Pipeline Guide

This guide explains what runs in the current AI Fabric framework GitHub Actions CI setup.

The source of truth is the workflow directory:

- `.github/workflows/framework-verify.yml`
- `.github/workflows/integration-tests-manual.yml`
- `.github/workflows/provider-suite-keys-only.yml`
- `.github/workflows/provider-configuration-check.yml`
- `.github/workflows/maven-central-release.yml`

## 1. Automatic PR / Push CI

### Workflow

`Framework Build`

File:

- `.github/workflows/framework-verify.yml`

Triggers:

- push to `main`
- pull request targeting `main`
- manual `workflow_dispatch`

This is the default CI gate for framework code changes.

### Workflow Dependencies

| Dependency | Needed by | Notes |
| --- | --- | --- |
| GitHub runner `ubuntu-latest` | all jobs | Uses the standard hosted runner image. |
| repository checkout | all jobs | Every job starts from the current commit. |
| Python 3.x + `pyyaml` | `Provider Registry` | Required by registry/release guard validation scripts. |
| Python 3.x | `Maven Build` smoke scripts | Required by the P1 relay smoke, ecommerce-to-chat data-sync smoke, and P1 real-app scenario smoke for JSON assertions/local stub services. |
| JDK 21 / Temurin | `Maven Build`, `Vector Provider Container Contracts` | Required for the Spring Boot 4.1.x / Java 21 framework build. |
| Maven | `Maven Build` | Provided by the runner/JDK setup path. |
| Docker daemon | `Maven Build`, `Vector Provider Container Contracts` | Verified before container-backed checks. Required by Testcontainers. |
| `.github/scripts/*` guard scripts | `Provider Registry` | Must be executable and committed. |
| `examples/minimal-spring-boot` | `Maven Build` | Used as a consumer compile check. |
| `examples/real-apps` | `Maven Build` | Built, smoke-booted offline, used for the ecommerce-to-chat data-sync proof, and used for deterministic P1 product-flow smokes. |
| `ai-fabric-relay` packaged boot jar | `Maven Build` | Built by the framework reactor and exercised by the P1 packaged relay local smoke. |
| `.github/scripts/run-vector-container-contracts.sh` | `Vector Provider Container Contracts` | One-command runner for Qdrant REST/gRPC, Weaviate, and Milvus contract parity. |

### Job: Provider Registry

Runs first.

It sets up Python, installs `pyyaml`, then runs:

```bash
.github/scripts/validate-framework-release-guards.sh
```

That guard runs:

- `.github/scripts/validate-provider-registry.sh`
- `.github/scripts/validate-workflow-test-policy.sh`
- `.github/scripts/test-validate-release-doc-policy.sh`
- `.github/scripts/validate-release-doc-policy.sh`
- `.github/scripts/validate-no-production-stubs.sh`
- `.github/scripts/test-verify-vector-readiness-health.sh`

What this protects:

- provider registry entries match supported LLM and embedding providers
- workflow commands do not drift back to Maven test-skipping flags
- release docs do not show skipped-test Maven commands
- docs do not overclaim vector provider behavior
- production source does not contain obvious TODO, dummy, stub, or not-implemented markers
- vector readiness verifier behavior is covered by offline tests

### Job: Maven Build

Runs after the release guards pass.

Step dependencies:

| Step | Depends on | Fails when |
| --- | --- | --- |
| Set up JDK 21 | `actions/setup-java@v4`, Maven cache | Java 21 cannot be installed or Maven cache restore fails badly. |
| Verify Docker is available | Docker service on `ubuntu-latest` | Docker is unavailable; this blocks container-aware verification. |
| Build, test, and install framework reactor | JDK 21, Maven, all non-integration framework modules | Compile error, unit test failure, packaging failure, dependency resolution failure. |
| P1 packaged relay local smoke | executable `ai-fabric-relay` jar, `.github/scripts/smoke-p1-relay-local.sh`, free local ports, Python 3 | Relay fails to boot as a packaged jar, API-key auth fails, action forwarding/idempotency fails, retrieval forwarding fails, or generated retrieval responses are not rejected. |
| Compile restored integration test suites | Framework modules built by `-am`, integration test modules | Integration test source no longer compiles against framework APIs. |
| Compile minimal consumer example | Installed framework artifacts and example POM | A normal consumer app cannot compile. |
| Build and install real apps suite | Installed framework artifacts and real-app modules | Real application examples fail to compile, test, or package. |
| Smoke boot-test real apps | real-app artifacts and `.github/scripts/smoke-boot-realapps.sh` | Offline smoke profile startup fails. |
| Smoke data-sync between ecommerce and chat runtime | real-app artifacts, `.github/scripts/smoke-ecommerce-chat-datasync.sh`, free local ports, Python 3 | Cross-app product upsert/search/delete/search proof fails or stale vector results survive delete. |
| P1 deterministic real-app scenario smoke | real-app artifacts, `.github/scripts/smoke-p1-realapp-scenarios.sh`, free local ports, Python 3 | Product-shaped P1 flows fail: RAG quality, privacy deletion, relationship query, behavior signals, support action authorization/confirmation, migration/backfill, or chat action confirmation/interceptor behavior. |

It sets up JDK 21, confirms Docker is available, then runs the framework reactor:

```bash
mvn -B -V --no-transfer-progress -f ai-infrastructure-module/pom.xml \
  -Dai.vector-db.lucene.cleanup-on-close=true \
  -pl '!integration-Testing/testcontainers-support,!integration-Testing/integration-tests,!integration-Testing/relationship-query-integration-tests,!integration-Testing/chat-session-integration-tests,!integration-Testing/behavior-integration-tests' \
  install
.github/scripts/smoke-p1-relay-local.sh
```

This compiles, tests, packages, and installs framework modules except the restored integration-test
suites, which are handled separately. The relay smoke then starts the packaged relay jar and a local
stub internal service to prove the Customer Connector API boundary.

Then it compiles the restored integration-test suites:

```bash
mvn -B -V --no-transfer-progress -f ai-infrastructure-module/pom.xml \
  -pl integration-Testing/testcontainers-support,integration-Testing/integration-tests,integration-Testing/relationship-query-integration-tests,integration-Testing/chat-session-integration-tests,integration-Testing/behavior-integration-tests \
  -am \
  test-compile
```

Then it checks consumer/examples:

```bash
mvn -B -V --no-transfer-progress -f examples/minimal-spring-boot/pom.xml compile
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml install
.github/scripts/smoke-boot-realapps.sh
.github/scripts/smoke-ecommerce-chat-datasync.sh
.github/scripts/smoke-p1-realapp-scenarios.sh
```

So automatic CI verifies:

- release guard scripts
- framework unit and module tests
- framework packaging/install
- executable relay packaging plus local Customer Connector API smoke for action forwarding,
  idempotency, API-key auth, retrieval forwarding, and documents-only rejection
- restored integration suite compilation
- minimal consumer compilation
- real-app build/install
- offline real-app smoke boot
- deterministic ecommerce-store to chat-capabilities-demo data-sync upsert, runtime vector search,
  delete propagation, and stale-result cache eviction
- deterministic P1 real-app product flows for RAG quality, privacy/governance deletion,
  relationship query, behavior analysis, migration/backfill, and chat action
  confirmation/interceptor behavior

It does not run the full RealAPI provider matrix automatically on every PR.

### Job: Vector Provider Container Contracts

Runs after the release guards pass and in parallel with the main Maven build.

Step dependencies:

| Step | Depends on | Fails when |
| --- | --- | --- |
| Set up JDK 21 | `actions/setup-java@v4`, Maven cache | Java 21 cannot be installed or Maven cache restore fails badly. |
| Verify Docker is available | Docker service on `ubuntu-latest` | Docker is unavailable, so Testcontainers cannot start vector providers. |
| Run vector provider container contracts | `.github/scripts/run-vector-container-contracts.sh`, Docker, Maven, vector provider modules | Qdrant REST/gRPC, Weaviate, or Milvus fail the shared lifecycle/admin contract. |
| Upload vector contract reports | Failsafe/Surefire report output | Report upload failure; the test result is already decided by the contract step. |

Command:

```bash
.github/scripts/run-vector-container-contracts.sh
```

Equivalent Maven command:

```bash
cd ai-infrastructure-module
mvn -B -V --no-transfer-progress clean verify \
  -Pcontainer-contract-tests \
  -pl integration-Testing/vector-contract-tests \
  -am
```

This starts Docker/Testcontainers providers and runs the shared `VectorDatabaseService` lifecycle
contract against:

- Qdrant REST
- Qdrant gRPC
- Weaviate
- Milvus

For local provider-version validation, override images through environment variables:

```bash
TESTCONTAINERS_QDRANT_IMAGE=qdrant/qdrant:v1.16.1 \
TESTCONTAINERS_WEAVIATE_IMAGE=semitechnologies/weaviate:1.23.0 \
TESTCONTAINERS_MILVUS_IMAGE=milvusdb/milvus:v2.4.0 \
.github/scripts/run-vector-container-contracts.sh
```

This Docker-backed job does not replace live SaaS checks. Pinecone remains in the Pinecone live gate,
and LLM/embedding vendors remain in the RealAPI provider matrix because Docker cannot reproduce those
hosted APIs.

## 2. Manual Integration CI

### Workflow

`Framework Integration Tests (Manual Trigger)`

File:

- `.github/workflows/integration-tests-manual.yml`

Trigger:

- manual `workflow_dispatch`

Use this workflow when you want RealAPI/integration validation for selected modules and provider
combinations.

Main inputs:

- `modules`: `all`, `ai-fabric`, `relationship-query`, `chat-session`, or `behavior`
- LLM provider/key/model
- embedding provider/key/model
- vector database: `lucene`, `pinecone`, `weaviate`, `qdrant`, `milvus`, or `memory`
- persistence database: `h2` or `postgresql`
- `test_chunk`: `all`, `core`, `vector`, `intent-actions`, or `advanced`
- provider-matrix success thresholds

### Workflow Dependencies

| Dependency | Needed by | Notes |
| --- | --- | --- |
| Manual workflow inputs | all jobs | Select modules, providers, vector DB, persistence DB, timeout, logging, and test chunk. |
| Provider API keys | RealAPI jobs | Can be supplied as workflow inputs or repository secrets. Manual input values are explicitly masked. |
| `.github/actions/configure-providers` | provider setup | Configures provider environment variables and validates required credentials. |
| JDK 21 / Maven | all module jobs | Each selected module job builds the framework before running its suite. |
| Docker daemon | vector/Testcontainers runs | Required for Qdrant, Weaviate, Milvus, PostgreSQL, and other Testcontainers-backed paths. |
| Pinecone API key and location | Pinecone vector runs | Needs `PINECONE_API_KEY` plus `PINECONE_API_HOST`, or `PINECONE_INDEX_NAME` plus `PINECONE_ENVIRONMENT`. |
| Module runner scripts | selected module jobs | Each job delegates to the module's shell runner. |
| GitHub artifact storage | reports/evidence | Used for Surefire/Failsafe reports, provider scorecards, and generated evidence. |

### Jobs

The workflow can run these jobs depending on the `modules` input:

- `AI Fabric Integration Tests`
- `Relationship Query Integration Tests`
- `Chat Session Integration Tests`
- `Behavior Integration Tests`
- `Test Summary`

Each module job generally does this:

- masks workflow-dispatch API keys because manual inputs are not GitHub secrets
- sets up JDK 21
- verifies Docker availability for Testcontainers
- caches Maven dependencies
- builds the framework with:

```bash
cd ai-infrastructure-module
mvn clean install -B -V
```

- configures provider credentials with `.github/actions/configure-providers`
- validates provider availability
- runs the selected module's RealAPI/integration runner
- uploads Surefire/Failsafe reports and provider scorecards
- publishes test results to the GitHub check summary

For Qdrant, Weaviate, and Milvus vector runs, the workflow activates Testcontainers profiles. Pinecone
runs against a configured hosted index and requires either `PINECONE_API_HOST` or
`PINECONE_INDEX_NAME` plus `PINECONE_ENVIRONMENT`.

Job-specific dependencies:

| Job | Real test runner | Required provider/vector dependencies |
| --- | --- | --- |
| `AI Fabric Integration Tests` | `integration-Testing/integration-tests/run-provider-matrix-tests.sh` | selected LLM/embedding credentials; selected vector DB; Docker for Testcontainers vectors. |
| `Relationship Query Integration Tests` | `integration-Testing/relationship-query-integration-tests/run-relationship-query-realapi-tests.sh` | selected LLM/embedding credentials; vector DB when the selected scenario needs retrieval. |
| `Chat Session Integration Tests` | `integration-Testing/chat-session-integration-tests/run-chat-session-realapi-tests.sh` | selected LLM/embedding credentials; configured persistence and any retrieval/vector profile selected. |
| `Behavior Integration Tests` | `integration-Testing/behavior-integration-tests/run-behavior-realapi-tests.sh` | selected LLM/embedding credentials and behavior test fixtures. |
| `Test Summary` | downloaded artifacts from selected jobs | Depends on selected jobs completing or being skipped according to the `modules` input. |

## 3. Manual Provider Matrix CI

### Workflow

`Framework Provider Matrix Suite`

File:

- `.github/workflows/provider-suite-keys-only.yml`

Trigger:

- manual `workflow_dispatch`

Use this workflow for release-candidate provider validation.

### Workflow Dependencies

| Dependency | Needed by | Notes |
| --- | --- | --- |
| Manual workflow inputs | matrix resolution and provider rows | API keys can be passed as inputs, but repository secrets are preferred. |
| Repository secrets | matrix resolution and provider rows | `OPENAI_API_KEY`, `PINECONE_API_KEY`, `COHERE_API_KEY`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`. |
| Repository variables | Pinecone row | `PINECONE_INDEX_NAME`, `PINECONE_API_HOST`, `PINECONE_ENVIRONMENT` when not supplied as inputs. |
| Docker daemon | vector contracts and container-backed rows | Required for Qdrant REST/gRPC, Weaviate, Milvus, and Testcontainers profiles. |
| JDK 21 / Maven | vector contracts and provider rows | Every provider row builds the framework first. |
| Provider runner scripts | provider rows | Main, relationship-query, chat-session, and behavior RealAPI scripts must be executable. |
| Deployed runtime URL | optional readiness smoke | Only needed when `runtime_base_url` is supplied. |

### Matrix Resolution

The `Resolve Matrix` job builds a matrix from available workflow inputs, repository secrets, and
repository variables.

Rows currently include:

- `openai-onnx-pinecone`, when OpenAI, Pinecone, and Pinecone location are configured
- `cohere-onnx-qdrant`, when Cohere is configured
- `anthropic-onnx-milvus`, when Anthropic is configured
- `gemini-onnx-weaviate`, when Gemini is configured
- `openai-onnx-lucene`, when `run_openai_lucene=true` and OpenAI is configured

### Vector Provider Container Contracts

Runs by default unless `run_vector_contracts=false`.

Dependencies:

| Dependency | Why |
| --- | --- |
| Docker | Starts provider containers through Testcontainers. |
| JDK 21 and Maven | Compiles provider modules and runs the contract suite. |
| Vector provider modules | Builds memory, Lucene, Qdrant, Weaviate, and Milvus providers through `-am`. |
| Testcontainers images | Pulls or reuses Qdrant, Weaviate, and Milvus images. |

Command:

```bash
.github/scripts/run-vector-container-contracts.sh
```

This starts Docker/Testcontainers providers and runs the shared `VectorDatabaseService` lifecycle
contract against:

- Qdrant REST
- Qdrant gRPC
- Weaviate
- Milvus

It uploads the vector contract Surefire/Failsafe reports.

### Deployed Vector Readiness Smoke

Runs only when `runtime_base_url` is supplied.

Dependencies:

| Dependency | Why |
| --- | --- |
| `RUNTIME_BASE_URL` | Base URL for the deployed AI Fabric runtime. |
| `/actuator/health/vectorProvider` or compatible readiness JSON | Endpoint/input inspected by `.github/scripts/verify-vector-readiness-health.sh`. |
| Runtime health details | Needed to expose readiness, production readiness, and fallback counters. |
| Optional `VECTOR_READINESS_ALLOW_WARN` | Allows warning state only when explicitly accepted. |

Command:

```bash
.github/scripts/verify-vector-readiness-health.sh
```

It checks the deployed runtime vector readiness response, usually:

- `/actuator/health/vectorProvider`

By default, it fails on `NOT_READY`, `WARN`, `productionReady=false`, and fallback evidence such as
metadata-filter or count fallback counters. Set `vector_readiness_allow_warn=true` only for an
explicitly accepted non-release exception.

### Provider Suite Rows

Each provider row:

1. Sets up JDK 21 and Docker.
2. Runs a full framework build with unit tests:

```bash
cd ai-infrastructure-module
mvn install -B -V
```

3. For the Pinecone row, runs direct live Pinecone provider verification:

```bash
cd ai-infrastructure-module
PINECONE_LIVE_REQUIRED=true \
mvn verify -Ppinecone-live-tests -pl victor-databases/ai-fabric-vector-pinecone -am -B -V
```

4. Runs the main provider-matrix application suite:

```bash
cd ai-infrastructure-module/integration-Testing/integration-tests
bash run-provider-matrix-tests.sh "<llm>:<embedding>:<vector_db>" "" "all"
```

5. Runs the relationship-query RealAPI suite:

```bash
cd ai-infrastructure-module/integration-Testing/relationship-query-integration-tests
bash run-relationship-query-realapi-tests.sh "<llm>:<embedding>:<vector_db>"
```

6. Runs the chat-session RealAPI suite:

```bash
cd ai-infrastructure-module/integration-Testing/chat-session-integration-tests
bash run-chat-session-realapi-tests.sh "<llm>:<embedding>:<vector_db>"
```

7. Runs the behavior RealAPI suite:

```bash
cd ai-infrastructure-module/integration-Testing/behavior-integration-tests
bash run-behavior-realapi-tests.sh "<llm>:<embedding>"
```

8. Uploads provider matrix scorecards.

Provider-row dependencies:

| Row | Required secrets/variables | Vector/runtime dependency |
| --- | --- | --- |
| `openai-onnx-pinecone` | `OPENAI_API_KEY`, `PINECONE_API_KEY`, and Pinecone host or index/environment | Hosted Pinecone index; direct Pinecone live suite runs with `PINECONE_LIVE_REQUIRED=true`. |
| `cohere-onnx-qdrant` | `COHERE_API_KEY` | Docker/Testcontainers Qdrant. |
| `anthropic-onnx-milvus` | `ANTHROPIC_API_KEY` | Docker/Testcontainers Milvus. |
| `gemini-onnx-weaviate` | `GEMINI_API_KEY` | Docker/Testcontainers Weaviate. |
| `openai-onnx-lucene` | `OPENAI_API_KEY` and `run_openai_lucene=true` | Local Lucene vector store. |

Suite order dependencies:

| Step | Depends on |
| --- | --- |
| Framework build with unit tests | JDK 21, Maven, all framework modules. |
| Pinecone provider-live suite | Pinecone row, hosted Pinecone credentials/location, strict live flag. |
| Main provider-matrix suite | Framework build, selected provider credentials, selected vector backend. |
| Relationship-query suite | Framework build, selected provider credentials, selected vector backend/profile. |
| Chat-session suite | Framework build, selected provider credentials, selected vector backend/profile. |
| Behavior suite | Framework build and selected LLM/embedding provider credentials. |
| Scorecard upload | Generated provider-matrix report directories. |

## 4. Provider Configuration Check

### Workflow

`Provider Configuration Check`

File:

- `.github/workflows/provider-configuration-check.yml`

Trigger:

- manual `workflow_dispatch`

Use this workflow to validate one LLM/embedding provider selection before running the heavier
integration suites.

### Workflow Dependencies

| Dependency | Needed by | Notes |
| --- | --- | --- |
| Provider selection inputs | provider configuration action | Selects one LLM provider and one embedding provider. |
| Provider secrets | `.github/actions/configure-providers` | Uses matching secrets for OpenAI, Anthropic, Gemini, Cohere, or Azure. |
| Azure repository variables | Azure provider checks | Uses endpoint, deployment name, embedding deployment name, and API version when Azure is selected. |
| `.github/scripts/validate-provider-availability.sh` | final validation step | Fails if the selected provider pair is unsupported or underconfigured. |

It:

- uses `.github/actions/configure-providers`
- injects available provider secrets/variables
- runs:

```bash
.github/scripts/validate-provider-availability.sh "<llm_provider>" "<embedding_provider>"
```

## 5. Maven Central Release CI

### Workflow

`Maven Central Release`

File:

- `.github/workflows/maven-central-release.yml`

Triggers:

- GitHub release publication with a tag starting `ai-fabric-framework-v`
- manual `workflow_dispatch` with a release ref/tag

The job:

1. Checks out the release ref.
2. Configures JDK 21, Maven Central credentials, and GPG signing.
3. Reads the Maven project version from `ai-infrastructure-module/pom.xml`.
4. Verifies the release tag matches `ai-fabric-framework-v<version>`.
5. Checks Maven Central to avoid re-publishing an immutable version.
6. Deploys signed artifacts with:

```bash
mvn -B -V --no-transfer-progress -f ai-infrastructure-module/pom.xml \
  -Prelease,central \
  -pl '!integration-Testing/testcontainers-support,!integration-Testing/integration-tests,!integration-Testing/relationship-query-integration-tests,!integration-Testing/chat-session-integration-tests,!integration-Testing/behavior-integration-tests' \
  deploy
```

Do not use this as the first proof that a release candidate is good. Run `Framework Build`,
`Framework Provider Matrix Suite`, and the required live-provider gates before publishing.

### Workflow Dependencies

| Dependency | Needed by | Notes |
| --- | --- | --- |
| Release tag/ref | checkout and version validation | Tag should match `ai-fabric-framework-v<project.version>`. |
| JDK 21 / Maven | version read and deploy | Release build runs on Java 21. |
| Maven Central credentials | deploy | Requires `CENTRAL_TOKEN_USERNAME` and `CENTRAL_TOKEN_PASSWORD`. |
| GPG signing secrets | deploy | Requires `MAVEN_GPG_PRIVATE_KEY` and `MAVEN_GPG_PASSPHRASE`. |
| Maven Central availability check | publish guard | Skips deploy if the immutable version already exists. |
| Prior verification evidence | release decision | This workflow publishes; it does not replace CI/provider/live verification. |

## 6. Release Candidate Checklist

Before cutting a release from a branch, run or confirm:

```bash
.github/scripts/validate-framework-release-guards.sh
```

```bash
mvn -f ai-infrastructure-module/pom.xml \
  -Dai.vector-db.lucene.cleanup-on-close=true \
  -pl '!integration-Testing/testcontainers-support,!integration-Testing/integration-tests,!integration-Testing/relationship-query-integration-tests,!integration-Testing/chat-session-integration-tests,!integration-Testing/behavior-integration-tests' \
  install
```

```bash
mvn -f ai-infrastructure-module/pom.xml \
  -pl integration-Testing/testcontainers-support,integration-Testing/integration-tests,integration-Testing/relationship-query-integration-tests,integration-Testing/chat-session-integration-tests,integration-Testing/behavior-integration-tests \
  -am \
  test-compile
```

```bash
.github/scripts/run-vector-container-contracts.sh
```

If Pinecone is part of the release claim, also run with real Pinecone credentials/index:

```bash
cd ai-infrastructure-module
PINECONE_API_KEY=... \
PINECONE_API_HOST=https://<index-host>.pinecone.io \
PINECONE_INDEX_NAME=<index-name> \
PINECONE_LIVE_REQUIRED=true \
mvn verify -Ppinecone-live-tests -pl victor-databases/ai-fabric-vector-pinecone -am
```

For release publication, confirm the GitHub Actions run has green results for:

- `Framework Build`
- `Framework Provider Matrix Suite`
- required manual RealAPI module suites
- Pinecone live verification when Pinecone is part of the claim
- deployed vector readiness smoke when validating a deployed runtime

## 7. Artifacts And Evidence

CI uploads these useful artifacts:

- Surefire/Failsafe reports from module and integration tests
- provider-matrix scorecards
- vector provider contract reports
- test execution evidence JSON/Markdown from manual integration runs

Keep these artifacts with the release candidate review. A green Maven command is useful, but the
reports and scorecards are the evidence maintainers should inspect when a provider row is flaky,
skipped, or below the configured success threshold.
