package ai.fabric.execution.specialist;

import java.time.Duration;
import java.util.Objects;

/**
 * Locally enforceable bounds for one specialist invocation.
 */
public record SpecialistLimits(
    Duration maxDuration,
    int maxInputCharacters,
    int maxGroundingCharacters,
    int maxEvidenceReferences
) {
    public SpecialistLimits {
        Objects.requireNonNull(maxDuration, "maxDuration is required");
        if (maxDuration.isZero() || maxDuration.isNegative()) {
            throw new IllegalArgumentException("maxDuration must be positive");
        }
        if (maxInputCharacters < 1) {
            throw new IllegalArgumentException("maxInputCharacters must be positive");
        }
        if (maxGroundingCharacters < 1) {
            throw new IllegalArgumentException(
                "maxGroundingCharacters must be positive"
            );
        }
        if (maxEvidenceReferences < 0) {
            throw new IllegalArgumentException("maxEvidenceReferences must not be negative");
        }
    }

    public SpecialistLimits(
        Duration maxDuration,
        int maxInputCharacters,
        int maxEvidenceReferences
    ) {
        this(
            maxDuration,
            maxInputCharacters,
            maxInputCharacters,
            maxEvidenceReferences
        );
    }

    public static SpecialistLimits defaults() {
        return new SpecialistLimits(Duration.ofSeconds(45), 12_000, 16_000, 20);
    }
}
