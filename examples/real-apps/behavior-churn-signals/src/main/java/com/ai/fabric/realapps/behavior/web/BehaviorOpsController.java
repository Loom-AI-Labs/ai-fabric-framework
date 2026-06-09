package com.ai.fabric.realapps.behavior.web;

import com.ai.infrastructure.behavior.entity.BehaviorInsights;
import com.ai.infrastructure.behavior.repository.BehaviorInsightsRepository;
import com.ai.infrastructure.behavior.service.BehaviorAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/behavior")
@RequiredArgsConstructor
public class BehaviorOpsController {

    private final BehaviorAnalysisService behaviorAnalysisService;
    private final BehaviorInsightsRepository insightsRepository;

    @PostMapping("/analyze/{userId}")
    public BehaviorInsights analyzeUser(@PathVariable String userId) {
        return behaviorAnalysisService.analyzeUser(userId);
    }

    @PostMapping("/process-next")
    public BehaviorInsights processNext() {
        return behaviorAnalysisService.processNextUser();
    }

    @GetMapping("/insights")
    public List<BehaviorInsights> list() {
        return insightsRepository.findAll();
    }

    @GetMapping("/insights/{userId}")
    public BehaviorInsights get(@PathVariable String userId) {
        return insightsRepository.findByUserId(userId).orElseThrow();
    }

    @GetMapping("/insights/{userId}/summary")
    public Map<String, Object> summary(@PathVariable String userId) {
        BehaviorInsights insight = insightsRepository.findByUserId(userId).orElseThrow();
        return Map.of(
            "userId", insight.getUserId(),
            "segment", insight.getSegment(),
            "sentimentLabel", insight.getSentimentLabel() != null ? insight.getSentimentLabel().name() : null,
            "sentimentScore", insight.getSentimentScore(),
            "churnRisk", insight.getChurnRisk(),
            "trend", insight.getTrend() != null ? insight.getTrend().name() : null,
            "requiresImmediateAction", insight.requiresImmediateAction()
        );
    }
}
