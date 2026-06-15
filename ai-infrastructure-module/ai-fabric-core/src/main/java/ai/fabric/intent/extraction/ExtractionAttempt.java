package ai.fabric.intent.extraction;

import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.IntentExtractionValidator;
import lombok.Builder;
import lombok.Data;

/**
 * Result of a single intent extraction attempt.
 */
@Data
@Builder
public class ExtractionAttempt {

    /**
     * Whether extraction succeeded (structurally valid).
     */
    private boolean success;

    /**
     * Parsed response (may be null on failure).
     */
    private MultiIntentResponse response;

    /**
     * Validation result (may be null on failure).
     */
    private IntentExtractionValidator.ValidationResult validationResult;

    /**
     * Raw model content (useful for repair attempts).
     */
    private String rawContent;

    /**
     * The generation request used for this attempt (useful for repair attempts).
     */
    private AIGenerationRequest generationRequest;

    /**
     * Error message if failed.
     */
    private String errorMessage;

    /**
     * Exception if failed.
     */
    private Exception exception;

    /**
     * Strategy name that produced this attempt.
     */
    private String strategyName;

    /**
     * Number of LLM calls made by this attempt.
     */
    private int llmCalls;

    /**
     * Wall-clock processing time for this attempt.
     */
    private Long processingTimeMs;

    /**
     * Provider-reported processing time for this attempt when available.
     */
    private Long providerProcessingTimeMs;

    /**
     * Model used for this attempt when available.
     */
    private String model;

    public boolean isStructuralFailure() {
        return validationResult != null && validationResult.isStructuralFailure();
    }
}
