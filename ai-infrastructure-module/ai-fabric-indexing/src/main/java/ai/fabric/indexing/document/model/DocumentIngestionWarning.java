package ai.fabric.indexing.document.model;

import java.util.Objects;

/** Warning evidence that never contains source content or metadata values. */
public record DocumentIngestionWarning(
    DocumentIngestionWarningCode code,
    String field,
    int count
) {
    public DocumentIngestionWarning {
        Objects.requireNonNull(code, "code is required");
        field = field == null ? "" : field.trim();
        if (field.length() > 128) {
            field = field.substring(0, 128);
        }
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
    }

    public static DocumentIngestionWarning one(
        DocumentIngestionWarningCode code,
        String field
    ) {
        return new DocumentIngestionWarning(code, field, 1);
    }
}
