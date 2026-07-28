package com.ai.fabric.realapps.agenticresolver.service;

import com.ai.fabric.realapps.agenticresolver.entity.Subscription;
import com.ai.fabric.realapps.agenticresolver.entity.User;
import com.ai.fabric.realapps.agenticresolver.repository.RefundRequestRepository;
import com.ai.fabric.realapps.agenticresolver.repository.SubscriptionRepository;
import com.ai.fabric.realapps.agenticresolver.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DemoSessionCleanupServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final SubscriptionRepository subscriptionRepository = mock(SubscriptionRepository.class);
    private final RefundRequestRepository refundRequestRepository = mock(RefundRequestRepository.class);
    private final DemoSessionCleanupService service = new DemoSessionCleanupService(
        userRepository,
        subscriptionRepository,
        refundRequestRepository,
        Clock.fixed(LocalDateTime.of(2026, 7, 4, 10, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
    );

    @Test
    void cleanupExpiredDemoSessionUsersDeletesOnlyClonedResolverUsers() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 7, 4, 4, 0);
        User first = demoUser(101L);
        User second = demoUser(999_001L);
        List<User> expiredUsers = List.of(first, second);
        List<UUID> userIds = expiredUsers.stream().map(User::getId).toList();
        when(userRepository.findByUserIdGreaterThanAndUsernameStartingWithAndCreatedAtBefore(
            100L,
            "resolver_user_",
            cutoff
        )).thenReturn(expiredUsers);
        when(subscriptionRepository.findByUserIdIn(userIds)).thenReturn(List.of(
            subscription(first.getId()),
            subscription(second.getId())
        ));
        when(refundRequestRepository.deleteByUserIdIn(userIds)).thenReturn(1L);
        when(subscriptionRepository.deleteByUserIdIn(userIds)).thenReturn(2L);

        DemoSessionCleanupService.DemoCleanupResult result = service.cleanupExpiredDemoSessionUsers(cutoff);

        assertThat(result.deletedUsers()).isEqualTo(2);
        assertThat(result.deletedSubscriptions()).isEqualTo(2);
        assertThat(result.deletedRefundRequests()).isEqualTo(1);
        assertThat(result.cutoff()).isEqualTo(cutoff);
        verify(userRepository).deleteAllInBatch(expiredUsers);
    }

    @Test
    void cleanupExpiredDemoSessionUsersDoesNothingWhenNoClonesExpired() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 7, 4, 4, 0);
        when(userRepository.findByUserIdGreaterThanAndUsernameStartingWithAndCreatedAtBefore(
            100L,
            "resolver_user_",
            cutoff
        )).thenReturn(List.of());

        DemoSessionCleanupService.DemoCleanupResult result = service.cleanupExpiredDemoSessionUsers(cutoff);

        assertThat(result.deletedUsers()).isZero();
        assertThat(result.deletedSubscriptions()).isZero();
        assertThat(result.deletedRefundRequests()).isZero();
        verify(refundRequestRepository, never()).deleteByUserIdIn(anyCollection());
        verify(subscriptionRepository, never()).deleteByUserIdIn(anyCollection());
        verify(userRepository, never()).deleteAllInBatch(anyCollection());
    }

    private static User demoUser(Long numericUserId) {
        return User.builder()
            .id(UUID.randomUUID())
            .userId(numericUserId)
            .username("resolver_user_" + numericUserId)
            .email("resolver.user" + numericUserId + "@example.com")
            .firstName("Demo")
            .lastName("User")
            .createdAt(LocalDateTime.of(2026, 7, 4, 3, 0))
            .build();
    }

    private static Subscription subscription(UUID userId) {
        return Subscription.builder()
            .id(UUID.randomUUID())
            .userId(userId)
            .planId(UUID.randomUUID())
            .status(Subscription.SubscriptionStatus.ACTIVE)
            .billingCycle(Subscription.BillingCycle.MONTHLY)
            .startDate(LocalDateTime.of(2026, 7, 1, 0, 0))
            .endDate(LocalDateTime.of(2026, 8, 1, 0, 0))
            .build();
    }
}
