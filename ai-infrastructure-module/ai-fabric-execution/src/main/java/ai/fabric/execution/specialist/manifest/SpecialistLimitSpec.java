package ai.fabric.execution.specialist.manifest;

import java.time.Duration;

public record SpecialistLimitSpec(
    Duration maxDuration,
    int maxInputCharacters,
    int maxGroundingCharacters,
    int maxEvidenceReferences,
    int maxOutputCharacters,
    int maxOutputTokens
) {}
