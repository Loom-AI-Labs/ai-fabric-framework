# AI Fabric LLM Session Lessons Learned

Use this document as a compact reference for LLM-assisted AI Fabric debugging sessions. It captures
real issues found while building and deploying the public real-app demos, the symptoms users saw,
the root cause, and the fix pattern.

## How To Use This Reference

When an LLM session is asked to debug an AI Fabric app:

1. Reproduce the failing HTTP request directly with `curl`.
2. Identify whether the error comes from the app controller, AI Fabric orchestration pipeline, provider
   call, action handler, vector provider, or browser/CORS boundary.
3. Inspect the app-owned integration hooks before changing framework code.
4. Add a focused regression test in the real app or framework module that owns the behavior.
5. Keep framework security fail-closed unless the framework contract itself is wrong.

## Lesson 1: `Pipeline step failed: AccessControl`

### User Symptom

The browser chat UI shows an error card:

```text
Pipeline step failed: AccessControl
```

The response from the natural-language endpoint looks like:

```json
{
  "type": "ERROR",
  "success": false,
  "message": "Pipeline step failed: AccessControl"
}
```

### Where It Happened

Real app: `examples/real-apps/ai-fabric-account-resolver`

Endpoint:

```text
POST /api/subscriptions/query
```

Flow:

```text
NaturalLanguageController
  -> RAGOrchestrator
  -> AccessControlStep
  -> AIAccessControlService
  -> EntityAccessPolicy
```

### Root Cause

AI Fabric's `AIAccessControlService` is intentionally fail-closed. It requires the customer
application to provide an `EntityAccessPolicy` bean. If that app-owned policy hook is missing, the
`AccessControlStep` fails before intent extraction, retrieval, or action execution.

This is the correct framework behavior. AI Fabric should not silently allow orchestration without an
application-owned access decision.

### Fix Pattern

Add an app-owned policy bean. For a public demo, keep it narrow and explicit:

```java
@Configuration(proxyBeanMethods = false)
class DemoAccessControlConfiguration {

    @Bean
    EntityAccessPolicy accountResolverDemoEntityAccessPolicy() {
        return (authContext, entity) -> hasSubject(authContext)
            && "rag:intent".equals(entity.get("resourceId"))
            && "READ".equalsIgnoreCase(String.valueOf(entity.get("operationType")));
    }
}
```

For production apps, replace the demo policy with checks against verified identity, tenant,
deployment, customer, scopes, and resource metadata.

### Regression Test

Add a small unit test for the policy:

- grants `rag:intent` / `READ` when `subjectId` or `sessionId` is present;
- grants anonymous-session demo access only if the demo intends to support anonymous users;
- denies unknown resources;
- denies missing subject/session.

### What Not To Do

- Do not disable or bypass `AccessControlStep` in framework code.
- Do not add a global allow-all policy to a production app.
- Do not assume the problem is an LLM/provider failure; this happens before model execution.

## Quick Triage Checklist

### Orchestration Error Before Any AI Response

Check the `message` field:

- `Pipeline step failed: AccessControl`: app probably lacks `EntityAccessPolicy` or policy threw.
- `Pipeline step failed: IntentExtraction`: inspect LLM/provider output, structured JSON parsing, and
  extraction repair.
- `Pipeline step failed: VectorSpaceResolution`: inspect entity type/vector-space config.
- `Pipeline step failed: ResponseSanitization`: inspect unsafe output or policy metadata.

### Browser Shows API Offline

Check in this order:

1. `GET /actuator/health`
2. target route from `curl`
3. `Origin: https://ai-fabric.dev` CORS response
4. frontend bundle contains the intended backend URL
5. deployed frontend environment variables do not override the default URL

### Actions Do Not Execute

Separate these paths:

- Natural-language orchestration: `/query` endpoints go through the pipeline and access policy.
- Manual action endpoint: `/actions/execute` usually reaches `AIActionRegistry` and action handlers
  directly.
- Confirmation flows may intentionally return a non-success response until `confirmed=true`.

## Principles

- Framework-owned security must fail closed.
- Real apps must provide the app-owned policy hooks that production users would also provide.
- Demo policies should be visibly labeled and narrowly scoped.
- Every fix should include a reproducible command, code evidence, and a regression test.
