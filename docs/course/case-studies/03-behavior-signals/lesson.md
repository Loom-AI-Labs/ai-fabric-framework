---
id: case-03
slug: behavior-signals
title: AI Fabric Behavior Signals
track: case-studies
order: 3
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
  - examples/real-apps/behavior-churn-signals/README.md
  - examples/real-apps/behavior-churn-signals/src/main/java/com/ai/fabric/realapps/behavior/spi/DbExternalEventProvider.java
  - examples/real-apps/behavior-churn-signals/src/main/java/com/ai/fabric/realapps/behavior/service/BehaviorDemoScenarioService.java
  - examples/real-apps/behavior-churn-signals/src/main/java/com/ai/fabric/realapps/behavior/service/AgenticUiComposerService.java
  - examples/real-apps/behavior-churn-signals/src/test/java/com/ai/fabric/realapps/behavior/spi/DbExternalEventProviderTest.java
  - examples/real-apps/behavior-churn-signals/src/test/java/com/ai/fabric/realapps/behavior/service/AgenticUiComposerServiceTest.java
theoryVideoIds:
  - case-behavior-signals-walkthrough
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

# Reproduce Behavior Signals And Agentic UI

## Start Here

This case study uses raw application events to produce a persisted behavior insight. A second
structured LLM call selects a short allowlisted list of home-page components; backend code fills
their trusted props.

Open:

- analytics: `https://ai-fabric.dev/demos/ai-fabric-behavior-signals`
- user-home preview: `https://ai-fabric.dev/demos/ai-fabric-behavior-signals/agentic-ui`
- backend health: `https://behavior-churn-signals.46.224.145.148.sslip.io/api/behavior-demo/health`

## Architecture To Recognize

```text
raw app events -> ExternalEventProvider -> BehaviorAnalysisService -> persisted insight
                                      previous insight + new events -----^

insight + allowlisted component catalog -> LLM {name, reason}[]
                                         -> backend validation
                                         -> trusted props
                                         -> rendered user-home modules
```

The event store is application-owned. AI Fabric is not a generic analytics warehouse. Follow-up
analysis receives the previous insight and only events newer than that analysis, so old history is
not mislabeled as new evidence.

## Step 1: Inspect Provider Posture

Reset your isolated session and inspect health. The public demo must report a live external provider
and the model used by each generated insight. If live selection fails, the UI must show the failure;
it must not silently substitute deterministic output.

## Step 2: Compare Seeded Users

Run user behavior analysis for every seeded persona. Compare:

- churn probability and sentiment;
- evidence event types;
- trend and recommended action family;
- last analyzed timestamp;
- selected user-home components.

The recommendation family is model analysis for an internal operator. It is not an automatically
executed customer offer.

## Step 3: Add Negative Raw Events

Choose a healthy user and record realistic raw events such as:

```text
PAYMENT_FAILED
FEATURE_ERROR
NO_LOGIN_14D
HELP_CENTER_SEARCH
```

The record-event command must only persist the event. Run **User behavior analysis** separately.
Expected: the latest events appear first, the next insight cites the fresh evidence, and risk may
move in the negative direction.

## Step 4: Add Recovery Events

Choose the high-risk user and record positive recovery events. Run analysis again. Expected:

- the previous insight remains part of the analysis context;
- only post-analysis events are labeled new;
- the updated insight explains the direction of change;
- the component plan can change from retention/help surfaces toward loyalty, progress, or upgrade
  surfaces.

## Step 5: Inspect Agentic UI Safely

Open the user-home preview for the same user and refresh insight. The LLM receives component names,
descriptions, and intended uses. It returns only names and reasons. It does not return HTML, CSS,
React props, discounts, or arbitrary API calls.

`AgenticUiComposerService` rejects unknown component names. The backend supplies prices, points,
offer facts, and links from trusted state.

## Intentional Failure

Do not describe a prose sentence such as "customer wants to cancel" as a raw app event. A real event
has a typed name, timestamp, source, and structured facts. Also verify that recording an event does
not automatically run analysis.

## Run Locally

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml \
  -pl behavior-churn-signals -am test
```

The no-key profile is deterministic and must be labeled that way. To reproduce live public posture:

```bash
AI_LLM_PROVIDER=openai \
OPENAI_ENABLED=true \
OPENAI_API_KEY="$OPENAI_API_KEY" \
OPENAI_MODEL=gpt-4o-mini \
mvn -f examples/real-apps/behavior-churn-signals/pom.xml spring-boot:run
```

## Done When

- provider posture and model identity are visible;
- you compared all seeded users;
- negative events changed a previously healthy user's analysis;
- recovery events updated a high-risk user's analysis incrementally;
- the agentic UI used only allowlisted names and backend-owned props;
- no LLM failure was hidden by fallback output.

## Next Lesson

CASE-04 applies explicit identity and metadata filters to prevent cross-tenant evidence and actions.
