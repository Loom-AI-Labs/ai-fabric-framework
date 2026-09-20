package ai.fabric.indexing.document.model;

import java.util.Objects;

/** Typed document-ingestion failure with a bounded, transport-safe message. */
public class DocumentIngestionException extends RuntimeException {

    private final DocumentIngestionFailureCode code;

    public DocumentIngestionException(
        DocumentIngestionFailureCode code,
        String safeMessage
    ) {
        super(requireMessage(safeMessage));
        this.code = Objects.requireNonNull(code, "code is required");
    }

    public DocumentIngestionException(
        DocumentIngestionFailureCode code,
        String safeMessage,
        Throwable cause
    ) {
        super(requireMessage(safeMessage), cause);
        this.code = Objects.requireNonNull(code, "code is required");
    }

    public DocumentIngestionFailureCode getCode() {
        return code;
    }

    private static String requireMessage(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("safeMessage is required");
        }
        String normalized = value.trim();
        return normalized.length() <= 256
            ? normalized
            : normalized.substring(0, 256);
    }
}
