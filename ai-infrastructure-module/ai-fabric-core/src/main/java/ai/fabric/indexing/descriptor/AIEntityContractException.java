package ai.fabric.indexing.descriptor;

/**
 * Indicates an invalid entity declaration or incompatible runtime policy.
 */
public class AIEntityContractException extends IllegalStateException {

    public AIEntityContractException(String message) {
        super(message);
    }

    public AIEntityContractException(String message, Throwable cause) {
        super(message, cause);
    }
}
