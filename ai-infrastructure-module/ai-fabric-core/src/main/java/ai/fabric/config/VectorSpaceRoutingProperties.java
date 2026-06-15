package ai.fabric.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for vectorSpace routing when intent extraction omits vectorSpace.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "ai.rag.vectorspace-routing")
public class VectorSpaceRoutingProperties {

    /**
     * Routing strategy.
     */
    private Strategy strategy = Strategy.BOUNDED_FAN_OUT;

    /**
     * Maximum number of spaces to consider in fan-out mode.
     */
    @Min(1)
    @Max(10)
    private int fanOutMaxSpaces = 3;

    /**
     * Top K documents to retrieve per space in fan-out mode.
     */
    @Min(1)
    @Max(20)
    private int fanOutTopKPerSpace = 5;

    /**
     * Similarity threshold used for each individual fan-out RAG query.
     *
     * <p>This is passed to {@code RAGRequest.threshold} and allows frameworks/apps to avoid pulling extremely
     * low-quality matches from each space. Use {@code 0.0} to disable threshold filtering.</p>
     */
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double fanOutRagThreshold = 0.3d;

    /**
     * Minimum similarity score to consider fan-out successful.
     *
     * <p>If retrieval scores are available and the best score is below this threshold,
     * the pipeline will ask the user to clarify the desired domain.</p>
     */
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double clarificationThreshold = 0.4d;

    public enum Strategy {
        HEURISTIC,
        BOUNDED_FAN_OUT,
        CLARIFICATION
    }
}
