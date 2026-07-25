---
id: case-04
slug: tenant-guard
title: AI Fabric Tenant Guard
track: case-studies
order: 4
durationMinutes: 55
availability: published
courseVersion: 0.4.0-course.4-beta
frameworkVersion: 0.4.0
frameworkTag: ai-fabric-framework-v0.4.0
courseSourceTag: ai-fabric-course-v0.4.0.4
starterRef: course-0.4.0-p08-production-ready
solutionRef: course-0.4.0-p08-production-ready
requiresOpenAi: true
requiresDocker: false
optionalProviderExercises:
  - openai
sourcePaths:
  - examples/real-apps/tenant-knowledge-portal/README.md
  - examples/real-apps/tenant-knowledge-portal/src/main/java/com/ai/fabric/realapps/tenantportal/service/TenantKnowledgeService.java
  - examples/real-apps/tenant-knowledge-portal/src/main/java/com/ai/fabric/realapps/tenantportal/web/TenantGuardDemoController.java
  - examples/real-apps/tenant-knowledge-portal/src/test/java/com/ai/fabric/realapps/tenantportal/service/TenantKnowledgeServiceTest.java
  - examples/real-apps/tenant-knowledge-portal/src/test/java/com/ai/fabric/realapps/tenantportal/web/TenantGuardDemoControllerTest.java
theoryVideoIds:
  - case-tenant-guard-walkthrough
assistant:
  mode: reproduce
  implementationPrompt: assistant-prompt.md
  reviewPrompt: assistant-review-prompt.md
  validationStatus: passed
knowledgeCheck:
  source: knowledge-check.yml
  required: true
  passingScorePercent: 80
---

# Reproduce Tenant-Safe Retrieval And Writes

## Start Here

Tenant Guard proves that identity and access policy constrain retrieval before evidence reaches
generation. It also demonstrates role-aware catalogs, governed writes, and tenant-scoped deletion.

Open:

- live UI: `https://ai-fabric.dev/demos/ai-fabric-tenant-guard`
- backend health: `https://ai-fabric-tenant-guard.46.224.145.148.sslip.io/api/demo/health`
- source: `examples/real-apps/tenant-knowledge-portal`

## Architecture To Recognize

```text
authenticated demo principal
        -> tenant + role policy
        -> AI Fabric metadata filter
        -> vector provider search
        -> application post-hit verification
        -> approved evidence only
        -> optional generation
```

The provider performs exact tenant/visibility filtering where supported. The application verifies
returned hits again and fails closed if the adapter violates the boundary. Prompt wording is not a
tenant control.

## Step 1: Seed Overlapping Documents

Reset your isolated session and seed both demo tenants. Inspect the vector proof. The tenants use
overlapping document titles so successful isolation cannot be explained by different keywords.

## Step 2: Compare Allowed Retrieval

Run the same natural-language query as a regular user in each tenant. Expected:

- tenant A sees only tenant A IDs;
- tenant B sees only tenant B IDs;
- restricted/admin-only evidence is absent for regular users;
- generation receives only the approved hit set.

Inspect the request's effective tenant and role rather than trusting a browser label.

## Step 3: Compare Role Visibility

Inspect the catalog as a regular user and then as an admin in the same tenant. The admin may see
additional approved evidence, but neither role may cross into another tenant.

## Step 4: Govern A Write

Ask the natural-language endpoint to archive an allowed document. Expected:

1. the LLM may select a typed action draft;
2. backend target, tenant, role, and policy checks still run;
3. confirmation is required;
4. reject changes nothing;
5. confirm updates only the allowed tenant document.

Attempt the same target from the other tenant. It must be denied before execution.

## Step 5: Prove Tenant Deletion

Delete one tenant through the explicit demo operation. Inspect remaining vector IDs and query the
other tenant. The deleted tenant's evidence must be absent and the surviving tenant must remain
searchable.

## Intentional Failure

Try to send a different `tenantId` in request data or target a known cross-tenant document ID. A
successful retrieval or mutation is a security failure. A friendly model refusal is not sufficient
proof; verify zero forbidden IDs and zero side effects.

## Run Locally

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml \
  -pl tenant-knowledge-portal -am test
```

Live provider posture requires protected environment values:

```bash
AI_LLM_PROVIDER=openai \
AI_EMBEDDING_PROVIDER=openai \
OPENAI_ENABLED=true \
OPENAI_API_KEY="$OPENAI_API_KEY" \
mvn -f examples/real-apps/tenant-knowledge-portal/pom.xml spring-boot:run
```

## Done When

- overlapping-title retrieval remains tenant scoped;
- regular/admin visibility differs only inside the same tenant;
- forbidden IDs never reach generation;
- cross-tenant write attempts have zero side effects;
- reject and confirm produce distinct, verified state;
- one-tenant deletion preserves the other tenant.

## Next Lesson

CASE-05 moves from evidence authorization to sensitive-input processing and safe persistence.
