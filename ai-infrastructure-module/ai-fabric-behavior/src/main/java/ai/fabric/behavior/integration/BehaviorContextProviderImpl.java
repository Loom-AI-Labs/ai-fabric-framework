package ai.fabric.behavior.integration;

import ai.fabric.behavior.entity.BehaviorInsights;
import ai.fabric.behavior.service.BehaviorStorageAdapter;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.spi.BehaviorContext;
import ai.fabric.spi.BehaviorContextProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Bridges behavior insights into the SPI contract without coupling core to behavior.
 * Accepts arbitrary string userIds for maximum flexibility.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BehaviorContextProviderImpl implements BehaviorContextProvider {

    private static final Duration MAX_INSIGHT_AGE = Duration.ofHours(24);

    private final BehaviorStorageAdapter storageAdapter;

    @Override
    public Optional<BehaviorContext> getBehaviorContext(OrchestrationContext context) {
        if (context == null || !context.isAuthenticated() || !StringUtils.hasText(context.getUserId())) {
            log.trace("Behavior context not available for anonymous or missing user.");
            return Optional.empty();
        }

        String userId = context.getUserId();

        try {
            Optional<BehaviorInsights> insights = storageAdapter.findByUserId(userId);
            if (insights.isEmpty()) {
                log.debug("No behavior insights found for userId={}", userId);
                return Optional.empty();
            }

            BehaviorInsights insight = insights.get();
            if (isStale(insight)) {
                log.info("Behavior insights stale for userId={} (updatedAt={})", userId, insight.getUpdatedAt());
                return Optional.empty();
            }

            BehaviorContext behaviorContext = toBehaviorContext(insight, userId, context.getSessionId());
            log.debug("Behavior context loaded for userId={} segment={} sentiment={}",
                userId, behaviorContext.getSegment(), behaviorContext.getSentimentLabel());
            return Optional.of(behaviorContext);
        } catch (Exception ex) {
            log.error("Failed to fetch behavior context for userId={}", userId, ex);
            return Optional.empty();
        }
    }

    private boolean isStale(BehaviorInsights insights) {
        LocalDateTime analyzedAt = insights.getUpdatedAt();
        if (analyzedAt == null) {
            return true;
        }
        Duration age = Duration.between(analyzedAt, LocalDateTime.now());
        return age.compareTo(MAX_INSIGHT_AGE) > 0;
    }

    private BehaviorContext toBehaviorContext(BehaviorInsights insights, String userId, String sessionId) {
        return BehaviorContext.builder()
            .userId(userId)
            .sessionId(sessionId)
            .segment(insights.getSegment())
            .patterns(insights.getPatterns())
            .recommendations(insights.getRecommendations())
            .insights(insights.getInsights())
            .sentimentLabel(insights.getSentimentLabel() != null ? insights.getSentimentLabel().name() : null)
            .sentimentScore(insights.getSentimentScore())
            .churnRisk(insights.getChurnRisk())
            .churnReason(insights.getChurnReason())
            .trend(insights.getTrend() != null ? insights.getTrend().name() : null)
            .confidence(insights.getConfidence())
            .analyzedAt(insights.getUpdatedAt())
            .build();
    }
}
