package com.ai.infrastructure.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for progressive intent extraction.
 *
 * <p>Progressive intent extraction applies a bounded fallback ladder:
 * compound → repair → completion → multi-step.</p>
 */
@Data
@Validated
@ConfigurationProperties(prefix = "ai.intent-extraction.progressive")
public class ProgressiveIntentExtractionProperties {

    /**
     * Enable progressive fallback (compound → repair → completion → multi-step).
     */
    private boolean enabled = true;

    /**
     * Enable repair step for structurally invalid outputs.
     */
    private boolean repairEnabled = true;

    /**
     * Maximum repair attempts.
     */
    @Min(0)
    @Max(3)
    private int repairMaxAttempts = 1;

    /**
     * Enable multi-step fallback extraction.
     */
    private boolean multiStepEnabled = true;

    /**
     * Enable completion step for contract-incomplete but structurally valid outputs.
     */
    private boolean completionEnabled = true;

    /**
     * Maximum completion attempts.
     */
    @Min(0)
    @Max(3)
    private int completionMaxAttempts = 1;

    /**
     * Force a specific extraction mode for debugging.
     * Allowed values: compound, repair, completion, multi_step, auto (or blank/null).
     */
    private String forceMode;

    /**
     * Maximum total LLM calls allowed for extraction per request (cost control).
     */
    @Min(1)
    @Max(10)
    private int maxTotalLlmCalls = 5;
}
