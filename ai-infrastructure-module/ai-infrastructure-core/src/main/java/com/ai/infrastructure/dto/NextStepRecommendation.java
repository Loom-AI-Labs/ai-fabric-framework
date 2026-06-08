package com.ai.infrastructure.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Suggestion returned by the intent extraction layer describing the most helpful
 * follow-up step for the user.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class NextStepRecommendation {

    /**
     * Machine friendly identifier of the recommended intent (e.g. {@code show_refund_process}).
     */
    private String intent;

    /**
     * Suggested follow-up query that should be executed to fulfil the recommendation.
     */
    private String query;

    /**
     * Natural language rationale describing why this recommendation is appropriate.
     */
    private String rationale;

    /**
     * Confidence score (0.0 - 1.0) indicating how confident the model is in this recommendation.
     */
    private Double confidence;

    /**
     * Knowledge base vector space or entity type to search for this recommendation
     * (e.g. 'faq', 'policies', 'reference'). Specifies where the follow-up query should retrieve documents from.
     */
    private String vectorSpace;
}
