package ai.fabric.spi;

/** Only transient availability failures may use an explicitly configured pre-rerank order. */
public final class RerankingUnavailableException extends RuntimeException {
    public enum Reason { CAPACITY, TIMEOUT, TRANSIENT_HTTP, CONNECTION_UNAVAILABLE }
    private final Reason reason;

    public RerankingUnavailableException(Reason reason) {
        super("RERANKING_UNAVAILABLE_" + java.util.Objects.requireNonNull(reason).name());
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
