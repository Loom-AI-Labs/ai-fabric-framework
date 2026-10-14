# Conversation And Execution

## Conversation Ownership

Use `ai-fabric-chat-session` when the backend must own bounded history, pending state, conversation access, and authority transitions. The client sends the latest user turn plus stable session/conversation identity; it must not become the authoritative transcript store.

- Bind every conversation to its trusted owner and tenant/deployment scope.
- Load only bounded approved history into orchestration.
- Persist sanitized turns after result normalization.
- Reject stale or cross-owner conversation IDs instead of silently creating a replacement under the same ID.
- Do not transfer anonymous history, pending confirmations, or handover state into a newly authenticated conversation unless a separate explicit migration contract authorizes it.

Read [Interactive Dialogue Ownership](../../../docs/Framework-Dev-Guides/application-patterns/INTERACTIVE_DIALOGUE_OWNERSHIP.md) for session boundaries.

## Human Handover

Conversation control is distinct from Human Review. Review approves a proposed effect; handover temporarily changes who may speak for a conversation.

- Use the conversation-control gateway/store and channel provider contracts.
- Treat `AI_ACTIVE`, transition, `HUMAN_ACTIVE`, and return-to-AI states as server-owned lifecycle state.
- A model-recommended transfer follows configured confirmation policy; an explicit user request may use the separately declared policy.
- While human authority is active, AI generation and actions remain blocked for that conversation.
- External operator systems own operator authentication, queues, notifications, and message delivery. The framework supplies the generic authority/lifecycle contract.
- Return to AI only through an authorized explicit transition unless the application has a separately reviewed expiry policy.

Read [Conversation Control And Human Handover](../../../docs/Framework-Dev-Guides/application-patterns/CONVERSATION_CONTROL_AND_HUMAN_HANDOVER.md) and inspect [`conversation-handover-demo`](../../../examples/real-apps/conversation-handover-demo).

## Specialists And Declarative Execution

Use `ai-fabric-execution` for bounded specialists and declarative chains when responsibilities are genuinely distinct. Keep worker inputs/outputs typed, budgets explicit, state durable when required, and business effects behind governed actions. Do not label one prompt with several personas and call it a specialist team.

For chain work, read [Bounded Multi-Specialist Chains](../../../docs/Framework-Dev-Guides/application-patterns/BOUNDED_MULTI_SPECIALIST_CHAINS.md). Verify restart, replay/idempotency, cancellation, definition drift, tenant isolation, and provider failure. A background plan or event trigger does not weaken action authorization.

## Unsupported Requests

Stop and report a public-contract gap when the request requires an endpoint, lifecycle transition, durable store, provider feature, or ownership model not present in released source/docs. Include the inspected symbols and closest supported alternative. Do not copy private runtime code or invent a compatibility layer.
