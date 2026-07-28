package com.ai.fabric.realapps.agenticresolver.service;

import com.ai.fabric.realapps.agenticresolver.entity.Subscription;
import com.ai.fabric.realapps.agenticresolver.entity.User;
import com.ai.fabric.realapps.agenticresolver.repository.RefundRequestRepository;
import com.ai.fabric.realapps.agenticresolver.repository.SubscriptionRepository;
import com.ai.fabric.realapps.agenticresolver.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DemoSessionCleanupService {

    private static final long CANONICAL_DEMO_USER_MAX_ID = 100L;
    private static final String DEMO_USERNAME_PREFIX = "resolver_user_";

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final RefundRequestRepository refundRequestRepository;
    private final Clock clock;

    @Value("${app.demo.cleanup.enabled:true}")
    private boolean cleanupEnabled;

    @Value("${app.demo.cleanup.ttl:PT6H}")
    private Duration cleanupTtl;

    @Scheduled(cron = "${app.demo.cleanup.cron:0 */30 * * * *}")
    @Transactional
    public void scheduledCleanup() {
        if (!cleanupEnabled) {
            return;
        }

        DemoCleanupResult result = cleanupExpiredDemoSessionUsers();
        if (result.deletedUsers() > 0 || result.deletedSubscriptions() > 0 || result.deletedRefundRequests() > 0) {
            log.info(
                "Cleaned account resolver demo sessions: users={}, subscriptions={}, refunds={}, cutoff={}",
                result.deletedUsers(),
                result.deletedSubscriptions(),
                result.deletedRefundRequests(),
                result.cutoff()
            );
        }
    }

    @Transactional
    public DemoCleanupResult cleanupExpiredDemoSessionUsers() {
        Duration effectiveTtl = cleanupTtl != null && !cleanupTtl.isNegative() && !cleanupTtl.isZero()
            ? cleanupTtl
            : Duration.ofHours(6);
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(effectiveTtl);
        return cleanupExpiredDemoSessionUsers(cutoff);
    }

    @Transactional
    DemoCleanupResult cleanupExpiredDemoSessionUsers(LocalDateTime cutoff) {
        List<User> expiredUsers = userRepository.findByUserIdGreaterThanAndUsernameStartingWithAndCreatedAtBefore(
            CANONICAL_DEMO_USER_MAX_ID,
            DEMO_USERNAME_PREFIX,
            cutoff
        );
        if (expiredUsers.isEmpty()) {
            return new DemoCleanupResult(0, 0, 0, cutoff);
        }

        List<UUID> userIds = expiredUsers.stream()
            .map(User::getId)
            .toList();
        List<Subscription> subscriptions = subscriptionRepository.findByUserIdIn(userIds);
        long deletedRefunds = refundRequestRepository.deleteByUserIdIn(userIds);
        long deletedSubscriptions = subscriptionRepository.deleteByUserIdIn(userIds);
        userRepository.deleteAllInBatch(expiredUsers);

        return new DemoCleanupResult(
            expiredUsers.size(),
            Math.max(deletedSubscriptions, subscriptions.size()),
            deletedRefunds,
            cutoff
        );
    }

    public record DemoCleanupResult(
        long deletedUsers,
        long deletedSubscriptions,
        long deletedRefundRequests,
        LocalDateTime cutoff
    ) { }
}
