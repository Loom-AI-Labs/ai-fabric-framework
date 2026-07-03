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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubscriptionServiceTest {

    private final SubscriptionRepository subscriptionRepository = mock(SubscriptionRepository.class);
    private final SubscriptionPlanRepository planRepository = mock(SubscriptionPlanRepository.class);
    private final BehaviorEventService behaviorEventService = new BehaviorEventService();
    private final AICoreService aiCoreService = mock(AICoreService.class);
    private final SubscriptionService service = new SubscriptionService(
        subscriptionRepository,
        planRepository,
        behaviorEventService
    );

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

    @Test
    void resolvePlanIdAcceptsUserFacingPlanNameOrTier() {
        UUID basicId = UUID.randomUUID();
        UUID proId = UUID.randomUUID();
        UUID enterpriseId = UUID.randomUUID();
        SubscriptionPlan basic = plan(basicId, "Basic Plan", "Starter features", SubscriptionPlan.PlanTier.BASIC);
        SubscriptionPlan pro = plan(proId, "Pro Plan", "Priority support", SubscriptionPlan.PlanTier.PRO);
        SubscriptionPlan enterprise = plan(enterpriseId, "Enterprise Plan", "Enterprise governance", SubscriptionPlan.PlanTier.ENTERPRISE);
        when(planRepository.findByIsActiveTrue()).thenReturn(List.of(basic, pro, enterprise));

        assertThat(service.resolvePlanId("Pro")).isEqualTo(proId);
        assertThat(service.resolvePlanId("Enterprise Plan")).isEqualTo(enterpriseId);
        assertThat(service.resolvePlanId("basic")).isEqualTo(basicId);
    }

    @Test
    void resolvePlanIdStillAcceptsInternalUuidWhenSuppliedByBackendContext() {
        UUID proId = UUID.randomUUID();
        SubscriptionPlan pro = plan(proId, "Pro Plan", "Priority support", SubscriptionPlan.PlanTier.PRO);
        when(planRepository.findById(proId)).thenReturn(Optional.of(pro));

        assertThat(service.resolvePlanId(proId.toString())).isEqualTo(proId);
    }

    @Test
    void resolvePlanIdRejectsUnknownPlanReference() {
        SubscriptionPlan pro = plan(UUID.randomUUID(), "Pro Plan", "Priority support", SubscriptionPlan.PlanTier.PRO);
        when(planRepository.findByIsActiveTrue()).thenReturn(List.of(pro));

        assertThatThrownBy(() -> service.resolvePlanId("Premium"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Plan not found");
    }

    private static SubscriptionPlan plan(UUID id, String name, String description) {
        return plan(id, name, description, SubscriptionPlan.PlanTier.PRO);
    }

    private static SubscriptionPlan plan(UUID id, String name, String description, SubscriptionPlan.PlanTier tier) {
        return SubscriptionPlan.builder()
            .id(id)
            .name(name)
            .description(description)
            .monthlyPrice(BigDecimal.TEN)
            .tier(tier)
            .isActive(true)
            .build();
    }
}
