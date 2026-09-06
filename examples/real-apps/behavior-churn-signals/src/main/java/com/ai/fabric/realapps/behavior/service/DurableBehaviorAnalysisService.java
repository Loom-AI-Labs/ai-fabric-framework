package com.ai.fabric.realapps.behavior.service;

import ai.fabric.behavior.entity.BehaviorInsights;
import ai.fabric.behavior.model.BehaviorTrend;
import ai.fabric.behavior.model.SentimentLabel;
import ai.fabric.behavior.repository.BehaviorInsightsRepository;
import ai.fabric.behavior.service.BehaviorInsightPersistenceService;
import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.gateway.ExecutionHandle;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.client.SpecialistClient;
import ai.fabric.execution.specialist.client.SpecialistClientFactory;
import ai.fabric.execution.specialist.client.SpecialistExecutionSnapshot;
import ai.fabric.execution.specialist.client.SpecialistInvocation;
import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import com.ai.fabric.realapps.behavior.domain.AppBehaviorEvent;
import com.ai.fabric.realapps.behavior.domain.BehaviorAnalysisJob;
import com.ai.fabric.realapps.behavior.repo.AppBehaviorEventRepository;
import com.ai.fabric.realapps.behavior.repo.BehaviorAnalysisJobRepository;
import com.ai.fabric.realapps.behavior.specialist.BehaviorRiskAnalysisRequest;
import com.ai.fabric.realapps.behavior.specialist.BehaviorRiskAnalysisResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class DurableBehaviorAnalysisService {

    public static final SpecialistId SPECIALIST_ID =
        SpecialistId.of("behavior-risk-analyst", "1");
    private static final Set<String> SCOPES = Set.of(
        "specialist:behavior-risk-analyst@1"
    );

    private final SpecialistClient<BehaviorRiskAnalysisRequest, BehaviorRiskAnalysisResult> client;
    private final BehaviorDemoScenarioService scenarioService;
    private final AppBehaviorEventRepository eventRepository;
    private final BehaviorInsightsRepository insightRepository;
    private final BehaviorInsightPersistenceService persistenceService;
    private final BehaviorAnalysisJobRepository jobRepository;
    private final ObjectMapper objectMapper;
    private final CanonicalJsonSupport canonicalJson;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public DurableBehaviorAnalysisService(
        SpecialistClientFactory clientFactory,
        BehaviorDemoScenarioService scenarioService,
        AppBehaviorEventRepository eventRepository,
        BehaviorInsightsRepository insightRepository,
        BehaviorInsightPersistenceService persistenceService,
        BehaviorAnalysisJobRepository jobRepository,
        ObjectMapper objectMapper,
        CanonicalJsonSupport canonicalJson
    ) {
        this(
            clientFactory.bind(
                SPECIALIST_ID,
                BehaviorRiskAnalysisRequest.class,
                BehaviorRiskAnalysisResult.class
            ),
            scenarioService,
            eventRepository,
            insightRepository,
            persistenceService,
            jobRepository,
            objectMapper,
            canonicalJson,
            Clock.systemUTC()
        );
    }

    DurableBehaviorAnalysisService(
        SpecialistClient<BehaviorRiskAnalysisRequest, BehaviorRiskAnalysisResult> client,
        BehaviorDemoScenarioService scenarioService,
        AppBehaviorEventRepository eventRepository,
        BehaviorInsightsRepository insightRepository,
        BehaviorInsightPersistenceService persistenceService,
        BehaviorAnalysisJobRepository jobRepository,
        ObjectMapper objectMapper,
        CanonicalJsonSupport canonicalJson,
        Clock clock
    ) {
        this.client = client;
        this.scenarioService = scenarioService;
        this.eventRepository = eventRepository;
        this.insightRepository = insightRepository;
        this.persistenceService = persistenceService;
        this.jobRepository = jobRepository;
        this.objectMapper = objectMapper;
        this.canonicalJson = canonicalJson;
        this.clock = clock;
    }

    public AnalysisView submit(String sessionId, String userId, String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        return submit(
            sessionId,
            userId,
            idempotencyKey.trim(),
            ExecutionSource.APPLICATION
        );
    }

    public AnalysisView submitScheduled(String sessionId, String userId) {
        return submit(sessionId, userId, null, ExecutionSource.SCHEDULED);
    }

    private AnalysisView submit(
        String sessionId,
        String userId,
        String requestedIdempotencyKey,
        ExecutionSource executionSource
    ) {
        scenarioService.requireSessionUser(sessionId, userId);

        Optional<BehaviorInsights> previous = insightRepository.findByUserId(userId);
        List<AppBehaviorEvent> events = newEvents(userId, previous.orElse(null));
        if (events.isEmpty()) {
            throw new IllegalStateException(
                "No new behavior events are available for analysis"
            );
        }

        BehaviorRiskAnalysisRequest input = request(userId, previous.orElse(null), events);
        String idempotencyKey = executionSource == ExecutionSource.SCHEDULED
            ? scheduledIdempotencyKey(userId, input)
            : requestedIdempotencyKey;
        ExecutionHandle handle = client.submit(
            new SpecialistInvocation<>(
                input,
                trustedContext(sessionId, userId, executionSource),
                null,
                null,
                idempotencyKey
            )
        );

        if (handle.failureReason() != null) {
            return rejected(
                handle,
                userId,
                executionSource,
                previous.orElse(null),
                events
            );
        }

        Optional<BehaviorAnalysisJob> existing = jobRepository.findByInvocationId(handle.invocationId());
        boolean replayed = existing.isPresent();
        BehaviorAnalysisJob job = existing.orElseGet(() -> jobRepository.save(
            new BehaviorAnalysisJob(
                handle.invocationId(),
                sessionId,
                userId,
                idempotencyKey,
                executionSource.name(),
                json(previous.map(this::previousInsight).orElse(null)),
                json(events.stream().map(this::eventFact).toList()),
                events.size(),
                clock.instant()
            )
        ));
        return view(job, handle, null, replayed, null);
    }

    @Transactional
    public AnalysisView find(String sessionId, String invocationId) {
        BehaviorAnalysisJob job = requireOwnedJob(sessionId, invocationId);
        SpecialistExecutionSnapshot<BehaviorRiskAnalysisResult> snapshot = client.find(
            invocationId,
            trustedContext(
                sessionId,
                job.getUserId(),
                executionSource(job)
            )
        ).orElseThrow(() -> new IllegalArgumentException("Analysis invocation was not found"));

        BehaviorDemoScenarioService.BehaviorScenarioResult result = null;
        String projectionStatus = null;
        if (snapshot.result() != null && snapshot.result().succeeded()) {
            Projection projection = applyOnce(job, snapshot.result().output());
            result = projection.result();
            projectionStatus = projection.status();
        }
        return view(job, snapshot.handle(), result, false, projectionStatus);
    }

    @Transactional(readOnly = true)
    public List<AnalysisView> list(String sessionId) {
        requireSessionId(sessionId);
        return jobRepository.findBySessionIdOrderBySubmittedAtDesc(sessionId).stream()
            .map(job -> client.find(
                job.getInvocationId(),
                trustedContext(
                    sessionId,
                    job.getUserId(),
                    executionSource(job)
                )
            ).map(snapshot -> view(job, snapshot.handle(), null, false, null))
                .orElseGet(() -> missing(job)))
            .toList();
    }

    @Transactional
    public AnalysisView cancel(String sessionId, String invocationId) {
        BehaviorAnalysisJob job = requireOwnedJob(sessionId, invocationId);
        client.cancel(
            invocationId,
            trustedContext(
                sessionId,
                job.getUserId(),
                executionSource(job)
            )
        );
        return find(sessionId, invocationId);
    }

    @Transactional
    public void deleteSessionJobs(String sessionId) {
        requireSessionId(sessionId);
        jobRepository.deleteBySessionId(sessionId);
    }

    private Projection applyOnce(
        BehaviorAnalysisJob job,
        BehaviorRiskAnalysisResult output
    ) {
        if (output.insights().get("action_family") == null) {
            throw new IllegalStateException(
                "Behavior specialist output is missing insights.action_family"
            );
        }
        BehaviorInsights current = insightRepository.findByUserId(job.getUserId()).orElse(null);
        if (job.getAppliedAt() != null) {
            return new Projection(
                scenarioService.projectInsight(job.getUserId(), current),
                "APPLIED"
            );
        }
        if (current != null && current.getAnalyzedAt() != null) {
            Instant currentAnalysis = current.getAnalyzedAt().toInstant(ZoneOffset.UTC);
            if (currentAnalysis.isAfter(job.getSubmittedAt())) {
                return new Projection(
                    scenarioService.projectInsight(job.getUserId(), current),
                    "SUPERSEDED"
                );
            }
        }

        long started = System.currentTimeMillis();
        BehaviorInsights next = BehaviorInsights.builder()
            .id(current != null ? current.getId() : null)
            .userId(job.getUserId())
            .segment(output.segment())
            .patterns(output.patterns())
            .recommendations(output.recommendations())
            .insights(output.insights())
            .sentimentScore(output.sentiment().score())
            .sentimentLabel(SentimentLabel.fromString(output.sentiment().label()))
            .churnRisk(output.churn().risk())
            .churnReason(output.churn().reason())
            .previousSentimentScore(current != null ? current.getSentimentScore() : null)
            .previousChurnRisk(current != null ? current.getChurnRisk() : null)
            .trend(BehaviorTrend.fromString(output.trend()))
            .analyzedAt(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
            .confidence(output.confidence())
            .aiModelUsed(SPECIALIST_ID.toString())
            .modelPromptVersion("specialist-v1")
            .processingTimeMs(System.currentTimeMillis() - started)
            .createdAt(current != null ? current.getCreatedAt() : null)
            .build();
        BehaviorInsights saved = persistenceService.save(next);
        job.markApplied(clock.instant());
        jobRepository.save(job);
        return new Projection(
            scenarioService.projectInsight(job.getUserId(), saved),
            "APPLIED"
        );
    }

    private AnalysisView view(
        BehaviorAnalysisJob job,
        ExecutionHandle handle,
        BehaviorDemoScenarioService.BehaviorScenarioResult result,
        boolean replayed,
        String projectionStatus
    ) {
        return new AnalysisView(
            handle.invocationId(),
            job.getUserId(),
            handle.durability().name(),
            handle.status().name(),
            executionSource(job).name(),
            principalType(executionSource(job)).name(),
            replayed,
            job.getSubmittedAt(),
            handle.deadline(),
            handle.expiresAt(),
            parse(job.getPreviousInsightJson()),
            parseList(job.getConsideredEventsJson()),
            job.getConsideredEventCount(),
            projectionStatus,
            result,
            handle.failureReason() == null
                ? null
                : new FailureView(handle.failureReason(), publicFailure(handle.failureReason()))
        );
    }

    private AnalysisView rejected(
        ExecutionHandle handle,
        String userId,
        ExecutionSource executionSource,
        BehaviorInsights previous,
        List<AppBehaviorEvent> events
    ) {
        return new AnalysisView(
            handle.invocationId(),
            userId,
            handle.durability().name(),
            handle.status().name(),
            executionSource.name(),
            principalType(executionSource).name(),
            false,
            clock.instant(),
            handle.deadline(),
            handle.expiresAt(),
            previous == null ? Map.of() : objectMapper.convertValue(previousInsight(previous), new TypeReference<>() {}),
            events.stream().map(event -> objectMapper.convertValue(eventFact(event), new TypeReference<Map<String, Object>>() {})).toList(),
            events.size(),
            null,
            null,
            new FailureView(handle.failureReason(), publicFailure(handle.failureReason()))
        );
    }

    private AnalysisView missing(BehaviorAnalysisJob job) {
        return new AnalysisView(
            job.getInvocationId(),
            job.getUserId(),
            "DURABLE",
            "EXPIRED",
            executionSource(job).name(),
            principalType(executionSource(job)).name(),
            false,
            job.getSubmittedAt(),
            null,
            null,
            parse(job.getPreviousInsightJson()),
            parseList(job.getConsideredEventsJson()),
            job.getConsideredEventCount(),
            null,
            null,
            new FailureView("EXECUTION_NOT_FOUND", "The durable result is no longer available")
        );
    }

    private BehaviorRiskAnalysisRequest request(
        String userId,
        BehaviorInsights previous,
        List<AppBehaviorEvent> events
    ) {
        BehaviorDemoScenarioService.DemoScenarioSummary scenario = scenarioService.dashboard().scenarios().stream()
            .filter(candidate -> userId.equals(candidate.userId()))
            .findFirst()
            .orElseGet(() -> scenarioService.dashboard(sessionFromUser(userId)).scenarios().stream()
                .filter(candidate -> userId.equals(candidate.userId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown behavior user")));
        return new BehaviorRiskAnalysisRequest(
            "Compare the previous approved insight with the new raw events and produce the current behavior risk assessment.",
            previous != null ? previousInsight(previous) : null,
            events.stream().map(this::eventFact).toList(),
            Map.of(
                "planId", scenario.planId(),
                "scenario", scenario.id(),
                "operatorGoal", scenario.operatorGoal()
            )
        );
    }

    private List<AppBehaviorEvent> newEvents(String userId, BehaviorInsights previous) {
        if (previous == null || previous.getAnalyzedAt() == null) {
            return eventRepository.findByUserIdOrderByEventTimestampAsc(userId);
        }
        return eventRepository.findByUserIdAndEventTimestampAfterOrderByEventTimestampAsc(
            userId,
            previous.getAnalyzedAt()
        );
    }

    private BehaviorRiskAnalysisRequest.PreviousInsight previousInsight(BehaviorInsights insight) {
        return new BehaviorRiskAnalysisRequest.PreviousInsight(
            insight.getSegment(),
            insight.getSentimentLabel() != null ? insight.getSentimentLabel().name() : null,
            insight.getSentimentScore(),
            insight.getChurnRisk(),
            insight.getChurnReason(),
            insight.getTrend() != null ? insight.getTrend().name() : null,
            insight.getPatterns(),
            insight.getRecommendations(),
            insight.getConfidence(),
            insight.getAnalyzedAt() != null ? insight.getAnalyzedAt().toString() : null
        );
    }

    private BehaviorRiskAnalysisRequest.EventFact eventFact(AppBehaviorEvent event) {
        return new BehaviorRiskAnalysisRequest.EventFact(
            StringUtils.hasText(event.getExternalEventId())
                ? event.getExternalEventId()
                : "db-event-" + event.getId(),
            event.getEventType(),
            event.getEventTimestamp().toString(),
            StringUtils.hasText(event.getSource()) ? event.getSource() : "application",
            eventData(event.getEventData())
        );
    }

    private Map<String, Object> eventData(String raw) {
        if (!StringUtils.hasText(raw)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<>() {});
        } catch (Exception ex) {
            throw new IllegalStateException("Stored behavior event data is invalid", ex);
        }
    }

    private BehaviorAnalysisJob requireOwnedJob(String sessionId, String invocationId) {
        requireSessionId(sessionId);
        BehaviorAnalysisJob job = jobRepository.findByInvocationId(invocationId)
            .orElseThrow(() -> new IllegalArgumentException("Analysis invocation was not found"));
        if (!job.getSessionId().equals(sessionId)) {
            throw new IllegalArgumentException("Analysis invocation does not belong to this session");
        }
        return job;
    }

    private TrustedExecutionContext trustedContext(
        String sessionId,
        String userId,
        ExecutionSource source
    ) {
        return new TrustedExecutionContext(
            new ExecutionPrincipal(
                source == ExecutionSource.SCHEDULED
                    ? "behavior-signals-scheduler"
                    : "behavior-signals-demo",
                principalType(source)
            ),
            new ExecutionSubjectRef("behavior-user", userId),
            source,
            "behavior-demo",
            sessionId,
            SCOPES,
            sessionId,
            clock.instant()
        );
    }

    private ExecutionPrincipalType principalType(ExecutionSource source) {
        return source == ExecutionSource.SCHEDULED
            ? ExecutionPrincipalType.SYSTEM
            : ExecutionPrincipalType.SERVICE;
    }

    private ExecutionSource executionSource(BehaviorAnalysisJob job) {
        if (!StringUtils.hasText(job.getExecutionSource())) {
            return ExecutionSource.APPLICATION;
        }
        try {
            return ExecutionSource.valueOf(job.getExecutionSource());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                "Stored behavior analysis execution source is invalid",
                exception
            );
        }
    }

    private String scheduledIdempotencyKey(
        String userId,
        BehaviorRiskAnalysisRequest input
    ) {
        return "behavior-scheduled:v1:"
            + userId
            + ":"
            + canonicalJson.hashValue(input).substring(0, 24);
    }

    private void requireSessionId(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException("X-Demo-Session-Id is required");
        }
    }

    private void requireIdempotencyKey(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        if (value.trim().length() > 200) {
            throw new IllegalArgumentException("Idempotency-Key must not exceed 200 characters");
        }
    }

    private String sessionFromUser(String userId) {
        String prefix = BehaviorDemoScenarioService.SESSION_USER_PREFIX;
        if (!userId.startsWith(prefix)) {
            return "";
        }
        int baseUser = userId.lastIndexOf("-user-");
        return baseUser > prefix.length()
            ? userId.substring(prefix.length(), baseUser)
            : "";
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not persist behavior analysis context", ex);
        }
    }

    private Map<String, Object> parse(String value) {
        if (!StringUtils.hasText(value) || "null".equals(value)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (Exception ex) {
            throw new IllegalStateException("Stored previous insight is invalid", ex);
        }
    }

    private List<Map<String, Object>> parseList(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (Exception ex) {
            throw new IllegalStateException("Stored considered events are invalid", ex);
        }
    }

    private String publicFailure(String reason) {
        return switch (reason) {
            case "IDEMPOTENCY_CONFLICT" -> "This key was already used for different analysis facts";
            case "EXECUTION_CANCELLED" -> "The analysis was cancelled";
            case "EXECUTION_EXPIRED" -> "The analysis expired before completion";
            default -> "The durable behavior analysis did not complete";
        };
    }

    public record AnalysisView(
        String invocationId,
        String userId,
        String durability,
        String status,
        String executionSource,
        String principalType,
        boolean replayed,
        Instant submittedAt,
        Instant deadline,
        Instant expiresAt,
        Map<String, Object> previousInsight,
        List<Map<String, Object>> consideredEvents,
        int consideredEventCount,
        String projectionStatus,
        BehaviorDemoScenarioService.BehaviorScenarioResult result,
        FailureView failure
    ) {}

    public record FailureView(String reason, String message) {}

    private record Projection(
        BehaviorDemoScenarioService.BehaviorScenarioResult result,
        String status
    ) {}
}
