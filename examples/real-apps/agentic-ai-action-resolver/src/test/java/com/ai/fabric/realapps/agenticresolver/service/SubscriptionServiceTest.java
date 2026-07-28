package com.ai.fabric.realapps.agenticresolver.service;

import ai.fabric.core.AICoreService;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import com.ai.fabric.realapps.agenticresolver.entity.Address;
import com.ai.fabric.realapps.agenticresolver.entity.Subscription;
import com.ai.fabric.realapps.agenticresolver.entity.SubscriptionPlan;
import com.ai.fabric.realapps.agenticresolver.repository.SubscriptionPlanRepository;
import com.ai.fabric.realapps.agenticresolver.repository.SubscriptionRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
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
        behaviorEventService,
        aiCoreService
    );

    @Test
    void searchPlansUsesAiResultsWhenAvailableAndSkipsMalformedRows() {
        UUID proId = UUID.randomUUID();
        SubscriptionPlan pro = plan(proId, "Pro Plan", "Priority support");
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
    void searchPlansReturnsEmptyWhenSemanticSearchFindsNoEvidence() {
        when(aiCoreService.performSearch(any(AISearchRequest.class)))
            .thenReturn(AISearchResponse.builder().results(List.of()).build());

        List<SubscriptionPlan> results = service.searchPlans("priority", 5);

        assertThat(results).isEmpty();
    }

    @Test
    void searchPlansDoesNotHideAiSearchFailure() {
        when(aiCoreService.performSearch(any(AISearchRequest.class)))
            .thenThrow(new IllegalStateException("vector provider unavailable"));

        assertThatThrownBy(() -> service.searchPlans("priority", 5))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("vector provider unavailable");
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

    @Test
    void updateAddressPersistsAuthoritativeStateAndTracksSafeEvent() {
        UUID userId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = Subscription.builder()
            .id(subscriptionId)
            .userId(userId)
            .planId(UUID.randomUUID())
            .status(Subscription.SubscriptionStatus.ACTIVE)
            .startDate(LocalDateTime.now().minusDays(1))
            .billingCycle(Subscription.BillingCycle.MONTHLY)
            .build();
        Address address = Address.builder()
            .streetAddress("10 Downing Street")
            .city("London")
            .state("London")
            .postalCode("SW1A 2AA")
            .country("GB")
            .type(Address.AddressType.BILLING)
            .isValidated(true)
            .build();
        when(subscriptionRepository.findById(subscriptionId))
            .thenReturn(Optional.of(subscription));
        when(subscriptionRepository.save(subscription))
            .thenReturn(subscription);

        Subscription updated = service.updateAddress(
            subscriptionId,
            Address.AddressType.BILLING,
            address
        );

        assertThat(updated.getBillingAddress()).isSameAs(address);
        verify(subscriptionRepository).save(subscription);
        assertThat(behaviorEventService.getEventsForUser(
            userId.toString(),
            LocalDateTime.MIN,
            LocalDateTime.now().plusSeconds(1)
        )).singleElement().satisfies(event -> {
            assertThat(event.getEventType()).isEqualTo("UPDATE_ADDRESS");
            assertThat(event.getEventData())
                .containsEntry("addressType", "BILLING")
                .containsEntry("isValidated", "true")
                .doesNotContainKeys(
                    "streetAddress",
                    "postalCode",
                    "subscriptionId"
                );
        });
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
