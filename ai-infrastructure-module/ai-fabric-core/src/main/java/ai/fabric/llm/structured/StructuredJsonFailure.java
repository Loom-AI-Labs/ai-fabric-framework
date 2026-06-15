package ai.fabric.llm.structured;

public record StructuredJsonFailure(
    StructuredJsonFailureType type,
    String message,
    boolean truncationSuspected
) {
}

