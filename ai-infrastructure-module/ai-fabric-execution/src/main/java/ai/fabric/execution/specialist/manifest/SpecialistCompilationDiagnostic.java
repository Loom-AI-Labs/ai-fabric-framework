package ai.fabric.execution.specialist.manifest;

import java.util.Objects;

public record SpecialistCompilationDiagnostic(
    String reason,
    String message,
    String source
) {
    public SpecialistCompilationDiagnostic {
        reason = requireText(reason, "reason");
        message = requireText(message, "message");
        source = requireText(source, "source");
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(
            value,
            field + " is required"
        ).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
