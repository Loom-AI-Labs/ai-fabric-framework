package ai.fabric.evidence;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Provider-neutral, policy-filtered reference to evidence used by an AI execution.
 */
public record AIEvidenceReference(
    String evidenceId,
    String content,
    Double relevanceScore,
    String source,
    String sourceUrl,
    String vectorSpace,
    Map<String, Object> safeMetadata
) {
    public AIEvidenceReference {
        evidenceId = requireText(evidenceId, "evidenceId");
        content = requireText(content, "content");
        source = normalizeOptional(source);
        sourceUrl = normalizeOptional(sourceUrl);
        vectorSpace = normalizeOptional(vectorSpace);
        safeMetadata = safeMetadata == null || safeMetadata.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(safeMetadata));
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field + " is required").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
