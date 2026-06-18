package com.subscription.hub.service;

import ai.fabric.core.AICoreService;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import com.subscription.hub.entity.SubscriptionPlan;
import com.subscription.hub.repository.SubscriptionPlanRepository;
import com.subscription.hub.repository.SubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubscriptionServiceTest {

    private final SubscriptionRepository subscriptionRepository = mock(SubscriptionRepository.class);
    private final SubscriptionPlanRepository planRepository = mock(SubscriptionPlanRepository.class);
    private final AICoreService aiCoreService = mock(AICoreService.class);
    private final SubscriptionService service = new SubscriptionService(subscriptionRepository, planRepository);

    @Test
    void searchPlansUsesAiResultsWhenAvailableAndSkipsMalformedRows() {
        UUID proId = UUID.randomUUID();
        SubscriptionPlan pro = plan(proId, "Pro Plan", "Priority support");
        ReflectionTestUtils.setField(service, "aiCoreService", aiCoreService);
        when(planRepository.findAll()).thenReturn(List.of(pro));
        when(aiCoreService.performSearch(any(AISearchRequest.class))).thenReturn(AISearchResponse.builder()
            .results(List.of(
                Map.of("entityId", proId.toString()),
                Map.of("id", "not-a-uuid"),
                Map.of()
            ))
            .build());
        when(planRepository.findById(proId)).thenReturn(Optional.of(pro));

        List<SubscriptionPlan> results = service.searchPlans("priority", 5);

        assertThat(results).containsExactly(pro);
    }

    @Test
    void searchPlansFallsBackWhenAiCoreIsUnavailable() {
        SubscriptionPlan pro = plan(UUID.randomUUID(), "Pro Plan", "Priority support");
        SubscriptionPlan basic = plan(UUID.randomUUID(), "Basic Plan", "Starter features");
        when(planRepository.findAll()).thenReturn(List.of(pro, basic));

        List<SubscriptionPlan> results = service.searchPlans("priority", 5);

        assertThat(results).containsExactly(pro);
    }

    private static SubscriptionPlan plan(UUID id, String name, String description) {
        return SubscriptionPlan.builder()
            .id(id)
            .name(name)
            .description(description)
            .monthlyPrice(BigDecimal.TEN)
            .tier(SubscriptionPlan.PlanTier.PRO)
            .isActive(true)
            .build();
    }
}
