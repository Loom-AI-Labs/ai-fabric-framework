package ai.fabric.execution.gateway;

/**
 * Public-safe denial raised while resolving a specialist's effective capabilities.
 */
public final class SpecialistCapabilityResolutionException
    extends RuntimeException {

    private final String reason;

    public SpecialistCapabilityResolutionException(
        String reason,
        String message
    ) {
        super(message);
        this.reason = requireText(reason, "reason");
    }

    public String reason() {
        return reason;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
