package ai.fabric.behavior.service;

import ai.fabric.annotation.AIProcess;
import ai.fabric.behavior.entity.BehaviorInsights;
import ai.fabric.behavior.model.BehaviorTrend;
import ai.fabric.behavior.model.ExternalEvent;
import ai.fabric.behavior.model.SentimentLabel;
import ai.fabric.behavior.model.UserEventBatch;
import ai.fabric.behavior.spi.ExternalEventProvider;
import ai.fabric.core.AICoreService;
import ai.fabric.dto.AIAccessSubjectContexts;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.llm.structured.StructuredJsonCallExecutor;
import ai.fabric.llm.structured.StructuredJsonCallSpec;
import ai.fabric.llm.structured.StructuredJsonProviderHints;
import ai.fabric.llm.structured.StructuredJsonResult;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnBean(ExternalEventProvider.class)
public class BehaviorAnalysisService {

    private static final String TEMPLATE_FAMILY = "behavior/analysis";
    private static final String TEMPLATE_SYSTEM = "system";
    private static final String TEMPLATE_USER = "user";

    private static final String TEMPLATE_FAMILY_JSON_REPAIR = "structured-json/repair";
    private static final String TEMPLATE_JSON_REPAIR_SYSTEM_ADDON = "system-addon";
    private static final String TEMPLATE_JSON_REPAIR_USER = "user";

    private static final String PLACEHOLDER_USER_CONTEXT_SECTION = "user_context_section";
    private static final String PLACEHOLDER_PREVIOUS_ANALYSIS_SECTION = "previous_analysis_section";
    private static final String PLACEHOLDER_EVENTS_COUNT = "events_count";
    private static final String PLACEHOLDER_NEW_EVENTS_LINES = "new_events_lines";
    private static final String PLACEHOLDER_USER_REQUEST = "user_request";
    private static final String PLACEHOLDER_MALFORMED_RESPONSE = "malformed_response";
    
    private final ExternalEventProvider eventProvider;
    private final BehaviorStorageAdapter storageAdapter;
    private final AICoreService aiCoreService;
    private final ObjectMapper objectMapper;
    private final StructuredJsonCallExecutor structuredJsonCallExecutor;
    private final PromptTemplateResolver promptTemplateResolver;
    private final PromptRenderer promptRenderer;
    
    /**
     * CASE 1: Analyze a specific user (Targeted)
     */
    @Transactional
    public BehaviorInsights analyzeUser(String userId) {
        log.info("Starting targeted analysis for user: {}", userId);
        
        Optional<BehaviorInsights> existingInsight = storageAdapter.findByUserId(userId);
        BehaviorInsights oldInsight = existingInsight.orElse(null);
        LocalDateTime eventsSince = oldInsight != null ? oldInsight.getAnalyzedAt() : null;
        List<ExternalEvent> newEvents = eventProvider.getEventsForUser(userId, eventsSince, null);
        
        if (newEvents == null || newEvents.isEmpty()) {
            log.warn("No events found for user: {}", userId);
            return existingInsight.orElse(null);
        }
        
        BehaviorInsights updatedInsight = performEvolutionaryAnalysis(
            userId,
            oldInsight,
            newEvents,
            null
        );
        
        return saveAndIndex(updatedInsight);
    }
    
    /**
     * CASE 2: Process the next user in queue (Discovery)
     */
    @Transactional
    public BehaviorInsights processNextUser() {
        return processNextUserOptional().orElse(null);
    }

    @Transactional
    public Optional<BehaviorInsights> processNextUserOptional() {
        log.debug("Fetching next user for batch processing");
        
        UserEventBatch batch = eventProvider.getNextUserEvents();
        if (batch == null || batch.getUserId() == null) {
            log.debug("No users pending analysis");
            return Optional.empty();
        }
        
        List<ExternalEvent> events = batch.getEvents() != null
            ? batch.getEvents()
            : Collections.emptyList();
        
        if (events.isEmpty()) {
            log.warn("No events returned for discovery user: {}", batch.getUserId());
            return storageAdapter.findByUserId(batch.getUserId());
        }
        
        log.info("Processing batch for user: {} with {} events",
            batch.getUserId(), batch.getTotalEventCount());
        
        Optional<BehaviorInsights> existingInsight = storageAdapter.findByUserId(batch.getUserId());
        
        BehaviorInsights updatedInsight = performEvolutionaryAnalysis(
            batch.getUserId(),
            existingInsight.orElse(null),
            events,
            batch.getUserContext()
        );
        
        return Optional.ofNullable(saveAndIndex(updatedInsight));
    }
    
    private BehaviorInsights performEvolutionaryAnalysis(
        String userId,
        BehaviorInsights oldInsight,
        List<ExternalEvent> newEvents,
        Map<String, Object> userContext
    ) {
        long startTime = System.currentTimeMillis();
        try {
            String prompt = buildEvolutionaryPrompt(oldInsight, newEvents, userContext);

            AIGenerationRequest request = AIGenerationRequest.builder()
                .entityId(userId)
                .entityType("behavior-insight")
                .generationType("behavioral-analysis")
                .prompt(prompt)
                .systemPrompt(getSystemPrompt())
                .parameters(StructuredJsonProviderHints.jsonObjectResponseParameters())
                .temperature(0.2)
                .maxTokens(1200)
                .authContext(AIAccessSubjectContexts.system("behavior-analysis-service"))
                .build();

            AtomicReference<AIGenerationResponse> lastResponseRef = new AtomicReference<>();
            StructuredJsonResult<Map> structuredResult = structuredJsonCallExecutor.execute(
                StructuredJsonCallSpec.<Map>builder()
                    .callName("behavioral-analysis")
                    .maxAttempts(2)
                    .targetType(Map.class)
                    .objectMapper(objectMapper)
                    .caller(context -> {
                        AIGenerationResponse response;
                        if (context.attemptIndex() == 0) {
                            response = aiCoreService.generateContent(request);
                        } else {
                            response = aiCoreService.generateContent(buildRepairRequest(request, prompt, context.previousRawContent()));
                        }
                        lastResponseRef.set(response);
                        return response;
                    })
                    .build()
            );

            if (!structuredResult.isSuccess() || structuredResult.getValue() == null) {
                String failureMessage = structuredResult.getLastFailure() != null
                    ? structuredResult.getLastFailure().message()
                    : "Unknown structured JSON failure";
                throw new IllegalStateException("Behavior analysis did not return a valid JSON payload: " + failureMessage);
            }

            BehaviorInsights result = parseLLMResponse(userId, structuredResult.getValue(), oldInsight);

            // carry forward previous values for deltas
            if (oldInsight != null) {
                result.setPreviousSentimentScore(oldInsight.getSentimentScore());
                result.setPreviousChurnRisk(oldInsight.getChurnRisk());
                if (result.getTrend() == null) {
                    result.setTrend(BehaviorTrend.fromDeltas(result.getSentimentDelta(), result.getChurnDelta(), false));
                }
                result.setCreatedAt(oldInsight.getCreatedAt());
                result.setId(oldInsight.getId());
            } else if (result.getTrend() == null) {
                result.setTrend(BehaviorTrend.NEW_USER);
            }

            result.setProcessingTimeMs(System.currentTimeMillis() - startTime);
            AIGenerationResponse lastResponse = lastResponseRef.get();
            result.setAiModelUsed(lastResponse != null && lastResponse.getModel() != null ? lastResponse.getModel() : "gpt-4o");
            result.setModelPromptVersion("3.1.0");

            logTrendAlert(userId, oldInsight, result);

            return result;
        } catch (Exception e) {
            log.error("Failed to perform evolutionary analysis for user: {}", userId, e);
            throw new IllegalStateException("Behavior analysis failed for user " + userId, e);
        }
    }

    private String buildEvolutionaryPrompt(
        BehaviorInsights oldInsight,
        List<ExternalEvent> newEvents,
        Map<String, Object> userContext
    ) {
        String userContextSection = buildUserContextSection(userContext);
        String previousAnalysisSection = buildPreviousAnalysisSection(oldInsight);
        String newEventsLines = buildNewEventsLines(newEvents);

        return promptRenderer.render(
            promptTemplateResolver.resolve(TEMPLATE_FAMILY, TEMPLATE_USER).template(),
            Map.of(
                PLACEHOLDER_USER_CONTEXT_SECTION, userContextSection,
                PLACEHOLDER_PREVIOUS_ANALYSIS_SECTION, previousAnalysisSection,
                PLACEHOLDER_EVENTS_COUNT, String.valueOf(newEvents != null ? newEvents.size() : 0),
                PLACEHOLDER_NEW_EVENTS_LINES, newEventsLines
            )
        );
    }

    private String getSystemPrompt() {
        return promptRenderer.render(
            promptTemplateResolver.resolve(TEMPLATE_FAMILY, TEMPLATE_SYSTEM).template(),
            Map.of()
        );
    }

    private String buildUserContextSection(Map<String, Object> userContext) {
        if (userContext == null || userContext.isEmpty()) {
            return "";
        }
        StringBuilder prompt = new StringBuilder();
        prompt.append("=== USER CONTEXT ===\n");
        userContext.forEach((key, value) -> prompt.append("- ").append(key).append(": ").append(value).append("\n"));
        return prompt.append("\n").toString().trim();
    }

    private String buildPreviousAnalysisSection(BehaviorInsights oldInsight) {
        if (oldInsight == null) {
            return "This is a NEW user with no previous analysis.\n";
        }
        StringBuilder prompt = new StringBuilder();
        prompt.append("=== PREVIOUS ANALYSIS ===\n");
        prompt.append("- Segment: ").append(oldInsight.getSegment()).append("\n");
        prompt.append("- Sentiment: ").append(oldInsight.getSentimentScore())
            .append(" / ").append(oldInsight.getSentimentLabel()).append("\n");
        prompt.append("- Churn Risk: ").append(oldInsight.getChurnRisk()).append("\n");
        prompt.append("- Trend: ").append(oldInsight.getTrend()).append("\n\n");
        return prompt.toString().trim();
    }

    private String buildNewEventsLines(List<ExternalEvent> newEvents) {
        if (newEvents == null || newEvents.isEmpty()) {
            return "";
        }
        StringBuilder prompt = new StringBuilder();
        for (ExternalEvent event : newEvents) {
            if (event == null) {
                continue;
            }
            prompt.append("- ")
                .append(event.getEventType())
                .append(" at ").append(event.getTimestamp())
                .append(" | Data: ").append(event.getEventData())
                .append("\n");
        }
        return prompt.toString().trim();
    }

    private BehaviorInsights parseLLMResponse(
        String userId,
        Map<String, Object> parsed,
        BehaviorInsights oldInsight
    ) throws Exception {
        BehaviorInsights.BehaviorInsightsBuilder builder = BehaviorInsights.builder()
            .userId(userId)
            .segment(asString(parsed.get("segment")).orElse(null))
            .patterns(asStringList(parsed.get("patterns")).orElse(null))
            .recommendations(asStringList(parsed.get("recommendations")).orElse(null))
            .insights(asObjectMap(parsed.get("insights")).orElse(null))
            .confidence(asDouble(parsed.get("confidence"), 0.5))
            .analyzedAt(LocalDateTime.now());

        if (oldInsight != null) {
            builder.previousSentimentScore(oldInsight.getSentimentScore());
            builder.previousChurnRisk(oldInsight.getChurnRisk());
        }

        Optional<Map<String, Object>> sentiment = asObjectMap(parsed.get("sentiment"));
        if (sentiment.isPresent()) {
            Map<String, Object> sentimentMap = sentiment.get();
            Double score = clamp(asDouble(sentimentMap.get("score"), 0.0), -1.0, 1.0);
            String labelStr = asString(sentimentMap.get("label")).orElse(null);
            SentimentLabel label = SentimentLabel.fromString(labelStr);
            if (labelStr != null && label == SentimentLabel.NEUTRAL && !labelStr.equalsIgnoreCase("NEUTRAL")) {
                log.warn("Invalid sentiment label '{}' for user {}, defaulted to NEUTRAL", labelStr, userId);
            }
            builder.sentimentScore(score).sentimentLabel(label);
        }

        Optional<Map<String, Object>> churn = asObjectMap(parsed.get("churn"));
        if (churn.isPresent()) {
            Map<String, Object> churnMap = churn.get();
            Double risk = clamp(asDouble(churnMap.get("risk"), 0.0), 0.0, 1.0);
            String reason = asString(churnMap.get("reason")).orElse(null);
            if (risk > 0.5 && (reason == null || reason.isBlank())) {
                log.warn("High churn risk without reason for user {}", userId);
                reason = "Behavioral drift detected";
            }
            builder.churnRisk(risk).churnReason(reason);
        }

        String trendStr = asString(parsed.get("trend")).orElse(null);
        BehaviorTrend trend = BehaviorTrend.fromString(trendStr);
        if (trendStr != null && trend == BehaviorTrend.STABLE && !trendStr.equalsIgnoreCase("STABLE")) {
            log.warn("Invalid trend '{}' for user {}, computing from deltas", trendStr, userId);
        }

        BehaviorInsights temp = builder.build();
        if (trend == null || (oldInsight != null && trend == BehaviorTrend.STABLE)) {
            trend = BehaviorTrend.fromDeltas(temp.getSentimentDelta(), temp.getChurnDelta(), oldInsight == null);
        }
        builder.trend(trend);

        if (oldInsight != null) {
            builder.id(oldInsight.getId())
                .createdAt(oldInsight.getCreatedAt());
        }

        return builder.build();
    }

    private Optional<String> asString(Object raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    private Optional<List<String>> asStringList(Object raw) {
        if (raw == null) {
            return Optional.empty();
        }
        if (raw instanceof List<?> list) {
            return Optional.of(list.stream()
                .map(this::asString)
                .flatMap(Optional::stream)
                .toList());
        }
        return asString(raw).map(List::of);
    }

    private Optional<Map<String, Object>> asObjectMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Optional.empty();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (key != null) {
                normalized.put(key.toString(), value);
            }
        });
        return Optional.of(normalized);
    }

    private double asDouble(Object raw, double defaultValue) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private AIGenerationRequest buildRepairRequest(AIGenerationRequest originalRequest, String originalPrompt, String malformedContent) {
        String originalSystemPrompt = originalRequest != null ? originalRequest.getSystemPrompt() : null;
        String repairSystemPrompt = (org.springframework.util.StringUtils.hasText(originalSystemPrompt) ? originalSystemPrompt.trim() + "\n\n" : "")
            + promptRenderer.render(
                promptTemplateResolver.resolve(TEMPLATE_FAMILY_JSON_REPAIR, TEMPLATE_JSON_REPAIR_SYSTEM_ADDON).template(),
                Map.of()
            );

        String safeOriginalPrompt = originalPrompt != null ? originalPrompt : "";
        String safeMalformedContent = malformedContent != null ? malformedContent : "";
        String repairPrompt = promptRenderer.render(
            promptTemplateResolver.resolve(TEMPLATE_FAMILY_JSON_REPAIR, TEMPLATE_JSON_REPAIR_USER).template(),
            Map.of(
                PLACEHOLDER_USER_REQUEST, safeOriginalPrompt,
                PLACEHOLDER_MALFORMED_RESPONSE, safeMalformedContent
            )
        );

        String repairEntityId = originalRequest != null && originalRequest.getEntityId() != null
            ? originalRequest.getEntityId() + "-repair"
            : UUID.randomUUID().toString();

        return AIGenerationRequest.builder()
            .entityId(repairEntityId)
            .entityType(originalRequest != null ? originalRequest.getEntityType() : "behavior-insight")
            .generationType(originalRequest != null ? originalRequest.getGenerationType() : "behavioral-analysis")
            .prompt(repairPrompt)
            .systemPrompt(repairSystemPrompt)
            .parameters(StructuredJsonProviderHints.jsonObjectResponseParameters())
            .temperature(originalRequest != null ? originalRequest.getTemperature() : null)
            .maxTokens(originalRequest != null ? originalRequest.getMaxTokens() : null)
            .authContext(originalRequest != null ? originalRequest.getAuthContext() : AIAccessSubjectContexts.system("behavior-analysis-service"))
            .model(originalRequest != null ? originalRequest.getModel() : null)
            .build();
    }

    private void logTrendAlert(String userId, BehaviorInsights old, BehaviorInsights current) {
        if (old == null) {
            log.info("New user analyzed: {} | Sentiment: {} | Churn: {} | Trend: {}",
                userId, current.getSentimentLabel(), current.getChurnRisk(), current.getTrend());
            return;
        }

        if (current.getTrend() != null && current.getTrend().isNegative()) {
            log.warn("User {} trend worsening: {} -> {}", userId,
                old.getTrend(), current.getTrend());
        } else {
            log.info("User {} trend: {} -> {}", userId,
                old.getTrend(), current.getTrend());
        }
    }

    @AIProcess(
        entityType = "behavior-insight",
        processType = "create"
    )
    private BehaviorInsights saveAndIndex(BehaviorInsights insight) {
        return storageAdapter.save(insight);
    }
}
