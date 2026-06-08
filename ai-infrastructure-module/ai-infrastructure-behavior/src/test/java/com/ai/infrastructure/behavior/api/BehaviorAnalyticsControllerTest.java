package com.ai.infrastructure.behavior.api;

import com.ai.infrastructure.behavior.entity.BehaviorInsights;
import com.ai.infrastructure.behavior.model.BehaviorTrend;
import com.ai.infrastructure.behavior.model.SentimentLabel;
import com.ai.infrastructure.behavior.repository.BehaviorInsightsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class BehaviorAnalyticsControllerTest {

    private MockMvc mockMvc;

    @Mock
    private BehaviorInsightsRepository repository;

    @BeforeEach
    void setup() {
        BehaviorAnalyticsController controller = new BehaviorAnalyticsController(repository);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void rapidDeclineEndpointReturnsAlerts() throws Exception {
        BehaviorInsights bi = BehaviorInsights.builder()
            .userId("user-rapid-decline")
            .sentimentLabel(SentimentLabel.FRUSTRATED)
            .churnRisk(0.9)
            .churnReason("Payment failures")
            .trend(BehaviorTrend.RAPIDLY_DECLINING)
            .recommendations(List.of("reach_out"))
            .analyzedAt(LocalDateTime.now())
            .build();

        Mockito.when(repository.findRapidlyDecliningUsers()).thenReturn(List.of(bi));

        mockMvc.perform(get("/api/behavior/analytics/rapid-decline"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].trend").value("RAPIDLY_DECLINING"))
            .andExpect(jsonPath("$[0].churnRisk").value(0.9));
    }

    @Test
    void trendDistributionAggregatesCounts() throws Exception {
        BehaviorInsights bi1 = BehaviorInsights.builder().trend(BehaviorTrend.IMPROVING).build();
        BehaviorInsights bi2 = BehaviorInsights.builder().trend(BehaviorTrend.IMPROVING).build();
        BehaviorInsights bi3 = BehaviorInsights.builder().trend(BehaviorTrend.DECLINING).build();

        Mockito.when(repository.findAll()).thenReturn(List.of(bi1, bi2, bi3));

        mockMvc.perform(get("/api/behavior/analytics/trend-distribution"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"IMPROVING\":2")))
            .andExpect(content().string(containsString("\"DECLINING\":1")));
    }

    @Test
    void userTrendReturnsDeltas() throws Exception {
        String userId = "user-trend-deltas";
        BehaviorInsights bi = BehaviorInsights.builder()
            .userId(userId)
            .sentimentScore(0.6)
            .previousSentimentScore(0.1)
            .churnRisk(0.4)
            .previousChurnRisk(0.2)
            .trend(BehaviorTrend.IMPROVING)
            .churnReason("Engagement rising")
            .recommendations(List.of("reward"))
            .analyzedAt(LocalDateTime.now())
            .build();

        Mockito.when(repository.findByUserId(userId)).thenReturn(Optional.of(bi));

        mockMvc.perform(get("/api/behavior/analytics/users/{userId}/trend", userId)
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sentimentDelta").value(0.5))
            .andExpect(jsonPath("$.churnDelta").value(0.2))
            .andExpect(jsonPath("$.trend").value("IMPROVING"));
    }

    @Test
    void userTrendReturnsNotFoundForUnknownUser() throws Exception {
        String userId = "user-unknown";
        Mockito.when(repository.findByUserId(userId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/behavior/analytics/users/{userId}/trend", userId)
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotFound());
    }

    @Test
    void trendDistributionEmptyReturnsEmptyMap() throws Exception {
        Mockito.when(repository.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/behavior/analytics/trend-distribution"))
            .andExpect(status().isOk())
            .andExpect(content().string("{}"));
    }
}
