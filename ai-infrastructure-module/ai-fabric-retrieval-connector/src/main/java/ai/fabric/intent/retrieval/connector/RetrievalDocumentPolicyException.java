package ai.fabric.intent.retrieval.connector;

/**
 * Safe, stable failure raised by the retrieval response trust boundary.
 */
public final class RetrievalDocumentPolicyException
    extends RuntimeException {

    public static final String VECTOR_SPACE_MISMATCH =
        "VECTOR_SPACE_MISMATCH";
    public static final String RESPONSE_LIMIT_EXCEEDED =
        "RESPONSE_LIMIT_EXCEEDED";
    public static final String DOCUMENT_POLICY_VIOLATION =
        "DOCUMENT_POLICY_VIOLATION";
    public static final String METADATA_POLICY_VIOLATION =
        "METADATA_POLICY_VIOLATION";
    public static final String URL_POLICY_VIOLATION =
        "URL_POLICY_VIOLATION";
    public static final String SANITIZATION_FAILED =
        "SANITIZATION_FAILED";

    private final String errorCode;

    public RetrievalDocumentPolicyException(
        String errorCode,
        String message
    ) {
        super(message);
        if (errorCode == null || errorCode.isBlank()) {
            throw new IllegalArgumentException("errorCode is required");
        }
        this.errorCode = errorCode.trim();
    }

    public String errorCode() {
        return errorCode;
    }
}
