---
id: prod-03
slug: prompt-overlays
title: Prompt Management And Application Overlays
track: production
order: 3
durationMinutes: 80
availability: preview
courseVersion: 0.3.3-course.2-beta
frameworkVersion: 0.3.3
frameworkTag: ai-fabric-framework-v0.3.3
courseSourceTag: ai-fabric-course-v0.3.3.2
starterRef: course-0.3.3-p02-modes-positions
solutionRef: course-0.3.3-p03-prompt-overlays
requiresOpenAi: false
requiresDocker: false
optionalProviderExercises:
  - openai
sourcePaths:
  - docs/course/production/03-prompt-overlays/notebooklm/AI_FABRIC_PROMPT_BUNDLES_CURATED_OVERLAYS_NOTEBOOKLM_SCRIPT.md
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/config/PromptBundleProperties.java
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/prompt/PromptTemplateResolver.java
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/prompt/ClasspathPromptTemplateStore.java
  - ai-infrastructure-module/curated/ai-fabric-curated-support/src/main/resources/prompts/rag/generation/v1-support/answer.md
theoryVideoIds:
  - prompt-bundles-curated-overlays
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
  title: Prompt Bundles, Curated Packs, And Application Overlays
  publicUrl: https://www.youtube.com/watch?v=bvKibVVbPcA
  transcript: notebooklm/AI_FABRIC_PROMPT_BUNDLES_CURATED_OVERLAYS_NOTEBOOKLM_SCRIPT.md
  sourceManifest: notebooklm/source-manifest.yml
---

# Prompt Management And Application Overlays

## Start Here

Framework defaults should improve without forcing every application to copy them. Application
language should evolve without editing a dependency JAR. AI Fabric solves this with an ordered prompt
bundle: application overlays first, curated pack next, framework base last.

In this lesson you will retain the existing `v1-course-support` follow-up classifiers, add one
complete support-answer template, verify base fallback, and expose safe version diagnostics.

> Start from `course-0.3.3-p02-modes-positions`; compare with
> `course-0.3.3-p03-prompt-overlays`. The executable checkpoint is verified. Publication still waits
> for the reviewed theory recording.

## Resolution Model

```text
resolve(family, name)
  |
  +-- prompts/<family>/v1-course-support/<name>.md  application
  +-- prompts/<family>/v1-support/<name>.md         curated support
  +-- prompts/<family>/v1/<name>.md                 framework base
  `-- fail: no template found
```

An overlay replaces one complete template key. It is not a line-by-line patch. Add only the template
keys your application must own and let all other keys fall through.

## What You Will Prove

- candidate order is `v1-course-support`, `v1-support`, `v1`;
- classifier, compound-intent, and answer templates resolve from the application;
- action selection still resolves from `v1`;
- overlay resources are packaged in the JAR;
- diagnostics return versions, never prompt text;
- deterministic tests verify invariants instead of exact generated prose.

## Prerequisites

```bash
git clone https://github.com/Loom-AI-Labs/ai-fabric-course-support-assistant.git
cd ai-fabric-course-support-assistant
git switch --detach course-0.3.3-p02-modes-positions
git switch -c lesson/prod-03-prompt-overlays
./mvnw --batch-mode --no-transfer-progress clean verify
```

The required path is keyless and does not need Docker.

## Step 1: Keep Ordered Overlay Configuration

```yaml
ai:
  prompts:
    bundle:
      overlays:
        - v1-course-support
        - v1-support
```

AI Fabric appends its base version, producing the three candidates. Order matters: the first matching
classpath template wins.

## Step 2: Add A Narrow Answer Overlay

Create:

```text
src/main/resources/prompts/rag/generation/v1-course-support/answer.md
```

The template must contain `{{query}}` and `{{context}}`. Make the evidence boundary explicit, keep
confirmed facts separate from next steps, forbid internal metadata, and forbid claims that a write
occurred unless action evidence proves it.

Do not place authorization rules only in this file. Mode policy and action authorization from
PROD-02 remain authoritative even if the prompt is ignored.

## Step 3: Test Overlay And Fallback Resolution

Use `PromptTemplateResolver` through the real Spring context:

```java
assertThat(resolver.resolve("rag/generation", "answer")
    .template().key().version()).isEqualTo("v1-course-support");

assertThat(resolver.resolve("intent-extraction/multi-step", "select-actions")
    .template().key().version()).isEqualTo("v1");
```

Also resolve the two existing follow-up templates and assert their critical behavioral rules. This
protects the reason the application owns them without coupling tests to every word.

## Step 4: Prove Packaging

Assert the classloader can load the overlay path. A source-tree test alone can pass while a packaged
JAR omits the resource.

The packaged smoke calls `GET /api/demo/prompts` and expects:

```json
{
  "candidateVersions": ["v1-course-support", "v1-support", "v1"],
  "resolvedVersions": {
    "intent-classifier": "v1-course-support",
    "compound-intent": "v1-course-support",
    "support-answer": "v1-course-support",
    "action-selector": "v1"
  }
}
```

The endpoint must not return template content. Prompts can contain security guidance, proprietary
language, or future sensitive examples and are not a public diagnostics payload.

## Intentional Failure

Temporarily rename `answer.md`, run `CoursePromptOverlayContractTest`, and observe the version fall
back to `v1-support`. The assertion must fail because this lesson expects the application override.
Restore the file before continuing.

## Optional OpenAI Observation

Supply `OPENAI_API_KEY` through the environment and compare a grounded support answer before and
after the overlay. Record it as model observation only. The deterministic contract is resolution,
packaging, placeholders, evidence boundaries, and policy enforcement.

## Verification

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
./scripts/smoke-packaged.sh
```

Expected readiness checkpoint: `course-0.3.3-p03-prompt-overlays`; capability
`promptOverlays=true`.

## Done When

- application templates resolve first;
- curated/base templates still resolve for untouched keys;
- required placeholders are present;
- prompt bodies are absent from diagnostics;
- all mode, action, memory, privacy, and packaged tests still pass;
- no prompt is treated as an authorization control.

## Reset

```bash
./scripts/reset-course.sh
git switch --detach course-0.3.3-p02-modes-positions
```

## Next Lesson

PROD-04 separates application source rows from derived semantic evidence and backfills existing
articles through a durable migration job.
