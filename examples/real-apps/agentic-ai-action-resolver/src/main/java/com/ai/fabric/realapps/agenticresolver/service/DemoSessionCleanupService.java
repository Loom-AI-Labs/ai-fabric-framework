package com.ai.fabric.realapps.agenticresolver.service;

import com.ai.fabric.realapps.agenticresolver.entity.Subscription;
import com.ai.fabric.realapps.agenticresolver.entity.User;
import com.ai.fabric.realapps.agenticresolver.repository.AgenticResolverDemoSessionRepository;
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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DemoSessionCleanupService {

    private static final long CANONICAL_DEMO_USER_MAX_ID = 100L;
    private static final String DEMO_USERNAME_PREFIX = "resolver_user_";

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final RefundRequestRepository refundRequestRepository;
    private final AgenticResolverDemoSessionRepository sessionRepository;
    private final Clock clock;

    @Value("${app.demo.cleanup.enabled:true}")
    private boolean cleanupEnabled;

    @Value("${app.demo.cleanup.ttl:PT6H}")
    private Duration cleanupTtl;

    @Value("${app.agentic-resolver.sessions.ttl:PT6H}")
    private Duration sessionTtl;

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
        List<User> expiredCandidates = userRepository.findByUserIdGreaterThanAndUsernameStartingWithAndCreatedAtBefore(
            CANONICAL_DEMO_USER_MAX_ID,
            DEMO_USERNAME_PREFIX,
            cutoff
        );
        Set<UUID> activeSubjectIds = activeSessionSubjectIds();
        List<User> expiredUsers = expiredCandidates.stream()
            .filter(user -> !activeSubjectIds.contains(user.getId()))
            .toList();
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

    private Set<UUID> activeSessionSubjectIds() {
        Duration effectiveSessionTtl =
            sessionTtl != null
                && !sessionTtl.isNegative()
                && !sessionTtl.isZero()
                ? sessionTtl
                : Duration.ofHours(6);
        return sessionRepository.findByLastAccessedAtGreaterThanEqual(
                clock.instant().minus(effectiveSessionTtl)
            ).stream()
            .flatMap(session -> session.scenarios().values().stream())
            .map(scenario -> scenario.subjectUserId())
            .collect(Collectors.toUnmodifiableSet());
    }

    public record DemoCleanupResult(
        long deletedUsers,
        long deletedSubscriptions,
        long deletedRefundRequests,
        LocalDateTime cutoff
    ) { }
}
