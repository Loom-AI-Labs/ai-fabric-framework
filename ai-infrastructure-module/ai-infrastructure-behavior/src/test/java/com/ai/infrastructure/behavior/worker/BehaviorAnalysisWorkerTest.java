package com.ai.infrastructure.behavior.worker;

import com.ai.infrastructure.behavior.config.BehaviorProcessingProperties;
import com.ai.infrastructure.behavior.entity.BehaviorInsights;
import com.ai.infrastructure.behavior.service.BehaviorAnalysisService;
import com.ai.infrastructure.behavior.state.BehaviorProcessingState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BehaviorAnalysisWorkerTest {

    @Mock
    private BehaviorAnalysisService analysisService;

    private BehaviorProcessingProperties properties;
    private BehaviorProcessingState state;
    private BehaviorAnalysisWorker worker;

    @BeforeEach
    void setup() {
        properties = new BehaviorProcessingProperties();
        properties.setScheduledBatchSize(3);
        properties.setScheduledMaxDuration(Duration.ofMillis(50));
        properties.setProcessingDelay(Duration.ofMillis(0));

        state = new BehaviorProcessingState();
        worker = new BehaviorAnalysisWorker(analysisService, properties, state);
    }

    @Test
    void skipsWhenPaused() {
        state.setScheduledPaused(true);

        worker.processUserBehaviors();

        verifyNoInteractions(analysisService);
    }

    @Test
    void stopsWhenNoPendingUsers() throws Exception {
        when(analysisService.processNextUser()).thenReturn(null);

        worker.processUserBehaviors();

        verify(analysisService, times(1)).processNextUser();
    }

    @Test
    void respectsMaxDuration() throws Exception {
        properties.setScheduledBatchSize(10);
        properties.setScheduledMaxDuration(Duration.ofMillis(5));
        when(analysisService.processNextUser()).thenAnswer(inv -> {
            Thread.sleep(2); // force duration breach
            return BehaviorInsights.builder().build();
        });

        worker.processUserBehaviors();

        // At least one call, but should stop early due to duration
        verify(analysisService, atLeast(1)).processNextUser();
        verify(analysisService, atMost(3)).processNextUser();
    }
}
