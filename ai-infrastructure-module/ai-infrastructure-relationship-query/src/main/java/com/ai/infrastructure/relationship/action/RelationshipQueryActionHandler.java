package com.ai.infrastructure.relationship.action;

import com.ai.infrastructure.dto.AIAccessSubjectContext;
import com.ai.infrastructure.dto.RAGResponse;
import com.ai.infrastructure.intent.action.ActionContext;
import com.ai.infrastructure.intent.action.ActionAccessMode;
import com.ai.infrastructure.intent.action.ActionResult;
import com.ai.infrastructure.intent.action.ActionResultContracts;
import com.ai.infrastructure.intent.action.annotation.AIAction;
import com.ai.infrastructure.intent.action.annotation.ActionAllowed;
import com.ai.infrastructure.intent.action.annotation.ActionExecute;
import com.ai.infrastructure.intent.action.annotation.ActionFacts;
import com.ai.infrastructure.intent.action.annotation.Param;
import com.ai.infrastructure.relationship.model.QueryMode;
import com.ai.infrastructure.relationship.model.QueryOptions;
import com.ai.infrastructure.relationship.model.ReturnMode;
import com.ai.infrastructure.relationship.service.ReliableRelationshipQueryService;
import com.ai.infrastructure.relationship.spi.RelationshipQueryAccessControlPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * AI action that lets the orchestrator execute relationship queries.
 * 
 * <p><strong>REQUIREMENT:</strong> When orchestrator integration is enabled, users MUST provide
 * an implementation of {@link RelationshipQueryAccessControlPolicy}. The application will fail
 * to start if no policy bean is provided.</p>
 * 
 * <p><strong>Behavior:</strong>
 * <ul>
 *   <li>When {@code enable-orchestrator-integration=true}: Handler is created, policy is REQUIRED</li>
 *   <li>When {@code enable-orchestrator-integration=false}: Handler is NOT created, policy is NOT needed
 *       (standalone mode - users handle access control manually)</li>
 * </ul>
 * </p>
 */
@Slf4j
@AIAction(
    name = "relationship_query",
    description = "Execute natural language relationship queries across entities",
    category = "data_query",
    accessMode = ActionAccessMode.READ,
    requiresConfirmation = false
)
@RequiredArgsConstructor
@ConditionalOnBean({ReliableRelationshipQueryService.class, RelationshipQueryAccessControlPolicy.class})
@ConditionalOnProperty(
    prefix = "ai.infrastructure.relationship",
    name = "enable-orchestrator-integration",
    havingValue = "true",
    matchIfMissing = true
)
// Access control policy is REQUIRED when orchestrator integration is enabled
// Application will fail to start if no policy bean is provided
public class RelationshipQueryActionHandler {

    // Action metadata
    private static final String ACTION_NAME = "relationship_query";
    
    // Parameter names (extracted by LLM from user query)
    private static final String PARAM_QUERY = "query";  // From user input
    private static final String PARAM_ENTITY_TYPES = "entityTypes";  // Extracted by LLM
    
    // Application-level parameters (NOT extracted by LLM - passed by application code)
    private static final String PARAM_LIMIT = "limit";  // Application config
    private static final String PARAM_RETURN_MODE = "returnMode";  // Application config (IDS vs FULL)
    private static final String PARAM_SIMILARITY_THRESHOLD = "similarityThreshold";  // Application config
    
    // Error codes
    private static final String ERROR_ACCESS_DENIED = "ACCESS_DENIED";
    private static final String ERROR_INVALID_PARAMETERS = "INVALID_PARAMETERS";
    private static final String ERROR_EXECUTION_FAILED = "EXECUTION_FAILED";
    
    // Response data keys
    private static final String DATA_KEY_REQUESTED_ENTITY_TYPES = "requestedEntityTypes";
    private static final String DATA_KEY_ALLOWED_ENTITY_TYPES = "allowedEntityTypes";
    private static final String DATA_KEY_DENIED_ENTITY_TYPES = "deniedEntityTypes";
    private static final String DATA_KEY_ERROR_TYPE = "errorType";
    private static final String DATA_KEY_DOCUMENTS = "documents";
    private static final String DATA_KEY_TOTAL_RESULTS = "totalResults";
    private static final String DATA_KEY_RETURNED_RESULTS = "returnedResults";
    private static final String DATA_KEY_HYBRID_SEARCH_USED = "hybridSearchUsed";
    private static final String DATA_KEY_PROCESSING_TIME_MS = "processingTimeMs";
    private static final String DATA_KEY_METADATA = "metadata";
    private static final String DATA_KEY_WARNINGS = "warnings";
    private static final String DATA_KEY_CONFIDENCE_SCORE = "confidenceScore";
    private static final String DATA_KEY_ENTITY_TYPE = "entityType";
    
    // Default values
    private static final int DEFAULT_LIMIT = 20;

    private final ReliableRelationshipQueryService queryService;
    // REQUIRED - enforced via @ConditionalOnBean above
    // Users must provide their own implementation of RelationshipQueryAccessControlPolicy
    private final RelationshipQueryAccessControlPolicy accessControlPolicy;

    @ActionExecute
    public ActionResult execute(@Param(value = PARAM_QUERY, required = true, description = "Natural language query") String query,
                                @Param(value = PARAM_ENTITY_TYPES, description = "List<String> entity types to target (optional; empty means auto-detect)") List<String> entityTypes,
                                @Param(value = PARAM_LIMIT, description = "Maximum number of results (optional; default " + DEFAULT_LIMIT + ")", min = 1, max = 1000) Integer limit,
                                @Param(value = PARAM_RETURN_MODE, description = "IDS or FULL (optional; default IDS)", allowedValues = {"IDS", "FULL"}) ReturnMode returnMode,
                                @Param(value = PARAM_SIMILARITY_THRESHOLD, description = "Vector similarity threshold 0-1 (optional)") Double similarityThreshold,
                                ActionContext actionContext) {
        AIAccessSubjectContext authContext = actionContext != null ? actionContext.authContext() : null;
        String subjectId = subjectId(authContext);
        try {
            List<String> requestedEntityTypes = normalizeEntityTypes(entityTypes);
            List<String> allowedEntityTypes = filterAllowedEntityTypes(authContext, requestedEntityTypes);

            boolean autoDetect = requestedEntityTypes.isEmpty();
            
            // SECURITY CRITICAL: Access control ALWAYS enforced, even for auto-detect
            // If user didn't specify entity types, use ONLY what policy allows
            if (autoDetect) {
                // Auto-detect scenario: user didn't specify entity types
                // Security: Use ONLY entity types the user is allowed to access
                if (allowedEntityTypes.isEmpty()) {
                    // Policy returned no allowed entity types
                    log.warn("Access denied: subject {} has no allowed entity types for auto-detection", subjectId);
                    return ActionResult.builder()
                        .success(false)
                        .message("Access denied: You do not have permission to query any entity types")
                        .errorCode(ERROR_ACCESS_DENIED)
                        .data(ActionResultContracts.object(Map.of("reason", "No entity types accessible for auto-detection")))
                        .build();
                }
                // Continue with allowed entity types only (policy-constrained auto-detection)
                log.debug("Auto-detect: using policy-allowed entity types: {} for subject {}",
                    allowedEntityTypes, subjectId);
            } else {
                // Explicit entity types requested: ALL must be allowed (fail-closed)
                if (allowedEntityTypes.isEmpty()) {
                    // All requested entity types denied
                    return ActionResult.builder()
                        .success(false)
                        .message("Access denied: You do not have permission to query the requested entity types")
                        .errorCode(ERROR_ACCESS_DENIED)
                        .data(ActionResultContracts.object(Map.of(DATA_KEY_REQUESTED_ENTITY_TYPES, requestedEntityTypes)))
                        .build();
                }
                
                if (allowedEntityTypes.size() < requestedEntityTypes.size()) {
                    // Some entity types denied - fail-closed for security
                    List<String> denied = new ArrayList<>(requestedEntityTypes);
                    denied.removeAll(allowedEntityTypes);
                    log.warn("Access denied: subject {} requested unauthorized entity types: {}", subjectId, denied);
                    return ActionResult.builder()
                        .success(false)
                        .message("Access denied: You do not have permission to query some of the requested entity types")
                        .errorCode(ERROR_ACCESS_DENIED)
                        .data(ActionResultContracts.object(Map.of(
                            DATA_KEY_REQUESTED_ENTITY_TYPES, requestedEntityTypes,
                            DATA_KEY_ALLOWED_ENTITY_TYPES, allowedEntityTypes,
                            DATA_KEY_DENIED_ENTITY_TYPES, denied
                        )))
                        .build();
                }
            }

            QueryOptions options = buildQueryOptions(limit, returnMode, similarityThreshold);
            // Always pass allowed entity types (never null, never unrestricted)
            RAGResponse response = queryService.execute(query, allowedEntityTypes, options);

            return ActionResult.builder()
                .success(response.getSuccess() == null || response.getSuccess())
                .message(buildSuccessMessage(response))
                .data(ActionResultContracts.object(buildResultData(response)))
                .build();
        } catch (IllegalArgumentException ex) {
            return ActionResult.builder()
                .success(false)
                .message(ex.getMessage())
                .errorCode(ERROR_INVALID_PARAMETERS)
                .build();
        } catch (Exception ex) {
            return handleError(ex, subjectId);
        }
    }

    public ActionResult handleError(Exception e, String userId) {
        log.error("Relationship query execution failed for user {}", userId, e);

        Map<String, Object> data = new HashMap<>();
        data.put(DATA_KEY_ERROR_TYPE, e.getClass().getSimpleName());

        // Best-effort: preserve the stable response contract by returning metadata (including a minimal plan)
        // when the underlying exception carries structured context.
        if (e instanceof com.ai.infrastructure.relationship.exception.RelationshipQueryException rqe) {
            rqe.getContext().ifPresent(ctx -> {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("executionStage", ctx.getExecutionStage());
                metadata.put("timestamp", ctx.getTimestamp() != null ? ctx.getTimestamp().toString() : null);
                metadata.put("fallbackUsed", ctx.isFallbackUsed());
                metadata.put("errorAttributes", ctx.getAttributes());

                // Minimal plan for callers/tests: primaryEntityType + candidates + strategy.
                com.ai.infrastructure.relationship.dto.RelationshipQueryPlan plan = com.ai.infrastructure.relationship.dto.RelationshipQueryPlan.builder()
                    .originalQuery(ctx.getOriginalQuery())
                    .semanticQuery(ctx.getOriginalQuery())
                    .primaryEntityType(ctx.getPrimaryEntityType())
                    .candidateEntityTypes(ctx.getCandidateEntityTypes())
                    .queryStrategy(com.ai.infrastructure.relationship.dto.QueryStrategy.RELATIONSHIP)
                    .needsSemanticSearch(false)
                    .confidenceScore(0.0)
                    .build();
                metadata.put("plan", plan);
                data.put(DATA_KEY_METADATA, metadata);
            });
        }

        return ActionResult.builder()
            .success(false)
            .message("Relationship query failed: " + e.getMessage())
            .errorCode(ERROR_EXECUTION_FAILED)
            .data(ActionResultContracts.object(data))
            .build();
    }

    @ActionAllowed
    public boolean allowed(ActionContext context) {
        AIAccessSubjectContext authContext = context != null ? context.authContext() : null;
        if (authContext == null || subjectId(authContext) == null) {
            return false;
        }
        // Policy is always present when orchestrator integration is enabled (enforced by @ConditionalOnBean)
        return accessControlPolicy.canExecuteRelationshipQueries(authContext);
    }

    @ActionFacts
    public Map<String, Object> buildFacts(ActionResult actionResult, ActionContext context) {
        if (actionResult == null) {
            return Map.of();
        }

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("success", actionResult.isSuccess());
        if (actionResult.getMessage() != null && !actionResult.getMessage().isBlank()) {
            facts.put("message", actionResult.getMessage());
        }
        if (actionResult.getErrorCode() != null && !actionResult.getErrorCode().isBlank()) {
            facts.put("errorCode", actionResult.getErrorCode());
        }

        Map<String, Object> data = actionResult.getData() != null
            ? actionResult.getData().toMap()
            : Map.of();
        copyIfPresent(data, facts, DATA_KEY_TOTAL_RESULTS);
        copyIfPresent(data, facts, DATA_KEY_RETURNED_RESULTS);
        copyIfPresent(data, facts, DATA_KEY_ENTITY_TYPE);
        copyIfPresent(data, facts, DATA_KEY_CONFIDENCE_SCORE);

        Object documents = data.get(DATA_KEY_DOCUMENTS);
        List<Map<String, Object>> documentFacts = compactDocumentFacts(documents);
        facts.put(DATA_KEY_DOCUMENTS, documentFacts);
        facts.put("documentCount", documentFacts.size());
        return Collections.unmodifiableMap(facts);
    }

    private QueryOptions buildQueryOptions(Integer limit, ReturnMode returnMode, Double similarityThreshold) {
        QueryOptions.QueryOptionsBuilder builder = QueryOptions.builder();

        builder.limit(limit != null ? limit : DEFAULT_LIMIT);
        builder.returnMode(returnMode != null ? returnMode : ReturnMode.IDS);
        if (similarityThreshold != null) {
            builder.similarityThreshold(similarityThreshold);
        }

        return builder.build();
    }

    private List<String> normalizeEntityTypes(List<String> entityTypes) {
        if (entityTypes == null || entityTypes.isEmpty()) {
            log.warn("No entityTypes supplied for relationship_query; falling back to auto-detection");
            return List.of();
        }

        List<String> normalized = new ArrayList<>();
        for (String entry : entityTypes) {
            if (entry == null) {
                continue;
            }
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed.toLowerCase(Locale.ROOT));
            }
        }
        return normalized;
    }

    /**
     * Filter entity types based on user permissions using RelationshipQueryAccessControlPolicy SPI.
     * 
     * Policy is REQUIRED when orchestrator integration is enabled (enforced by @ConditionalOnBean).
     * 
     * @param authContext Canonical request auth context
     * @param requestedEntityTypes Entity types requested in the query
     * @return Filtered list of entity types the user is allowed to query
     */
    private List<String> filterAllowedEntityTypes(AIAccessSubjectContext authContext, List<String> requestedEntityTypes) {
        String subjectId = subjectId(authContext);
        if (requestedEntityTypes == null || requestedEntityTypes.isEmpty()) {
            // If no entity types specified, get allowed entity types from policy
            List<String> allowed = accessControlPolicy.getAllowedEntityTypes(authContext);
            if (log.isDebugEnabled()) {
                log.debug("No entity types specified - using policy allowed types: {} for subject {}", allowed, subjectId);
            }
            return allowed;
        }
        
        // Filter requested entity types based on user permissions
        List<String> allowed = new ArrayList<>();
        for (String entityType : requestedEntityTypes) {
            if (accessControlPolicy.canQueryEntityType(authContext, entityType)) {
                allowed.add(entityType);
            } else {
                log.debug("Access denied: subject {} cannot query entity type {}", subjectId, entityType);
            }
        }
        
        return allowed;
    }

    private String subjectId(AIAccessSubjectContext authContext) {
        if (authContext == null) {
            return null;
        }
        if (authContext.getSubjectId() != null && !authContext.getSubjectId().isBlank()) {
            return authContext.getSubjectId().trim();
        }
        if (authContext.getSessionId() != null && !authContext.getSessionId().isBlank()) {
            return authContext.getSessionId().trim();
        }
        return null;
    }

    private int parseInteger(Object raw, int defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(raw.toString());
        } catch (NumberFormatException ex) {
            log.warn("Invalid integer value '{}', using default {}", raw, defaultValue);
            return defaultValue;
        }
    }

    private Double parseDouble(Object raw, Double defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(raw.toString());
        } catch (NumberFormatException ex) {
            log.warn("Invalid double value '{}', using default {}", raw, defaultValue);
            return defaultValue;
        }
    }

    private ReturnMode parseReturnMode(Object raw) {
        if (raw == null) {
            return ReturnMode.IDS;
        }
        return ReturnMode.fromValue(raw.toString());
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source != null && source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    private List<Map<String, Object>> compactDocumentFacts(Object documents) {
        if (!(documents instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> facts = new ArrayList<>();
        for (Object raw : list) {
            if (facts.size() >= 5) {
                break;
            }
            Map<String, Object> fact = compactDocumentFact(raw);
            if (!fact.isEmpty()) {
                facts.add(Collections.unmodifiableMap(fact));
            }
        }
        return facts.isEmpty() ? List.of() : List.copyOf(facts);
    }

    private Map<String, Object> compactDocumentFact(Object raw) {
        if (raw instanceof RAGResponse.RAGDocument document) {
            Map<String, Object> fact = new LinkedHashMap<>();
            putIfPresent(fact, "title", document.getTitle());
            putIfPresent(fact, "entityType", firstNonBlank(document.getType(), metadataString(document.getMetadata(), "entityType"), metadataString(document.getMetadata(), "documentType"), metadataString(document.getMetadata(), "vectorSpace")));
            putSelectedMetadata(fact, document.getMetadata());
            if (!hasCommerceFacts(fact)) {
                putIfPresent(fact, "content", truncate(document.getContent(), 300));
            }
            return fact;
        }
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> fact = new LinkedHashMap<>();
            putIfPresent(fact, "title", mapValue(map, "title"));
            putIfPresent(fact, "entityType", firstNonBlank(asString(mapValue(map, "entityType")), asString(mapValue(map, "type"))));
            Object metadata = mapValue(map, "metadata");
            if (metadata instanceof Map<?, ?> metadataMap) {
                putSelectedMetadata(fact, metadataMap);
            }
            if (!hasCommerceFacts(fact)) {
                putIfPresent(fact, "content", truncate(asString(mapValue(map, "content")), 300));
            }
            return fact;
        }
        return Map.of();
    }

    private boolean hasCommerceFacts(Map<String, Object> fact) {
        if (fact == null || fact.isEmpty()) {
            return false;
        }
        return fact.containsKey("price")
            || fact.containsKey("available")
            || fact.containsKey("inventoryQuantity")
            || fact.containsKey("reviewSignalsPresent")
            || fact.containsKey("reviewAverage")
            || fact.containsKey("reviewCount");
    }

    private void putSelectedMetadata(Map<String, Object> fact, Map<?, ?> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        putIfPresent(fact, "vendor", mapValue(metadata, "vendor"));
        putIfPresent(fact, "productType", mapValue(metadata, "productType"));
        putIfPresent(fact, "available", mapValue(metadata, "available"));
        putIfPresent(fact, "price", mapValue(metadata, "price"));
        putIfPresent(fact, "inventoryQuantity", mapValue(metadata, "inventoryQuantity"));
        putIfPresent(fact, "primarySku", mapValue(metadata, "primarySku"));
        putIfPresent(fact, "currency", mapValue(metadata, "currency"));
    }

    private Object mapValue(Map<?, ?> map, String key) {
        if (map == null || key == null) {
            return null;
        }
        Object direct = map.get(key);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry != null && entry.getKey() != null && key.equalsIgnoreCase(entry.getKey().toString())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String metadataString(Map<?, ?> metadata, String key) {
        return asString(mapValue(metadata, key));
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (!trimmed.isEmpty()) {
                target.put(key, trimmed);
            }
            return;
        }
        target.put(key, value);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        return Objects.toString(value, null);
    }

    private String truncate(String value, int maxChars) {
        if (value == null || maxChars <= 0 || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars - 3)) + "...";
    }


    private String buildSuccessMessage(RAGResponse response) {
        Integer total = response.getTotalResults();
        Long duration = response.getProcessingTimeMs();
        int totalSafe = total != null ? total : 0;
        String timePart = duration != null ? " in " + duration + "ms" : "";
        if (totalSafe == 0) {
            return "No results found" + timePart;
        }
        return "Found " + totalSafe + " result" + (totalSafe == 1 ? "" : "s") + timePart;
    }

    private Map<String, Object> buildResultData(RAGResponse response) {
        Map<String, Object> data = new HashMap<>();
        data.put(DATA_KEY_DOCUMENTS, response.getDocuments());
        data.put(DATA_KEY_TOTAL_RESULTS, response.getTotalResults());
        data.put(DATA_KEY_RETURNED_RESULTS, response.getReturnedResults());
        data.put(DATA_KEY_HYBRID_SEARCH_USED, response.getHybridSearchUsed());
        data.put(DATA_KEY_PROCESSING_TIME_MS, response.getProcessingTimeMs());
        if (response.getMetadata() != null && !response.getMetadata().isEmpty()) {
            data.put(DATA_KEY_METADATA, response.getMetadata());
        }
        if (response.getWarnings() != null && !response.getWarnings().isEmpty()) {
            data.put(DATA_KEY_WARNINGS, response.getWarnings());
        }
        if (response.getConfidenceScore() != null) {
            data.put(DATA_KEY_CONFIDENCE_SCORE, response.getConfidenceScore());
        }
        if (response.getEntityType() != null) {
            data.put(DATA_KEY_ENTITY_TYPE, response.getEntityType());
        }
        return data;
    }
}
