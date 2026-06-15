package ai.fabric.behavior.service;

import ai.fabric.behavior.entity.BehaviorInsights;
import ai.fabric.behavior.model.ExternalEvent;
import ai.fabric.behavior.model.SentimentLabel;
import ai.fabric.behavior.model.UserEventBatch;
import ai.fabric.behavior.spi.ExternalEventProvider;
import ai.fabric.core.AICoreService;
import ai.fabric.config.PromptBundleProperties;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.llm.structured.DefaultStructuredJsonCallExecutor;
import ai.fabric.llm.structured.StructuredJsonExtractor;
import ai.fabric.prompt.ClasspathPromptTemplateStore;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.DefaultResourceLoader;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BehaviorAnalysisServiceTest {

    @Mock
    private ExternalEventProvider eventProvider;
    @Mock
    private BehaviorStorageAdapter storageAdapter;
    @Mock
    private AICoreService aiCoreService;

    private ObjectMapper objectMapper;

    private BehaviorAnalysisService service;

    @BeforeEach
    void setup() {
        objectMapper = new ObjectMapper();
        PromptRenderer promptRenderer = new PromptRenderer();
        PromptTemplateResolver promptTemplateResolver = new PromptTemplateResolver(
            new ClasspathPromptTemplateStore(new DefaultResourceLoader()),
            new PromptBundleProperties()
        );
        service = new BehaviorAnalysisService(
            eventProvider,
            storageAdapter,
            aiCoreService,
            objectMapper,
            new DefaultStructuredJsonCallExecutor(new StructuredJsonExtractor(), objectMapper),
            promptTemplateResolver,
            promptRenderer
        );
    }

    @Test
    void analyzeUser_returnsExistingWhenNoEvents() {
        String userId = "user-12345";
        BehaviorInsights existing = BehaviorInsights.builder()
            .id(UUID.randomUUID())
            .userId(userId)
            .segment("existing")
            .analyzedAt(LocalDateTime.now())
            .build();

        when(storageAdapter.findByUserId(userId)).thenReturn(Optional.of(existing));
        when(eventProvider.getEventsForUser(userId, null, null)).thenReturn(List.of());

        BehaviorInsights result = service.analyzeUser(userId);

        assertThat(result).isSameAs(existing);
        verify(storageAdapter, never()).save(any());
    }

    @Test
    void analyzeUser_processesEventsAndSaves() {
        String userId = "user-67890";
        List<ExternalEvent> events = List.of(
            ExternalEvent.builder()
                .eventType("purchase")
                .timestamp(LocalDateTime.now())
                .eventData(Map.of("amount", 50))
                .source("web")
                .build()
        );

        when(storageAdapter.findByUserId(userId)).thenReturn(Optional.empty());
        when(eventProvider.getEventsForUser(userId, null, null)).thenReturn(events);
        when(aiCoreService.generateContent(any())).thenReturn(
            AIGenerationResponse.builder()
                .content("{\"segment\":\"Power\",\"patterns\":[\"buy\"],\"recommendations\":[\"upsell\"],\"insights\":{},\"confidence\":0.9}")
                .model("gpt-4o")
                .build()
        );
        when(storageAdapter.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BehaviorInsights result = service.analyzeUser(userId);

        assertThat(result).isNotNull();
        assertThat(result.getSegment()).isEqualTo("Power");
        assertThat(result.getPatterns()).containsExactly("buy");
        assertThat(result.getRecommendations()).containsExactly("upsell");
        assertThat(result.getConfidence()).isEqualTo(0.9);
        verify(storageAdapter).save(any(BehaviorInsights.class));
    }

    @Test
    void processNextUser_usesContextAndSaves() {
        String userId = "user-abc123";
        UserEventBatch batch = UserEventBatch.builder()
            .userId(userId)
            .events(List.of(
                ExternalEvent.builder()
                    .eventType("login")
                    .timestamp(LocalDateTime.now())
                    .eventData(Map.of("device", "mobile"))
                    .source("app")
                    .build()
            ))
            .totalEventCount(1)
            .userContext(Map.of("tier", "gold"))
            .build();

        when(eventProvider.getNextUserEvents()).thenReturn(batch);
        when(storageAdapter.findByUserId(userId)).thenReturn(Optional.empty());
        when(aiCoreService.generateContent(any())).thenReturn(
            AIGenerationResponse.builder()
                .content("{\"segment\":\"Active\",\"patterns\":[\"login\"],\"recommendations\":[\"notify\"],\"insights\":{},\"confidence\":0.75}")
                .model("gpt-4o")
                .build()
        );
        when(storageAdapter.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BehaviorInsights result = service.processNextUser();

        assertThat(result).isNotNull();
        assertThat(result.getSegment()).isEqualTo("Active");

        ArgumentCaptor<AIGenerationResponse> captor = ArgumentCaptor.forClass(AIGenerationResponse.class);
        verify(aiCoreService).generateContent(any());
        verify(storageAdapter).save(any(BehaviorInsights.class));
    }

    @Test
    void clampsAndDefaultsInvalidSentimentAndChurn() {
        String userId = "user-xyz789";
        when(eventProvider.getEventsForUser(userId, null, null)).thenReturn(
            List.of(ExternalEvent.builder().eventType("test").build())
        );
        when(storageAdapter.findByUserId(userId)).thenReturn(Optional.empty());
        when(aiCoreService.generateContent(any())).thenReturn(
            AIGenerationResponse.builder()
                .content("""
                    {
                      "segment": "Test",
                      "patterns": [],
                      "sentiment": {"score": 1.5, "label": "INVALID"},
                      "churn": {"risk": -0.2},
                      "trend": "STABLE",
                      "recommendations": [],
                      "insights": {},
                      "confidence": 0.5
                    }
                    """)
                .build()
        );
        when(storageAdapter.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BehaviorInsights result = service.analyzeUser(userId);

        assertThat(result.getSentimentScore()).isBetween(-1.0, 1.0);
        assertThat(result.getChurnRisk()).isBetween(0.0, 1.0);
        assertThat(result.getSentimentLabel()).isEqualTo(SentimentLabel.NEUTRAL);
    }

    @Test
    void fallsBackGracefullyOnMalformedLLMResponse() {
        String userId = "user-malformed";
        when(eventProvider.getEventsForUser(userId, null, null)).thenReturn(
            List.of(ExternalEvent.builder().eventType("purchase").build())
        );
        when(storageAdapter.findByUserId(userId)).thenReturn(Optional.empty());
        // malformed content (no JSON braces)
        when(aiCoreService.generateContent(any())).thenReturn(
            AIGenerationResponse.builder().content("not-json").model("bad-model").build()
        );
        when(storageAdapter.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BehaviorInsights result = service.analyzeUser(userId);

        assertThat(result).isNotNull();
        assertThat(result.getTrend()).isEqualTo(ai.fabric.behavior.model.BehaviorTrend.STABLE);
        assertThat(result.getConfidence()).isEqualTo(0.0);
        assertThat(result.getAiModelUsed()).isEqualTo("fallback");
        verify(storageAdapter).save(any(BehaviorInsights.class));
    }

    @Test
    void recomputesTrendFromDeltasWhenStableReturned() {
        String userId = "user-trend-test";
        BehaviorInsights existing = BehaviorInsights.builder()
            .id(UUID.randomUUID())
            .userId(userId)
            .sentimentScore(0.5)
            .churnRisk(0.1)
            .trend(ai.fabric.behavior.model.BehaviorTrend.IMPROVING)
            .createdAt(LocalDateTime.now().minusDays(1))
            .analyzedAt(LocalDateTime.now().minusDays(1))
            .build();

        when(storageAdapter.findByUserId(userId)).thenReturn(Optional.of(existing));
        when(eventProvider.getEventsForUser(userId, null, null)).thenReturn(
            List.of(ExternalEvent.builder().eventType("downgrade").timestamp(LocalDateTime.now()).build())
        );
        when(aiCoreService.generateContent(any())).thenReturn(
            AIGenerationResponse.builder()
                .content("""
                    {
                      "segment": "AtRisk",
                      "patterns": ["downgrade"],
                      "sentiment": {"score": 0.0, "label": "NEUTRAL"},
                      "churn": {"risk": 0.6, "reason": "downgrade"},
                      "trend": "STABLE",
                      "recommendations": ["retain"],
                      "insights": {},
                      "confidence": 0.7
                    }
                    """)
                .build()
        );
        when(storageAdapter.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BehaviorInsights result = service.analyzeUser(userId);

        assertThat(result.getTrend()).isEqualTo(ai.fabric.behavior.model.BehaviorTrend.RAPIDLY_DECLINING);
        assertThat(result.getPreviousSentimentScore()).isEqualTo(0.5);
        assertThat(result.getPreviousChurnRisk()).isEqualTo(0.1);
        assertThat(result.getSentimentDelta()).isEqualTo(-0.5);
        assertThat(result.getChurnDelta()).isEqualTo(0.5);
        assertThat(result.getId()).isEqualTo(existing.getId());
        assertThat(result.getCreatedAt()).isEqualTo(existing.getCreatedAt());
    }

    @Test
    void carriesForwardPreviousValuesWhenNewInsightCreated() {
        String userId = "user-carry-forward";
        BehaviorInsights existing = BehaviorInsights.builder()
            .id(UUID.randomUUID())
            .userId(userId)
            .sentimentScore(0.2)
            .churnRisk(0.3)
            .trend(ai.fabric.behavior.model.BehaviorTrend.STABLE)
            .createdAt(LocalDateTime.now().minusDays(2))
            .analyzedAt(LocalDateTime.now().minusDays(2))
            .build();

        when(storageAdapter.findByUserId(userId)).thenReturn(Optional.of(existing));
        when(eventProvider.getEventsForUser(userId, null, null)).thenReturn(
            List.of(ExternalEvent.builder().eventType("help").timestamp(LocalDateTime.now()).build())
        );
        when(aiCoreService.generateContent(any())).thenReturn(
            AIGenerationResponse.builder()
                .content("""
                    {
                      "segment": "NeedsHelp",
                      "patterns": ["support"],
                      "sentiment": {"score": 0.6, "label": "SATISFIED"},
                      "churn": {"risk": 0.1, "reason": "helped"},
                      "trend": "IMPROVING",
                      "recommendations": ["follow_up"],
                      "insights": {},
                      "confidence": 0.8
                    }
                    """)
                .build()
        );
        when(storageAdapter.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BehaviorInsights result = service.analyzeUser(userId);

        assertThat(result.getPreviousSentimentScore()).isEqualTo(0.2);
        assertThat(result.getPreviousChurnRisk()).isEqualTo(0.3);
        assertThat(result.getSentimentDelta()).isCloseTo(0.4, within(1e-9));
        assertThat(result.getChurnDelta()).isCloseTo(-0.2, within(1e-9));
        assertThat(result.getId()).isEqualTo(existing.getId());
        assertThat(result.getCreatedAt()).isEqualTo(existing.getCreatedAt());
    }
}
