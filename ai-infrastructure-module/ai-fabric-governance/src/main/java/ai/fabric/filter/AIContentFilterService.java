package ai.fabric.filter;

import ai.fabric.dto.AIContentFilterRequest;
import ai.fabric.dto.AIContentFilterResponse;
import ai.fabric.dto.AIAccessSubjectContext;
import ai.fabric.core.AICoreService;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * AI Content Filter Service for content moderation and filtering
 */
@Slf4j
@RequiredArgsConstructor
public class AIContentFilterService {

    private static final String TEMPLATE_FAMILY = "governance/content-filter";
    private static final String TEMPLATE_ANALYZE_VIOLATIONS = "analyze-violations";
    private static final String PLACEHOLDER_CONTENT = "content";

    private final AICoreService aiCoreService;
    private final PromptTemplateResolver promptTemplateResolver;
    private final PromptRenderer promptRenderer;
    private final Map<String, List<String>> blockedContent = new ConcurrentHashMap<>();
    private final Map<String, List<String>> allowedContent = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> filterSettings = new ConcurrentHashMap<>();

    /**
     * Filter content based on policies and rules
     */
    public AIContentFilterResponse filterContent(AIContentFilterRequest request) {
        Objects.requireNonNull(request, "content filter request must not be null");
        log.info("Filtering content for subject: {}", resolveSubjectId(request));
        
        try {
            long startTime = System.currentTimeMillis();
            
            // Analyze content for violations
            List<String> violations = analyzeContentViolations(request);
            
            // Check content against blocked lists
            boolean isBlocked = checkBlockedContent(request.getContent());
            
            // Check content against allowed lists
            boolean isAllowed = checkAllowedContent(request.getContent());
            
            // Determine if content should be filtered
            boolean shouldFilter = shouldFilterContent(violations, isBlocked, isAllowed, request);
            
            // Apply content sanitization if needed
            String sanitizedContent = applyContentSanitization(request.getContent(), violations);
            
            // Generate filter recommendations
            List<String> recommendations = generateFilterRecommendations(violations, shouldFilter);
            
            // Calculate content score
            double contentScore = calculateContentScore(request.getContent(), violations);
            
            long processingTime = System.currentTimeMillis() - startTime;
            
            return AIContentFilterResponse.builder()
                .requestId(request.getRequestId())
                .subjectId(resolveSubjectId(request))
                .violations(violations)
                .isBlocked(isBlocked)
                .isAllowed(isAllowed)
                .shouldFilter(shouldFilter)
                .sanitizedContent(sanitizedContent)
                .contentScore(contentScore)
                .recommendations(recommendations)
                .processingTimeMs(processingTime)
                .timestamp(LocalDateTime.now())
                .success(true)
                .build();
                
        } catch (Exception e) {
            log.error("Error filtering content", e);
            return AIContentFilterResponse.builder()
                .requestId(request.getRequestId())
                .subjectId(resolveSubjectId(request))
                .shouldFilter(true) // Default to filtering on error
                .success(false)
                .errorMessage(e.getMessage())
                .build();
        }
    }

    private String resolveSubjectId(AIContentFilterRequest request) {
        if (request == null || request.getAuthContext() == null) {
            return null;
        }
        AIAccessSubjectContext authContext = request.getAuthContext();
        if (authContext.getSubjectId() != null && !authContext.getSubjectId().isBlank()) {
            return authContext.getSubjectId();
        }
        if (authContext.getSessionId() != null && !authContext.getSessionId().isBlank()) {
            return authContext.getSessionId();
        }
        return null;
    }

    /**
     * Analyze content for violations using AI
     */
    private List<String> analyzeContentViolations(AIContentFilterRequest request) {
        List<String> violations = new ArrayList<>();
        
        try {
            String content = request.getContent() != null ? request.getContent() : "";
            String prompt = promptRenderer.render(
                promptTemplateResolver.resolve(TEMPLATE_FAMILY, TEMPLATE_ANALYZE_VIOLATIONS).template(),
                Map.of(PLACEHOLDER_CONTENT, content)
            );
            
            String response = aiCoreService.generateText(prompt);
            violations = Arrays.stream(response.split("\n"))
                .map(String::trim)
                .filter(violation -> !violation.isEmpty() && !"NONE".equalsIgnoreCase(violation))
                .collect(Collectors.toList());
                
        } catch (Exception e) {
            log.warn("AI content analysis failed, using rule-based detection", e);
            violations = detectRuleBasedViolations(request.getContent());
        }
        
        return violations;
    }

    private List<String> detectRuleBasedViolations(String content) {
        List<String> violations = new ArrayList<>();
        
        if (content == null) return violations;
        
        String lowerContent = content.toLowerCase();
        
        if (containsHateSpeech(lowerContent)) {
            violations.add("HATE_SPEECH");
        }
        if (containsHarassment(lowerContent)) {
            violations.add("HARASSMENT");
        }
        if (containsViolence(lowerContent)) {
            violations.add("VIOLENCE");
        }
        if (containsExplicitContent(lowerContent)) {
            violations.add("EXPLICIT_CONTENT");
        }
        if (containsSpam(lowerContent)) {
            violations.add("SPAM");
        }
        
        return violations;
    }

    private boolean containsHateSpeech(String content) {
        return content.contains("hate") && content.contains("group");
    }

    private boolean containsHarassment(String content) {
        return content.contains("idiot") || content.contains("stupid");
    }

    private boolean containsViolence(String content) {
        return content.contains("kill") || content.contains("bomb");
    }

    private boolean containsExplicitContent(String content) {
        return content.contains("explicit") || content.contains("nude");
    }

    private boolean containsSpam(String content) {
        return content.contains("buy now") || content.contains("click here");
    }

    private boolean checkBlockedContent(String content) {
        if (content == null) return false;
        return blockedContent.values().stream()
            .flatMap(List::stream)
            .anyMatch(blocked -> content.toLowerCase().contains(blocked.toLowerCase()));
    }

    private boolean checkAllowedContent(String content) {
        if (content == null) return false;
        return allowedContent.values().stream()
            .flatMap(List::stream)
            .anyMatch(allowed -> content.toLowerCase().contains(allowed.toLowerCase()));
    }

    private boolean shouldFilterContent(List<String> violations, boolean isBlocked, boolean isAllowed, AIContentFilterRequest request) {
        if (isAllowed) {
            return false;
        }
        if (isBlocked) {
            return true;
        }
        if (violations == null || violations.isEmpty()) {
            return false;
        }
        return violations.size() > 0;
    }

    private String applyContentSanitization(String content, List<String> violations) {
        if (content == null || violations == null || violations.isEmpty()) {
            return content;
        }
        String sanitized = content;
        if (violations.contains("EXPLICIT_CONTENT")) {
            sanitized = sanitized.replaceAll("(?i)\\b(nude|explicit)\\b", "[REDACTED]");
        }
        if (violations.contains("VIOLENCE")) {
            sanitized = sanitized.replaceAll("(?i)\\b(kill|bomb)\\b", "[REDACTED]");
        }
        return sanitized;
    }

    private List<String> generateFilterRecommendations(List<String> violations, boolean shouldFilter) {
        if (violations == null || violations.isEmpty()) {
            return List.of();
        }
        List<String> recommendations = new ArrayList<>();
        if (shouldFilter) {
            recommendations.add("Content should be filtered or moderated");
        }
        recommendations.addAll(violations.stream()
            .map(v -> "Review policy for: " + v)
            .toList());
        return recommendations;
    }

    private double calculateContentScore(String content, List<String> violations) {
        if (content == null) {
            return 0.0;
        }
        int lengthPenalty = Math.min(50, content.length() / 200);
        int violationPenalty = violations != null ? violations.size() * 15 : 0;
        return Math.max(0.0, 100.0 - lengthPenalty - violationPenalty);
    }
}
