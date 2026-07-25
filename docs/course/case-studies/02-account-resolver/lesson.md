---
id: case-02
slug: account-resolver
title: AI Fabric Account Resolver
track: case-studies
order: 2
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
  - examples/real-apps/ai-fabric-account-resolver/README.md
  - examples/real-apps/ai-fabric-account-resolver/src/main/java/com/subscription/hub/controller/NaturalLanguageController.java
  - examples/real-apps/ai-fabric-account-resolver/src/main/java/com/subscription/hub/action/handler/GetAccountProfileActionHandler.java
  - examples/real-apps/ai-fabric-account-resolver/src/main/java/com/subscription/hub/action/handler/UpdateAddressActionHandler.java
  - examples/real-apps/ai-fabric-account-resolver/src/main/java/com/subscription/hub/config/ResolverPolicyRagIndexer.java
  - examples/real-apps/ai-fabric-account-resolver/src/main/java/com/subscription/hub/ai/ResolverAccountOwnedTargetResolutionStep.java
  - examples/real-apps/ai-fabric-account-resolver/src/test/java/com/subscription/hub/controller/NaturalLanguageControllerTest.java
  - examples/real-apps/ai-fabric-account-resolver/src/test/java/com/subscription/hub/action/handler/AccountResolverActionHandlerTest.java
theoryVideoIds:
  - case-account-resolver-walkthrough
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

# Reproduce The Account Resolver

## Start Here

This app lets AI Fabric read a current account profile, retrieve human-readable policies, explain a
blocker, and propose a governed remedy. The UI does not compute the blocker and does not ask the user
for application-owned identifiers.

Open:

- live UI: `https://ai-fabric.dev/demos/ai-fabric-account-resolver`
- backend health: `https://ai-fabric-account-resolver.46.224.145.148.sslip.io/api/account-resolver/health`
- source: `examples/real-apps/ai-fabric-account-resolver`

## Architecture To Recognize

```text
new user turn -> NaturalLanguageController -> resolver mode
                                             |
                            backend chat session + planner
                              /                        \
                 get_account_profile read       policy RAG
                              \                        /
                               LLM explains blocker
                                         |
                            confirmable remedy action
                                         |
                              domain service + result
```

`get_account_profile` returns facts, not a prewritten diagnosis. Policies are general guidance.
The model combines current facts and retrieved policy evidence; the backend still owns current-user
identity, authorization, action context, and execution.

## Step 1: Select A Missing-Address Persona

Reset your session and choose the missing billing address scenario. Ask:

```text
Why can't I place an order?
```

Expected: AI Fabric reads the current account profile, retrieves the billing-address policy, and
explains that the address is the blocker. Inspect the profile action result and policy evidence.

The request must not contain a trusted subscription ID. The application resolves the current
account from authenticated/session-owned context.

## Step 2: Use A Natural Follow-Up

Send:

```text
Update my billing address.
```

Expected: `update_address` is selected without generic target clarification. The action may request
only information the application does not already own, such as address fields. It must not ask the
user to type a subscription ID.

The custom target-resolution step prevents a generic entity resolver from treating current-account
actions as arbitrary object actions. That is an application boundary, not fake intelligence.

## Step 3: Confirm And Re-read

Complete the required address fields and confirm. Expected:

- no write occurs before confirmation;
- the action executes once;
- the result exposes a concise success message and safe fields;
- a fresh profile read shows the updated address;
- readiness can change, but readiness is dashboard proof rather than an LLM shortcut.

Ask again:

```text
Can I place an order now?
```

The answer should use the updated account state.

## Step 4: Observe Policy Outcomes

Choose the refund persona and request a small account credit, then a cash refund above the
application review threshold. Expected: policy-owned domain logic can return different statuses such
as approved or pending review. That status comes from backend execution, not UI wording.

## Intentional Failure

Start an address update, then reject it. The stored address must remain unchanged. Next, send a
follow-up with a new conversation ID; it should not inherit the previous pending action.

If the UI asks for subscription ID, provider, or card type already known by the application, inspect
action parameter ownership before editing prompts.

## Run Locally

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml \
  -pl ai-fabric-account-resolver -am test
```

For live orchestration:

```bash
OPENAI_ENABLED=true \
OPENAI_API_KEY="$OPENAI_API_KEY" \
OPENAI_MODEL=gpt-4o-mini \
mvn -f examples/real-apps/ai-fabric-account-resolver/pom.xml spring-boot:run
```

Keep the key in the environment only.

## Done When

- profile facts and policy evidence are both visible;
- the model explains the selected persona's actual blocker;
- a natural follow-up resolves the current-account action;
- only genuinely missing user input is requested;
- reject has no side effect and confirm executes once;
- post-action profile state reflects the domain write.

## Next Lesson

CASE-03 moves from conversational resolution to time-ordered behavior evidence and structured
component selection.
