package ai.fabric.execution.specialist.manifest;

import java.time.Duration;
import java.util.Objects;

/**
 * Hard framework ceilings applied when compiling specialist manifests.
 */
public record SpecialistFrameworkLimits(
    Duration maxDuration,
    int maxInputCharacters,
    int maxGroundingCharacters,
    int maxEvidenceReferences,
    int maxOutputCharacters,
    int maxOutputTokens
) {
    public static final SpecialistFrameworkLimits DEFAULT =
        new SpecialistFrameworkLimits(
            Duration.ofMinutes(5),
            100_000,
            100_000,
            100,
            100_000,
            8_192
        );

    public SpecialistFrameworkLimits {
        Objects.requireNonNull(maxDuration, "maxDuration is required");
        if (maxDuration.isZero()
            || maxDuration.isNegative()
            || maxInputCharacters < 1
            || maxGroundingCharacters < 1
            || maxEvidenceReferences < 0
            || maxOutputCharacters < 1
            || maxOutputTokens < 1) {
            throw new IllegalArgumentException(
                "Specialist framework limits must be positive"
            );
        }
    }
}
