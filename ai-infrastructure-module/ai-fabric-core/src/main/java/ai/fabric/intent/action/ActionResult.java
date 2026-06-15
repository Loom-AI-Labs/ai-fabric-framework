package ai.fabric.intent.action;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Encapsulates the outcome of executing an action handler.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ActionResult {

    private boolean success;

    private String message;

    private ActionPayload data;

    /**
     * Explicit, domain-agnostic targets that should be pinned for follow-up turns.
     *
     * <p>Greenfield contract: if an action wants the framework to deterministically support follow-ups
     * (e.g., "cancel it", "change address"), it must return pinnable targets explicitly instead of
     * relying on domain-key heuristics.</p>
     */
    private List<ActionTargetRef> pinnedTargets;

    private String errorCode;
}
