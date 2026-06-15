package ai.fabric.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for optional post-action LLM generation for action handlers.
 *
 * <p>When enabled, an ACTION intent may request an additional generation step (LLM purpose: {@code GENERATION})
 * grounded strictly in facts provided by the action handler via {@code AIActionHandler.buildPostActionLlmFacts(...)}.</p>
 */
@Data
@Validated
@ConfigurationProperties(prefix = "ai.post-action-generation")
public class PostActionGenerationProperties {

    /**
     * Enable post-action generation for actions that opt in by providing LLM-safe facts.
     */
    private boolean enabled = false;

    /**
     * Maximum character budget for the facts payload passed to the generation LLM.
     */
    @Min(500)
    @Max(50000)
    private int maxChars = 12000;

    /**
     * Temperature used for the post-action generation call.
     */
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double temperature = 0.2d;

    /**
     * Max tokens for the post-action generation call.
     */
    @Min(50)
    @Max(8192)
    private int maxTokens = 800;

    /**
     * Optional timeout override (seconds) for the post-action generation call.
     * When null, purpose defaults from {@code ai.providers.generation} apply.
     */
    @Min(1)
    @Max(300)
    private Integer timeoutSeconds;
}
