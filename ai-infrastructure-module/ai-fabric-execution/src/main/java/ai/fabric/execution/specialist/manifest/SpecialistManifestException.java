package ai.fabric.execution.specialist.manifest;

/**
 * Safe startup-time manifest failure with a stable reason code.
 */
public final class SpecialistManifestException extends RuntimeException {

    private final String reason;
    private final String source;

    public SpecialistManifestException(
        String reason,
        String message,
        String source
    ) {
        super(message);
        this.reason = requireText(reason, "reason");
        this.source = normalize(source);
    }

    public SpecialistManifestException(
        String reason,
        String message,
        String source,
        Throwable cause
    ) {
        super(message, cause);
        this.reason = requireText(reason, "reason");
        this.source = normalize(source);
    }

    public String reason() {
        return reason;
    }

    public String source() {
        return source;
    }

    private static String requireText(String value, String field) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
