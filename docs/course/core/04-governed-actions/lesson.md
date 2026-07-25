---
id: core-04
slug: governed-actions
title: Governed Actions And Confirmation
track: core
order: 4
durationMinutes: 85
availability: published
courseVersion: 0.4.0-course.4-beta
frameworkVersion: 0.4.0
frameworkTag: ai-fabric-framework-v0.4.0
courseSourceTag: ai-fabric-course-v0.4.0.4
starterRef: course-0.4.0-02-rag
solutionRef: course-0.4.0-03-actions
requiresOpenAi: true
requiresDocker: false
sourcePaths:
  - docs/course/core/04-governed-actions/notebooklm/AI_FABRIC_GOVERNED_ACTIONS_CONFIRMATION_NOTEBOOKLM_SCRIPT.md
  - docs/getting-started/05-first-governed-action.md
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/action/annotation/AIAction.java
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/action/annotation/Param.java
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/action/ActionAccessMode.java
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/action/AIActionRegistry.java
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/action/ActionContext.java
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/action/ActionResult.java
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/action/PendingActionStore.java
  - examples/real-apps/chat-capabilities-demo/src/main/java/com/ai/fabric/realapps/chat/support/action/CreateSupportTicketActionHandler.java
  - ai-infrastructure-module/ai-fabric-core/src/test/java/ai/fabric/intent/action/AIActionRegistryTest.java
  - ai-infrastructure-module/ai-fabric-core/src/test/java/ai/fabric/intent/orchestration/pipeline/steps/IntentHandlingStepRequiredParamsPlaceholderTest.java
  - docs/course/labs/AI_FABRIC_CHAT_UI_LAB.md
theoryVideoIds:
  - governed-actions-and-confirmation
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

# Governed Actions And Confirmation

## Start Here

Retrieval lets a model explain approved facts. An action lets a user request application work. That
does not make the model a database client.

In this lesson, you will register one read action and one confirmation-gated write action for the
Support Knowledge Assistant:

- `get_my_ticket_status` reads one ticket the authenticated user is allowed to see;
- `create_support_ticket` creates a ticket only after required parameters, authorization, and user
  confirmation have all succeeded.

You will prove the complete state machine, including clarification, denial, confirmation, rejection,
single execution, and safe result projection.

> **Verified checkpoints:** start from `course-0.4.0-02-rag` and finish at
> `course-0.4.0-03-actions`. Deterministic tests inject structured intent; the optional live run
> uses a real LLM and keeps provider failure visible instead of replacing it with keyword logic.

## The Governing Rule

Keep this ownership chain visible throughout the lab:

```text
LLM
  -> interprets natural language and proposes a registered action + parameters

AI Fabric
  -> constrains the catalog, validates typed parameters, applies action policy,
     manages pending confirmation, and invokes the registered handler

Your application
  -> supplies trusted identity, authorizes the caller and target, owns the transaction,
     performs the side effect, and projects safe result facts
```

No branch should connect model output directly to a repository.

## Step 1: Define The Action Catalog

Create `action-contract.md`:

| Action | Access mode | User parameters | Context-owned data | Confirmation |
| --- | --- | --- | --- | --- |
| `get_my_ticket_status` | `READ` | `ticketNumber` | subject, tenant, session | No |
| `create_support_ticket` | `WRITE_ONLY` | `subject`, `description`, optional `priority` | subject, tenant, conversation | Yes |

The current access modes are `READ`, `READ_WRITE`, and `WRITE_ONLY`. Only `READ` is treated as
read-only and grounding-eligible by default. Planner-driven use still requires
`readActionResolutionEligible=true` on a reviewed read action.

Do not infer safety from action names. Declare it.

## Step 2: Register The Read Action

Use the current annotation shape:

```java
@AIAction(
    name = "get_my_ticket_status",
    description = "Read the status of one support ticket owned by the current customer",
    category = "support",
    accessMode = ActionAccessMode.READ,
    requiresConfirmation = false,
    readActionResolutionEligible = true
)
@RequiredArgsConstructor
public class GetMyTicketStatusActionHandler {
    private final TicketService ticketService;

    @ActionAllowed
    public boolean allowed(ActionContext context) {
        return context != null
            && context.userId() != null
            && context.authContext().getTenantId() != null;
    }

    @ActionExecute
    public ActionResult execute(
        @Param(
            value = "ticketNumber",
            description = "Ticket number such as T-104",
            required = true,
            pattern = "T-[0-9]+"
        ) String ticketNumber,
        ActionContext context
    ) {
        TicketView ticket = ticketService.getForCurrentCustomer(
            ticketNumber,
            context.userId(),
            context.authContext().getTenantId()
        );

        return ActionResult.builder()
            .success(true)
            .message("Ticket status loaded")
            .data(ActionResultContracts.object(Map.of(
                "ticketNumber", ticket.ticketNumber(),
                "status", ticket.status(),
                "priority", ticket.priority(),
                "updatedAt", ticket.updatedAt()
            )))
            .build();
    }
}
```

`@ActionAllowed` is an early action-level gate. `TicketService.getForCurrentCustomer` must still
enforce object ownership and tenant scope because the service owns domain authorization.

## Step 3: Register The Confirmation-Gated Write

```java
@AIAction(
    name = "create_support_ticket",
    description = "Create a support ticket for the current authenticated customer",
    category = "support",
    accessMode = ActionAccessMode.WRITE_ONLY,
    requiresConfirmation = true
)
@RequiredArgsConstructor
public class CreateSupportTicketActionHandler {
    private final TicketService ticketService;

    @ActionAllowed
    public boolean allowed(ActionContext context) {
        return context != null
            && context.userId() != null
            && context.authContext().getTenantId() != null;
    }

    @ActionConfirmation
    public String confirmation(
        @Param(value = "subject", required = true) String subject
    ) {
        return "Create a support ticket titled '" + subject + "'?";
    }

    @ActionExecute
    public ActionResult execute(
        @Param(
            value = "subject",
            description = "Short support-ticket subject",
            required = true
        ) String subject,
        @Param(
            value = "description",
            description = "What happened and what help is needed",
            required = true
        ) String description,
        @Param(
            value = "priority",
            description = "Requested priority",
            allowedValues = {"LOW", "NORMAL", "HIGH"}
        ) String priority,
        ActionContext context
    ) {
        TicketView ticket = ticketService.createForCurrentCustomer(
            subject,
            description,
            priority,
            context.userId(),
            context.authContext().getTenantId()
        );

        return ActionResult.builder()
            .success(true)
            .message("Support ticket created")
            .data(ActionResultContracts.object(Map.of(
                "ticketNumber", ticket.ticketNumber(),
                "status", ticket.status(),
                "priority", ticket.priority()
            )))
            .build();
    }
}
```

The handler delegates the transaction to `TicketService`. It returns concise trusted facts rather
than a JPA entity, repository graph, stack trace, or raw JSON string.

## Step 4: Keep Context-Owned Values Out Of `@Param`

Add a registry test that inspects the model-visible schemas for both actions.

The write action must expose exactly:

```text
subject
description
priority
```

It must not expose:

```text
userId
tenantId
customerId
conversationId
sessionId
```

These values come from authenticated server context. Asking the user to provide them creates poor UX
and lets untrusted model output propose security boundaries.

### Intentional Schema Failure

Temporarily add this parameter:

```java
@Param(value = "userId", required = true) String userId
```

Run the registry test and confirm it fails. Remove the parameter and use `context.userId()`.

This failure protects a real architectural boundary, not merely naming style.

## Step 5: Prove Registration Before Natural Language

Test the `AIActionRegistry` snapshot directly:

```text
get_my_ticket_status
  accessMode = READ
  requiresConfirmation = false
  readActionResolutionEligible = true
  required params = [ticketNumber]

create_support_ticket
  accessMode = WRITE_ONLY
  requiresConfirmation = true
  required params = [subject, description]
  optional params = [priority]
```

The registry is the executable allowlist. Unknown model output must become `ACTION_NOT_FOUND`, not a
reflection lookup against arbitrary application methods.

Also verify duplicate action names and handlers without exactly one `@ActionExecute` method fail at
registry construction.

## Step 6: Test Clarification Without Executing

Inject a structured action intent that contains `subject` but omits `description`. Do not depend on
live model wording in this deterministic test.

Expected orchestration result:

```json
{
  "type": "CLARIFICATION_REQUIRED",
  "action": "create_support_ticket",
  "missingParameters": ["description"]
}
```

Assert all of these:

- the ticket count does not change;
- no pending confirmation is created for an incomplete action;
- `TicketService.createForCurrentCustomer` is never called;
- the response asks only for user-owned missing values.

## Step 7: Test The Confirmation State Machine

Use one owner and conversation for every turn.

### Complete Request

```text
Create a high-priority support ticket titled Account locked and explain that recovery emails never arrive.
```

Expected state:

```text
result = CONFIRMATION_REQUIRED
pending action = create_support_ticket + validated parameters
ticket count delta = 0
```

### Reject

Send `no` in the same conversation.

Expected state:

```text
pending action removed
ticket count delta = 0
result = rejected/cancelled, not ACTION_EXECUTED
```

### Confirm

Create the request again, then send `yes` in the same conversation.

Expected state:

```text
pending action consumed
result = ACTION_EXECUTED
ticket count delta = 1
projected ticketNumber/status/priority present
```

### Duplicate Confirm

Send `yes` once more.

Expected state:

```text
no pending action remains
ticket count delta stays 1
no second handler invocation
```

Confirmation is backend state keyed by conversation and owner. A UI button can send the next user
turn, but it must not reconstruct and execute the write itself.

## Step 8: Prove Authorization At Two Levels

Add these tests:

1. Missing authenticated identity returns `ACTION_DENIED` before confirmation.
2. A user from another tenant cannot read a ticket even with a valid ticket number.
3. The write handler receives the subject and tenant from `ActionContext`, not action parameters.
4. The domain service rechecks authorization inside the transaction.

Treat `@ActionAllowed` as an early gate, not a replacement for domain authorization or database
constraints.

## Step 9: Project The Result For Humans And Machines

Keep the API result structured, then let the UI render the useful fields:

```text
Support ticket created
Ticket: T-204
Status: OPEN
Priority: HIGH
```

Do not make the primary result card recursively print this:

```text
Ticket entity -> customer -> tickets -> comments -> audit entries -> ...
```

The API test should assert the safe structured fields remain available. The UI test should reject
raw serialized payloads in the primary result card.

### Optional Chat UI Checkpoint

Use the [AI Fabric Chat UI lab](../../labs/AI_FABRIC_CHAT_UI_LAB.md) to exercise clarification,
confirmation, rejection, denial, and execution against the real orchestration endpoint. Confirm and
Reject send `yes` or `no` as the next turn in the same backend conversation. Register a domain
renderer for `create_support_ticket`; do not make the browser reconstruct or execute the action.

## Step 10: Run One Real-Provider Smoke

After deterministic tests pass, enable the OpenAI profile and send natural-language requests for:

1. ticket-status read;
2. incomplete ticket creation;
3. complete ticket creation;
4. rejection;
5. a new request followed by confirmation.

Record the structured intent, orchestration result type, action name, safe parameters, and domain
state transition. Do not log API keys, full prompts, or sensitive user content.

If the provider fails or returns malformed intent, report the real failure. Do not add text matching
such as `message.contains("ticket")` to make the smoke pass.

## Commands And Requests

```bash
./mvnw clean verify
./scripts/download-onnx-model.sh
OPENAI_API_KEY=<set-locally> ./mvnw spring-boot:run -Dspring-boot.run.profiles=openai
```

Open `requests/03-governed-actions.http` for the read, clarification, confirmation, execution, and
duplicate-confirmation sequence.

## Common Mistakes

| Mistake | Consequence | Correct approach |
| --- | --- | --- |
| Letting the browser match action words | Fake intelligence and bypassed policy | Send natural language to backend orchestration |
| Exposing subject or tenant as `@Param` | User is asked for server-owned identity | Resolve from trusted `ActionContext` |
| Treating typed parameters as authorization | Valid shapes can still target forbidden records | Authorize in action gate and domain service |
| Executing before confirmation | UI cannot stop the side effect | Store a complete pending action and execute on a later turn |
| Reusing a pending payload after confirmation | Duplicate side effects | Consume pending state and test second confirmation |
| Returning JPA entities | Leakage, cycles, and poor UX | Return `ActionResult` with an explicit payload contract |
| Hiding provider failure with keyword rules | A broken AI path appears healthy | Keep deterministic tests separate and fail live smoke honestly |

## Troubleshooting

| Symptom | Inspect |
| --- | --- |
| Action is not discovered | Spring bean scanning, `@AIAction`, unique name, and one `@ActionExecute` |
| Model asks for user or tenant ID | Registry parameter schema and misplaced `@Param` |
| Complete request still asks for fields | Parameter names, descriptions, allowed values, and intent diagnostics |
| Action executes immediately | `requiresConfirmation`, effective mode, and confirmation handling |
| `yes` does nothing | Same conversation/owner and pending-action store state |
| `yes` executes twice | Pending action consumption and idempotency/domain constraints |
| Read leaks another user's ticket | Trusted context and domain authorization |
| UI shows raw nested data | `ActionResult` projection and component renderer |

## Done When

You are done with this lesson when:

- both actions are registered with the expected metadata;
- model-visible schemas contain only user-provided values;
- missing required data clarifies without mutation;
- authorization failure occurs before confirmation;
- the write cannot execute before confirmation;
- rejection does not mutate state;
- confirmation executes exactly once;
- the result contains concise trusted facts rather than persistence objects;
- the real-provider smoke uses no keyword or canned-success fallback;
- you score at least 80 percent on the knowledge check.

## Next Lesson

CORE-05 replaces request-carried conversation history with backend-owned session memory so follow-up
questions and pending confirmations survive across turns without trusting the browser to replay state.
