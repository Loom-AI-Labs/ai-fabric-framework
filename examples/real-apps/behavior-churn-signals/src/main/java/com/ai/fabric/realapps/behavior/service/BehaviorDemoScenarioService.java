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
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BehaviorDemoScenarioService {

    public static final String SESSION_USER_PREFIX = "behavior-demo-user-";
    public static final String SESSION_ACCOUNT_PREFIX = "behavior-demo-account-";

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
    public BehaviorDemoDashboard seed(String sessionId) {
        if (StringUtils.hasText(sessionId)) {
            seedSessionScenarios(sessionId);
            return dashboard(sessionId);
        }
        return seed();
    }

    @Transactional
    public BehaviorDemoDashboard seedAndAnalyze() {
        seeder.seed();
        SCENARIOS.keySet().forEach(behaviorAnalysisService::analyzeUser);
        return dashboard();
    }

    @Transactional
    public BehaviorDemoDashboard seedAndAnalyze(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return seedAndAnalyze();
        }
        List<DemoScenarioDefinition> scenarios = seedSessionScenarios(sessionId);
        scenarios.forEach(scenario -> behaviorAnalysisService.analyzeUser(scenario.userId()));
        return dashboard(sessionId);
    }

    @Transactional(readOnly = true)
    public BehaviorDemoDashboard dashboard() {
        return dashboard(null);
    }

    @Transactional(readOnly = true)
    public BehaviorDemoDashboard dashboard(String sessionId) {
        List<BehaviorInsights> insights = insightsRepository.findAll();
        List<DemoScenarioSummary> summaries = scenariosForSession(sessionId).stream()
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
    public DemoSessionResponse createSession(CreateDemoSessionRequest request) {
        String sessionId = request != null && StringUtils.hasText(request.sessionId())
            ? normalizeSessionId(request.sessionId())
            : "behavior-session-" + UUID.randomUUID();
        boolean analyze = request == null || request.analyze() == null || Boolean.TRUE.equals(request.analyze());
        List<DemoScenarioDefinition> scenarios = seedSessionScenarios(sessionId);
        if (analyze) {
            scenarios.forEach(scenario -> behaviorAnalysisService.analyzeUser(scenario.userId()));
        }
        return new DemoSessionResponse(
            sessionId,
            scenarios.stream().map(scenario -> summarize(scenario, insightsRepository.findByUserId(scenario.userId()))).toList(),
            dashboard(sessionId)
        );
    }

    @Transactional
    public ResetResult reset(ResetRequest request) {
        ResetRequest effective = request != null ? request : new ResetRequest(null, null);
        if (!Boolean.TRUE.equals(effective.confirm())) {
            return new ResetResult(false, "confirm=true is required to reset behavior demo data", 0, dashboard(effective.sessionId()));
        }

        int deletedUsers;
        if (StringUtils.hasText(effective.sessionId())) {
            List<DemoScenarioDefinition> scenarios = scenariosForSession(effective.sessionId());
            scenarios.forEach(scenario -> {
                insightsRepository.deleteByUserId(scenario.userId());
                eventRepository.deleteByUserId(scenario.userId());
            });
            deletedUsers = scenarios.size();
            return new ResetResult(true, "Behavior demo session reset", deletedUsers, dashboard(effective.sessionId()));
        }

        deletedUsers = eventRepository.findDistinctUserIds().size();
        insightsRepository.deleteAll();
        eventRepository.deleteAll();
        return new ResetResult(true, "Behavior demo data reset", deletedUsers, dashboard());
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
            : defaultEventData(scenario)));
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
            definition.baseUserId(),
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
            definition.expectedActionFamily(),
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
        Optional<DemoScenarioDefinition> scenario = resolveScenario(userId);
        if (scenario.isEmpty()) {
            throw new IllegalArgumentException("Unknown behavior demo user: " + userId);
        }
        return scenario.get();
    }

    private String toJson(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException ex) {
            return "{\"message\":\"" + String.valueOf(data).replace("\"", "'") + "\"}";
        }
    }

    private Map<String, Object> defaultEventData(DemoScenarioDefinition scenario) {
        return switch (scenario.id()) {
            case "billing-cancellation-risk" -> Map.of(
                "reason", "card_declined",
                "invoiceStatus", "past_due",
                "renewalAttempt", "2",
                "gateway", "stripe"
            );
            case "expansion-ready-account" -> Map.of(
                "count", "8",
                "plan", "enterprise",
                "channel", "admin_console"
            );
            case "onboarding-friction" -> Map.of(
                "query", "invite team members",
                "resultsClicked", "0",
                "sessionMinutes", "12"
            );
            case "release-regression" -> Map.of(
                "feature", "dashboard",
                "code", "REPORT_WIDGET_TIMEOUT",
                "releaseVersion", "2026.07.dashboard"
            );
            case "silent-churn" -> Map.of(
                "days", "14",
                "previousWeeklyLogins", "9",
                "currentWeeklyLogins", "0"
            );
            default -> Map.of("category", scenario.defaultSignalType().toLowerCase(), "scenario", scenario.id());
        };
    }

    private static Map<String, DemoScenarioDefinition> createScenarios() {
        Map<String, DemoScenarioDefinition> scenarios = new LinkedHashMap<>();
        scenarios.put("user-1001", new DemoScenarioDefinition(
            "billing-cancellation-risk",
            "user-1001",
            "user-1001",
            "acct-1001",
            "Acme Finance",
            "pro",
            "Billing-driven cancellation risk",
            "A finance team has repeated failed renewals, billing complaints, and cancellation intent.",
            "Spot churn before the renewal is lost and create a safe retention offer.",
            "PAYMENT_FAILED",
            "Renewal payment failed in the billing service.",
            25,
            62,
            2,
            3,
            "RETENTION_OFFER"
        ));
        scenarios.put("user-1002", new DemoScenarioDefinition(
            "expansion-ready-account",
            "user-1002",
            "user-1002",
            "acct-1002",
            "Northstar Analytics",
            "enterprise",
            "Expansion-ready healthy account",
            "A growing team logs in often, uses reports, and upgraded twice.",
            "Confirm this account is healthy and avoid unnecessary retention intervention.",
            "SEAT_ADDED",
            "Account admin added seats through the billing system.",
            0,
            0,
            0,
            0,
            "EXPANSION_FOLLOW_UP"
        ));
        scenarios.put("user-1003", new DemoScenarioDefinition(
            "onboarding-friction",
            "user-1003",
            "user-1003",
            "acct-1003",
            "Harbor Clinics",
            "starter",
            "Onboarding friction and support confusion",
            "A healthcare operations team is searching help docs and complaining about admin setup.",
            "Route the account to adoption help before support friction becomes churn.",
            "HELP_CENTER_SEARCH",
            "User searched help content during setup.",
            10,
            34,
            0,
            2,
            "ADOPTION_HELP"
        ));
        scenarios.put("user-1004", new DemoScenarioDefinition(
            "release-regression",
            "user-1004",
            "user-1004",
            "acct-1004",
            "BrightMarket",
            "pro",
            "Release regression signal",
            "A team hit dashboard errors after a release and report exports dropped sharply.",
            "Escalate product regression evidence instead of offering a discount first.",
            "FEATURE_ERROR",
            "Dashboard client telemetry recorded a report widget timeout.",
            0,
            58,
            0,
            2,
            "ENGINEERING_ESCALATION"
        ));
        scenarios.put("user-1005", new DemoScenarioDefinition(
            "silent-churn",
            "user-1005",
            "user-1005",
            "acct-1005",
            "QuietRiver Legal",
            "starter",
            "Silent churn",
            "A customer has no complaints, but usage and logins are steadily disappearing.",
            "Detect quiet disengagement before a cancellation request exists.",
            "NO_LOGIN_14D",
            "Usage analytics recorded no login for fourteen days.",
            0,
            41,
            0,
            0,
            "PROACTIVE_CHECK_IN"
        ));
        return Collections.unmodifiableMap(scenarios);
    }

    private List<DemoScenarioDefinition> seedSessionScenarios(String sessionId) {
        String normalized = normalizeSessionId(sessionId);
        LocalDateTime base = LocalDateTime.now().minusHours(2);
        return SCENARIOS.values().stream()
            .map(definition -> cloneForSession(definition, normalized))
            .peek(definition -> seeder.seedScenario(
                definition.userId(),
                definition.id(),
                "demo-session",
                base.plusMinutes(Math.abs(definition.id().hashCode() % 60))
            ))
            .toList();
    }

    private List<DemoScenarioDefinition> scenariosForSession(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return List.copyOf(SCENARIOS.values());
        }
        String normalized = normalizeSessionId(sessionId);
        return SCENARIOS.values().stream()
            .map(definition -> cloneForSession(definition, normalized))
            .toList();
    }

    private Optional<DemoScenarioDefinition> resolveScenario(String userId) {
        if (!StringUtils.hasText(userId)) {
            return Optional.empty();
        }
        DemoScenarioDefinition canonical = SCENARIOS.get(userId);
        if (canonical != null) {
            return Optional.of(canonical);
        }
        if (!userId.startsWith(SESSION_USER_PREFIX)) {
            return Optional.empty();
        }
        return SCENARIOS.values().stream()
            .filter(definition -> userId.endsWith("-" + definition.baseUserId()))
            .findFirst()
            .map(definition -> cloneForUserId(definition, userId));
    }

    private DemoScenarioDefinition cloneForSession(DemoScenarioDefinition definition, String sessionId) {
        return cloneForUserId(definition, SESSION_USER_PREFIX + sessionId + "-" + definition.baseUserId());
    }

    private DemoScenarioDefinition cloneForUserId(DemoScenarioDefinition definition, String userId) {
        String suffix = userId.replace(SESSION_USER_PREFIX, "");
        return new DemoScenarioDefinition(
            definition.id(),
            definition.baseUserId(),
            userId,
            SESSION_ACCOUNT_PREFIX + suffix + "-" + definition.accountId(),
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
            definition.expectedActionFamily()
        );
    }

    private String normalizeSessionId(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return "behavior-session-" + UUID.randomUUID();
        }
        return sessionId.trim().replaceAll("[^A-Za-z0-9_-]", "-");
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
        String baseUserId,
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
        String expectedActionFamily,
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

    public record CreateDemoSessionRequest(
        String sessionId,
        Boolean analyze
    ) {}

    public record ResetRequest(
        String sessionId,
        Boolean confirm
    ) {}

    public record ResetResult(
        boolean success,
        String message,
        int deletedUsers,
        BehaviorDemoDashboard dashboard
    ) {}

    public record DemoSessionResponse(
        String sessionId,
        List<DemoScenarioSummary> scenarios,
        BehaviorDemoDashboard dashboard
    ) {}

    private record DemoScenarioDefinition(
        String id,
        String baseUserId,
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
        String expectedActionFamily
    ) {}
}
