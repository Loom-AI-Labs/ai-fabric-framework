# CORE-05 Coding-Assistant Implementation Prompt

Status: Migrated to the AI Fabric 0.4 CORE-05 behavioral contract. Immutable checkpoint validation
is pending publication of `course-0.4.0-03-actions`.

```text
You are implementing AI Fabric course lesson CORE-05: Backend-Owned Conversation Memory.

Use AI Fabric 0.4.0 / ai-fabric-framework-v0.4.0, Java 21, and Spring Boot 4.1.x. Before editing,
verify that the declared starter ref exists. If it does not, stop and report that the 0.4 learner
checkpoint is not published; never substitute `main` or an older 0.3 tag. Once published, work only
from
`course-0.4.0-03-actions` in
https://github.com/Loom-AI-Labs/ai-fabric-course-support-assistant. Do not inspect or copy the
`course-0.4.0-04-memory` solution checkpoint while implementing.

Read first:
- docs/course/core/05-backend-conversation-memory/lesson.md
- docs/getting-started/06-chat-session-memory.md
- current ChatSessionProperties, ChatSessionBaseConfiguration, ChatSessionServiceImpl,
  ChatSessionAccessControlPolicy, ConversationEnrichmentStep, ConversationRecordingStep, and
  ChatSessionPendingActionStore APIs
- the starter's authentication, support-ticket authorization, and governed escalation action

Goal:
Enable backend-owned chat sessions so the UI sends only a new message and stable conversation ID,
then prove authorized bounded history, short follow-up resolution, pending confirmation continuity,
transient-request behavior, and cross-user denial.

Before editing:
1. Verify the starter ref, worktree, dependency management, database profile, tests, and provider posture.
2. Identify the server-authenticated owner source; do not accept owner or tenant from request JSON.
3. Inspect current chat-session APIs and the existing confirmation action; do not invent memory APIs.
4. Inventory any browser-carried history, pending action, or duplicate chat persistence.
5. Give a concise implementation and test plan.

Required behavior:
1. Add ai-fabric-chat-session and explicit bounded ai.chat configuration.
2. Provide an application-owned ChatSessionAccessControlPolicy.
3. Keep the public request to message, conversationId, and current attachments.
4. Derive owner and tenant from authenticated server context.
5. Record sanitized first-turn messages through AI Fabric session storage.
6. Load role-aware history for a same-conversation follow-up without browser replay.
7. Preserve and consume the owner-scoped pending escalation exactly once.
8. Prevent a new conversation or different owner from reusing prior target or pending state.
9. Prove window, character, target-reuse, and pending-stack bounds.
10. Skip enrichment and recording for trusted NEVER_PERSIST requests.
11. Keep panel visibility and dismissed suggestions in UI state, separate from conversation state.
12. Add a narrow app prompt overlay only if live follow-up classification needs domain guidance.

Testing:
- application context and required-bean startup tests;
- public request-shape test with no historyMessages, pendingAction, ownerId, or tenantId;
- first-turn auto-create and sanitized-recording tests;
- same-ID and new-ID follow-up tests using structured orchestration state;
- blank identity, denied policy, and stored-owner mismatch tests;
- pending push/peek/pop, rejection, approval, and duplicate-approval tests;
- window and max-context whole-message eviction tests;
- pinned-target freshness and fresh-attachment precedence tests;
- NEVER_PERSIST enrichment and recording tests;
- UI reopen test that preserves conversation identity;
- clean ./mvnw clean verify;
- explicit real-provider three-turn smoke when credentials are available.

Do not:
- send or trust browser-rebuilt history, action drafts, or pending action payloads;
- accept ownerId, userId, or tenantId as an untrusted request authority;
- add keyword matching or hard-coded action selection;
- use a permissive production access policy;
- assume stored history guarantees model follow-up quality;
- persist raw sensitive content or ignore NEVER_PERSIST;
- delete backend state when the UI panel closes;
- hide provider failures with deterministic answers;
- use -DskipTests, commit secrets, or discard unrelated changes;
- commit, push, or deploy.

Stop and report when the starter checkpoint is missing, authenticated identity is unavailable,
required storage cannot be configured, pinned APIs contradict the lesson, or a requested live run
lacks credentials.

Finish with changed files, exact command outcomes, request shape, ownership proof, first-turn
storage, history diagnostics, same/new conversation results, pending transitions, mutation counts,
bounds, NEVER_PERSIST proof, live smoke status, unexecuted checks, and the final request/state flow.
```
