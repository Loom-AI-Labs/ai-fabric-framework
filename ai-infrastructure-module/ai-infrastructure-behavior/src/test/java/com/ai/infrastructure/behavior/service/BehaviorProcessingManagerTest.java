package com.ai.infrastructure.behavior.service;

import com.ai.infrastructure.behavior.api.dto.BatchProcessingRequest;
import com.ai.infrastructure.behavior.api.dto.BatchProcessingResult;
import com.ai.infrastructure.behavior.api.dto.ContinuousProcessingRequest;
import com.ai.infrastructure.behavior.api.dto.ContinuousProcessingResponse;
import com.ai.infrastructure.behavior.config.BehaviorProcessingProperties;
import com.ai.infrastructure.behavior.entity.BehaviorInsights;
import com.ai.infrastructure.behavior.state.BehaviorProcessingState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BehaviorProcessingManagerTest {

    @Mock
    private BehaviorAnalysisService analysisService;

    private BehaviorProcessingProperties properties;
    private BehaviorProcessingState state;
    private SimpleMeterRegistry meterRegistry;
    private BehaviorProcessingManager manager;

    @BeforeEach
    void setup() {
        properties = new BehaviorProcessingProperties();
        properties.setApiMaxBatchSize(5);
        properties.setApiMaxDuration(Duration.ofSeconds(1));
        properties.setProcessingDelay(Duration.ofMillis(0));
        properties.setContinuousUsersPerBatch(2);
        properties.setContinuousInterval(Duration.ofMillis(5));

        state = new BehaviorProcessingState();
        meterRegistry = new SimpleMeterRegistry();
        manager = new BehaviorProcessingManager(analysisService, properties, state, meterRegistry);
    }

    @Test
    void processBatch_countsSuccessAndMetrics() {
        BehaviorInsights insight = BehaviorInsights.builder()
            .userId("user-batch-test")
            .analyzedAt(LocalDateTime.now())
            .build();

        when(analysisService.processNextUser())
            .thenReturn(insight)
            .thenReturn(insight)
            .thenReturn(null);

        BatchProcessingRequest request = new BatchProcessingRequest();
        request.setMaxUsers(5);
        request.setMaxDurationMinutes(1);
        request.setDelayBetweenUsersMs(0L);

        BatchProcessingResult result = manager.processBatch(request);

        assertThat(result.getProcessedCount()).isEqualTo(2);
        assertThat(result.getSuccessCount()).isEqualTo(2);
        assertThat(result.getErrorCount()).isZero();
        assertThat(meterRegistry.counter("ai.behavior.processing.processed").count()).isEqualTo(2.0);
        assertThat(meterRegistry.counter("ai.behavior.processing.errors").count()).isEqualTo(0.0);
    }

    @Test
    void continuousJobCompletesAfterMaxIterations() throws Exception {
        when(analysisService.processNextUser()).thenReturn(null);

        ContinuousProcessingRequest request = new ContinuousProcessingRequest();
        request.setUsersPerBatch(1);
        request.setIntervalMinutes(0);
        request.setMaxIterations(1);

        ContinuousProcessingResponse start = manager.startContinuous(request);
        Thread.sleep(50); // allow background job to finish

        ContinuousProcessingResponse status = manager.cancelContinuous(start.getJobId());

        assertThat(status).isNotNull();
        assertThat(status.getStatus()).isIn("COMPLETED", "CANCELLED");
    }

    @Test
    void continuousJobCanBeCancelledWhileSleeping() throws Exception {
        when(analysisService.processNextUser()).thenReturn(null);

        ContinuousProcessingRequest request = new ContinuousProcessingRequest();
        request.setUsersPerBatch(1);
        request.setIntervalMinutes(1); // force sleep between iterations
        request.setMaxIterations(5);

        ContinuousProcessingResponse start = manager.startContinuous(request);
        Thread.sleep(20); // ensure the job has started and is sleeping

        ContinuousProcessingResponse cancelled = manager.cancelContinuous(start.getJobId());

        assertThat(cancelled).isNotNull();
        assertThat(cancelled.getStatus()).isEqualTo("CANCELLED");
    }
}
