package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.SmartSuggestionsProperties;
import ai.fabric.dto.NextStepRecommendation;
import ai.fabric.dto.RAGRequest;
import ai.fabric.dto.RAGResponse;
import ai.fabric.intent.orchestration.OrchestrationAuthContextResolver;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.pipeline.PipelineStep;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import ai.fabric.spi.RAGProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pipeline step that generates smart suggestions based on next step recommendations.
 * 
 * <p>This step analyzes the next step recommendations from intent handling and,
 * if a high-confidence suggestion is found, performs a RAG query to provide
 * additional context and information.</p>
 * 
 * <p><strong>Order:</strong> 80 (after metadata building)</p>
 * 
 * <p><strong>Configuration:</strong> Controlled by {@link SmartSuggestionsProperties}:</p>
 * <ul>
 *   <li>{@code enabled} - Enable/disable smart suggestions</li>
 *   <li>{@code minConfidence} - Minimum confidence threshold</li>
 *   <li>{@code primaryConfidence} - Threshold for PRIMARY priority</li>
 *   <li>{@code retrievalLimit} - Max documents to retrieve</li>
 *   <li>{@code retrievalThreshold} - Similarity threshold for retrieval</li>
 * </ul>
 * 
 * @see SmartSuggestionsProperties
 * @see RAGProvider
 * @see PipelineStep
 * @since 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmartSuggestionsStep implements PipelineStep {
    
    // =========================================================================
    // Constants
    // =========================================================================
    
    private static final String STEP_NAME = "SmartSuggestions";
    private static final int STEP_ORDER = 80;
    
    // Metadata keys
    private static final String METADATA_KEY_SOURCE = "source";
    private static final String METADATA_KEY_SUGGESTION_INTENT = "suggestionIntent";
    private static final String METADATA_KEY_SUGGESTION_CONFIDENCE = "suggestionConfidence";
    private static final String METADATA_KEY_VECTOR_SPACE = "vectorSpace";
    private static final String METADATA_KEY_SUBJECT_ID = "subjectId";
    private static final String METADATA_KEY_SESSION_ID = "sessionId";
    private static final String METADATA_KEY_AUTH_MODE = "authMode";
    
    // Metadata values
    private static final String METADATA_VALUE_SMART_SUGGESTION = "smart-suggestion";
    private static final String METADATA_VALUE_UNSPECIFIED = "unspecified";
    
    // Suggestion keys
    private static final String SUGGESTION_KEY_INTENT = "intent";
    private static final String SUGGESTION_KEY_TITLE = "title";
    private static final String SUGGESTION_KEY_QUERY = "query";
    private static final String SUGGESTION_KEY_CONFIDENCE = "confidence";
    private static final String SUGGESTION_KEY_PRIORITY = "priority";
    private static final String SUGGESTION_KEY_RATIONALE = "rationale";
    private static final String SUGGESTION_KEY_RESPONSE = "response";
    private static final String SUGGESTION_KEY_DOCUMENTS = "documents";
    private static final String SUGGESTION_KEY_METADATA = "metadata";
    
    // Priority values
    private static final String PRIORITY_PRIMARY = "PRIMARY";
    private static final String PRIORITY_SECONDARY = "SECONDARY";
    
    // Data keys
    private static final String DATA_KEY_SMART_SUGGESTION = "smartSuggestion";
    
    // =========================================================================
    // Dependencies
    // =========================================================================
    
    private final SmartSuggestionsProperties smartSuggestionsProperties;
    private final ObjectProvider<RAGProvider> ragProvider;
    
    // =========================================================================
    // PipelineStep Implementation
    // =========================================================================
    
    /**
     * {@inheritDoc}
     */
    @Override
    public String getStepName() {
        return STEP_NAME;
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public int getOrder() {
        return STEP_ORDER;
    }
    
    /**
     * Generate smart suggestions based on next step recommendations.
     * 
     * <p>This step:</p>
     * <ol>
     *   <li>Checks if smart suggestions are enabled</li>
     *   <li>Finds the highest confidence next step recommendation</li>
     *   <li>If above threshold, performs RAG query for related content</li>
     *   <li>Attaches suggestion data to the result</li>
     * </ol>
     * 
     * @param context the current pipeline context
     * @return updated context with smart suggestion (if applicable)
     */
    @Override
    public PipelineContext process(PipelineContext context) {
        OrchestrationResult result = context.getIntentResult();

        OrchestrationPolicy policy = context.getOrchestrationPolicy();
        if (policy != null
            && policy.capabilities() != null
            && !policy.capabilities().suggestionsEnabled()) {
            log.debug("Smart suggestions skipped for request {} (suggestions disabled by policy)", context.getRequestId());
            return context;
        }

        if (result == null || !smartSuggestionsProperties.isEnabled()) {
            log.debug("Smart suggestions skipped for request {} (result={}, enabled={})", 
                context.getRequestId(), result != null, smartSuggestionsProperties.isEnabled());
            return context;
        }
        
        List<NextStepRecommendation> nextSteps = result.getNextSteps();
        if (CollectionUtils.isEmpty(nextSteps)) {
            log.debug("No next steps for smart suggestions in request {}", context.getRequestId());
            return context;
        }
        
        NextStepRecommendation candidate = nextSteps.stream()
            .filter(Objects::nonNull)
            .filter(step -> step.getConfidence() != null && 
                step.getConfidence() >= smartSuggestionsProperties.getMinConfidence())
            .max(Comparator.comparingDouble(NextStepRecommendation::getConfidence))
            .orElse(null);
        
        if (candidate == null) {
            log.debug("No high-confidence suggestions for request {}", context.getRequestId());
            return context;
        }
        
        String query = StringUtils.hasText(candidate.getQuery()) 
            ? candidate.getQuery() 
            : candidate.getIntent();
        
        if (!StringUtils.hasText(query)) {
            log.debug("Skipping smart suggestion - missing query for intent {} in request {}", 
                candidate.getIntent(), context.getRequestId());
            return context;
        }
        
        try {
            Map<String, Object> suggestionMetadata = buildSuggestionMetadata(candidate, context);
            
            RAGRequest ragRequest = RAGRequest.builder()
                .query(query)
                .entityType(candidate.getVectorSpace())
                .limit(smartSuggestionsProperties.getRetrievalLimit())
                .threshold(smartSuggestionsProperties.getRetrievalThreshold())
                .metadata(Collections.unmodifiableMap(suggestionMetadata))
                .authContext(OrchestrationAuthContextResolver.from(context.getOrchestrationContext()))
                .build();
            
            RAGProvider provider = ragProvider.getIfAvailable();
            if (provider == null) {
                log.debug("Smart suggestions skipped for request {} (no RAGProvider bean present)", context.getRequestId());
                return context;
            }

            RAGResponse ragResponse = provider.performRag(ragRequest);
            if (ragResponse == null) {
                log.debug("Smart suggestion retrieval returned null for intent {} in request {}", 
                    candidate.getIntent(), context.getRequestId());
                return context;
            }
            
            Map<String, Object> suggestion = buildSuggestion(candidate, query, ragResponse);
            
            result.setSmartSuggestion(Collections.unmodifiableMap(suggestion));
            result.withAdditionalData(Map.of(DATA_KEY_SMART_SUGGESTION, suggestion));
            
            log.debug("Generated smart suggestion for intent {} in request {}", 
                candidate.getIntent(), context.getRequestId());
            
            return context.toBuilder()
                .smartSuggestion(suggestion)
                .build();
            
        } catch (Exception ex) {
            log.warn("Failed to generate smart suggestion for intent {} in request {}: {}", 
                candidate.getIntent(), context.getRequestId(), ex.getMessage(), ex);
            return context;
        }
    }
    
    // =========================================================================
    // Private Helper Methods
    // =========================================================================
    
    private Map<String, Object> buildSuggestionMetadata(NextStepRecommendation candidate, 
                                                         PipelineContext context) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(METADATA_KEY_SOURCE, METADATA_VALUE_SMART_SUGGESTION);
        metadata.put(METADATA_KEY_SUGGESTION_INTENT, candidate.getIntent());
        metadata.put(METADATA_KEY_SUGGESTION_CONFIDENCE, candidate.getConfidence());
        metadata.put(METADATA_KEY_VECTOR_SPACE, 
            candidate.getVectorSpace() != null ? candidate.getVectorSpace() : METADATA_VALUE_UNSPECIFIED);
        var authContext = OrchestrationAuthContextResolver.from(context.getOrchestrationContext());
        if (authContext != null && StringUtils.hasText(authContext.getSubjectId())) {
            metadata.put(METADATA_KEY_SUBJECT_ID, authContext.getSubjectId());
        }
        if (authContext != null && StringUtils.hasText(authContext.getSessionId())) {
            metadata.put(METADATA_KEY_SESSION_ID, authContext.getSessionId());
        }
        if (authContext != null && StringUtils.hasText(authContext.getAuthMode())) {
            metadata.put(METADATA_KEY_AUTH_MODE, authContext.getAuthMode());
        }
        
        return metadata;
    }
    
    private Map<String, Object> buildSuggestion(NextStepRecommendation candidate, 
                                                 String query, 
                                                 RAGResponse ragResponse) {
        Map<String, Object> suggestion = new LinkedHashMap<>();
        suggestion.put(SUGGESTION_KEY_INTENT, candidate.getIntent());
        suggestion.put(SUGGESTION_KEY_TITLE, convertToTitle(candidate.getIntent()));
        suggestion.put(SUGGESTION_KEY_QUERY, query);
        suggestion.put(SUGGESTION_KEY_CONFIDENCE, candidate.getConfidence());
        suggestion.put(SUGGESTION_KEY_PRIORITY, 
            candidate.getConfidence() >= smartSuggestionsProperties.getPrimaryConfidence() 
                ? PRIORITY_PRIMARY 
                : PRIORITY_SECONDARY);
        
        if (smartSuggestionsProperties.isIncludeRationale() && 
            StringUtils.hasText(candidate.getRationale())) {
            suggestion.put(SUGGESTION_KEY_RATIONALE, candidate.getRationale());
        }
        
        suggestion.put(SUGGESTION_KEY_RESPONSE, ragResponse.getContext());
        suggestion.put(SUGGESTION_KEY_DOCUMENTS, ragResponse.getDocuments());
        suggestion.put(SUGGESTION_KEY_METADATA, ragResponse.getMetadata());
        
        return suggestion;
    }
    
    private String convertToTitle(String intent) {
        if (!StringUtils.hasText(intent)) {
            return null;
        }
        String normalized = intent.replaceAll("[_\\-]+", " ").trim();
        if (normalized.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder(normalized.length());
        boolean capitalizeNext = true;
        for (char ch : normalized.toCharArray()) {
            if (Character.isWhitespace(ch)) {
                capitalizeNext = true;
                builder.append(ch);
            } else if (capitalizeNext) {
                builder.append(Character.toTitleCase(ch));
                capitalizeNext = false;
            } else {
                builder.append(Character.toLowerCase(ch));
            }
        }
        return builder.toString();
    }
}
