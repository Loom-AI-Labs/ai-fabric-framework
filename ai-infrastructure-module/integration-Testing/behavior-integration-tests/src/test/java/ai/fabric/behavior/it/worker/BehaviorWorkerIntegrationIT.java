package ai.fabric.behavior.it.worker;

import ai.fabric.behavior.entity.BehaviorInsights;
import ai.fabric.behavior.it.BehaviorIntegrationTestApp;
import ai.fabric.behavior.state.BehaviorProcessingState;
import ai.fabric.behavior.worker.BehaviorAnalysisWorker;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.Mockito.*;

@SpringBootTest(
    classes = BehaviorIntegrationTestApp.class,
    properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "ai.behavior.processing.scheduled-enabled=true",
        "ai.behavior.processing.scheduled-batch-size=5",
        "ai.behavior.processing.scheduled-max-duration=5ms",
        "ai.behavior.processing.processing-delay=0ms"
    }
)
@ActiveProfiles("integration")
class BehaviorWorkerIntegrationIT {

    @Autowired
    private BehaviorAnalysisWorker worker;

    @Autowired
    private BehaviorProcessingState state;

    @MockitoBean
    private ai.fabric.behavior.service.BehaviorAnalysisService analysisService;

    @Test
    void pausedSkipsProcessing() {
        state.setScheduledPaused(true);

        worker.processUserBehaviors();

        verifyNoInteractions(analysisService);
    }

    @Test
    void stopsWhenNoPendingUsers() {
        state.setScheduledPaused(false);
        when(analysisService.processNextUser()).thenReturn(null);

        worker.processUserBehaviors();

        verify(analysisService, times(1)).processNextUser();
    }

    @Test
    void stopsAfterMaxDuration() throws Exception {
        state.setScheduledPaused(false);
        when(analysisService.processNextUser()).thenAnswer(inv -> {
            Thread.sleep(3);
            return BehaviorInsights.builder()
                .userId("test-user-" + java.util.UUID.randomUUID().toString())
                .analyzedAt(LocalDateTime.now())
                .build();
        });

        worker.processUserBehaviors();

        verify(analysisService, atLeast(1)).processNextUser();
        verify(analysisService, atMost(3)).processNextUser();
    }
}
