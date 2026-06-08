package com.ai.infrastructure.intent.action;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Describes an action exposed to the RAG orchestrator.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AIActionMetaData {

    private String name;

    private String displayName;

    private String description;

    private String category;

    /**
     * Side-effect semantics for this action.
     */
    private ActionAccessMode accessMode;

    /**
     * Whether this action may execute for anonymous orchestration requests.
     *
     * <p>Defaults to {@code false}. Deployments that intentionally support a guest-safe anonymous surface
     * must opt in per action via the action contract instead of relying on runtime hardcoded allowlists.</p>
     */
    private boolean anonymousAllowed;

    /**
     * Whether this action requires an explicit confirmation step before execution.
     */
    private boolean confirmationRequired;

    /**
     * Whether this action's result is eligible to ground answer generation.
     */
    private boolean groundingEligible;

    /**
     * Whether this READ action may be selected by the read-action resolution planner.
     */
    private boolean readActionResolutionEligible;

    /**
     * Stable side-effect posture for shell and governance surfaces.
     */
    private ActionSideEffectLevel sideEffectLevel;

    /**
     * Advisory shell hint for rendering action outcomes.
     */
    private ActionResultPresentationHint resultPresentationHint;

    /**
     * Optional built-in shell module mapping for this action.
     */
    private String builtInModuleId;

    /**
     * Optional built-in shell card mapping for this action.
     */
    private String builtInCardId;

    /**
     * Resolved provenance metadata for this action capability.
     */
    private AIContributionProvenance provenance;

    @Builder.Default
    private Map<String, String> parameters = Collections.emptyMap();

    /**
     * Structured parameter schemas for actionParams.
     *
     * <p>This is the contract the LLM should follow when populating actionParams, including
     * array/object shapes for batch-capable actions.</p>
     */
    @Builder.Default
    private Map<String, AIActionParamSchema> parameterSchemas = Collections.emptyMap();

    /**
     * Parameter names that must be provided for the action to execute safely.
     *
     * <p>This is used for deterministic validation and provider-agnostic intent extraction fallbacks.
     * It should not be inferred from human-readable parameter descriptions.</p>
     */
    @Builder.Default
    private Set<String> requiredParameters = Collections.emptySet();

    public void setParameters(Map<String, String> parameters) {
        this.parameters = parameters == null ? Collections.emptyMap() : Map.copyOf(parameters);
    }

    public void setParameterSchemas(Map<String, AIActionParamSchema> parameterSchemas) {
        this.parameterSchemas = parameterSchemas == null ? Collections.emptyMap() : Map.copyOf(parameterSchemas);
    }

    public void setRequiredParameters(Set<String> requiredParameters) {
        this.requiredParameters = requiredParameters == null ? Collections.emptySet() : Set.copyOf(requiredParameters);
    }
}
