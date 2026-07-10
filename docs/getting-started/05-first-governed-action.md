# First Governed Action

Governed actions let the assistant propose app work without giving the LLM direct database or HTTP
power. The action handler is normal Java code; AI Fabric decides when to propose it, extracts
parameters, asks for confirmation when required, and executes only through registered handlers.

## Dependencies

```xml
<dependency>
  <groupId>io.github.loom-ai-labs</groupId>
  <artifactId>ai-fabric-starter</artifactId>
</dependency>
<dependency>
  <groupId>io.github.loom-ai-labs</groupId>
  <artifactId>ai-fabric-chat-session</artifactId>
</dependency>
```

For connector-backed actions:

```xml
<dependency>
  <groupId>io.github.loom-ai-labs</groupId>
  <artifactId>ai-fabric-actions-connector</artifactId>
</dependency>
<dependency>
  <groupId>io.github.loom-ai-labs</groupId>
  <artifactId>ai-fabric-actions-registry</artifactId>
</dependency>
```

## Define An Action

```java
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.annotation.AIAction;
import org.springframework.stereotype.Component;

@AIAction(
    name = "update_payment_method",
    description = "Update the current account payment method after user confirmation.",
    category = "billing",
    accessMode = ActionAccessMode.WRITE,
    requiresConfirmation = true
)
public class UpdatePaymentMethodActionHandler {
    public Map<String, Object> execute(Map<String, Object> params) {
        String last4 = String.valueOf(params.get("last4"));
        return billingService.updateCurrentUserPaymentMethod(last4);
    }
}
```

Action design rules:

- Do not ask the user for IDs the app already knows, such as the current user's subscription ID.
- For side effects, set `requiresConfirmation = true`.
- Use `readActionResolutionEligible = true` only for reviewed READ actions.
- Return domain-shaped results that a UI can render without dumping raw objects.
- Fail closed when required parameters are missing.

## Confirmation Flow

With `ai-fabric-chat-session`, pending actions are stored in conversation state. The user can say
`yes`, `confirm`, or reject in a later turn.

```yaml
ai:
  chat:
    enabled: true
    window-size: 12
    max-context-chars: 8000
    auto-create-sessions: true
```

The frontend should send the new message and stable conversation/user identifiers. It should not
rebuild its own history prompt.

## What To Test

- Action is discovered in the registry.
- Missing parameters produce clarification, not execution.
- Side-effect action produces a confirmation request.
- Confirm executes the action once.
- Reject does not execute the action.
- Action result is concise and domain-shaped.
- Access policy blocks anonymous or unauthorized calls.

## Real App Reference

Copy from:

- `examples/real-apps/ai-fabric-account-resolver`
- `examples/real-apps/it-support-action-bot`
- `examples/real-apps/db-action-registry-lab`
- `examples/real-apps/chat-capabilities-demo`

