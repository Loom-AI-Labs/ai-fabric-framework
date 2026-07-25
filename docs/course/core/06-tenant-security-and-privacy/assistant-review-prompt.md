# CORE-06 Independent Review Prompt

Status: Migrated for independent review of the AI Fabric 0.4 CORE-06 behavior contract. Immutable
checkpoint comparison is pending publication.

Review a developer's CORE-06 implementation against AI Fabric Framework 0.4.0, the current CORE-06
course source, and pinned access, vector-filter, PII, response-sanitization, action, and chat APIs.

Use a findings-first review. Order findings by severity and cite changed files/tests plus the course
or pinned source. Trace one allowed and one denied request from verified authentication through
retrieval or action, response projection, and conversation persistence.

Check for these failure classes:

- tenant, owner, role, or scope trusted from request JSON or an unverified header;
- missing or optimistic-default `EntityAccessPolicy`;
- entry policy incorrectly presented as automatic row-level vector or action authorization;
- indexed evidence missing trusted tenant or positive visibility metadata;
- inequality, unsupported, or silently widened required metadata filters;
- forbidden hit reaching prompt context, citations, logs, chat working set, or backend response;
- frontend masking presented as evidence isolation;
- cross-tenant or unauthorized action reaching pending confirmation;
- confirmation treated as an authorization grant;
- PII dependency present but detection disabled or left in `PASS_THROUGH`;
- raw PII entering persistence, vector content, embedding query, generation request, response, logs, or chat;
- detector failure returning raw content in a privacy-critical path;
- `HASH:` protected-original data described as encrypted;
- regex detection presented as complete or compliant;
- payment secrets accepted through ordinary chat/action parameters;
- deterministic success hiding provider or policy failure;
- negative tests asserting only UI labels or generated refusal prose.

Then report:

1. findings with severity;
2. missing proof or unexecuted checks;
3. whether canonical identity is server-derived;
4. whether entry, retrieval, and action policies are distinct and fail closed;
5. whether forbidden evidence is absent before generation;
6. whether unauthorized writes stop before confirmation;
7. whether every ordinary sensitive-data sink is proved redacted;
8. whether protected-original and transient behavior is described accurately;
9. the smallest corrections required to pass CORE-06.

The lesson passes only when backend tests prove that forbidden evidence and matched sensitive values
do not cross their allowed boundaries, independently of model behavior and frontend presentation.
