package ai.fabric.indexing.api;

/**
 * Signals an invalid lifecycle boundary or target resolver result.
 */
public class AIProcessContractException extends RuntimeException {

    public AIProcessContractException(String message) {
        super(message);
    }

    public AIProcessContractException(String message, Throwable cause) {
        super(message, cause);
    }
}
