---
id: prod-08
slug: production-ready
title: Operations And Release Readiness
track: production
order: 8
durationMinutes: 70
availability: preview
courseVersion: 0.3.3-course.1-beta
frameworkVersion: 0.3.3
frameworkTag: ai-fabric-framework-v0.3.3
courseSourceTag: ai-fabric-course-v0.3.3.1
starterRef: course-0.3.3-p07-qdrant
solutionRef: course-0.3.3-p08-production-ready
requiresOpenAi: false
requiresDocker: true
optionalProviderExercises:
  - openai
sourcePaths:
  - docs/course/production/08-production-ready/notebooklm/AI_FABRIC_OPERATIONS_RELEASE_READINESS_NOTEBOOKLM_SCRIPT.md
  - ai-infrastructure-module/ai-fabric-chat-session/src/main/java/ai/fabric/chat/spi/ChatSessionStorageProvider.java
  - ai-infrastructure-module/ai-fabric-migration/src/main/java/ai/fabric/migration/repository/MigrationJobRepository.java
  - ai-infrastructure-module/ai-fabric-indexing/src/main/java/ai/fabric/repository/IndexingQueueRepository.java
theoryVideoIds:
  - operations-release-readiness
assistant:
  mode: implement
  implementationPrompt: assistant-prompt.md
  reviewPrompt: assistant-review-prompt.md
  validationStatus: passed
knowledgeCheck:
  source: knowledge-check.yml
  required: true
  passingScorePercent: 80
video:
  status: published
  generator: NotebookLM
  purpose: pre-lesson-theory
  placement: before-lab
  targetDurationMinutes: 10
  title: Proving The Exact Release Artifact
  publicUrl: https://www.youtube.com/watch?v=MrvMGlUN0fs
  transcript: notebooklm/AI_FABRIC_OPERATIONS_RELEASE_READINESS_NOTEBOOKLM_SCRIPT.md
  sourceManifest: notebooklm/source-manifest.yml
---

# Operations And Release Readiness

You have built a useful AI application. This lesson proves that the exact artifact you release can
start, identify itself, reach each required store, survive restart, clean only eligible operational
state, and reject an explicitly selected provider whose credential is missing.

Release readiness is broader than `GET /actuator/health`. A running process can still have the
wrong commit, an unavailable vector provider, volatile conversation state, stalled indexing work,
or a hidden provider fallback. You will turn those concerns into independent evidence.

## Outcome

You will:

- build a non-root container from the exact Git commit;
- inject source commit, branch, and build time without copying `.git` into the image;
- expose safe readiness for build, database, vector, session, indexing, migration, and generation;
- keep disabled optional generation distinct from a failed required provider;
- persist source data, Qdrant vectors, chat turns, migration jobs, and indexing state across restart;
- add admin-scoped release-probe and retention operations;
- prove retention does not delete application-owned source rows or reusable vector evidence;
- prove OpenAI selection without `OPENAI_API_KEY` fails visibly;
- retain required keyless and optional keyed evidence in separate machine-readable files.

## Start Here

```bash
git clone https://github.com/Loom-AI-Labs/ai-fabric-course-support-assistant.git
cd ai-fabric-course-support-assistant
git switch --detach course-0.3.3-p07-qdrant
./mvnw --batch-mode --no-transfer-progress clean verify
./scripts/download-onnx-model.sh
```

Docker must be running. The required path uses ONNX, H2, and local Docker Qdrant. It needs no API
key.

## Step 1: Classify Runtime State

Before writing a health endpoint, decide who owns each state:

```text
application source rows       durable, application-owned
Qdrant evidence               durable provider state, derived and rebuildable
chat sessions and turns       durable AI workflow state
migration and indexing rows   durable operational state
prompt/cache snapshots        versioned or ephemeral, never source truth
provider credentials          external secret store, never diagnostic output
```

Restart proof must cover every durable category. Cleanup may remove expired workflow and completed
operational rows, but it must preserve application source truth. This checkpoint deliberately also
preserves vector evidence during routine operational cleanup; a separate reindex procedure owns
vector replacement.

## Step 2: Add A Production-Like Profile

`application-operations.yml` uses a file-backed H2 database, graceful shutdown, and explicit
maintenance controls:

```yaml
server:
  shutdown: graceful

spring:
  datasource:
    url: jdbc:h2:file:${COURSE_DB_PATH:./data/operations/course-support};MODE=PostgreSQL;AUTO_SERVER=FALSE
  jpa:
    hibernate:
      ddl-auto: update
  lifecycle:
    timeout-per-shutdown-phase: 20s

course:
  release:
    runtime-mode: production-keyless
  operations:
    maintenance-enabled: ${COURSE_OPERATIONS_MAINTENANCE_ENABLED:true}
    release-probes-enabled: ${COURSE_RELEASE_PROBES_ENABLED:true}
    completed-record-retention: ${COURSE_COMPLETED_RECORD_RETENTION:PT168H}
    conversation-retention: ${COURSE_CONVERSATION_RETENTION:PT168H}
```

The profile is production-like evidence, not a recommendation to use H2 for a real multi-instance
deployment. Replace H2 with your application database while retaining the ownership and readiness
contract.

## Step 3: Expose Independent Readiness

Add `GET /api/demo/operations/readiness`. Return a stable component map rather than one ambiguous
boolean:

```json
{
  "checkpoint": "course-0.3.3-p08-production-ready",
  "status": "READY",
  "components": {
    "build": { "status": "UP", "required": true },
    "database": { "status": "UP", "required": true },
    "vector": { "status": "UP", "required": true },
    "sessions": { "status": "UP", "required": true },
    "indexing": { "status": "UP", "required": true },
    "migration": { "status": "UP", "required": true },
    "generationProvider": { "status": "DISABLED", "required": false }
  }
}
```

Each required component catches its own probe failure and reports a stable public error code. One
broken store must not erase the status of the others. Diagnostics may identify provider type,
transport, safe counts, and source revision. They must not include API keys, database passwords,
raw prompts, PII, or full provider exception details.

`DISABLED` is healthy only because generation is optional in this keyless runtime. If generation is
enabled with OpenAI selected, it becomes required. Missing credentials then produce `DOWN` or,
through AI Fabric's startup validator, prevent the application from starting.

## Step 4: Build A Source-Identified Container

The multi-stage `Dockerfile`:

- runs `clean verify`; it never uses `-DskipTests`;
- downloads the ONNX model during the build;
- requires `SOURCE_COMMIT` and `BUILD_TIME` build arguments;
- records the commit in `org.opencontainers.image.revision`;
- passes source identity to `/api/demo/health`;
- runs as a dedicated non-root user;
- includes only the JAR and required ONNX assets in the runtime image.

Build it manually:

```bash
docker build \
  --build-arg SOURCE_COMMIT="$(git rev-parse HEAD)" \
  --build-arg SOURCE_BRANCH="$(git branch --show-current)" \
  --build-arg BUILD_TIME="$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  -t ai-fabric-course-support-assistant:prod08 .
```

The Docker build context excludes `.git`. Tests receive the explicit source commit, while runtime
health reads the same immutable value. A developer's working tree is not a deployment identity.

## Step 5: Start Durable Application And Vector Storage

```bash
export SOURCE_COMMIT="$(git rev-parse HEAD)"
export SOURCE_BRANCH="$(git branch --show-current)"
export BUILD_TIME="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
docker compose -f compose.release.yml up --build -d
curl --fail http://localhost:8080/actuator/health
curl -s http://localhost:8080/api/demo/health | jq
curl -s http://localhost:8080/api/demo/operations/readiness | jq
```

`compose.release.yml` pins Qdrant `v1.16.1` and mounts separate named volumes for H2 and Qdrant.
The application uses the canonical `COURSE_RELEASE_RUNTIME_MODE` property override so the selected
Qdrant profile cannot mislabel the combined release posture.

Verify that the OCI revision, `/api/demo/health.commit`, and `SOURCE_COMMIT` are equal. Actuator
proves process health; operations readiness proves the application dependencies and state contract.

## Step 6: Create Honest Persistence Evidence

Seed source rows and run migration through the existing admin boundary. Then call:

```bash
curl -s -X POST http://localhost:8080/api/admin/operations/release-probes \
  -H 'Authorization: Bearer course-alex-local-token' | jq
```

The endpoint stores one backend chat turn and returns:

```json
{
  "storedTurns": 1,
  "modelInvoked": false
}
```

The assistant text says that a persistence probe was recorded and no model was invoked. This is
operational evidence, not fake intelligence. It must never be presented as a generated answer or a
live-provider test.

## Step 7: Restart And Prove Durability

Restart only the application container, retaining both volumes. After restart, assert:

- `startedAt` changed while source commit did not;
- nine source articles remain;
- nine Qdrant vectors remain;
- the release conversation and its exact turn remain;
- completed migration and indexing records remain;
- both tenant golden RAG suites still pass.

State count alone is insufficient. The golden scorecard proves that retained vectors still satisfy
expected IDs, forbidden IDs, tenant boundaries, and current source fragments.

## Step 8: Apply A Bounded Retention Policy

`POST /api/admin/operations/retention/cleanup` requires `migration:admin` and explicit maintenance
enablement. It removes only:

- expired course-user chat sessions;
- completed migration jobs older than the configured cutoff;
- completed, failed, or dead-letter indexing rows older than the cutoff.

It reports before, after, and removed counts plus source/vector preservation booleans. It does not
remove knowledge articles, policies, accounts, tickets, or vector evidence.

The automated gate uses zero-duration cutoffs only in its disposable environment. Production
retention must use reviewed durations and scheduled execution. This endpoint is an application
example; your production scheduler, authorization, audit, and backup policy remain application and
platform responsibilities.

## Step 9: Prove Missing Required Credentials Fail

Run the built image with `SPRING_PROFILES_ACTIVE=openai` and no key. AI Fabric's provider validator
must stop startup with:

```text
OpenAI API key is required when OpenAI is selected.
```

The process must return non-zero. Do not catch this error and start with a local response provider.
The keyless release remains valid because generation is disabled there; the OpenAI profile is a
different, explicitly selected contract.

## Required Verification

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
./scripts/download-onnx-model.sh
COURSE_SMOKE_USE_EXISTING_JAR=true ./scripts/smoke-packaged.sh
COURSE_SMOKE_USE_EXISTING_JAR=true ./scripts/smoke-qdrant.sh
./scripts/smoke-release.sh
./scripts/smoke-openai-optional.sh
jq . target/course-release-evidence/release-keyless-summary.json
jq . target/course-release-evidence/openai-keyed-summary.json
```

Expected checkpoint: `course-0.3.3-p08-production-ready`. The Java suite contains 71 tests.
`release-keyless-summary.json` must report `PASS` and the exact source commit.
`openai-keyed-summary.json` may report `NOT_RUN` when no key was supplied; that is honest evidence,
not a skipped required gate.

## Optional Live OpenAI Evidence

Provide the key only through your shell, IDE secret field, CI secret, or deployment secret store:

```bash
export OPENAI_API_KEY='<set locally; never commit>'
export AI_ORCHESTRATION_MODEL='gpt-4o-mini'
export AI_GENERATION_MODEL='gpt-4o-mini'
./scripts/smoke-openai-optional.sh
jq . target/course-release-evidence/openai-keyed-summary.json
```

The script starts the packaged `openai` profile, indexes real ONNX evidence, calls the real provider,
and records provider/model posture plus structured result status. It does not store the key or
borrow success from deterministic test providers. A keyed failure is `FAIL`, not a local fallback.

## Intentional Failures

1. Omit `SOURCE_COMMIT` during image build. The build must fail before producing an unidentified
   image.
2. Stop Qdrant. The vector component reports `DOWN`; no Lucene fallback makes readiness green.
3. Start the OpenAI profile without `OPENAI_API_KEY`. Startup fails with the configuration error.
4. Call cleanup without admin scope. The request is denied and state is unchanged.
5. Remove the persistent application volume and restart. The gate detects missing source, chat, and
   operational state instead of claiming normal restart survival.

## Done When

- all 71 tests pass without skipping;
- packaged Lucene and Docker Qdrant gates remain green;
- the image runs as non-root and reports the exact OCI/source revision;
- required components are independently `UP` and optional generation is honestly `DISABLED`;
- source, vector, session, migration, and indexing state survive an application restart;
- deterministic RAG quality passes before and after restart;
- cleanup removes eligible operational records while preserving source and vectors;
- selected OpenAI without a key fails startup visibly;
- optional OpenAI evidence is a separate `PASS`, `FAIL`, or `NOT_RUN` artifact;
- CI retains test and release evidence for the source commit.

## Reset And Cleanup

```bash
docker compose -f compose.release.yml down -v
./scripts/reset-course.sh
git switch --detach course-0.3.3-p07-qdrant
```

`down -v` removes only the named course volumes from this compose project. Do not run destructive
database or vector cleanup against a shared environment.

## Troubleshooting

**Readiness says build is DOWN:** inspect the image revision label and `SOURCE_COMMIT`. Do not replace
an unknown revision with a handwritten version string.

**Health says `qdrant-local` instead of `production-keyless`:** use the canonical
`COURSE_RELEASE_RUNTIME_MODE` environment variable. A generic alias in base YAML can lose to a
profile-specific property.

**Tests pass locally but fail in Docker:** `.git` is absent by design. Supply source metadata as
build arguments to the test and runtime stages.

**H2 or Qdrant state disappears:** verify named volumes and paths. Container restart proof is invalid
when storage is inside the disposable writable layer.

**Cleanup reports zero removed:** records are newer than the configured retention cutoffs. Use a
disposable environment for zero-duration tests; do not weaken production retention to satisfy a
smoke check.

**OpenAI evidence says `NOT_RUN`:** no key was supplied. The required release is still complete, but
you must not claim live-provider verification.

## Production Track Complete

You have now configured provider purpose routing, orchestration modes, application prompt overlays,
initial migration, continuous Data Sync, deterministic RAG quality, Qdrant, and release operations
in one continuing Spring Boot application. The next course section studies deployed applications
that combine these capabilities in different domains.
