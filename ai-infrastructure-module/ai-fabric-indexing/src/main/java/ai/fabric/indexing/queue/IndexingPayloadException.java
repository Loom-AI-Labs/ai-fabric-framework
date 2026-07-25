package ai.fabric.indexing.queue;

public class IndexingPayloadException extends RuntimeException {

    private final String errorCode;

    public IndexingPayloadException(String errorCode) {
        super(errorCode);
        this.errorCode = errorCode;
    }

    public IndexingPayloadException(String errorCode, Throwable cause) {
        super(errorCode, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
