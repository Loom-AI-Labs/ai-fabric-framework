# CORE-04 Coding-Assistant Implementation Prompt

Status: Validated against `course-0.3.3-02-rag` and the CORE-04 behavioral contract.

```text
You are implementing AI Fabric course lesson CORE-04: Governed Actions And Confirmation.

Use AI Fabric 0.3.3 / ai-fabric-framework-v0.3.3, Java 21, and Spring Boot 4.1.x. Work only from
`course-0.3.3-02-rag` in
https://github.com/Loom-AI-Labs/ai-fabric-course-support-assistant. Do not inspect or copy the
`course-0.3.3-03-actions` solution checkpoint while implementing.

Read first:
- docs/course/core/04-governed-actions/lesson.md
- docs/getting-started/05-first-governed-action.md
- current AIAction, Param, ActionAccessMode, AIActionRegistry, ActionContext, ActionResult, and
  PendingActionStore APIs
- the starter's existing support domain authorization and transaction code

Goal:
Register get_my_ticket_status and create_support_ticket, keep identity and tenant in trusted backend
context, require confirmation for creation, and prove clarification, authorization, rejection,
single execution, and safe result projection.

Before editing:
1. Verify the starter ref, tests, provider profile, authentication context, and worktree.
2. Inspect pinned action APIs and existing domain services; do not invent annotations or modes.
3. Produce the action catalog and user-parameter/context-owned-value table.
4. Identify where object authorization and transactions already belong.
5. Give a concise implementation and test plan.

Required behavior:
1. Register a READ get_my_ticket_status handler with explicit planner eligibility.
2. Register a WRITE_ONLY create_support_ticket handler with confirmation required.
3. Expose only ticketNumber or subject/description/priority through @Param as appropriate.
4. Resolve subject, tenant, conversation, and session from ActionContext.
5. Keep @ActionAllowed as an early gate and reauthorize in TicketService.
6. Clarify missing required values without mutation or complete pending confirmation.
7. Store a complete pending action before execution.
8. Reject without mutation; confirm exactly once; make duplicate confirmation non-mutating.
9. Return concise ActionResult payload facts, never JPA entities.
10. Add one real-provider smoke with no text-matching or canned-success fallback.

Testing:
- registry metadata and model-visible parameter-schema tests;
- duplicate/invalid registration tests where relevant;
- missing-parameter clarification test;
- missing identity and cross-tenant denial tests;
- confirmation-required, rejection, confirmation, and duplicate-confirmation tests;
- API/result projection test;
- clean `./mvnw clean verify`;
- explicit live-provider result when credentials are available.

Do not:
- add browser or backend keyword matching for action selection;
- expose userId, tenantId, accountId, conversationId, or sessionId as @Param;
- treat typed values as authorization;
- execute the write before a consumed backend confirmation;
- trust client-replayed action payloads as pending state;
- return persistence objects or raw provider output;
- hide provider failures;
- use -DskipTests, commit secrets, or discard unrelated changes;
- commit, push, or deploy.

Stop and report when the starter checkpoint is missing, pinned APIs contradict the lesson, trusted
identity is unavailable, or a requested live run lacks credentials.

Finish with changed files, exact command outcomes, registry schema, clarification proof,
authorization proof, confirmation transitions, domain mutation counts, safe result shape, live smoke
status, unexecuted checks, and the final action flow.
```
