package ai.fabric.provider.springai;

import ai.fabric.provider.ProviderStatus;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

final class ProviderMetrics {

    private final String providerName;
    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong successfulRequests = new AtomicLong();
    private final AtomicLong failedRequests = new AtomicLong();
    private final AtomicLong totalResponseTimeMs = new AtomicLong();
    private volatile LocalDateTime lastSuccess;
    private volatile LocalDateTime lastError;
    private volatile String lastErrorMessage;

    ProviderMetrics(String providerName) {
        this.providerName = providerName;
    }

    void recordSuccess(long responseTimeMs) {
        totalRequests.incrementAndGet();
        successfulRequests.incrementAndGet();
        totalResponseTimeMs.addAndGet(Math.max(responseTimeMs, 0L));
        lastSuccess = LocalDateTime.now();
    }

    void recordFailure(Throwable failure) {
        totalRequests.incrementAndGet();
        failedRequests.incrementAndGet();
        lastError = LocalDateTime.now();
        lastErrorMessage = failure != null ? failure.getMessage() : "Unknown provider failure";
    }

    ProviderStatus status(boolean available, String details) {
        long total = totalRequests.get();
        long successes = successfulRequests.get();
        long failures = failedRequests.get();
        double successRate = total == 0 ? (available ? 1.0d : 0.0d) : (double) successes / total;
        double averageResponseTime = successes == 0 ? 0.0d : (double) totalResponseTimeMs.get() / successes;
        return ProviderStatus.builder()
            .providerName(providerName)
            .available(available)
            .healthy(available && (failures == 0 || successRate >= 0.8d))
            .lastSuccess(lastSuccess)
            .lastError(lastError)
            .lastErrorMessage(lastErrorMessage)
            .totalRequests(total)
            .successfulRequests(successes)
            .failedRequests(failures)
            .averageResponseTime(averageResponseTime)
            .successRate(successRate)
            .rateLimitRemaining(-1)
            .details(details)
            .lastUpdated(LocalDateTime.now())
            .build();
    }
}
