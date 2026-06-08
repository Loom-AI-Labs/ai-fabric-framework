package com.ai.infrastructure.behavior.api;

import com.ai.infrastructure.behavior.entity.BehaviorInsights;
import com.ai.infrastructure.behavior.repository.BehaviorInsightsRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/behavior/analytics")
@RequiredArgsConstructor
public class BehaviorAnalyticsController {

    private final BehaviorInsightsRepository repository;

    @GetMapping("/rapid-decline")
    public ResponseEntity<List<TrendAlertDTO>> getRapidDeclineAlerts() {
        List<TrendAlertDTO> alerts = repository.findRapidlyDecliningUsers().stream()
            .map(bi -> TrendAlertDTO.builder()
                .userId(bi.getUserId())
                .sentiment(bi.getSentimentLabel() != null ? bi.getSentimentLabel().name() : null)
                .churnRisk(bi.getChurnRisk())
                .churnReason(bi.getChurnReason())
                .trend(bi.getTrend() != null ? bi.getTrend().name() : null)
                .recommendations(bi.getRecommendations())
                .analyzedAt(bi.getAnalyzedAt())
                .build())
            .toList();

        return ResponseEntity.ok(alerts);
    }

    @GetMapping("/trend-distribution")
    public ResponseEntity<Map<String, Long>> getTrendDistribution() {
        Map<String, Long> distribution = repository.findAll().stream()
            .filter(bi -> bi.getTrend() != null)
            .collect(Collectors.groupingBy(
                bi -> bi.getTrend().name(),
                Collectors.counting()
            ));
        return ResponseEntity.ok(distribution);
    }

    @GetMapping("/sentiment-distribution")
    public ResponseEntity<Map<String, Long>> getSentimentDistribution() {
        Map<String, Long> distribution = repository.findAll().stream()
            .filter(bi -> bi.getSentimentLabel() != null)
            .collect(Collectors.groupingBy(
                bi -> bi.getSentimentLabel().getValue(),
                Collectors.counting()
            ));
        return ResponseEntity.ok(distribution);
    }

    @GetMapping("/users/{userId}/trend")
    public ResponseEntity<UserTrendDTO> getUserTrend(@PathVariable String userId) {
        return repository.findByUserId(userId)
            .map(insight -> UserTrendDTO.builder()
                .userId(userId)
                .currentSentiment(insight.getSentimentScore())
                .previousSentiment(insight.getPreviousSentimentScore())
                .sentimentDelta(insight.getSentimentDelta())
                .currentChurnRisk(insight.getChurnRisk())
                .previousChurnRisk(insight.getPreviousChurnRisk())
                .churnDelta(insight.getChurnDelta())
                .trend(insight.getTrend() != null ? insight.getTrend().name() : null)
                .churnReason(insight.getChurnReason())
                .recommendations(insight.getRecommendations())
                .analyzedAt(insight.getAnalyzedAt())
                .build())
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Data
    @Builder
    public static class TrendAlertDTO {
        private String userId;
        private String sentiment;
        private Double churnRisk;
        private String churnReason;
        private String trend;
        private List<String> recommendations;
        private LocalDateTime analyzedAt;
    }

    @Data
    @Builder
    public static class UserTrendDTO {
        private String userId;
        private Double currentSentiment;
        private Double previousSentiment;
        private Double sentimentDelta;
        private Double currentChurnRisk;
        private Double previousChurnRisk;
        private Double churnDelta;
        private String trend;
        private String churnReason;
        private List<String> recommendations;
        private LocalDateTime analyzedAt;
    }
}
