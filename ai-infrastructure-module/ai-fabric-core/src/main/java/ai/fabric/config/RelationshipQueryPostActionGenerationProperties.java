package ai.fabric.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for chaining relationship_query action execution with post-action LLM generation.
 *
 * <p>When enabled, an ACTION intent for {@code relationship_query} can request an additional
 * summarization/explanation step (LLM purpose: {@code GENERATION}) grounded in the action results.</p>
 */
@Data
@Validated
@ConfigurationProperties(prefix = "ai.relationship-query.post-action-generation")
public class RelationshipQueryPostActionGenerationProperties {

    /**
     * Enable post-action generation for relationship_query intents.
     */
    private boolean enabled = false;

    /**
     * Maximum number of items (documents/rows) included in the generation facts payload.
     */
    @Min(1)
    @Max(50)
    private int maxItems = 10;

    /**
     * Maximum character budget for the facts payload passed to the generation LLM.
     */
    @Min(500)
    @Max(50000)
    private int maxChars = 12000;

    /**
     * Temperature used for the post-action summarization call.
     */
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double temperature = 0.2d;

    /**
     * Optional timeout override (seconds) for the post-action generation call.
     * When null, purpose defaults from {@code ai.providers.generation} apply.
     */
    @Min(1)
    @Max(300)
    private Integer timeoutSeconds;
}

