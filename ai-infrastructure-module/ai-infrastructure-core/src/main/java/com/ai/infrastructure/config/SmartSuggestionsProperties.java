package com.ai.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "ai.smart-suggestions")
public class SmartSuggestionsProperties {

    /**
     * Enable or disable smart suggestion retrieval.
     */
    private boolean enabled = true;

    /**
     * Minimum confidence required for a next-step recommendation to trigger smart suggestions.
     */
    private double minConfidence = 0.70d;

    /**
     * Confidence threshold for upgrading the suggestion priority to PRIMARY.
     */
    private double primaryConfidence = 0.85d;

    /**
     * Maximum number of documents to retrieve when generating a smart suggestion.
     */
    private int retrievalLimit = 3;

    /**
     * Similarity threshold applied when generating smart suggestion retrievals.
     */
    private double retrievalThreshold = 0.55d;

    /**
     * Whether to include the rationale in the smart suggestion payload.
     */
    private boolean includeRationale = true;
}
