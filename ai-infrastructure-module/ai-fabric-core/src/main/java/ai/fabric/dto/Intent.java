package ai.fabric.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a single intent extracted from a user query.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Intent {

    private IntentType type;

    /**
     * Canonical name of the detected intent (e.g. {@code cancel_subscription}).
     */
    private String intent;

    /**
     * Confidence score (0.0 - 1.0) supplied by the intent extractor.
     */
    private Double confidence;

    /**
     * Name of the action to execute when the intent type is {@link IntentType#ACTION}.
     */
    private String action;

    /**
     * Action parameters to be forwarded when executing the action.
     */
    @Builder.Default
    private Map<String, Object> actionParams = Collections.emptyMap();

    /**
     * Logical vector space or index that should be queried to fulfil the intent.
     */
    private String vectorSpace;

    /**
     * Whether this intent requires document retrieval before responding.
     */
    @JsonAlias({"requires_retrieval"})
    private Boolean requiresRetrieval;

    /**
     * Whether this informational intent needs LLM generation after retrieval.
     */
    @JsonAlias({"requires_generation"})
    private Boolean requiresGeneration;

    /**
     * LLM-selected response depth profile for final answer generation.
     *
     * <p>Framework configuration constrains the budget for each profile, but the model
     * decides which profile best fits the request.</p>
     */
    @JsonAlias({"response_profile", "responseProfile", "responseGenerationProfile"})
    private ResponseGenerationProfile responseProfile;

    /**
     * Whether this intent needs target resolution (e.g., resolving "this/it/both" from attachments or prior working set).
     *
     * <p>Set to {@code true} when the request depends on one or more specific entities but the user did not provide
     * explicit identifiers in the message. The orchestration layer may then resolve targets from active attachments or
     * the conversation working set, and will fail-closed (ask a clarification) when targets are ambiguous or missing.</p>
     */
    @JsonAlias({"requires_target_resolution", "requiresTargetResolution"})
    private Boolean requiresTargetResolution;

    /**
     * Direct short answer to return to the user when {@code requiresRetrieval=false}.
     *
     * <p>This allows the intent-extraction LLM to produce a brief reply (e.g., acknowledgements)
     * without triggering retrieval or a second generation call.</p>
     */
    @JsonAlias({"direct_answer", "directAnswer"})
    private String directAnswer;

    /**
     * Optional post-action generation instructions.
     *
     * <p>This is primarily used for chained requests such as "run relationship_query, then summarize/explain the results".
     * When populated for {@link IntentType#ACTION} intents, the orchestrator may execute the action and then invoke the
     * generation LLM using this instruction text plus the action output as grounded facts.</p>
     */
    @JsonAlias({"generation_instructions", "generationInstructions"})
    private String generationInstructions;

    /**
     * Whether this intent needs advanced RAG (query expansion, re-ranking, context optimization).
     *
     * <p>This value is expected to be produced by the intent extractor LLM. When absent (null),
     * the orchestrator may fall back to heuristic complexity checks if configured.</p>
     */
    @JsonAlias({"needs_advanced_rag", "needsAdvancedRAG"})
    private Boolean needsAdvancedRAG;

    /**
     * Optimized, system-terminology aligned query for embeddings.
     */
    private String optimizedQuery;

    /**
     * Optional recommendation that should be surfaced after handling this intent.
     */
    private NextStepRecommendation nextStepRecommended;

    public void setActionParams(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            this.actionParams = Collections.emptyMap();
            return;
        }

        // Jackson can deserialize JSON objects that contain null values, but Map.copyOf rejects null keys/values.
        // Be defensive and drop null entries to avoid NPEs during intent extraction parsing.
        Map<String, Object> cleaned = new java.util.LinkedHashMap<>(params.size());
        params.forEach((key, value) -> {
            if (key == null || value == null) {
                return;
            }
            cleaned.put(key, value);
        });

        this.actionParams = cleaned.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(cleaned);
    }

    /**
     * Returns {@code true} whenever the intent type represents an actionable operation.
     */
    public boolean isActionable() {
        return type == IntentType.ACTION;
    }

    public double confidenceOrDefault(double fallback) {
        return confidence != null ? confidence : fallback;
    }

    public boolean requiresRetrievalOrDefault(boolean fallback) {
        return requiresRetrieval != null ? requiresRetrieval : fallback;
    }

    public boolean requiresGenerationOrDefault(boolean fallback) {
        return requiresGeneration != null ? requiresGeneration : fallback;
    }

    public ResponseGenerationProfile responseProfileOrDefault(ResponseGenerationProfile fallback) {
        return responseProfile != null ? responseProfile : fallback;
    }

    public boolean requiresTargetResolutionOrDefault(boolean fallback) {
        return requiresTargetResolution != null ? requiresTargetResolution : fallback;
    }

    public Boolean getNeedsAdvancedRAG() {
        return needsAdvancedRAG;
    }

    public void setNeedsAdvancedRAG(Boolean needsAdvancedRAG) {
        this.needsAdvancedRAG = needsAdvancedRAG;
    }

    public boolean needsAdvancedRagOrDefault(boolean fallback) {
        return needsAdvancedRAG != null ? needsAdvancedRAG : fallback;
    }

    public String getIntentOrAction() {
        if (intent != null && !intent.isBlank()) {
            return intent;
        }
        return action;
    }

    public void normalize() {
        if (confidence != null) {
            confidence = Math.max(0.0d, Math.min(1.0d, confidence));
        }
        if (requiresRetrieval == null) {
            requiresRetrieval = type == IntentType.INFORMATION;
        }
        if (requiresGeneration == null) {
            requiresGeneration = Boolean.FALSE;
        }
        if (!Boolean.TRUE.equals(requiresGeneration)) {
            responseProfile = null;
        }
        if (requiresTargetResolution == null) {
            requiresTargetResolution = Boolean.FALSE;
        }
        // needsAdvancedRAG is intentionally NOT defaulted here. When null it indicates
        // the LLM did not provide an explicit decision (allowing heuristic fallback).
        if (actionParams == null) {
            actionParams = Collections.emptyMap();
        } else {
            actionParams = Map.copyOf(actionParams);
        }
        if (optimizedQuery != null && optimizedQuery.isBlank()) {
            optimizedQuery = null;
        }
        if (directAnswer != null && directAnswer.isBlank()) {
            directAnswer = null;
        }
        if (generationInstructions != null) {
            String trimmed = generationInstructions.trim();
            if (trimmed.isEmpty()) {
                generationInstructions = null;
            } else if (trimmed.length() > 1000) {
                generationInstructions = trimmed.substring(0, 1000);
            } else {
                generationInstructions = trimmed;
            }
        }
        if (nextStepRecommended != null && nextStepRecommended.getConfidence() != null) {
            double value = Math.max(0.0d, Math.min(1.0d, nextStepRecommended.getConfidence()));
            nextStepRecommended.setConfidence(value);
        }
    }

    public boolean hasValidType() {
        return type != null;
    }

    public boolean hasMeaningfulName() {
        return Objects.nonNull(getIntentOrAction()) && !getIntentOrAction().isBlank();
    }
}
