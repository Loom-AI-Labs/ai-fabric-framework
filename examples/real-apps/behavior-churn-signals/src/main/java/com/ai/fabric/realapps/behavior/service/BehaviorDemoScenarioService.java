package com.ai.fabric.realapps.behavior.service;

import ai.fabric.behavior.entity.BehaviorInsights;
import ai.fabric.behavior.repository.BehaviorInsightsRepository;
import ai.fabric.behavior.service.BehaviorAnalysisService;
import com.ai.fabric.realapps.behavior.domain.AppBehaviorEvent;
import com.ai.fabric.realapps.behavior.repo.AppBehaviorEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class BehaviorDemoScenarioService {

    private static final Map<String, DemoScenarioDefinition> SCENARIOS = createScenarios();

    private final DemoEventSeeder seeder;
    private final BehaviorAnalysisService behaviorAnalysisService;
    private final AppBehaviorEventRepository eventRepository;
    private final BehaviorInsightsRepository insightsRepository;
    private final RetentionStudioService retentionStudioService;
    private final ObjectMapper objectMapper;

    @Transactional
    public BehaviorDemoDashboard seed() {
        seeder.seed();
        return dashboard();
    }

    @Transactional
    public BehaviorDemoDashboard seedAndAnalyze() {
        seeder.seed();
        SCENARIOS.keySet().forEach(behaviorAnalysisService::analyzeUser);
        return dashboard();
    }

    @Transactional(readOnly = true)
    public BehaviorDemoDashboard dashboard() {
        List<BehaviorInsights> insights = insightsRepository.findAll();
        List<DemoScenarioSummary> summaries = SCENARIOS.values().stream()
            .map(definition -> summarize(definition, findInsight(insights, definition.userId())))
            .toList();

        return new BehaviorDemoDashboard(
            summaries,
            insights.stream().map(this::toInsightSummary).toList(),
            trendDistribution(insights),
            sentimentDistribution(insights),
            insights.stream()
                .filter(BehaviorInsights::requiresImmediateAction)
                .map(this::toInsightSummary)
                .toList(),
            eventRepository.count()
        );
    }

    @Transactional
    public BehaviorScenarioResult analyze(String userId) {
        DemoScenarioDefinition scenario = requireScenario(userId);
        BehaviorInsights insight = behaviorAnalysisService.analyzeUser(userId);
        return resultFor(scenario, insight);
    }

    @Transactional
    public BehaviorScenarioResult recordSignal(String userId, RecordBehaviorSignalRequest request) {
        DemoScenarioDefinition scenario = requireScenario(userId);
        RecordBehaviorSignalRequest effective = request != null ? request : new RecordBehaviorSignalRequest(null, null, null);

        AppBehaviorEvent event = new AppBehaviorEvent();
        event.setUserId(userId);
        event.setEventType(StringUtils.hasText(effective.eventType()) ? effective.eventType() : scenario.defaultSignalType());
        event.setEventTimestamp(LocalDateTime.now());
        event.setEventData(toJson(effective.eventData() != null && !effective.eventData().isEmpty()
            ? effective.eventData()
            : Map.of("message", scenario.defaultSignalMessage())));
        event.setSource(StringUtils.hasText(effective.source()) ? effective.source() : "demo-ui");
        eventRepository.save(event);

        BehaviorInsights insight = behaviorAnalysisService.analyzeUser(userId);
        return resultFor(scenario, insight);
    }

    @Transactional
    public RetentionOfferDemoResult retentionOffer(String userId, RetentionOfferDemoRequest request) {
        DemoScenarioDefinition scenario = requireScenario(userId);
        int discountPercent = request != null && request.discountPercent() != null
            ? request.discountPercent()
            : scenario.defaultDiscountPercent();
        boolean confirmed = request != null && Boolean.TRUE.equals(request.confirmed());

        RetentionStudioService.RetentionOfferResult offer = retentionStudioService.createOffer(
            new RetentionStudioService.RetentionOfferRequest(
                scenario.accountId(),
                scenario.userId(),
                discountPercent,
                confirmed
            )
        );

        return new RetentionOfferDemoResult(
            scenario.userId(),
            scenario.accountId(),
            scenario.customerName(),
            "create_retention_offer",
            "Create " + discountPercent + "% retention offer for " + scenario.customerName() + "?",
            offer
        );
    }

    private BehaviorScenarioResult resultFor(DemoScenarioDefinition scenario, BehaviorInsights insight) {
        RetentionStudioService.RetentionReviewResult review = retentionStudioService.review(
            new RetentionStudioService.RetentionReviewRequest(
                scenario.accountId(),
                scenario.userId(),
                scenario.planId(),
                scenario.usageDropPercent(),
                scenario.failedPayments(),
                scenario.supportTickets()
            )
        );

        return new BehaviorScenarioResult(
            summarize(scenario, Optional.ofNullable(insight)),
            toInsightSummary(insight),
            eventRepository.findByUserIdOrderByEventTimestampAsc(scenario.userId()).stream()
                .map(BehaviorEventSummary::from)
                .toList(),
            review,
            retentionOffer(scenario.userId(), new RetentionOfferDemoRequest(scenario.defaultDiscountPercent(), false))
        );
    }

    private DemoScenarioSummary summarize(DemoScenarioDefinition definition, Optional<BehaviorInsights> insight) {
        return new DemoScenarioSummary(
            definition.id(),
            definition.userId(),
            definition.accountId(),
            definition.customerName(),
            definition.planId(),
            definition.title(),
            definition.useCase(),
            definition.operatorGoal(),
            definition.defaultSignalType(),
            definition.defaultSignalMessage(),
            definition.defaultDiscountPercent(),
            definition.usageDropPercent(),
            definition.failedPayments(),
            definition.supportTickets(),
            eventRepository.findByUserIdOrderByEventTimestampAsc(definition.userId()).size(),
            insight.map(this::toInsightSummary).orElse(null)
        );
    }

    private Optional<BehaviorInsights> findInsight(List<BehaviorInsights> insights, String userId) {
        return insights.stream()
            .filter(insight -> userId.equals(insight.getUserId()))
            .findFirst();
    }

    private InsightSummary toInsightSummary(BehaviorInsights insight) {
        if (insight == null) {
            return null;
        }
        return new InsightSummary(
            insight.getUserId(),
            insight.getSegment(),
            insight.getSentimentLabel() != null ? insight.getSentimentLabel().name() : null,
            insight.getSentimentScore(),
            insight.getChurnRisk(),
            insight.getChurnReason(),
            insight.getTrend() != null ? insight.getTrend().name() : null,
            insight.getPatterns() != null ? insight.getPatterns() : List.of(),
            insight.getRecommendations() != null ? insight.getRecommendations() : List.of(),
            insight.getConfidence(),
            insight.getAiModelUsed(),
            insight.getProcessingTimeMs(),
            insight.requiresImmediateAction(),
            insight.getAnalyzedAt()
        );
    }

    private Map<String, Long> trendDistribution(List<BehaviorInsights> insights) {
        Map<String, Long> distribution = new LinkedHashMap<>();
        insights.stream()
            .filter(insight -> insight.getTrend() != null)
            .sorted(Comparator.comparing(insight -> insight.getTrend().name()))
            .forEach(insight -> distribution.merge(insight.getTrend().name(), 1L, Long::sum));
        return distribution;
    }

    private Map<String, Long> sentimentDistribution(List<BehaviorInsights> insights) {
        Map<String, Long> distribution = new LinkedHashMap<>();
        insights.stream()
            .filter(insight -> insight.getSentimentLabel() != null)
            .sorted(Comparator.comparing(insight -> insight.getSentimentLabel().name()))
            .forEach(insight -> distribution.merge(insight.getSentimentLabel().name(), 1L, Long::sum));
        return distribution;
    }

    private DemoScenarioDefinition requireScenario(String userId) {
        DemoScenarioDefinition scenario = SCENARIOS.get(userId);
        if (scenario == null) {
            throw new IllegalArgumentException("Unknown behavior demo user: " + userId);
        }
        return scenario;
    }

    private String toJson(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException ex) {
            return "{\"message\":\"" + String.valueOf(data).replace("\"", "'") + "\"}";
        }
    }

    private static Map<String, DemoScenarioDefinition> createScenarios() {
        Map<String, DemoScenarioDefinition> scenarios = new LinkedHashMap<>();
        scenarios.put("user-1001", new DemoScenarioDefinition(
            "billing-cancellation-risk",
            "user-1001",
            "acct-1001",
            "Acme Finance",
            "pro",
            "Billing-driven cancellation risk",
            "A finance team has repeated failed renewals, billing complaints, and cancellation intent.",
            "Spot churn before the renewal is lost and create a safe retention offer.",
            "CANCEL_INTENT",
            "Customer says renewal failed twice and asks how to cancel before month end.",
            25,
            62,
            2,
            3
        ));
        scenarios.put("user-1002", new DemoScenarioDefinition(
            "expansion-ready-account",
            "user-1002",
            "acct-1002",
            "Northstar Analytics",
            "enterprise",
            "Expansion-ready healthy account",
            "A growing team logs in often, uses reports, and upgraded twice.",
            "Confirm this account is healthy and avoid unnecessary retention intervention.",
            "UPGRADE",
            "Customer added more seats after adopting automated reporting.",
            0,
            0,
            0,
            0
        ));
        scenarios.put("user-1003", new DemoScenarioDefinition(
            "onboarding-friction",
            "user-1003",
            "acct-1003",
            "Harbor Clinics",
            "starter",
            "Onboarding friction and support confusion",
            "A healthcare operations team is searching help docs and complaining about admin setup.",
            "Route the account to adoption help before support friction becomes churn.",
            "SUPPORT_COMPLAINT",
            "Admin cannot find invite workflow and asks support for the third time.",
            10,
            34,
            0,
            1
        ));
        return Collections.unmodifiableMap(scenarios);
    }

    public record BehaviorDemoDashboard(
        List<DemoScenarioSummary> scenarios,
        List<InsightSummary> insights,
        Map<String, Long> trendDistribution,
        Map<String, Long> sentimentDistribution,
        List<InsightSummary> immediateAction,
        long totalEvents
    ) {}

    public record DemoScenarioSummary(
        String id,
        String userId,
        String accountId,
        String customerName,
        String planId,
        String title,
        String useCase,
        String operatorGoal,
        String defaultSignalType,
        String defaultSignalMessage,
        int defaultDiscountPercent,
        int usageDropPercent,
        int failedPayments,
        int supportTickets,
        int eventCount,
        InsightSummary insight
    ) {}

    public record BehaviorScenarioResult(
        DemoScenarioSummary scenario,
        InsightSummary insight,
        List<BehaviorEventSummary> events,
        RetentionStudioService.RetentionReviewResult retentionReview,
        RetentionOfferDemoResult retentionOfferPreview
    ) {}

    public record InsightSummary(
        String userId,
        String segment,
        String sentimentLabel,
        Double sentimentScore,
        Double churnRisk,
        String churnReason,
        String trend,
        List<String> patterns,
        List<String> recommendations,
        Double confidence,
        String model,
        Long processingTimeMs,
        boolean requiresImmediateAction,
        LocalDateTime analyzedAt
    ) {}

    public record BehaviorEventSummary(
        Long id,
        String userId,
        String eventType,
        LocalDateTime eventTimestamp,
        String eventData,
        String source
    ) {
        static BehaviorEventSummary from(AppBehaviorEvent event) {
            return new BehaviorEventSummary(
                event.getId(),
                event.getUserId(),
                event.getEventType(),
                event.getEventTimestamp(),
                event.getEventData(),
                event.getSource()
            );
        }
    }

    public record RecordBehaviorSignalRequest(
        String eventType,
        Map<String, Object> eventData,
        String source
    ) {}

    public record RetentionOfferDemoRequest(
        Integer discountPercent,
        Boolean confirmed
    ) {}

    public record RetentionOfferDemoResult(
        String userId,
        String accountId,
        String customerName,
        String actionName,
        String confirmationMessage,
        RetentionStudioService.RetentionOfferResult result
    ) {}

    private record DemoScenarioDefinition(
        String id,
        String userId,
        String accountId,
        String customerName,
        String planId,
        String title,
        String useCase,
        String operatorGoal,
        String defaultSignalType,
        String defaultSignalMessage,
        int defaultDiscountPercent,
        int usageDropPercent,
        int failedPayments,
        int supportTickets
    ) {}
}
