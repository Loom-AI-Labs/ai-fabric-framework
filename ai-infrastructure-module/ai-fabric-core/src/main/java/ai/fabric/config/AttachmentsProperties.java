package ai.fabric.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for request-level attachments (UI context) used to ground intent extraction and actions.
 */
@Data
@ConfigurationProperties(prefix = "ai.orchestration.attachments")
public class AttachmentsProperties {

    public enum VectorSpaceValidationMode {
        BEST_EFFORT,
        STRICT
    }

    /**
     * Enables attachment normalization and prompt grounding.
     */
    private boolean enabled = true;

    /**
     * Maximum number of attachments accepted per request.
     */
    private int maxAttachments = 10;

    /**
     * Maximum characters kept for attachment content used for grounding.
     */
    private int maxContentTextChars = 2000;

    /**
     * Maximum metadata keys accepted per attachment.
     */
    private int maxMetadataKeys = 12;

    /**
     * Maximum characters kept for any metadata value after normalization.
     */
    private int maxMetadataValueChars = 120;

    /**
     * Maximum characters kept for attachment id.
     */
    private int maxIdChars = 80;

    /**
     * Maximum characters kept for vectorSpace.
     */
    private int maxVectorSpaceChars = 80;

    /**
     * Validation mode for {@code attachments[].vectorSpace}.
     *
     * <p><strong>BEST_EFFORT (default):</strong> keep the attachment but do not use invalid vectorSpace values for scoping.</p>
     * <p><strong>STRICT:</strong> terminate with CLARIFICATION_REQUIRED when an invalid vectorSpace is provided.</p>
     */
    private VectorSpaceValidationMode vectorSpaceValidationMode = VectorSpaceValidationMode.BEST_EFFORT;

    /**
     * Maximum characters kept for source label.
     */
    private int maxSourceChars = 60;
}
