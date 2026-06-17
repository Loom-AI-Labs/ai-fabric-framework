# Post-Action Generation for Action Handlers (External Users)

This guide explains how to enable **post-action LLM generation** for custom `ActionHandler`s.

**What it does**
- The framework executes your action **once** (`executeAction(...)`).
- If the intent requests generation and your handler opts in, the framework calls the LLM (purpose: `GENERATION`) using **ONLY handler-provided facts**.
- The final response includes the raw `ActionResult` plus an LLM-generated `summary`.

**Safety model (greenfield)**
- Disabled by default.
- Strict opt-in: if your handler returns `Optional.empty()` facts, **no LLM call happens**.
- You explicitly shape what is sent to the LLM; the framework does not auto-dump domain objects.

---

## 1) Enable Post-Action Generation

```yaml
ai:
  post-action-generation:
    enabled: true
    max-chars: 12000
    max-tokens: 800
    temperature: 0.2
```

Notes:
- Generation happens only when the extracted intent has `requiresGeneration=true` (and/or `generationInstructions`).
- Generation is skipped when `ActionResult.success=false`.

---

## 2) Opt In From Your Action Handler (Facts Shaping)

Implement `buildPostActionLlmFacts(...)` and return a **small, safe map** (primitives/maps/lists recommended):

```java
@Component
public class MyActionHandler implements ActionHandler {
  // ... getActionMetadata(), validateActionAllowed(), etc.

  @Override
  public ActionResult executeAction(Map<String, Object> params, String userId) {
    // Execute once
    return ActionResult.builder()
      .success(true)
      .message("Action executed")
      .data(Map.of("orderId", "123", "status", "CONFIRMED"))
      .build();
  }

  @Override
  public Optional<Map<String, Object>> buildPostActionLlmFacts(ActionResult result, OrchestrationContext ctx) {
    // Shape what the LLM is allowed to see (no side effects, no re-execution)
    return Optional.of(Map.of(
      "orderId", "123",
      "status", "CONFIRMED"
    ));
  }
}
```

PII guidance:
- Treat the facts payload as “allowed to leave your system”.
- If you can’t send it to an LLM, don’t include it in `buildPostActionLlmFacts(...)`.

---

## 3) What You Get Back

When generation runs successfully:
- `OrchestrationResult.type` = `ACTION_EXECUTED`
- `OrchestrationResult.message` = generated summary
- `OrchestrationResult.data` includes:
  - `actionResult` (raw)
  - `summary` (generated)
  - `postActionGeneration` (metadata: `used`, `truncated`, `includedItems`, optional `model`)

When generation is skipped/fails:
- The action result is still returned.
- `postActionGeneration.used=false` and `message` remains the action’s message.

---

## Relationship Query Note

`relationship_query` has its own dedicated post-action generation switch:

```yaml
ai:
  relationship-query:
    post-action-generation:
      enabled: true
```

That path is intentionally separate because it needs query-specific materialization (`returnMode=FULL`) and a facts payload derived from relational results.

Important:
- The framework does **not** silently change `relationship_query` parameters. If you want post-action summarization, set `returnMode=FULL` explicitly in your application/request flow.
