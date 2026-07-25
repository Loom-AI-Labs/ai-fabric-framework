---
id: core-05
slug: backend-conversation-memory
title: Backend-Owned Conversation Memory
track: core
order: 5
durationMinutes: 60
availability: published
courseVersion: 0.4.0-course.3-beta
frameworkVersion: 0.4.0
frameworkTag: ai-fabric-framework-v0.4.0
courseSourceTag: ai-fabric-course-v0.4.0.3
starterRef: course-0.4.0-03-actions
solutionRef: course-0.4.0-04-memory
requiresOpenAi: true
requiresDocker: false
sourcePaths:
  - docs/course/core/05-backend-conversation-memory/notebooklm/AI_FABRIC_BACKEND_CONVERSATION_MEMORY_NOTEBOOKLM_SCRIPT.md
  - docs/getting-started/06-chat-session-memory.md
  - ai-infrastructure-module/ai-fabric-chat-session/src/main/java/ai/fabric/chat/config/ChatSessionProperties.java
  - ai-infrastructure-module/ai-fabric-chat-session/src/main/java/ai/fabric/chat/config/ChatSessionBaseConfiguration.java
  - ai-infrastructure-module/ai-fabric-chat-session/src/main/java/ai/fabric/chat/service/ChatSessionServiceImpl.java
  - ai-infrastructure-module/ai-fabric-chat-session/src/main/java/ai/fabric/chat/spi/ChatSessionAccessControlPolicy.java
  - ai-infrastructure-module/ai-fabric-chat-session/src/main/java/ai/fabric/chat/pipeline/ConversationEnrichmentStep.java
  - ai-infrastructure-module/ai-fabric-chat-session/src/main/java/ai/fabric/chat/pipeline/ConversationRecordingStep.java
  - ai-infrastructure-module/ai-fabric-chat-session/src/main/java/ai/fabric/chat/storage/ChatSessionPendingActionStore.java
  - ai-infrastructure-module/ai-fabric-chat-session/src/test/java/ai/fabric/chat/service/ChatSessionServiceImplTest.java
  - ai-infrastructure-module/ai-fabric-chat-session/src/test/java/ai/fabric/chat/storage/ChatSessionPendingActionStoreTest.java
  - ai-infrastructure-module/ai-fabric-chat-session/src/test/java/ai/fabric/chat/pipeline/ConversationEnrichmentStepTest.java
  - ai-infrastructure-module/ai-fabric-chat-session/src/test/java/ai/fabric/chat/pipeline/ConversationRecordingStepTest.java
  - examples/real-apps/ai-fabric-account-resolver/src/main/resources/prompts/intent-extraction/multi-step/v1-account-resolver/classify.md
  - docs/course/labs/AI_FABRIC_CHAT_UI_LAB.md
theoryVideoIds:
  - backend-conversation-memory
assistant:
  mode: implement
  implementationPrompt: assistant-prompt.md
  reviewPrompt: assistant-review-prompt.md
  validationStatus: passed
knowledgeCheck:
  source: knowledge-check.yml
  required: true
  passingScorePercent: 80
---

# Backend-Owned Conversation Memory

## Start Here

You already have evidence-grounded answers and a confirmation-gated support action. Now make this
three-turn exchange work without asking the browser to rebuild the conversation:

```text
User: Why is ticket T-1042 unresolved?
Assistant: The ticket is waiting for support escalation.
User: Escalate it.
Assistant: Escalate ticket T-1042 to tier two?
User: Yes.
```

The final two messages are ambiguous in isolation. In this lesson, you will let
`ai-fabric-chat-session` load recent turns, reuse bounded target context, and retain the pending
action. The client will send only the new message and a stable conversation ID. The server will
derive the owner from authenticated identity.

> **Verified checkpoints:** start from `course-0.4.0-03-actions` and finish at
> `course-0.4.0-04-memory`. Deterministic tests prove storage, ownership, bounds, and pending state.
> The optional OpenAI smoke proves natural-language follow-up interpretation and keeps provider
> failure visible.

## The Ownership Boundary

Keep these responsibilities separate:

```text
Browser
  -> new message, stable conversationId, current attachments, presentation state

Application backend
  -> authenticated owner, tenant/role checks, conversation lifecycle, domain authorization

AI Fabric chat session
  -> bounded recent turns, resolved targets, action drafts, pending confirmations

LLM
  -> interprets the new message using the supplied bounded context
```

The browser may render cached messages. It must not become authoritative for history, owner ID, or
the action awaiting confirmation.

## Step 1: Enable The Chat-Session Module

Add the module beside your existing AI Fabric dependencies:

```xml
<dependency>
  <groupId>io.github.loom-ai-labs</groupId>
  <artifactId>ai-fabric-chat-session</artifactId>
</dependency>
```

Configure a small, explicit context window:

```yaml
ai:
  chat:
    enabled: true
    auto-create-sessions: true
    window-size: 8
    max-context-chars: 4000
    pinned-target-reuse-window-turns: 3
    max-pending-action-stack-depth: 4
```

With JPA available, the module stores `ChatSession` and `ChatTurn` records. Enabling the module
also requires both a `ChatSessionStorageProvider` and a `ChatSessionAccessControlPolicy`. Missing
required beans must stop application startup instead of silently disabling memory.

### Expected Startup Proof

Start the application and verify that the Spring context contains:

```text
ChatSessionService
ChatSessionAccessControlPolicy
ChatSessionStorageProvider
ConversationEnrichmentStep
ConversationRecordingStep
PendingActionStore backed by ChatSessionPendingActionStore
```

Do not treat successful table creation alone as proof that ownership policy is correct.

## Step 2: Reduce The Client Request To Current-Turn Data

Define a request contract like this:

```java
public record ChatRequest(
    @NotBlank String message,
    @NotBlank String conversationId,
    List<OrchestrationAttachment> attachments
) {
}
```

At the HTTP boundary, derive the current owner from authenticated server state:

```java
@PostMapping("/api/support/chat")
OrchestrationResult chat(@Valid @RequestBody ChatRequest request, Principal principal) {
    String ownerId = principal.getName();

    OrchestrationContext context = OrchestrationContext.builder()
        .userId(ownerId)
        .conversationId(request.conversationId())
        .attachments(request.attachments())
        .position("support")
        .build();

    return ragOrchestrator.orchestrate(request.message(), context);
}
```

The public request must not accept authoritative fields such as:

```text
historyMessages
pendingAction
actionDraft
ownerId
tenantId
```

A request-contract test should serialize the DTO and prove those properties are absent. If an older
compatibility DTO still declares `historyMessages`, prove the controller ignores it, then remove it
from the public contract when compatibility permits.

## Step 3: Enforce Conversation Ownership

Implement the required policy through an application-owned authorization service:

```java
@Bean
ChatSessionAccessControlPolicy chatSessionAccessControlPolicy(
    ConversationAuthorization authorization
) {
    return new ChatSessionAccessControlPolicy() {
        @Override
        public boolean canCreateConversation(String ownerId) {
            return authorization.canCreate(ownerId);
        }

        @Override
        public boolean canAccessConversation(String ownerId, String conversationId) {
            return authorization.canAccess(ownerId, conversationId);
        }

        @Override
        public boolean canRecordTurn(String ownerId, String conversationId) {
            return authorization.canRecord(ownerId, conversationId);
        }

        @Override
        public boolean canDeleteConversation(String ownerId, String conversationId) {
            return authorization.canDelete(ownerId, conversationId);
        }
    };
}
```

AI Fabric checks the policy and also verifies that the stored session owner equals the requesting
owner. A permissive policy therefore does not make another user's stored session readable. Your
policy still needs real identity and tenant rules because it controls creation and other allowed
operations.

Add tests for blank identity, denied creation, denied access, and a stored-owner mismatch.

## Step 4: Prove The First Turn Is Recorded

Use a stable ID such as `support-user-17-001` and send:

```text
Why is ticket T-1042 unresolved?
```

After orchestration, inspect the backend session through `ChatSessionService` or a test storage
provider. Assert:

```text
session.id = support-user-17-001
session.ownerId = user-17
session.status = ACTIVE
turns = 1
turn[0].userQuery = the sanitized user message
turn[0].aiResponse = the sanitized assistant response
```

`ConversationRecordingStep` runs after response sanitization. When a PII service is available, it
redacts the stored query. Do not assert that a raw sensitive input is preserved.

## Step 5: Resolve A Follow-Up From Backend History

Send the second request with the same owner and conversation ID:

```json
{
  "message": "Escalate it.",
  "conversationId": "support-user-17-001"
}
```

Do not send the first turn again. `ConversationEnrichmentStep` loads role-aware messages before
intent handling and exposes bounded diagnostics under chat metadata.

The expected result is either clarification for genuinely ambiguous context or the supported
`escalate_support_ticket` action for T-1042. It must not come from controller code such as:

```java
if (message.toLowerCase().contains("escalate")) { ... }
```

### When History Exists But The Intent Is Still Wrong

Stored history makes context available; it does not force the model to use domain semantics. If a
supported short follow-up becomes `OUT_OF_SCOPE`, add a narrow application prompt overlay that
tells the intent extractor to inspect recent user and assistant turns before classifying ambiguous
follow-ups. Keep the curated default prompt as fallback and cover overlay precedence with prompt
resolution tests.

This is model guidance, not keyword routing. The LLM still interprets the request against the
registered action catalog and bounded conversation.

## Step 6: Preserve And Consume Pending Confirmation

When the escalation request is complete, expect:

```text
result type = CONFIRMATION_REQUIRED
pending action = escalate_support_ticket(ticketNumber=T-1042)
domain mutation count = 0
```

Now send only:

```json
{
  "message": "Yes.",
  "conversationId": "support-user-17-001"
}
```

The session-backed `PendingActionStore` resolves the approval against the owner-scoped pending
stack. Assert that the action executes once, its pending entry is removed, and a repeated `Yes`
does not execute it again.

Never post the action name and parameters back from the browser as proof of confirmation. The
browser contributes the user's current approval message; backend state identifies what is pending.

## Step 7: Reproduce The New-Conversation Failure

This intentional failure proves that continuity comes from backend state.

1. Complete the first turn in `support-user-17-001`.
2. Send `Escalate it.` with a new ID, `support-user-17-002`.
3. Assert that T-1042 is not silently inferred from another conversation.
4. Accept a clarification or no-target result; assert no mutation and no pending escalation for
   T-1042.
5. Repeat the follow-up with `support-user-17-001` and observe the authorized context.

Do not make this test depend on exact generated prose. Assert conversation ID, resolved target,
result type, pending state, and mutation count.

## Step 8: Bound History And Reused Targets

Stored turns can outlive the messages sent to a model. The default strategy applies two limits:

1. Keep the latest `window-size` turns.
2. Convert turns to user and assistant messages, then drop the oldest whole messages until
   `max-context-chars` is satisfied.

Add a deterministic test with oversized old turns and short recent turns. Verify that complete old
messages disappear and recent messages remain. Do not expect substring truncation inside an old
message.

Pinned targets are separate bounded session metadata. They may be reused only when the current
request has no fresh attachments and the configured turn window has not elapsed. Test that a fresh
attachment takes precedence and an expired pinned target is not reused.

## Step 9: Keep Transient Requests Out Of Memory

For a request that must not be persisted, set the framework query persistence mode to
`NEVER_PERSIST` through trusted orchestration metadata. The enrichment and recording steps then
skip conversation storage for that request.

Test both directions:

```text
normal request        -> history loaded and sanitized turn recorded
NEVER_PERSIST request -> no history enrichment and no turn or target metadata written
```

Do not build a second browser-only private mode that still sends sensitive content into the normal
backend persistence path.

## Step 10: Separate Conversation State From UI State

Closing a chat panel is a presentation event. It should not delete the backend conversation.
Reopening the panel can reload authorized turns from an application endpoint backed by
`ChatSessionService`.

Keep these values in UI state:

```text
panel open or closed
dismissed suggestion IDs
active local tab
scroll position
```

Keep these values in backend conversation state:

```text
sanitized turns
pending actions and action drafts
bounded resolved targets
owner and session status
```

Reset must create or select a new authorized conversation scope. A demo reset may also delete the
old session through a protected backend endpoint, but changing a React key is not a backend reset.

### Optional Chat UI Checkpoint

Wire the authorized conversation GET/DELETE routes through the
[AI Fabric Chat UI lab](../../labs/AI_FABRIC_CHAT_UI_LAB.md). Inspect the browser network trace:
each orchestration POST sends only the newest message and stable conversation ID. Closing the panel
must preserve backend state, and a failed reset must leave the old conversation visible with an
explicit error.

## Step 11: Build The Regression Suite

Your deterministic suite should cover:

| Test | Required proof |
| --- | --- |
| request contract | only message, conversation ID, and current attachments are client-owned |
| auto-create | first access creates an active session for the authenticated owner |
| first-turn recording | sanitized user and assistant messages are stored |
| same-conversation follow-up | backend history is supplied without browser replay |
| new-conversation follow-up | no target or pending state leaks across IDs |
| stored-owner mismatch | access fails even if a test policy returns true |
| bounded history | oldest whole messages are removed from prompt context |
| pending confirmation | approval consumes owner-scoped pending state exactly once |
| transient request | `NEVER_PERSIST` neither enriches nor records |
| presentation state | closing and reopening the panel does not rewrite conversation state |

Use structured orchestration state and storage assertions. Keep exact LLM wording out of no-key
tests.

## Step 12: Run One Real Follow-Up Smoke

After deterministic tests pass, run the OpenAI profile and execute the three-turn sequence through
the public API. Capture:

```text
conversationId
result type per turn
resolved action and safe parameters
history message count diagnostic
pending stack transition
domain mutation count
```

Then repeat `Escalate it.` with a new conversation ID. A provider error, malformed intent, or
unexpected classification is a real failed smoke. Do not replace it with a text-matching fallback
or a canned successful response.

## Commands And Requests

```bash
./mvnw clean verify
./scripts/download-onnx-model.sh
OPENAI_API_KEY=<set-locally> ./mvnw spring-boot:run -Dspring-boot.run.profiles=openai
```

Open `requests/04-backend-memory.http` for the latest-message-only, panel-reopen, confirmation,
duplicate-confirmation, and conversation-isolation sequence.

## Common Mistakes

| Mistake | Consequence | Correct approach |
| --- | --- | --- |
| Sending `historyMessages` from the browser | Manipulable and duplicated conversation authority | Send the new message and stable conversation ID |
| Accepting `ownerId` from request JSON | A caller can propose another user's security boundary | Derive identity from authentication |
| Enabling chat without an access policy | Unsafe ownership model or startup failure | Provide an application-owned policy |
| Using a new ID for every turn | Follow-ups and confirmations lose context | Reuse one authorized ID until reset |
| Assuming stored history guarantees intent quality | Domain follow-up can still be misclassified | Add and test a narrow prompt overlay |
| Reposting pending action JSON on confirm | Browser state can alter action authority | Resolve approval against backend pending state |
| Treating all stored turns as prompt context | Cost and stale-context growth | Configure turn and character bounds |
| Deleting a conversation when a panel closes | Presentation state destroys application state | Keep panel state local and session state backend-owned |
| Hiding provider failures with keywords | A broken AI path appears healthy | Separate deterministic proof from live smoke |

## Troubleshooting

| Symptom | Inspect |
| --- | --- |
| Application fails during startup | `ai.chat.enabled`, storage provider, JPA setup, and access-policy bean |
| Second turn has no history | stable conversation ID, authenticated owner, session status, and chat diagnostics |
| Access returns `ACCESS_DENIED` | policy decision and stored owner equality |
| `Escalate it` becomes `OUT_OF_SCOPE` | role-aware history, app prompt overlay, registered action descriptions |
| `Yes` does nothing | same owner/conversation, pending stack, confirmation expiry, and result type |
| `Yes` executes twice | pending pop/consumption and domain idempotency |
| Old context dominates | `window-size`, `max-context-chars`, and pinned-target reuse window |
| Sensitive one-time request appears in history | `NEVER_PERSIST` metadata and recording-step test |
| Reopened UI is empty | authorized history endpoint and stable conversation scope |

## Done When

You are done with this lesson when:

- the client sends only the new message, stable conversation ID, and current attachments;
- the backend derives the owner and checks policy plus stored ownership;
- the first sanitized turn is recorded;
- a same-conversation follow-up receives bounded backend history;
- a new conversation cannot reuse the prior target or pending action;
- confirmation survives a separate turn and executes exactly once;
- history, target reuse, and pending stacks are bounded;
- `NEVER_PERSIST` requests are neither enriched nor recorded;
- UI presentation state remains separate from conversation state;
- a real-provider smoke contains no keyword or canned-success fallback;
- you score at least 80 percent on the knowledge check.

## Next Lesson

CORE-06 applies identity, tenant policy, and PII handling before evidence or action data can cross a
security boundary.
