package com.ai.fabric.realapps.behavior.service;

import ai.fabric.behavior.repository.BehaviorInsightsRepository;
import com.ai.fabric.realapps.behavior.repo.AppBehaviorEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.behavior-demo.cleanup", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DemoSessionCleanupService {

    private final AppBehaviorEventRepository eventRepository;
    private final BehaviorInsightsRepository insightsRepository;

    @Value("${app.behavior-demo.cleanup.ttl:PT6H}")
    private String ttl;

    @Scheduled(cron = "${app.behavior-demo.cleanup.cron:0 */30 * * * *}")
    @Transactional
    public CleanupResult cleanupExpiredSessions() {
        Duration duration = parseDuration(ttl);
        LocalDateTime cutoff = LocalDateTime.now().minus(duration);
        List<String> expired = eventRepository.findDistinctUserIds().stream()
            .filter(userId -> userId != null && userId.startsWith(BehaviorDemoScenarioService.SESSION_USER_PREFIX))
            .filter(userId -> {
                LocalDateTime latest = eventRepository.findLatestTimestamp(userId);
                return latest == null || latest.isBefore(cutoff);
            })
            .toList();

        expired.forEach(userId -> {
            insightsRepository.deleteByUserId(userId);
            eventRepository.deleteByUserId(userId);
        });

        if (!expired.isEmpty()) {
            log.info("Deleted {} expired behavior demo session users", expired.size());
        }
        return new CleanupResult(expired.size(), cutoff, expired);
    }

    Duration configuredTtl() {
        return parseDuration(ttl);
    }

    private Duration parseDuration(String raw) {
        try {
            return Duration.parse(raw);
        } catch (Exception ignored) {
            return Duration.ofHours(6);
        }
    }

    public record CleanupResult(
        int deletedUsers,
        LocalDateTime cutoff,
        List<String> userIds
    ) {}
}
