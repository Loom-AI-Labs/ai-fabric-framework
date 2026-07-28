package com.ai.fabric.realapps.agenticresolver.controller;

import ai.fabric.core.AICoreService;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import com.ai.fabric.realapps.agenticresolver.entity.SubscriptionPlan;
import com.ai.fabric.realapps.agenticresolver.repository.SubscriptionPlanRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanAiSearchControllerTest {

    private final SubscriptionPlanRepository planRepository = mock(SubscriptionPlanRepository.class);
    private final Environment environment = mock(Environment.class);
    private final AICoreService aiCoreService = mock(AICoreService.class);
    private final ObjectProvider<AICoreService> aiCoreProvider = availableProvider(aiCoreService);
    private final PlanAiSearchController controller = new PlanAiSearchController(
        planRepository,
        environment,
        aiCoreProvider
    );

    @Test
    void searchIncludesPlanForValidEntityIdAndKeepsMalformedRowsInspectable() {
        UUID id = UUID.randomUUID();
        SubscriptionPlan plan = plan(id);
        when(environment.getProperty("ai.vector-db.type")).thenReturn("lucene");
        when(aiCoreService.performSearch(any(AISearchRequest.class))).thenReturn(AISearchResponse.builder()
            .results(List.of(
                Map.of("entityId", id.toString(), "score", 0.9d),
                Map.of("id", "bad", "score", 0.1d)
            ))
            .build());
        when(planRepository.findById(id)).thenReturn(Optional.of(plan));

        ResponseEntity<Map<String, Object>> response = controller.search("pro", 5);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).containsEntry("enabled", true);
        assertThat(response.getBody()).containsEntry("vectorDbType", "lucene");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matches = (List<Map<String, Object>>) response.getBody().get("matches");
        assertThat(matches).hasSize(2);
        assertThat(matches.getFirst()).containsEntry("entityId", id.toString()).containsEntry("plan", plan);
        assertThat(matches.get(1)).containsEntry("entityId", "bad").containsEntry("plan", null);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> availableProvider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static SubscriptionPlan plan(UUID id) {
        return SubscriptionPlan.builder()
            .id(id)
            .name("Pro Plan")
            .description("Priority support")
            .monthlyPrice(BigDecimal.TEN)
            .tier(SubscriptionPlan.PlanTier.PRO)
            .isActive(true)
            .build();
    }
}
