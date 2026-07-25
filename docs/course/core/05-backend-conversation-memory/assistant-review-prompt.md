# CORE-05 Independent Review Prompt

Status: Migrated for independent review of the AI Fabric 0.4 CORE-05 behavior contract. Immutable
checkpoint comparison is pending publication.

Review a developer's CORE-05 implementation against AI Fabric Framework 0.4.0, the current CORE-05
course source, and pinned chat-session APIs.

Use a findings-first review. Order findings by severity and cite changed files/tests plus the course
or pinned source. Trace one conversation from authenticated request through enrichment, intent,
pending confirmation, recording, and the next turn.

Check for these failure classes:

- browser-carried history, action drafts, or pending action payloads treated as authority;
- owner, user, or tenant accepted from request JSON instead of authenticated server context;
- missing or permissive production `ChatSessionAccessControlPolicy`;
- policy success used to bypass stored-owner equality;
- a new conversation inheriting another conversation's target or pending action;
- unbounded prompt history, target reuse, or pending stack;
- raw sensitive values stored before sanitization;
- `NEVER_PERSIST` requests enriched or recorded;
- keyword matching presented as short-follow-up intelligence;
- a domain prompt overlay replacing action registration, authorization, or confirmation;
- approval executing more than once or browser replay rebuilding the write;
- panel-close behavior deleting backend conversation state;
- provider failure hidden by canned success;
- deterministic tests asserting model prose instead of structured state.

Then report:

1. findings with severity;
2. missing proof or unexecuted checks;
3. whether the request and identity boundary is correct;
4. whether history and targets are bounded and owner-scoped;
5. whether same/new conversation behavior is proved;
6. whether pending confirmation survives and executes exactly once;
7. whether transient and sensitive requests avoid persistence;
8. whether the live smoke honestly exercises model interpretation;
9. the smallest corrections required to pass CORE-05.

The lesson passes only when the backend, not the browser, owns conversation authority and when
short natural-language follow-ups can use authorized bounded context without bypassing application
policy, confirmation, privacy, or provider-failure visibility.
