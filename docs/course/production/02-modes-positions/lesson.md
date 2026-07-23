---
id: prod-02
slug: modes-positions
title: Modes, Positions, And Orchestration Policy
track: production
order: 2
durationMinutes: 75
availability: preview
courseVersion: 0.3.3-course.1-beta
frameworkVersion: 0.3.3
frameworkTag: ai-fabric-framework-v0.3.3
courseSourceTag: ai-fabric-course-v0.3.3.1
starterRef: course-0.3.3-p01-provider-routing
solutionRef: course-0.3.3-p02-modes-positions
requiresOpenAi: false
requiresDocker: false
optionalProviderExercises:
  - openai
sourcePaths:
  - docs/course/production/02-modes-positions/notebooklm/AI_FABRIC_MODES_POSITIONS_ORCHESTRATION_POLICY_NOTEBOOKLM_SCRIPT.md
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/config/OrchestrationProperties.java
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/orchestration/OrchestrationContext.java
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/orchestration/pipeline/steps/OrchestrationPolicyResolutionStep.java
  - examples/real-apps/chat-capabilities-demo/src/main/java/com/ai/fabric/realapps/chat/web/CommerceModeResolver.java
theoryVideoIds:
  - modes-positions-orchestration-policy
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
  title: Modes, Positions, And Orchestration Policy
  publicUrl: https://www.youtube.com/watch?v=G0WvJ1PQj0s
  transcript: notebooklm/AI_FABRIC_MODES_POSITIONS_ORCHESTRATION_POLICY_NOTEBOOKLM_SCRIPT.md
  sourceManifest: notebooklm/source-manifest.yml
---

# Modes, Positions, And Orchestration Policy

## Start Here

You already route LLM work by purpose. Now you will control which AI capabilities are available for
each application surface without allowing a browser to invent privileges.

You will add two server-owned modes:

- `support_assistant`: retrieval is enabled and mutating actions are disabled;
- `support_resolver`: retrieval and governed actions are enabled.

The application maps `knowledge` and `ticket` positions to those modes. AI Fabric Core then resolves
and enforces the selected capability bundle. Position mapping remains application-owned because a UI
position describes product context; it is not authorization.

> Start from `course-0.3.3-p01-provider-routing`. The verified solution is
> `course-0.3.3-p02-modes-positions`. The lesson remains preview until its theory recording is
> reviewed and published.

## What You Will Prove

1. A position and a mode are different signals.
2. Explicit approved mode selection takes precedence over position mapping.
3. Unknown positions fail at the application boundary when strict position routing is enabled.
4. Unknown modes reach Core and fail under `strict-mode-routing`.
5. A retrieval-only mode cannot execute a write even when intent extraction proposes one.
6. The established no-option API remains action-capable for Core-course compatibility.

## Request Flow

```text
POST /api/assistant/orchestrate
  message + optional position/mode
             |
             v
SupportModeResolver
  explicit mode? keep it for Core validation
  known position? map it
  no hint? use server default
             |
             v
OrchestrationContext(position, mode, trusted identity)
             |
             v
OrchestrationPolicyResolutionStep
  allowlist + strict mode validation + capability bundle
             |
             v
retrieval / clarification / governed confirmation
```

The resolver never authorizes an action. Authentication, tenant policy, action access, typed
parameters, confirmation, and execution remain server controls.

## Prerequisites

```bash
git clone https://github.com/Loom-AI-Labs/ai-fabric-course-support-assistant.git
cd ai-fabric-course-support-assistant
git switch --detach course-0.3.3-p01-provider-routing
git switch -c lesson/prod-02-modes-positions
./mvnw --batch-mode --no-transfer-progress clean verify
```

No external key or Docker service is required.

## Step 1: Define Server-Owned Modes

Add the following under `ai.orchestration` in `application.yml`:

```yaml
default-mode: support_resolver
strict-mode-routing: true
strict-position-routing: true
position-routing:
  knowledge: support_assistant
  ticket: support_resolver
modes:
  support_assistant:
    actions-enabled: false
    actions-preferred: false
    retrieval-enabled: true
    information-mode: DETERMINISTIC_RAG_GENERATE
    suggestions-enabled: false
    rag:
      fanout-enabled: false
      top-k-per-space: 5
      retrieval-vector-spaces-allowlist: [knowledge-article]
  support_resolver:
    actions-enabled: true
    actions-preferred: true
    retrieval-enabled: true
    information-mode: DETERMINISTIC_RAG_GENERATE
    suggestions-enabled: false
    rag:
      fanout-enabled: false
      top-k-per-space: 3
      retrieval-vector-spaces-allowlist: [knowledge-article]
```

`actions-preferred` guides orchestration; `actions-enabled` is the deterministic gate. Prompt text
does not replace either setting.

## Step 2: Add The Application Position Resolver

Create `SupportModeResolver`. Its precedence is:

1. preserve an explicitly requested mode so Core can validate it;
2. map a known position from `position-routing`;
3. use the server default when neither hint is supplied;
4. reject an unknown position when strict application routing is enabled.

Do not silently turn an unknown explicit mode into a safe-looking default. That hides a client or
deployment defect.

## Step 3: Extend The HTTP Contract Narrowly

Add optional `mode` and `position` fields to `AssistantQueryRequest`. Constrain both to 64 characters
and `[A-Za-z0-9_-]+`. Keep identity, tenant, session owner, action context, pending work, and history
out of the request body.

Pass the resolved mode and position into `OrchestrationContext`. Add only the non-sensitive routing
source to metadata for diagnostics.

## Step 4: Prove The Capability Bundles

Run:

```bash
./mvnw --batch-mode --no-transfer-progress -Dtest=ModesAndPositionsIntegrationTest test
```

The test provider returns a structured `create_support_ticket` intent. The application does not
text-match the user message.

Expected retrieval-only result:

```json
{
  "type": "CLARIFICATION_REQUIRED",
  "data": {"reason": "ACTIONS_DISABLED_BY_POLICY"},
  "metadata": {
    "orchestrationPolicy": {
      "mode": "support_assistant",
      "position": "knowledge",
      "actionsEnabled": false,
      "retrievalEnabled": true
    }
  }
}
```

Expected resolver result:

```json
{
  "type": "CONFIRMATION_REQUIRED",
  "data": {"action": "create_support_ticket"},
  "metadata": {
    "orchestrationPolicy": {
      "mode": "support_resolver",
      "position": "ticket",
      "actionsEnabled": true
    }
  }
}
```

## Intentional Failures

Send `mode: untrusted_admin`. Core returns `ERROR` with `Unsupported mode: untrusted_admin`.

Send `position: admin-console` without a mode. The application returns HTTP 400 with
`Unsupported support position: admin-console`.

These are separate failures because they belong to separate ownership boundaries.

## Optional OpenAI Observation

With `OPENAI_API_KEY` supplied only through your environment, repeat the two position requests using
the `openai` profile. Observe natural intent extraction, then verify the same deterministic policy
metadata. This observation does not replace the keyless tests.

## Full Verification

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
./scripts/smoke-packaged.sh
```

Expected: all tests pass, packaged ONNX/Lucene smoke passes, and readiness reports
`course-0.3.3-p02-modes-positions` with `modeRouting=true`.

## Done When

- Core-course action tests still pass with no routing hints.
- `knowledge` blocks writes and retains retrieval.
- `ticket` reaches governed confirmation.
- explicit approved mode wins over position mapping.
- unknown mode and position failures remain visible.
- no client field controls identity, tenant, or authorization.

## Reset

```bash
./scripts/reset-course.sh
git switch --detach course-0.3.3-p01-provider-routing
```

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Every action is disabled | Ensure the no-hint default is `support_resolver`; use `knowledge` only for retrieval-only requests. |
| Unknown mode silently works | Confirm `strict-mode-routing: true` and pass the explicit mode into Core. |
| Position YAML has no effect | Core intentionally does not map UI positions; verify `SupportModeResolver` is invoked. |
| Browser can change tenant | Remove tenant/identity fields from the request and use authenticated server context. |

## Next Lesson

PROD-03 moves follow-up guidance into a narrow classpath prompt overlay while keeping these policy
controls in configuration and Java.
