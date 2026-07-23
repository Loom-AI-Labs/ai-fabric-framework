# Optional Lab: Add The AI Fabric Chat UI

This lab adds a reusable presentation layer to the Support Knowledge Assistant built during the
Core course. Complete the backend behavior for the current lesson first. The browser is never the
source of retrieval, intent resolution, action policy, conversation authority, or generated text.

Repository:
[Loom-AI-Labs/ai-fabric-chat-ui](https://github.com/Loom-AI-Labs/ai-fabric-chat-ui)

Pinned UI version: `v0.1.0`

## What The UI Provides

- Plain HTML Web Component and optional React wrapper.
- Responsive floating, inline, and full-page layouts.
- Typed rendering for every AI Fabric `OrchestrationResultType`.
- RAG evidence, clarification forms, confirmation, denial, action results, and compound results.
- Authorized conversation restore, reset, and optional browsing.
- Safe Markdown, Arabic/RTL labels, dark mode, structured attachments, and server-allowlisted modes.
- Visible HTTP, provider, renderer, reset, and response-contract failures.

AI Fabric and the application backend still provide all intelligence and policy.

## Request And Ownership Flow

```text
user
  -> AI Fabric Chat UI
     sends only current message + stable conversationId + current attachments
  -> Spring Boot controller
     resolves authenticated principal, tenant, and allowed mode/position
  -> ai-fabric-chat-session
     authorizes and loads bounded history and pending actions
  -> AI Fabric orchestration
     handles intent, retrieval, generation, clarification, and governed actions
  -> application response projection
     exposes OrchestrationResult + sanitizedPayload
  -> AI Fabric Chat UI
     renders the typed result without inventing a fallback
```

Never place user ID, tenant ID, roles, provider credentials, or trusted domain context in widget
attributes.

## Prerequisites

- Node.js 20 or later for building the bundle.
- A working `POST /api/assistant/orchestrate` endpoint.
- CORE-03 complete for grounded answers and evidence.
- CORE-04 complete before enabling confirmation and action-result scenarios.
- CORE-05 complete before claiming backend conversation restore.

The current course application contract is:

```text
POST   /api/assistant/orchestrate
GET    /api/assistant/conversations/{conversationId}
DELETE /api/assistant/conversations/{conversationId}
```

## Step 1: Install A Pinned UI Build

After the npm package is published:

```bash
npm install @loom-ai-labs/ai-fabric-chat-ui@0.1.0
```

Until then, build the immutable Git tag:

```bash
git clone --branch v0.1.0 --depth 1 https://github.com/Loom-AI-Labs/ai-fabric-chat-ui.git
cd ai-fabric-chat-ui
npm ci
npm run verify
```

Copy the generated bundle into the course application:

```bash
mkdir -p <course-app>/src/main/resources/static/vendor
cp dist/ai-fabric-chat-ui.js \
  <course-app>/src/main/resources/static/vendor/ai-fabric-chat-ui.js
```

Use a versioned artifact in a real application. Do not copy a moving `main` bundle into a release.

## Step 2: Add The Chat Page

Create `src/main/resources/static/assistant.html`:

```html
<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Support Knowledge Assistant</title>
    <script type="module" src="/vendor/ai-fabric-chat-ui.js"></script>
    <style>
      body { margin: 0; background: #f4f7fb; }
      ai-fabric-chat {
        display: block;
        width: min(100% - 24px, 960px);
        height: min(760px, calc(100vh - 24px));
        margin: 12px auto;
      }
    </style>
  </head>
  <body>
    <ai-fabric-chat
      endpoint="/api/assistant/orchestrate"
      history-endpoint="/api/assistant/conversations/{conversationId}"
      layout="inline"
      title="Support Knowledge Assistant"
      welcome-message="Ask about approved support knowledge or request a supported action.">
    </ai-fabric-chat>
  </body>
</html>
```

Open `http://localhost:8080/assistant.html`. The default transport sends same-origin credentials.

## Step 3: Verify Evidence-Grounded Answers

Ask the CORE-03 account-lockout question. The UI must show the backend answer and the expected
stable evidence ID. Then clear the index and repeat.

Expected transition:

```text
indexed evidence -> INFORMATION_PROVIDED + evidence panel
empty evidence   -> explicit no-evidence/error result, no invented answer
provider failure -> visible failure, no canned success
```

An evidence badge by itself is not proof. Inspect the POST response and verify the IDs originated
from the backend result.

## Step 4: Verify Governed Actions

Request a support ticket without one required user-owned field. Complete the clarification form,
then confirm or reject the pending action.

The component sends `yes` or `no` through the same orchestration endpoint and conversation ID. It
does not select a handler or call an execution endpoint.

Add a domain renderer so the result card contains only useful public fields:

```html
<script type="module">
  const chat = document.querySelector("ai-fabric-chat");

  chat.actionRenderers = {
    create_support_ticket: ({ actionResult }) => ({
      title: actionResult?.message ?? "Support ticket created",
      status: actionResult?.status,
      fields: [
        ["Ticket", actionResult?.data?.ticketNumber],
        ["Status", actionResult?.data?.status],
        ["Priority", actionResult?.data?.priority],
      ]
        .filter(([, value]) => value !== undefined && value !== null && value !== "")
        .map(([label, value]) => ({ label, value: String(value) })),
    }),
  };
</script>
```

The backend must already provide a safe `sanitizedPayload`. A renderer is presentation, not a
redaction or authorization boundary.

## Step 5: Verify Backend-Owned Memory

Run one conversation:

```text
Why is ticket T-1042 unresolved?
Escalate it.
Yes.
```

Inspect each POST. It may contain the new message, stable conversation ID, current attachments,
and allowed mode/position hints. It must not contain copied history, owner, tenant, or pending
action JSON.

Close and reopen the panel. Authorized history can be restored through the GET endpoint. Start a
new conversation and verify that a short follow-up does not inherit the old target. If backend
DELETE fails, the existing conversation must stay visible with an error; a failed reset must not
look successful.

## Step 6: Verify Security And Privacy

Run the CORE-06 second-user and cross-tenant tests against the public page:

1. Another principal cannot load or delete the first principal's conversation.
2. Forbidden evidence never reaches the browser response or debug view.
3. Raw protected input does not appear in action cards, evidence, history, logs, or browser events.
4. Mode and position values are allowlisted again by the backend.
5. The application never exposes provider credentials to the bundle.

The widget is not a security boundary. CSS hiding, disabled controls, and unknown conversation IDs
provide no authorization.

## Step 7: Add Browser Release Proof

Cover these cases with Playwright or the application's browser test stack:

| Scenario | Required proof |
| --- | --- |
| RAG answer | expected evidence ID is visible and originated in the API response |
| Clarification | only framework-returned, user-owned missing fields are requested |
| Confirmation | confirm/reject sends a new turn and the write occurs at most once |
| Action result | allowlisted fields render; raw nested data does not |
| Memory | current-message-only POST and authorized history restore |
| Reset failure | old conversation remains visible with an explicit error |
| Provider outage | UI shows failure and no synthetic answer |
| Invalid response | UI shows a contract error |
| Responsive UI | composer and commands remain usable on desktop and mobile |
| Accessibility | no serious or critical automated findings in the tested flow |

Verify the served frontend asset and backend build identity separately. A current backend commit
does not prove the browser bundle was redeployed.

## React Alternative

```tsx
import { AiFabricChat } from "@loom-ai-labs/ai-fabric-chat-ui/react";

export function AssistantPage() {
  return (
    <AiFabricChat
      endpoint="/api/assistant/orchestrate"
      historyEndpoint="/api/assistant/conversations/{conversationId}"
      layout="inline"
      title="Support Knowledge Assistant"
    />
  );
}
```

The wrapper uses the same Web Component and backend contract. Do not create a second React-owned
conversation history and send it back to AI Fabric.

## Coding-Assistant Path

Give the coding assistant these sources before it edits the application:

1. [`llms.txt`](https://github.com/Loom-AI-Labs/ai-fabric-chat-ui/blob/v0.1.0/llms.txt)
2. [Course integration guide](https://github.com/Loom-AI-Labs/ai-fabric-chat-ui/blob/v0.1.0/docs/COURSE_INTEGRATION.md)
3. [Backend contract](https://github.com/Loom-AI-Labs/ai-fabric-chat-ui/blob/v0.1.0/docs/BACKEND_CONTRACT.md)
4. [Security model](https://github.com/Loom-AI-Labs/ai-fabric-chat-ui/blob/v0.1.0/docs/SECURITY.md)
5. [Coding-assistant guide](https://github.com/Loom-AI-Labs/ai-fabric-chat-ui/blob/v0.1.0/docs/CODING_ASSISTANT_GUIDE.md)
6. The application's real controller, authentication, and public response DTOs.

Require it to identify the endpoint envelope, backend identity source, authorized conversation
routes, safe action fields, and executed browser tests. Do not accept a screenshot as the only
proof.

## Done When

- The UI uses the real AI Fabric endpoint as its only intelligence source.
- Every result type used by the course app has a useful, stable presentation.
- Evidence, clarification, and action data come from the backend contract.
- Confirmation and memory use the same authorized conversation.
- The browser never sends copied history or trusted identity values.
- Action results avoid raw nested JSON.
- Provider, transport, renderer, contract, and reset failures remain visible.
- Desktop and mobile browser tests pass against the packaged application.

Full package documentation lives in the
[AI Fabric Chat UI repository](https://github.com/Loom-AI-Labs/ai-fabric-chat-ui/tree/v0.1.0/docs).
