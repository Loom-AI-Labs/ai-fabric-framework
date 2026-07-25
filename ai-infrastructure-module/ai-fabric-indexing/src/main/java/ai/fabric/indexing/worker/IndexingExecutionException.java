package ai.fabric.indexing.worker;

public class IndexingExecutionException extends RuntimeException {

    private final String errorCode;

    public IndexingExecutionException(String errorCode) {
        super(errorCode);
        this.errorCode = errorCode;
    }

    public IndexingExecutionException(String errorCode, Throwable cause) {
        super(errorCode, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
