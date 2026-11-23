package ai.fabric.spi;

import java.util.UUID;

/** Trusted host bridge to its existing accounting ledger; no implicit unmetered implementation. */
public interface RerankingCallBudget {
    /** Reserve an approved finite bound before one native send; denial must throw before transmission. */
    Attempt reserve(String provider, String model, RerankingProvider.Request request);

    interface Attempt {
        UUID id();
        void settle(RerankingProvider.Usage usage, String providerRequestId);
        /** Retain a conservative hold when the native outcome or accounting cannot be established. */
        void ambiguous(String causeCode);
    }
}
