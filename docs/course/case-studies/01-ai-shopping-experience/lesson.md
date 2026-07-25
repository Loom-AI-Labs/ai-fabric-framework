---
id: case-01
slug: ai-shopping-experience
title: AI Shopping Experience
track: case-studies
order: 1
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
  - examples/real-apps/chat-capabilities-demo/README.md
  - examples/real-apps/chat-capabilities-demo/src/main/java/com/ai/fabric/realapps/chat/web/ChatController.java
  - examples/real-apps/chat-capabilities-demo/src/main/java/com/ai/fabric/realapps/chat/web/CommerceModeResolver.java
  - examples/real-apps/chat-capabilities-demo/src/main/java/com/ai/fabric/realapps/chat/catalog/domain/Product.java
  - examples/real-apps/chat-capabilities-demo/src/main/java/com/ai/fabric/realapps/chat/catalog/service/ProductService.java
  - examples/real-apps/chat-capabilities-demo/src/main/java/com/ai/fabric/realapps/chat/cart/action/AddToCartActionHandler.java
  - examples/real-apps/chat-capabilities-demo/src/main/java/com/ai/fabric/realapps/chat/demo/service/DemoStageSeedService.java
  - examples/real-apps/chat-capabilities-demo/src/test/java/com/ai/fabric/realapps/chat/web/ChatControllerConversationsTest.java
  - examples/real-apps/chat-capabilities-demo/src/test/java/com/ai/fabric/realapps/chat/demo/service/DemoStageSeedServiceTest.java
theoryVideoIds:
  - case-shopping-walkthrough
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

# Reproduce The AI Shopping Experience

## Start Here

This case study connects a normal commerce domain to staged RAG, backend conversation memory, typed
attachments, and governed cart writes. You will use the deployed application first, then trace each
visible result to the Spring Boot code that owns it.

Open:

- live UI: `https://ai-fabric.dev/demos/ai-shopping-experience`
- backend health: `https://ai-fabric-chat-capabilities-demo.46.224.145.148.sslip.io/api/demo/health`
- source: `examples/real-apps/chat-capabilities-demo`

The public backend already has a protected OpenAI key. For a local live-provider run, export
`OPENAI_API_KEY` in your shell; never place it in YAML, HTTP files, screenshots, or commits.

## Architecture To Recognize

```text
Shopping UI -> POST /api/chat/query -> CommerceModeResolver
                                         |
                          chat-session + orchestrator
                          /             |             \
               product/review RAG   typed actions   OpenAI
                        |                |
                 Lucene evidence    CartService
                        ^                |
                        +-- @AIProcess <-+
```

The browser may request a position such as search, support, cart, or checkout. The backend maps that
position to an allowlisted orchestration mode. It does not let a browser invent framework policy.
The browser sends only the new message and typed attachments; `ai-fabric-chat-session` owns recent
turns and pending confirmation state.

## Step 1: Prove Evidence Readiness

Open the RAG Journey in the live UI and reset your isolated demo session to no evidence. Ask:

```text
Which gaming laptop has the best display?
```

Expected: the application must not claim an evidence-grounded catalog answer. Seed Products, then
Reviews, and repeat the same question. Inspect the evidence IDs and counts after each stage.

The important proof is not that the prose becomes longer. It is that retrieval changes only after
the corresponding source records are indexed. `DemoStageSeedService` owns the stage transition and
`DemoReadinessService` reports source and vector state independently.

## Step 2: Compare Explicit Product Targets

Attach Alienware M18 R2 and Razer Blade 16 Studio from the product UI, then send:

```text
Compare them for gaming.
```

Expected:

- both attachments are visible before send;
- the request carries stable product identity and approved metadata;
- the answer compares the attached products instead of guessing a target;
- product/review evidence remains inspectable.

Attachments are context, not authorization. The backend still validates their shape and uses
application services for current facts.

## Step 3: Follow Up From Backend Memory

Send:

```text
Which is below $2800?
```

Then:

```text
Which is better for gaming?
```

Expected: the same backend conversation ID is reused, the UI does not submit copied history, and the
second follow-up is resolved from stored turns and current evidence.

Inspect `ChatController` and `ChatControllerConversationsTest`. Presentation state in the browser is
not the conversation authority.

## Step 4: Execute A Governed Cart Write

Ask:

```text
Add Alienware M18 R2 to my cart.
```

Expected:

1. AI Fabric resolves `add_to_cart`.
2. The handler resolves the friendly title to canonical product identity.
3. The result is `CONFIRMATION_REQUIRED`; no cart row changes yet.
4. Confirm in the UI.
5. Exactly one cart mutation runs.
6. The UI shows a concise projected result, not a raw nested object.

Reject a second proposal. Verify the cart remains unchanged. Confirmation is a backend state
transition, not a decorative button.

## Intentional Failure

Reset to no evidence and ask for the best-reviewed gaming laptop. A generic model answer presented as
store evidence is a failure. The correct behavior exposes missing evidence or limits the answer.

Also close the chat and reopen it. The conversation should remain available because the backend owns
the turns. If reopening clears context, diagnose session ownership before changing prompts.

## Run The App Locally

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml \
  -pl chat-capabilities-demo -am test
```

Live OpenAI run:

```bash
OPENAI_ENABLED=true \
OPENAI_API_KEY="$OPENAI_API_KEY" \
OPENAI_MODEL=gpt-4o-mini \
OPENAI_EMBEDDING_MODEL=text-embedding-3-small \
mvn -f examples/real-apps/chat-capabilities-demo/pom.xml spring-boot:run
```

Use `requests/demo.http` for staged evidence and action calls.

## What You Should Be Able To Explain

- why the source database and vector index are separate states;
- why position-to-mode mapping belongs on the backend;
- why attachments help target resolution without granting authority;
- why writes require authorization, confirmation, and application-owned execution;
- why backend memory is required for short follow-ups;
- why user-facing action projection is part of the application contract.

## Done When

- you reproduced no-evidence and indexed-evidence behavior;
- you compared two attached products with visible evidence;
- a short follow-up used the same backend conversation;
- confirm executed one cart mutation and reject executed none;
- you can point from each UI result to the owning backend class and test.

## Reset

Use **Reset to no evidence** in the RAG Journey or call the documented demo reset endpoint for your
session. Do not delete shared deployment data directly.

## Next Lesson

CASE-02 applies the same read, retrieval, memory, and confirmation concepts to current-account
resolution, where application-owned identity becomes even more important.
