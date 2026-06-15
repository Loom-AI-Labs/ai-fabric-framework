package ai.fabric.chat.interception;

import ai.fabric.dto.Intent;
import java.util.List;
import java.util.Set;

/**
 * Result of a confirmation interception handler.
 *
 * @param intents the intents to replace the extracted intent response with
 * @param confirmedActions action names to mark as confirmed for this request
 */
public record InterceptionDecision(
    List<Intent> intents,
    Set<String> confirmedActions
) {
}

