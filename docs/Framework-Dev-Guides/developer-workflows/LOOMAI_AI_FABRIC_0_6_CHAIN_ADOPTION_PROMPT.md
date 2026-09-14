# LoomAI AI Fabric 0.6 Chain Adoption Prompt

Use this prompt in a coding-assistant session that has access to the LoomAI
repository and the immutable AI Fabric `0.6.0` source/API documentation.

```text
You are adopting AI Fabric 0.6.0 bounded multi-specialist chains in LoomAI.

Read before editing:
1. AI Fabric 0.6.0 release notes.
2. BOUNDED_MULTI_SPECIALIST_CHAINS.md.
3. LOOMAI_AI_FABRIC_0_6_CHAIN_MIGRATION_RUNBOOK.md.
4. LoomAI's current specialist manifests, trusted-context construction,
   datasource migrations, deployment health, and private secret conventions.

Goal:
Add one opt-in, read-only LoomAI canary chain whose useful workers depend on
the request or a prior projected result. Preserve every existing non-chain
path and do not enable the chain globally until all gates pass.

Before implementation:
- verify AI Fabric 0.6.0 is immutable and available from Maven Central;
- record the current LoomAI branch, commit, dependency version, and test state;
- identify one exact manager and at most two exact-version read-only workers;
- explain why direct execution, a fixed plan, or the one-worker conversation
  manager is insufficient for this canary;
- identify application-owned trusted identity, tenant, deployment, subject,
  evidence, mapper, projector, migration, and rollback boundaries; and
- choose and record `ai.execution.output-finalization.max-attempts`: `1` keeps
  the previous single-generation behavior, while the `0.6.0` default of `2`
  permits one bounded correction attempt and can increase provider usage; and
- produce a concise file-level implementation and test plan.

Required implementation:
1. Upgrade through the AI Fabric BOM and first prove all existing behavior with
   ai.execution.specialist-chains.enabled=false.
2. Define the manager and workers with existing strict specialist manifests.
3. Give the manager the exact SpecialistChainDirective JSON Schema, with
   additionalProperties=false and exact target enums.
4. Register one Java SpecialistChainDefinition bean with exact component IDs,
   typed input mappers, bounded safe result projectors, conservative limits,
   and the correct conversation policy.
5. Keep every worker READ-only, non-interactive, conversation-isolated, and
   independently authorized.
6. Build TrustedExecutionContext only from authenticated backend state. Strip
   or reject authority-like request fields.
7. Add the reviewed ai_specialist_chain_execution database migration. Use
   initialize-schema=false outside tests.
8. Read two different 32+-character stable secrets from LoomAI's private
   deployment secret boundary. Never print or commit them.
9. Expose async submit, access-scoped status/result, cancellation, safe trace,
   replay, and deployed version/commit readiness.
10. Preserve visible provider, mapping, projection, grounding, persistence,
    deadline, and required-branch failures. Add no route or answer fallback.
11. Configure the structured-output attempt budget explicitly. Do not confuse
    output-finalization attempts with durable specialist-chain recovery
    attempts, and expose usage evidence for every model call.

Required tests:
- no worker, one worker, adaptive two-worker, and independent parallel cases;
- clarification and terminal handoff only if the canary intentionally supports
  them;
- invented target and prompt-injection pressure;
- spoofed tenant, deployment, subject, scope, and evidence fields;
- required branch failure with no final answer;
- exact replay and changed-payload conflict;
- restart after manager decision, one completed worker, and parallel workers;
- uncertain in-flight provider outcome is not blindly rerun;
- current authorization is re-evaluated after restart;
- cancellation, deadline, and discarded late response;
- retention cleanup preserves active records;
- malformed and schema-invalid manager/worker output within and beyond the
  configured output-finalization budget, including proof that rejected payload
  values are absent from logs;
- no raw request, provider payload, trusted context, evidence, or secrets in
  logs or API output;
- packaged application startup and HTTP smoke; and
- valid-key real-provider canaries with no hidden fallback.

Do not:
- add recursive worker delegation or handoff;
- build a general graph runtime;
- add WRITE-capable chain workers;
- let a model select an unregistered target, provider, Mode, action, vector
  space, tenant, or authority;
- send browser-owned history when backend conversation memory is enabled;
- expose raw worker output or chain-of-thought;
- create a LoomAI-only YAML chain format for AI Fabric 0.6.0;
- use deterministic keyword routing as a live fallback;
- use Maven test-skipping flags;
- commit credentials; or
- claim the release/deployment gate passed without direct evidence.

Stop and report instead of improvising when:
- 0.6.0 is not published or does not match the reviewed API;
- the proposed worker needs writes or conversation ownership;
- the datasource migration or stable secrets are unavailable;
- trusted identity/evidence boundaries cannot be derived server-side;
- existing tests fail before the change; or
- a real provider key is unavailable for the keyed gate.

At completion report:
- changed files and why;
- exact commands and test counts;
- deterministic, restart, packaged, keyed, and deployed results separately;
- migration and secret readiness without secret values;
- remaining external gates;
- rollback steps; and
- a final verdict: blocked, source-candidate ready, or fully deployed and
  verified.
```
