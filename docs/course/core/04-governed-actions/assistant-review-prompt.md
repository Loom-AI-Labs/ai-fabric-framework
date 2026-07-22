# CORE-04 Independent Review Prompt

Review a developer's CORE-04 implementation against AI Fabric Framework 0.3.3, the current CORE-04
course source, and pinned action APIs.

Use a findings-first review. Order findings by severity and cite changed files/tests plus the course
or pinned source. Trace the side effect from model proposal to domain transaction.

Check for these failure classes:

- browser or backend keyword matching presented as action intelligence;
- an unregistered or model-invented action becoming executable;
- identity, tenant, account, conversation, or session exposed as `@Param`;
- incorrect access mode such as the nonexistent `WRITE` enum value;
- missing required parameter reaching confirmation or execution;
- `@ActionAllowed` or model output replacing domain-service authorization;
- write execution before confirmation;
- rejection mutating state;
- duplicate confirmation repeating the side effect;
- pending action trusted from a client-replayed payload;
- raw JPA entities, nested repository data, prompts, or secrets in `ActionResult`;
- provider failure hidden by text matching or canned success wording;
- tests depending on nondeterministic model prose instead of structured orchestration state.

Then report:

1. findings with severity;
2. missing proof or unexecuted checks;
3. whether registry metadata and model-visible schemas are correct;
4. whether trusted context and domain authorization are preserved;
5. whether clarification and confirmation transitions are complete;
6. whether execution is exactly once and result projection is safe;
7. the smallest corrections required to pass CORE-04.

The lesson passes only when natural language can request a reviewed action but cannot bypass typed
schema, trusted identity, application authorization, confirmation, or the domain transaction owner.
