# CORE-07 Coding-Assistant Verification Prompt

Status: Validated against `course-0.3.3-05-security` and the CORE-07 release-evidence contract.

```text
You are verifying AI Fabric course lesson CORE-07: Test And Ship The Vertical Slice.

Use AI Fabric 0.3.3 / ai-fabric-framework-v0.3.3, Java 21, and Spring Boot 4.1.x. Work only from
`course-0.3.3-05-security` in
https://github.com/Loom-AI-Labs/ai-fabric-course-support-assistant. Do not inspect or copy the
`course-0.3.3-06-tested-solution` solution checkpoint while implementing.

Read first:
- docs/course/core/07-test-and-ship/lesson.md
- docs/getting-started/11-testing-and-verification.md
- docs/getting-started/13-production-checklist.md
- the application's complete CORE-01 through CORE-06 implementation and tests
- current health/build-metadata code, runtime profiles, Dockerfile, CI, and deployment configuration

Goal:
Turn every capability and failure claim into labeled deterministic, packaged-runtime, selected
provider, and deployment evidence without skipping tests, leaking secrets, or hiding provider
failures.

Before editing or running:
1. Verify the starter ref, worktree, Java/Maven versions, profiles, artifact name, and expected providers.
2. Inventory every Core capability, success path, failure path, and forbidden side effect.
3. Classify existing tests as deterministic, packaged, container, keyed, or deployed proof.
4. Identify missing evidence, unavailable dependencies, credentials, and deployment access.
5. Give a concise verification plan and name which checks can run now.

Required behavior:
1. Create a requirement-to-proof matrix for indexing, RAG, actions, memory, tenant security, and PII.
2. Keep local/smoke and live-provider profiles explicit and user-visible.
3. Add deterministic tests for every state transition, denial, no-evidence path, and forbidden side effect.
4. Run a clean Maven verify with no test-skipping flags and retain reports.
5. package the jar, start it under the smoke profile, and exercise representative HTTP scenarios.
6. Run required real vector-engine contracts when a managed vector provider is part of the claim.
7. Run keyed provider smoke only with runtime credentials and label exactly what ran.
8. Prove an unavailable live provider remains an explicit failure with no mutation or canned success.
9. Expose application version, AI Fabric version, source commit, branch, build time, and provider posture without secrets.
10. Verify served frontend asset and backend health independently when both deploy.
11. Record test counts, failures, skips, not-run rows, exceptions, commands, and artifact links.
12. Make the release recommendation only from available evidence.

Testing and evidence:
- domain, registry, orchestration, retrieval/RAG, memory, tenant, and PII reports;
- packaged jar startup log and direct HTTP scenario results;
- Docker/vector report when applicable;
- keyed provider scorecard with provider/model/profile and considered/pass/fail/skip counts;
- explicit live-provider failure result;
- deployed backend health JSON and source commit;
- public HTML and referenced JavaScript asset identity when applicable;
- clean working tree and secret scan;
- final release matrix with PASS, FAIL, SKIPPED, or NOT RUN per row.

Do not:
- use -DskipTests or -Dmaven.test.skip;
- call deterministic doubles live provider tests;
- call jar startup complete endpoint coverage;
- claim Docker proves hosted LLM, embedding, or Pinecone behavior;
- hide unavailable providers behind canned or local success;
- treat skipped/not-run provider rows as passes;
- infer release readiness from health or one UI interaction;
- print or commit keys, raw PII, private prompts, or confidential fixtures;
- discard unrelated changes;
- commit, push, deploy, or trigger paid/manual provider workflows without explicit authorization.

Stop and report when the starter checkpoint is missing, a required deterministic test fails, the jar
cannot start, release-required provider proof cannot run, pinned APIs contradict the lesson, or a
secret-safe execution path is unavailable. Continue with every independent check that remains safe.

Finish with findings first, changed files, exact commands and exit codes, test/report counts,
packaged scenarios, provider rows and posture, failure visibility, deployment identity, skipped/not
run reasons, residual risks, and a release decision of READY, NOT READY, or READY WITH EXPLICIT
EXCEPTION.
```
