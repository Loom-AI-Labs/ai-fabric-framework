package ai.fabric.document;

public class DocumentReadException extends RuntimeException {
    private final String code;
    public DocumentReadException(String code, String message) { super(message); this.code = code; }
    public DocumentReadException(String code, String message, Throwable cause) { super(message, cause); this.code = code; }
    public String code() { return code; }
}
