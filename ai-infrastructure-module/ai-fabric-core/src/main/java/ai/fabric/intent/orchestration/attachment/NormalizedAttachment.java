package ai.fabric.intent.orchestration.attachment;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

/**
 * Normalized, safe attachment representation used by the orchestration pipeline.
 *
 * <p>All metadata values are scalar and stringified. Limits are enforced by {@code AttachmentNormalizationStep}.</p>
 */
@Value
@Builder(toBuilder = true)
public class NormalizedAttachment {

    String id;

    String vectorSpace;

    String contentText;

    @Builder.Default
    boolean contentTextTruncated = false;

    @Builder.Default
    Map<String, String> metadata = Map.of();

    String source;
}
