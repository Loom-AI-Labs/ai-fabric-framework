package ai.fabric.filter;

import java.util.List;
import java.util.Map;

/**
 * Provider-owned moderation decision. No natural-language inference is performed by the framework.
 */
public record ContentModerationResult(
    boolean shouldFilter,
    List<String> violations,
    String sanitizedContent,
    Double contentScore,
    List<String> recommendations,
    Map<String, Object> metadata
) {
    public ContentModerationResult {
        violations = violations == null ? List.of() : List.copyOf(violations);
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
