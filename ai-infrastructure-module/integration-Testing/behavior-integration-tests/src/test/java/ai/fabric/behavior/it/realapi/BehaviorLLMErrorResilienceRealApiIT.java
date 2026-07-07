package ai.fabric.behavior.it.realapi;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @MockitoBean
    private AICoreService aiCoreService;

    @BeforeEach
    void setup() {
        repository.deleteAll();
        eventProvider.clear();
    }

    @Test
    void providerFailureSurfacesAndDoesNotPersistFallbackInsight() {
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

        assertThatThrownBy(() -> analysisService.analyzeUser(userId))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Behavior analysis failed for user " + userId)
            .hasRootCauseMessage("Behavior analysis did not return a valid JSON payload: LLM 502");

        assertThat(repository.findByUserId(userId)).isEmpty();
    }
}
