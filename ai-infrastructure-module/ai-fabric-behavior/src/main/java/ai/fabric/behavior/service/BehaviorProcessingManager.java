package ai.fabric.behavior.service;

import ai.fabric.behavior.api.dto.BatchProcessingRequest;
import ai.fabric.behavior.api.dto.BatchProcessingResult;
import ai.fabric.behavior.api.dto.ContinuousProcessingRequest;
import ai.fabric.behavior.api.dto.ContinuousProcessingResponse;
import ai.fabric.behavior.api.dto.ScheduledControlResponse;
import ai.fabric.behavior.config.BehaviorProcessingProperties;
import ai.fabric.behavior.entity.BehaviorInsights;
import ai.fabric.behavior.state.BehaviorProcessingState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class BehaviorProcessingManager {

    private final BehaviorAnalysisService analysisService;
    private final BehaviorProcessingProperties properties;
    private final BehaviorProcessingState processingState;
    private final MeterRegistry meterRegistry;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<String, Future<?>> runningJobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ContinuousJobStatus> jobStatuses = new ConcurrentHashMap<>();

    private Counter processedCounter() {
        return meterRegistry != null ? meterRegistry.counter("ai.behavior.processing.processed") : null;
    }

    private Counter errorCounter() {
        return meterRegistry != null ? meterRegistry.counter("ai.behavior.processing.errors") : null;
    }

    public BehaviorInsights analyzeUser(String userId) {
        return analysisService.analyzeUser(userId);
    }

    public BatchProcessingResult processBatch(BatchProcessingRequest request) {
        BatchProcessingRequest effectiveRequest = request != null ? request : new BatchProcessingRequest();
        int configuredBatchSize = positiveOrDefault(properties.getScheduledBatchSize(), 1);
        int apiMaxBatchSize = positiveOrDefault(properties.getApiMaxBatchSize(), configuredBatchSize);
        int requestedMaxUsers = effectiveRequest.getMaxUsers() != null
            ? requirePositive(effectiveRequest.getMaxUsers(), "maxUsers")
            : configuredBatchSize;
        int maxUsers = Math.min(requestedMaxUsers, apiMaxBatchSize);
        Duration maxDuration = effectiveRequest.getMaxDurationMinutes() != null
            ? Duration.ofMinutes(requirePositive(effectiveRequest.getMaxDurationMinutes(), "maxDurationMinutes"))
            : positiveDurationOrDefault(properties.getApiMaxDuration(), Duration.ofMinutes(1));
        Duration delay = effectiveRequest.getDelayBetweenUsersMs() != null
            ? Duration.ofMillis(requireNonNegative(effectiveRequest.getDelayBetweenUsersMs(), "delayBetweenUsersMs"))
            : nonNegativeDurationOrDefault(properties.getProcessingDelay(), Duration.ZERO);

        return executeBatchProcessing(maxUsers, maxDuration, delay, false);
    }

    public ContinuousProcessingResponse startContinuous(ContinuousProcessingRequest request) {
        ContinuousProcessingRequest effectiveRequest = request != null ? request : new ContinuousProcessingRequest();
        int apiMaxBatchSize = positiveOrDefault(properties.getApiMaxBatchSize(), 1);
        final int usersPerBatch = Math.min(
            effectiveRequest.getUsersPerBatch() != null
                ? requirePositive(effectiveRequest.getUsersPerBatch(), "usersPerBatch")
                : positiveOrDefault(properties.getContinuousUsersPerBatch(), 1),
            apiMaxBatchSize
        );
        final Duration interval = effectiveRequest.getIntervalMinutes() != null
            ? Duration.ofMinutes(requireNonNegative(effectiveRequest.getIntervalMinutes(), "intervalMinutes"))
            : nonNegativeDurationOrDefault(properties.getContinuousInterval(), Duration.ofMinutes(5));
        final int maxIterations = effectiveRequest.getMaxIterations() != null
            ? requirePositive(effectiveRequest.getMaxIterations(), "maxIterations")
            : Integer.MAX_VALUE;

        String jobId = UUID.randomUUID().toString();
        ContinuousJobStatus status = new ContinuousJobStatus();
        status.setJobId(jobId);
        status.setStatus("RUNNING");
        status.setStartedAt(LocalDateTime.now());
        status.setMaxIterations(maxIterations == Integer.MAX_VALUE ? null : maxIterations);
        jobStatuses.put(jobId, status);

        Future<?> future = executor.submit(() -> {
            int totalProcessed = 0;
            try {
                for (int i = 0; i < maxIterations; i++) {
                    if (Thread.currentThread().isInterrupted()) {
                        status.setStatus("CANCELLED");
                        break;
                    }
                    status.setCurrentIteration(i + 1);
                    BatchProcessingResult res = executeBatchProcessing(
                        usersPerBatch,
                        positiveDurationOrDefault(properties.getApiMaxDuration(), Duration.ofMinutes(1)),
                        nonNegativeDurationOrDefault(properties.getProcessingDelay(), Duration.ZERO),
                        true
                    );
                    totalProcessed += res.getProcessedCount();
                    status.setTotalProcessed(totalProcessed);
                    if (i < maxIterations - 1) {
                        Thread.sleep(interval.toMillis());
                    }
                }
                if (!"CANCELLED".equals(status.getStatus())) {
                    status.setStatus("COMPLETED");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                status.setStatus("CANCELLED");
            } catch (Exception e) {
                log.error("Continuous job {} failed", jobId, e);
                status.setStatus("FAILED");
                status.setError(e.getMessage());
            } finally {
                status.setCompletedAt(LocalDateTime.now());
                runningJobs.remove(jobId);
            }
        });

        runningJobs.put(jobId, future);
        return ContinuousProcessingResponse.builder().jobId(jobId).status("RUNNING").build();
    }

    public ContinuousProcessingResponse cancelContinuous(String jobId) {
        return cancelContinuousOptional(jobId).orElse(null);
    }

    public Optional<ContinuousProcessingResponse> cancelContinuousOptional(String jobId) {
        ContinuousJobStatus status = jobStatuses.get(jobId);
        if (status == null) {
            return Optional.empty();
        }

        Future<?> future = runningJobs.remove(jobId);
        if (future != null) {
            future.cancel(true);
            status.setStatus("CANCELLED");
            status.setCompletedAt(LocalDateTime.now());
        } else {
            // Job already completed or was never started; return the last known status
            if (status.getCompletedAt() == null) {
                status.setCompletedAt(LocalDateTime.now());
            }
            if (status.getStatus() == null) {
                status.setStatus("COMPLETED");
            }
        }

        return Optional.of(ContinuousProcessingResponse.builder()
            .jobId(jobId)
            .status(status.getStatus())
            .build());
    }

    public ScheduledControlResponse pauseScheduled() {
        processingState.setScheduledPaused(true);
        return ScheduledControlResponse.builder()
            .message("Scheduled processing paused. Worker will skip processing until resumed.")
            .paused(true)
            .build();
    }

    public ScheduledControlResponse resumeScheduled() {
        processingState.setScheduledPaused(false);
        return ScheduledControlResponse.builder()
            .message("Scheduled processing resumed.")
            .paused(false)
            .build();
    }

    private BatchProcessingResult executeBatchProcessing(int maxUsers, Duration maxDuration, Duration delay, boolean suppressLogs) {
        maxUsers = Math.max(0, maxUsers);
        maxDuration = positiveDurationOrDefault(maxDuration, Duration.ofMinutes(1));
        delay = nonNegativeDurationOrDefault(delay, Duration.ZERO);
        Instant start = Instant.now();
        int processed = 0;
        int success = 0;
        int errors = 0;

        Counter processedMetric = processedCounter();
        Counter errorMetric = errorCounter();

        for (int i = 0; i < maxUsers; i++) {
            if (Duration.between(start, Instant.now()).compareTo(maxDuration) > 0) {
                break;
            }
            try {
                BehaviorInsights insight = analysisService.processNextUser();
                if (insight == null) {
                    break;
                }
                processed++;
                success++;
                if (processedMetric != null) {
                    processedMetric.increment();
                }
                if (delay.toMillis() > 0 && i < maxUsers - 1) {
                    Thread.sleep(delay.toMillis());
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                errors++;
                if (errorMetric != null) {
                    errorMetric.increment();
                }
                if (!suppressLogs) {
                    log.error("Error during batch processing", e);
                }
            }
        }

        Duration duration = Duration.between(start, Instant.now());
        return BatchProcessingResult.builder()
            .processedCount(processed)
            .successCount(success)
            .errorCount(errors)
            .durationMs(duration.toMillis())
            .build();
    }

    private int requirePositive(Integer value, String fieldName) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be greater than zero");
        }
        return value;
    }

    private long requireNonNegative(Long value, String fieldName) {
        if (value == null || value < 0) {
            throw new IllegalArgumentException(fieldName + " must be zero or greater");
        }
        return value;
    }

    private int requireNonNegative(Integer value, String fieldName) {
        if (value == null || value < 0) {
            throw new IllegalArgumentException(fieldName + " must be zero or greater");
        }
        return value;
    }

    private int positiveOrDefault(int value, int defaultValue) {
        return value > 0 ? value : Math.max(1, defaultValue);
    }

    private Duration positiveDurationOrDefault(Duration value, Duration defaultValue) {
        if (value != null && !value.isNegative() && !value.isZero()) {
            return value;
        }
        return defaultValue != null && !defaultValue.isNegative() && !defaultValue.isZero()
            ? defaultValue
            : Duration.ofMinutes(1);
    }

    private Duration nonNegativeDurationOrDefault(Duration value, Duration defaultValue) {
        if (value != null && !value.isNegative()) {
            return value;
        }
        return defaultValue != null && !defaultValue.isNegative() ? defaultValue : Duration.ZERO;
    }

    @Data
    public static class ContinuousJobStatus {
        private String jobId;
        private String status;
        private LocalDateTime startedAt;
        private LocalDateTime completedAt;
        private Integer currentIteration;
        private Integer maxIterations;
        private Integer totalProcessed;
        private String error;
    }
}
