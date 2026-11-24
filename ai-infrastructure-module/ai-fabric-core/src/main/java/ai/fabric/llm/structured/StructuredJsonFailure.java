package ai.fabric.llm.structured;

public record StructuredJsonFailure(
    StructuredJsonFailureType type,
    String message,
    boolean truncationSuspected,
    ai.fabric.dto.ProviderOutcome providerOutcome
) {
    public StructuredJsonFailure(StructuredJsonFailureType type, String message, boolean truncationSuspected) {
        this(type, message, truncationSuspected, null);
    }
}

