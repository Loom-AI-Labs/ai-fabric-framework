---
id: case-06
slug: live-data-sync
title: AI Fabric Live Data Sync
track: case-studies
order: 6
durationMinutes: 55
availability: published
courseVersion: 0.4.0-course.4-beta
frameworkVersion: 0.4.0
frameworkTag: ai-fabric-framework-v0.4.0
courseSourceTag: ai-fabric-course-v0.4.0.4
starterRef: course-0.4.0-p08-production-ready
solutionRef: course-0.4.0-p08-production-ready
requiresOpenAi: true
requiresDocker: false
optionalProviderExercises:
  - openai
sourcePaths:
  - examples/real-apps/ai-fabric-live-data-sync/README.md
  - examples/real-apps/ai-fabric-live-data-sync/src/main/java/com/ai/fabric/realapps/livesync/domain/SyncProduct.java
  - examples/real-apps/ai-fabric-live-data-sync/src/main/java/com/ai/fabric/realapps/livesync/domain/SyncPolicy.java
  - examples/real-apps/ai-fabric-live-data-sync/src/main/java/com/ai/fabric/realapps/livesync/domain/SyncGuide.java
  - examples/real-apps/ai-fabric-live-data-sync/src/main/java/com/ai/fabric/realapps/livesync/service/SyncProductService.java
  - examples/real-apps/ai-fabric-live-data-sync/src/main/java/com/ai/fabric/realapps/livesync/service/DemoStateService.java
  - examples/real-apps/ai-fabric-live-data-sync/src/test/java/com/ai/fabric/realapps/livesync/AnnotationSyncContractTest.java
  - examples/real-apps/ai-fabric-live-data-sync/src/test/java/com/ai/fabric/realapps/livesync/LiveDataSyncIntegrationTest.java
theoryVideoIds:
  - case-live-data-sync-walkthrough
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

# Reproduce Annotation-Driven Live Data Sync

## Start Here

This app proves that ordinary JPA entities can remain business truth while AI Fabric keeps derived
vector evidence synchronized. It uses products, policies, and troubleshooting guides so the concept
is not mistaken for one hardcoded entity adapter.

Open:

- live UI: `https://ai-fabric.dev/demos/ai-fabric-live-data-sync`
- backend health: `https://ai-fabric-live-data-sync.46.224.145.148.sslip.io/api/demo/health`
- source: `examples/real-apps/ai-fabric-live-data-sync`

## Architecture To Recognize

```text
JPA entity + @AICapable
  @AISearchable -> canonical text
  @AIContext    -> typed metadata

service create/update/delete + @AIProcess
                 -> indexing lifecycle
                 -> OpenAI embedding
                 -> Lucene vector
                 -> RAG/chat evidence
```

AI Fabric 0.4 treats annotation metadata as the authoritative sync contract. Vector-space names,
searchable fields, context fields, and create/update/delete operations must agree with the manifest
and tests.

## Step 1: Inspect The Seeded Manifest

Create an isolated workspace. Inspect its manifest and source/vector counts. Expected:

- three entity types: product, policy, guide;
- each source row has one stable logical identity;
- synchronized counts and vector counts agree;
- annotation metadata is visible as operational proof.

## Step 2: Query Current Product Evidence

Ask for the NovaBook Air battery life. Record the answer, evidence ID, source revision, and vector
revision.

## Step 3: Update The Source Entity

Edit NovaBook Air battery life to a new value. Wait for synchronization, then ask the same question.
Expected:

- the source revision increases;
- the same logical vector identity is updated rather than duplicated;
- the new value is retrievable;
- the old value is no longer returned;
- AI generation uses the updated evidence.

## Step 4: Delete Evidence At The Source

Delete a policy or guide from the source editor. Search for its distinctive phrase and ask the chat
about it. Expected: the source row and vector disappear and RAG cannot cite stale evidence.

## Step 5: Compare Entity Types

Repeat an update for a policy and a guide. Inspect how `@AISearchable` fields become text and how
`@AIContext` fields remain typed metadata. Confirm that status, revision, workspace, and record key
are metadata rather than accidental prose.

## Intentional Failure

A database row changing while the vector revision remains old is a failed sync, even if a generic
LLM answer sounds plausible. Likewise, a delete that removes the source row but leaves a retrievable
vector is a release blocker.

## Run Locally

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml \
  -pl ai-fabric-live-data-sync -am test
```

No-key smoke uses deterministic providers. For parity with the public demo:

```bash
OPENAI_ENABLED=true \
OPENAI_API_KEY="$OPENAI_API_KEY" \
OPENAI_MODEL=gpt-4o-mini \
OPENAI_EMBEDDING_MODEL=text-embedding-3-small \
OPENAI_EMBEDDING_DIMENSIONS=512 \
mvn -f examples/real-apps/ai-fabric-live-data-sync/pom.xml spring-boot:run
```

## Done When

- three annotated entity contracts are visible;
- create/update/delete counts converge;
- update preserves stable identity and removes old text;
- delete removes both source and retrievable vector evidence;
- the chat answer changes only after current evidence is available;
- annotation and lifecycle tests pass.

## Track Complete

You have now reproduced six distinct AI-enabled Spring Boot applications: commerce RAG and actions,
account resolution, behavior analysis, tenant isolation, privacy-safe indexing, and live source
synchronization. Use the Coding-Assistant track next to plan a bounded capability in your own app.
