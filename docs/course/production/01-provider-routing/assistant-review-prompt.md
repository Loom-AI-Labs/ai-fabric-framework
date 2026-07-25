# Coding Assistant Review Prompt: Audit PROD-01 Provider Routing

Review a completed `PROD-01` implementation in
`Loom-AI-Labs/ai-fabric-course-support-assistant` from a findings-first, production release stance.

## Contract To Review

- Framework `0.4.0`, Java 21, Spring Boot 4.1.x
- Starter `course-0.4.0-06-tested-solution`
- Target checkpoint `course-0.4.0-p01-provider-routing`
- Required completion is keyless
- Optional OpenAI evidence must be separately labelled

Before reviewing, verify that both declared refs exist. If either is missing, stop and report that
the 0.4 checkpoint comparison is not published; never substitute `main` or an older 0.3 tag.

## Review Priorities

1. **Purpose correctness**
   - orchestration calls use `LlmPurpose.ORCHESTRATION`;
   - user-facing grounded generation uses `LlmPurpose.GENERATION`;
   - configured provider and model defaults reach the provider request.
2. **Failure visibility**
   - fallback is false;
   - generation failure does not call the orchestration provider;
   - no canned answer turns a failed live route into success.
3. **Credential safety**
   - no API key in YAML, source, request files, reports, logs, tests, or generated website content;
   - health exposes only safe provider/model/posture facts.
4. **Profile truthfulness**
   - local uses ONNX/Lucene and reports LLM purposes disabled;
   - test fixtures have unmistakably test-only names;
   - OpenAI claims require an actually executed keyed check.
5. **Regression coverage**
   - all Core RAG, actions, memory, tenant, PII, and packaged smoke behavior remains intact;
   - tests do not use application keyword routing to imitate model intelligence.

## Required Commands

```bash
git diff course-0.4.0-06-tested-solution...HEAD --check
./mvnw --batch-mode --no-transfer-progress clean verify
./scripts/download-onnx-model.sh
COURSE_SMOKE_USE_EXISTING_JAR=true ./scripts/smoke-packaged.sh
jq . target/course-release-evidence/packaged-smoke-summary.json
```

Also scan tracked content for likely credentials and inspect `/api/demo/health` field names. Do not
print any secret found; identify only the file and remediation.

## Output Format

Report findings first, ordered by severity, with file/line references. Then provide:

- open questions or assumptions;
- deterministic test count and result;
- packaged smoke result and checkpoint;
- optional OpenAI status as `PASS`, `FAIL`, or `SKIPPED` with reason;
- a short change summary only after findings.

If there are no findings, say so directly and name any residual unexecuted live-provider risk.
