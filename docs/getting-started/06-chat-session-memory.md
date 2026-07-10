# Chat Session Memory

Use `ai-fabric-chat-session` when follow-up turns matter. It stores recent turns, pending
confirmations, and short-lived pinned targets so the backend, not the UI, owns conversation memory.

## Dependency

```xml
<dependency>
  <groupId>io.github.loom-ai-labs</groupId>
  <artifactId>ai-fabric-chat-session</artifactId>
</dependency>
```

## Configuration

```yaml
ai:
  chat:
    enabled: true
    window-size: 12
    max-context-chars: 8000
    auto-create-sessions: true
    pinned-target-reuse-window-turns: 3
```

The module creates and uses `chat_sessions` and `chat_turns` through JPA.

## Request Shape

Your app should send:

- current message
- stable `conversationId`
- stable owner/current user id
- optional attachments or selected targets

Your app should not send a handcrafted prompt containing old turns. The chat-session pipeline reads
history from storage and enriches the prompt consistently.

## What It Solves

- User says `yes` after a confirmation prompt.
- User says `add it` after the assistant suggested an action.
- User says `compare those` after a result list.
- User selects an attachment once and asks follow-up questions.
- Action drafts survive page refresh within the same conversation.

## Implementation Notes

- Keep conversation IDs scoped per user/session.
- Do not share session IDs across public demo users.
- Keep pinned targets short; they are context, not a durable knowledge base.
- Use database cleanup for demo sessions and abandoned conversations.

## What To Test

- First turn records user and assistant response.
- Second turn includes recent history.
- Pending confirmation persists until confirm, reject, expiry, or execution.
- User A cannot read or confirm User B's pending action.
- Resetting a demo session creates a new conversation scope.

## Real App Reference

Copy from:

- `examples/real-apps/ai-fabric-account-resolver`
- `examples/real-apps/chat-capabilities-demo`

