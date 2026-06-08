package com.ai.infrastructure.behavior.service;

import com.ai.infrastructure.behavior.api.dto.BatchProcessingRequest;
import com.ai.infrastructure.behavior.api.dto.BatchProcessingResult;
import com.ai.infrastructure.behavior.api.dto.ContinuousProcessingRequest;
import com.ai.infrastructure.behavior.api.dto.ContinuousProcessingResponse;
import com.ai.infrastructure.behavior.api.dto.ScheduledControlResponse;
import com.ai.infrastructure.behavior.config.BehaviorProcessingProperties;
import com.ai.infrastructure.behavior.entity.BehaviorInsights;
import com.ai.infrastructure.behavior.state.BehaviorProcessingState;
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
        int maxUsers = request.getMaxUsers() != null ? request.getMaxUsers() : properties.getScheduledBatchSize();
        maxUsers = Math.min(maxUsers, properties.getApiMaxBatchSize());
        Duration maxDuration = request.getMaxDurationMinutes() != null
            ? Duration.ofMinutes(request.getMaxDurationMinutes())
            : properties.getApiMaxDuration();
        Duration delay = request.getDelayBetweenUsersMs() != null
            ? Duration.ofMillis(request.getDelayBetweenUsersMs())
            : properties.getProcessingDelay();

        return executeBatchProcessing(maxUsers, maxDuration, delay, false);
    }

    public ContinuousProcessingResponse startContinuous(ContinuousProcessingRequest request) {
        String jobId = UUID.randomUUID().toString();
        ContinuousJobStatus status = new ContinuousJobStatus();
        status.setJobId(jobId);
        status.setStatus("RUNNING");
        status.setStartedAt(LocalDateTime.now());
        jobStatuses.put(jobId, status);

        final int usersPerBatch = Math.min(
            request.getUsersPerBatch() != null ? request.getUsersPerBatch() : properties.getContinuousUsersPerBatch(),
            properties.getApiMaxBatchSize()
        );
        final Duration interval = request.getIntervalMinutes() != null
            ? Duration.ofMinutes(request.getIntervalMinutes())
            : properties.getContinuousInterval();
        final int maxIterations = request.getMaxIterations() != null ? request.getMaxIterations() : Integer.MAX_VALUE;

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
                        properties.getApiMaxDuration(),
                        properties.getProcessingDelay(),
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
        ContinuousJobStatus status = jobStatuses.get(jobId);
        if (status == null) {
            return null;
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

        return ContinuousProcessingResponse.builder()
            .jobId(jobId)
            .status(status.getStatus())
            .build();
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
