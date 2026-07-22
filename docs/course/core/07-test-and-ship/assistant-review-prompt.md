# CORE-07 Independent Release-Gate Review Prompt

Review a developer's CORE-07 evidence against AI Fabric Framework 0.3.3, the current CORE-07 course
source, the application implementation, and the exact artifacts being proposed for release.

Use a findings-first review. Order findings by severity and cite tests, reports, commands,
workflows, health output, served assets, and source revisions. Do not infer a pass from absent
evidence.

Check for these failure classes:

- one successful chat or screenshot presented as release readiness;
- unit or controlled-provider tests mislabeled as packaged or live-provider proof;
- test-skipping flags in normal build, CI, or release commands;
- application tests run against stale locally installed framework artifacts;
- packaged startup presented as endpoint/scenario coverage;
- HTTP scenarios asserting only status 200 or exact model prose;
- missing no-evidence, denial, rejection, expiry, duplicate, cross-owner, tenant, or PII paths;
- Docker contracts mislabeled as OpenAI, hosted embedding, or Pinecone proof;
- required RealAPI rows skipped, conditionally absent, or zero-test but reported green;
- provider, model, profile, test counts, or skip reasons missing from evidence;
- live provider failure replaced by canned/local success;
- health containing secrets or manually stale build metadata;
- backend commit used as proof of frontend asset identity, or the reverse;
- current frontend/backend identity treated as proof of data readiness or workflow correctness;
- secrets, raw PII, private prompts, or confidential fixtures in logs or artifacts;
- release recommendation exceeding the evidence that actually ran.

Then report:

1. findings with severity;
2. a capability-by-evidence matrix;
3. exact successful, failed, skipped, and not-run commands or workflow rows;
4. whether packaged startup and HTTP scenarios are both proved;
5. whether provider posture and failure visibility are honest;
6. whether backend and frontend deployment identity are independently proved;
7. missing artifacts, counts, or skip reasons;
8. residual risks and explicit exceptions;
9. the smallest corrections required for a READY decision.

Return one decision: READY, NOT READY, or READY WITH EXPLICIT EXCEPTION. The Core track passes only
when the decision is reproducible from retained evidence and no deterministic, provider, security,
privacy, packaging, or deployment claim is silently substituted for another.
