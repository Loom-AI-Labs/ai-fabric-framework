package ai.fabric.behavior.it.realapi;

import ai.fabric.behavior.entity.BehaviorInsights;
import ai.fabric.behavior.it.BehaviorIntegrationTestApp;
import ai.fabric.behavior.it.support.TestEventProvider;
import ai.fabric.behavior.model.ExternalEvent;
import ai.fabric.behavior.repository.BehaviorInsightsRepository;
import ai.fabric.behavior.service.BehaviorAnalysisService;
import ai.fabric.core.AICoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest(
    classes = BehaviorIntegrationTestApp.class,
    properties = {
        "spring.main.allow-bean-definition-overriding=true"
    }
)
@ActiveProfiles("integration")
class BehaviorLLMErrorResilienceRealApiIT {

    @Autowired
    private BehaviorAnalysisService analysisService;

    @Autowired
    private BehaviorInsightsRepository repository;

    @Autowired
    private TestEventProvider eventProvider;

    @MockBean
    private AICoreService aiCoreService;

    @BeforeEach
    void setup() {
        repository.deleteAll();
        eventProvider.clear();
    }

    @Test
    void malformedJsonFallsBackAndDoesNotPersistBadState() {
        String userId = "test-user-" + java.util.UUID.randomUUID().toString();
        eventProvider.setTargetedEvents(List.of(
            ExternalEvent.builder()
                .eventType("page_view")
                .timestamp(LocalDateTime.now())
                .eventData(Map.of("path", "/home"))
                .source("web")
                .build()
        ));

        when(aiCoreService.generateContent(ArgumentMatchers.any()))
            .thenThrow(new IllegalStateException("LLM 502"));

        BehaviorInsights result = analysisService.analyzeUser(userId);

        assertThat(result).isNotNull();
        assertThat(result.getUserId()).isEqualTo(userId);
        assertThat(result.getSegment()).isEqualTo("unknown");
        assertThat(result.getTrend()).isEqualTo(ai.fabric.behavior.model.BehaviorTrend.STABLE);
        BehaviorInsights persisted = repository.findByUserId(userId).orElse(null);
        assertThat(persisted).isNotNull();
        assertThat(persisted.getSegment()).isEqualTo("unknown");
    }
}
