package com.ai.infrastructure.behavior.worker;

import com.ai.infrastructure.behavior.config.BehaviorProcessingProperties;
import com.ai.infrastructure.behavior.entity.BehaviorInsights;
import com.ai.infrastructure.behavior.service.BehaviorAnalysisService;
import com.ai.infrastructure.behavior.state.BehaviorProcessingState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai.behavior.processing", name = "scheduled-enabled", havingValue = "true")
public class BehaviorAnalysisWorker {

    private final BehaviorAnalysisService analysisService;
    private final BehaviorProcessingProperties properties;
    private final BehaviorProcessingState state;

    @Scheduled(cron = "${ai.behavior.processing.schedule-cron:0 */15 * * * *}")
    public void processUserBehaviors() {
        if (state.isScheduledPaused()) {
            log.info("Scheduled behavior processing paused; skipping run.");
            return;
        }

        int batchSize = properties.getScheduledBatchSize();
        Duration maxDuration = properties.getScheduledMaxDuration();
        Duration processingDelay = properties.getProcessingDelay();

        Instant startTime = Instant.now();
        int processedCount = 0;
        int successCount = 0;
        int errorCount = 0;

        log.info("Scheduled behavior processing started (batch={}, maxDuration={}, delay={})",
            batchSize, maxDuration, processingDelay);

        for (int i = 0; i < batchSize; i++) {
            if (i > 0 && Duration.between(startTime, Instant.now()).compareTo(maxDuration) > 0) {
                log.warn("Max duration exceeded, stopping scheduled run (processed={})", processedCount);
                break;
            }
            try {
                BehaviorInsights result = analysisService.processNextUser();
                if (result == null) {
                    log.debug("No pending users; stopping scheduled run at {}", processedCount);
                    break;
                }
                processedCount++;
                successCount++;
                if (processingDelay.toMillis() > 0 && i < batchSize - 1) {
                    Thread.sleep(processingDelay.toMillis());
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("Scheduled behavior processing interrupted");
                break;
            } catch (Exception e) {
                errorCount++;
                log.error("Error processing user in scheduled run", e);
            }
        }

        Duration total = Duration.between(startTime, Instant.now());
        log.info("Scheduled behavior processing finished: processed={}, success={}, errors={}, duration={}ms",
            processedCount, successCount, errorCount, total.toMillis());
    }
}
