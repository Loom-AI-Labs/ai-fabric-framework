package ai.fabric.rag;

/** Only transient lexical availability failures, never authorization or funding denial. */
public final class LexicalSearchUnavailableException extends RuntimeException {
    public LexicalSearchUnavailableException(String message) {
        super(message);
    }
}
