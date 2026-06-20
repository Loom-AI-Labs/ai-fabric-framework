package ai.fabric.security;

import ai.fabric.config.SecurityProperties;
import ai.fabric.dto.AIAccessSubjectContext;
import ai.fabric.dto.AISecurityEvent;
import ai.fabric.dto.AISecurityRequest;
import ai.fabric.dto.AISecurityResponse;
import ai.fabric.dto.PIIDetectionResult;
import ai.fabric.privacy.pii.PIIDetectionService;
import ai.fabric.security.policy.SecurityAnalysisPolicy;
import ai.fabric.security.policy.SecurityAnalysisResult;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Infrastructure-only security service that performs baseline threat detection and delegates
 * organisation-specific analysis to customer supplied hooks.
 */
@Slf4j
@RequiredArgsConstructor
public class AISecurityService {

    private static final int MAX_EVENTS_PER_USER = 1_000;
    private static final int MAX_ATTEMPTS_PER_WINDOW = 100;
    private static final long RATE_WINDOW_MS = Duration.ofMinutes(1).toMillis();

    private final PIIDetectionService piiDetectionService;
    private final Clock clock;
    private final SecurityProperties securityProperties;

    private final Map<String, List<AISecurityEvent>> securityEvents = new ConcurrentHashMap<>();
    private final Map<String, RateCounter> accessAttempts = new ConcurrentHashMap<>();
    private final AtomicLong eventSequence = new AtomicLong();

    @Autowired(required = false)
    private SecurityAnalysisPolicy securityPolicy;

    public AISecurityResponse analyzeRequest(AISecurityRequest request) {
        long started = System.nanoTime();
        try {
            validateRequest(request);
            String actorId = resolveSubjectId(request);

            LocalDateTime timestamp = Optional.ofNullable(request.getTimestamp())
                .orElseGet(() -> LocalDateTime.now(clock));

            List<String> threats = new ArrayList<>(detectBuiltInThreats(request));
            boolean blockPii = securityProperties.isBlockOnPiiDetection();
            log.debug("Security analysis for actor={} threats={}, blockOnPiiDetection={}",
                actorId, threats, blockPii);

            if (securityPolicy != null) {
                try {
                    SecurityAnalysisResult customResult = securityPolicy.analyzeSecurity(request);
                    if (customResult != null) {
                        if (customResult.getThreats() != null) {
                            threats.addAll(customResult.getThreats());
                        }
                        if (customResult.getRecommendations() != null && !customResult.getRecommendations().isEmpty()) {
                            log.debug("Custom security recommendations: {}", customResult.getRecommendations());
                        }
                    }
                } catch (Exception hookEx) {
                    log.warn("SecurityAnalysisPolicy threw an exception: {}", hookEx.getMessage());
                }
            }

            boolean rateLimited = checkRateLimit(request);
            if (rateLimited) {
                threats.add("RATE_LIMIT_EXCEEDED");
            }

            boolean blockingThreatPresent = threats.stream().anyMatch(this::isBlockingThreat);
            boolean shouldBlock = blockingThreatPresent || rateLimited;

            boolean secure = !shouldBlock;
            double securityScore = calculateSecurityScore(threats, rateLimited);

            AISecurityEvent event = recordSecurityEvent(request, timestamp, threats, securityScore, shouldBlock);

            long durationMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            return AISecurityResponse.builder()
                .requestId(request.getRequestId())
                .subjectId(actorId)
                .threatsDetected(List.copyOf(new HashSet<>(threats)))
                .securityScore(securityScore)
                .accessAllowed(!shouldBlock)
                .rateLimitExceeded(rateLimited)
                .shouldBlock(shouldBlock)
                .processingTimeMs(durationMs)
                .timestamp(timestamp)
                .success(true)
                .build();
        } catch (IllegalArgumentException | NullPointerException ex) {
            log.warn("Security request rejected: {}", ex.getMessage());
            return failedClosedResponse(request, ex.getMessage());
        } catch (Exception ex) {
            log.error("Security analysis failed", ex);
            return failedClosedResponse(request, ex.getMessage());
        }
    }

    public List<AISecurityEvent> getSecurityEvents(String subjectId) {
        return securityEvents.containsKey(subjectId)
            ? List.copyOf(securityEvents.get(subjectId))
            : List.of();
    }

    public List<AISecurityEvent> getAllSecurityEvents() {
        return securityEvents.values().stream()
            .flatMap(List::stream)
            .sorted((a, b) -> b.getTimestamp().compareTo(a.getTimestamp()))
            .toList();
    }

    public void clearSecurityEvents(String subjectId) {
        securityEvents.remove(subjectId);
    }

    public Map<String, Object> getSecurityStatistics() {
        long totalEvents = securityEvents.values().stream()
            .mapToLong(List::size)
            .sum();

        long blockedEvents = securityEvents.values().stream()
            .flatMap(List::stream)
            .filter(event -> "BLOCKED_REQUEST".equals(event.getEventType()))
            .count();

        return Map.of(
            "totalEvents", totalEvents,
            "blockedEvents", blockedEvents,
            "uniqueSubjects", securityEvents.size(),
            "blockRate", totalEvents > 0 ? (double) blockedEvents / totalEvents : 0.0
        );
    }

    private void validateRequest(AISecurityRequest request) {
        Objects.requireNonNull(request, "security request must not be null");
        AIAccessSubjectContext authContext = request.getAuthContext();
        if (authContext == null || (!hasText(authContext.getSubjectId()) && !hasText(authContext.getSessionId()))) {
            throw new IllegalArgumentException("authContext.subjectId or authContext.sessionId must be provided");
        }
    }

    private String resolveSubjectId(AISecurityRequest request) {
        if (request == null) {
            return null;
        }
        AIAccessSubjectContext authContext = request.getAuthContext();
        if (authContext == null) {
            return null;
        }
        if (hasText(authContext.getSubjectId())) {
            return authContext.getSubjectId().trim();
        }
        return hasText(authContext.getSessionId()) ? authContext.getSessionId().trim() : null;
    }

    private String resolveSessionId(AISecurityRequest request) {
        if (request == null || request.getAuthContext() == null) {
            return null;
        }
        String sessionId = request.getAuthContext().getSessionId();
        return hasText(sessionId) ? sessionId.trim() : null;
    }

    private List<String> detectBuiltInThreats(AISecurityRequest request) {
        List<String> threats = new ArrayList<>();
        String content = Optional.ofNullable(request.getContent()).orElse("");
        if (containsInjectionPatterns(content)) {
            threats.add("INJECTION_ATTACK");
        }
        if (containsPromptInjection(content)) {
            threats.add("PROMPT_INJECTION");
        }
        if (containsDataExfiltrationPatterns(content)) {
            threats.add("DATA_EXFILTRATION");
        }
        if (containsSystemManipulation(content)) {
            threats.add("SYSTEM_MANIPULATION");
        }
        if (piiDetectionService != null && !content.isBlank()) {
            PIIDetectionResult piiResult = piiDetectionService.analyze(content);
            if (piiResult != null && piiResult.isPiiDetected()) {
                threats.add("PII_DETECTED");
            }
        }
        return threats;
    }

    private boolean isBlockingThreat(String threat) {
        if ("PII_DETECTED".equals(threat)) {
            return securityProperties.isBlockOnPiiDetection();
        }
        return true;
    }

    private boolean containsInjectionPatterns(String content) {
        String lowered = content.toLowerCase();
        String[] patterns = {"';", "\";", " union ", " or 1=1", "<script", "eval(", "exec("};
        for (String pattern : patterns) {
            if (lowered.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsPromptInjection(String content) {
        String lowered = content.toLowerCase();
        String[] patterns = {"ignore previous instructions", "forget everything", "override", "jailbreak"};
        for (String pattern : patterns) {
            if (lowered.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsDataExfiltrationPatterns(String content) {
        String lowered = content.toLowerCase();
        String[] patterns = {"export all", "send data to", "download all", "copy database"};
        for (String pattern : patterns) {
            if (lowered.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsSystemManipulation(String content) {
        String lowered = content.toLowerCase();
        String[] patterns = {"shutdown", "restart service", "delete file", "kill process"};
        for (String pattern : patterns) {
            if (lowered.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private boolean checkRateLimit(AISecurityRequest request) {
        String actorId = resolveSubjectId(request);
        String key = actorId + ":" +
            Optional.ofNullable(request.getOperationType()).orElse("UNKNOWN");
        long now = clock.millis();
        RateCounter counter = accessAttempts.computeIfAbsent(key, k -> new RateCounter(now));
        synchronized (counter) {
            if (now - counter.windowStart > RATE_WINDOW_MS) {
                counter.windowStart = now;
                counter.count.set(0);
            }
            int attempts = counter.count.incrementAndGet();
            return attempts > MAX_ATTEMPTS_PER_WINDOW;
        }
    }

    private double calculateSecurityScore(List<String> threats, boolean rateLimited) {
        double score = 100.0;
        if (!threats.isEmpty()) {
            score -= Math.min(60, threats.size() * 15);
        }
        if (rateLimited) {
            score -= 25;
        }
        return Math.max(0, score);
    }

    private AISecurityEvent recordSecurityEvent(AISecurityRequest request,
                                                LocalDateTime timestamp,
                                                List<String> threats,
                                                double score,
        boolean blocked) {
        String actorId = resolveSubjectId(request);
        AISecurityEvent event = AISecurityEvent.builder()
            .eventId(nextEventId(timestamp))
            .subjectId(actorId)
            .requestId(request.getRequestId())
            .eventType(blocked ? "BLOCKED_REQUEST" : "SECURITY_CHECK")
            .threatsDetected(List.copyOf(new HashSet<>(threats)))
            .securityScore(score)
            .severity(determineSeverity(threats, score))
            .timestamp(timestamp)
            .ipAddress(request.getIpAddress())
            .userAgent(request.getUserAgent())
            .context(request.getContext())
            .sessionId(resolveSessionId(request))
            .build();

        securityEvents.computeIfAbsent(actorId,
                key -> Collections.synchronizedList(new ArrayList<>()))
            .add(event);

        List<AISecurityEvent> userEvents = securityEvents.get(actorId);
        if (userEvents.size() > MAX_EVENTS_PER_USER) {
            userEvents.remove(0);
        }

        return event;
    }

    private String nextEventId(LocalDateTime timestamp) {
        long epochSecond = timestamp.toEpochSecond(clock.getZone().getRules().getOffset(timestamp));
        return "SEC_" + epochSecond + "_" + eventSequence.incrementAndGet();
    }

    private AISecurityResponse failedClosedResponse(AISecurityRequest request, String errorMessage) {
        return AISecurityResponse.builder()
            .requestId(request != null ? request.getRequestId() : null)
            .subjectId(request != null ? resolveSubjectId(request) : null)
            .accessAllowed(false)
            .shouldBlock(true)
            .success(false)
            .errorMessage(errorMessage)
            .build();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String determineSeverity(List<String> threats, double score) {
        if (threats.contains("INJECTION_ATTACK") || threats.contains("SYSTEM_MANIPULATION")) {
            return "CRITICAL";
        }
        if (threats.contains("DATA_EXFILTRATION") || threats.contains("PII_DETECTED")) {
            return "HIGH";
        }
        if (score < 50.0) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private static final class RateCounter {
        private final AtomicInteger count = new AtomicInteger();
        private long windowStart;

        private RateCounter(long windowStart) {
            this.windowStart = windowStart;
        }
    }
}
