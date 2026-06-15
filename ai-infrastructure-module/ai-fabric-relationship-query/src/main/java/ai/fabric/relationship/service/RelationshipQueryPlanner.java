package ai.fabric.relationship.service;

import ai.fabric.core.AICoreService;
import ai.fabric.dto.AIAccessSubjectContexts;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.relationship.cache.QueryCache;
import ai.fabric.relationship.config.RelationshipQueryProperties;
import ai.fabric.relationship.dto.RelationshipQueryPlan;
import ai.fabric.relationship.dto.QueryStrategy;
import ai.fabric.relationship.validation.RelationshipQueryValidationException;
import ai.fabric.relationship.validation.RelationshipQueryValidator;
import ai.fabric.relationship.exception.QueryPlanningException;
import ai.fabric.relationship.exception.RelationshipQueryErrorContext;
import ai.fabric.relationship.metrics.QueryMetrics;
import ai.fabric.llm.structured.StructuredJsonExtraction;
import ai.fabric.llm.structured.StructuredJsonExtractor;
import ai.fabric.llm.structured.StructuredJsonProviderHints;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Uses the LLM via {@link AICoreService} to transform natural language queries into structured plans.
 */
@Slf4j
public class RelationshipQueryPlanner {

    private final AICoreService aiCoreService;
    private final RelationshipSchemaProvider schemaProvider;
    private final RelationshipQueryProperties properties;
    private final RelationshipQueryValidator validator;
    private final QueryCache queryCache;
    private final QueryMetrics queryMetrics;
    private final ObjectMapper objectMapper;
    private final StructuredJsonExtractor structuredJsonExtractor;

    private static final String TEMPLATE_FAMILY = "relationship-query/planner";
    private static final String TEMPLATE_SYSTEM = "system";
    private static final String TEMPLATE_SYSTEM_REPAIR = "system-repair";
    private static final String TEMPLATE_PLAN = "plan";
    private static final String TEMPLATE_PLAN_COMPACT = "plan-compact";
    private static final String TEMPLATE_EXAMPLES = "examples";
    private static final String TEMPLATE_REPAIR = "repair";

    private static final String PLACEHOLDER_SCHEMA = "schema";
    private static final String PLACEHOLDER_USER_QUERY = "user_query";
    private static final String PLACEHOLDER_EXAMPLES = "examples";
    private static final String PLACEHOLDER_FEEDBACK_SECTION = "feedback_section";
    private static final String PLACEHOLDER_ALLOWED_ENTITY_TYPES_LINE = "allowed_entity_types_line";
    private static final String PLACEHOLDER_MALFORMED_RESPONSE = "malformed_response";
    private static final String PLACEHOLDER_SAFE_PRIMARY_ENTITY = "safe_primary_entity";

    private final PromptTemplateResolver promptTemplateResolver;
    private final PromptRenderer promptRenderer;

    public RelationshipQueryPlanner(AICoreService aiCoreService,
                                    RelationshipSchemaProvider schemaProvider,
                                    RelationshipQueryProperties properties,
                                    RelationshipQueryValidator validator,
                                    QueryCache queryCache,
                                    QueryMetrics queryMetrics,
                                    ObjectMapper objectMapper,
                                    StructuredJsonExtractor structuredJsonExtractor,
                                    PromptTemplateResolver promptTemplateResolver,
                                    PromptRenderer promptRenderer) {
        this.aiCoreService = aiCoreService;
        this.schemaProvider = schemaProvider;
        this.properties = properties;
        this.validator = validator;
        this.queryCache = queryCache;
        this.queryMetrics = queryMetrics;
        this.objectMapper = objectMapper;
        this.structuredJsonExtractor = Objects.requireNonNull(structuredJsonExtractor, "structuredJsonExtractor");
        this.promptTemplateResolver = Objects.requireNonNull(promptTemplateResolver, "promptTemplateResolver");
        this.promptRenderer = Objects.requireNonNull(promptRenderer, "promptRenderer");
    }

    public RelationshipQueryPlan planQuery(String query, List<String> entityTypes) {
        long start = System.nanoTime();
        String cacheKey = buildCacheKey(query, entityTypes);
        if (queryCache.isEnabled()) {
            Optional<RelationshipQueryPlan> cachedPlan = queryCache.getPlan(cacheKey);
            if (cachedPlan.isPresent()) {
                recordPlanMetrics(start, true, true);
                return cachedPlan.get();
            }
        }

        RelationshipQueryPlan fallback = createFallbackPlan(query, entityTypes);
        int maxRetries = Math.max(0, properties.getPlanner().getMaxRetries());
        List<String> feedback = new ArrayList<>();
        Exception lastFailure = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                RelationshipQueryPlan plan = requestPlan(query, entityTypes, fallback, feedback);
                cachePlan(cacheKey, plan);
                recordPlanMetrics(start, false, true);
                return plan;
            } catch (RelationshipQueryValidationException ex) {
                lastFailure = ex;
                if (attempt < maxRetries) {
                    feedback = List.of(safeMessage(ex));
                    continue;
                }
        recordPlanMetrics(start, false, false);
        if (properties.getPlanner().isFailOnParseError()) {
            throw new QueryPlanningException(
                "Planner failed to produce a structured plan",
                        buildPlannerErrorContext(query, entityTypes, fallback, ex),
                        ex
                    );
                }
                return fallback;
            } catch (Exception ex) {
                lastFailure = ex;
                if (attempt < maxRetries) {
                    feedback = List.of(safeMessage(ex));
                    continue;
                }
                break;
            }
        }

        recordPlanMetrics(start, false, false);
        log.warn("Failed to obtain structured plan from LLM: {}", lastFailure != null ? lastFailure.getMessage() : "unknown error");
        if (properties.getPlanner().isFailOnParseError()) {
            throw new QueryPlanningException(
                "Planner failed to produce a structured plan",
                buildPlannerErrorContext(query, entityTypes, fallback, lastFailure),
                lastFailure
            );
        }
        return fallback;
    }

    private String buildCacheKey(String query, List<String> entityTypes) {
        String normalizedQuery = query != null ? query.trim() : "";
        String normalizedEntityTypes = "";
        if (!CollectionUtils.isEmpty(entityTypes)) {
            normalizedEntityTypes = entityTypes.stream()
                .filter(StringUtils::hasText)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .sorted()
                .collect(Collectors.joining(","));
        }
        return QueryCache.hash(normalizedQuery + "|" + normalizedEntityTypes);
    }

    private RelationshipQueryPlan requestPlan(String query,
                                              List<String> entityTypes,
                                              RelationshipQueryPlan fallback,
                                              List<String> feedback) throws Exception {
        String prompt = buildPrompt(query, entityTypes, feedback);
        AIGenerationResponse response = aiCoreService.generateContent(buildRequest(query, prompt));
        RelationshipQueryPlan plan = parsePlan(response.getContent(), query, entityTypes);
        applyDefaults(plan, fallback);
        sanitizeHallucinatedFilters(plan, query);
        normalizeCrossEntityValueReferences(plan);
        validateCrossEntityReferences(plan);
        validateFilterFields(plan);
        validator.validate(plan);
        return plan;
    }

    /**
     * Provider-agnostic normalization for cross-entity comparisons.
     *
     * <p>The planner prompt asks models to express cross-entity comparisons using
     * "<entity-slug>.<field>" (e.g., "destination-account.ownerName"). Some providers instead emit
     * the relationship field name (e.g., "destinationAccount.ownerName"). We normalize those cases
     * to the entity slug when the mapping is available from relationshipPaths.</p>
     *
     * <p>This improves stability without introducing provider-specific behavior.</p>
     */
    private void normalizeCrossEntityValueReferences(RelationshipQueryPlan plan) {
        if (plan == null || CollectionUtils.isEmpty(plan.getRelationshipPaths())) {
            return;
        }

        Map<String, String> relationshipFieldToEntityType = new java.util.LinkedHashMap<>();
        plan.getRelationshipPaths().forEach(path -> {
            if (path == null) {
                return;
            }
            if (StringUtils.hasText(path.getRelationshipType()) && StringUtils.hasText(path.getToEntityType())) {
                relationshipFieldToEntityType.put(path.getRelationshipType().trim(), path.getToEntityType().trim());
            }
        });
        if (relationshipFieldToEntityType.isEmpty()) {
            return;
        }

        if (plan.getDirectFilters() != null && !plan.getDirectFilters().isEmpty()) {
            plan.getDirectFilters().values().forEach(filters ->
                normalizeCrossEntityValueReferences(filters, relationshipFieldToEntityType));
        }
        if (plan.getRelationshipFilters() != null && !plan.getRelationshipFilters().isEmpty()) {
            plan.getRelationshipFilters().values().forEach(filters ->
                normalizeCrossEntityValueReferences(filters, relationshipFieldToEntityType));
        }
        if (!CollectionUtils.isEmpty(plan.getRelationshipPaths())) {
            plan.getRelationshipPaths().forEach(path -> {
                if (path != null) {
                    normalizeCrossEntityValueReferences(path.getConditions(), relationshipFieldToEntityType);
                }
            });
        }
    }

    private void normalizeCrossEntityValueReferences(List<ai.fabric.relationship.dto.FilterCondition> filters,
                                                     Map<String, String> relationshipFieldToEntityType) {
        if (CollectionUtils.isEmpty(filters) || relationshipFieldToEntityType == null || relationshipFieldToEntityType.isEmpty()) {
            return;
        }
        for (var filter : filters) {
            if (filter == null) {
                continue;
            }
            filter.setValue(normalizeReferenceValue(filter.getValue(), relationshipFieldToEntityType));
            filter.setSecondaryValue(normalizeReferenceValue(filter.getSecondaryValue(), relationshipFieldToEntityType));
        }
    }

    private Object normalizeReferenceValue(Object raw, Map<String, String> relationshipFieldToEntityType) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof List<?> list) {
            List<Object> normalized = new ArrayList<>(list.size());
            for (Object item : list) {
                normalized.add(normalizeReferenceValue(item, relationshipFieldToEntityType));
            }
            return normalized;
        }
        if (!(raw instanceof String text)) {
            return raw;
        }
        String trimmed = text.trim();
        if (!trimmed.contains(".")) {
            return raw;
        }
        String[] parts = trimmed.split("\\.", 2);
        if (parts.length != 2) {
            return raw;
        }

        String head = parts[0].trim();
        String tail = parts[1].trim();
        if (!StringUtils.hasText(head) || !StringUtils.hasText(tail)) {
            return raw;
        }

        // If it is already a known entity type, leave it untouched.
        if (schemaProvider.getEntitySchema(head).isPresent()) {
            return raw;
        }

        // If it's a relationship field name, rewrite to the target entity type slug.
        String mapped = relationshipFieldToEntityType.get(head);
        if (StringUtils.hasText(mapped)) {
            return mapped + "." + tail;
        }

        return raw;
    }

    /**
     * Validate that any cross-entity reference "<entity-type>.<field>" points to an entity type that is actually
     * present in the plan (primary/candidates/relationshipPaths). If not, fail planning deterministically so the
     * retry loop can ask the model to include the required relationship path(s).
     */
    private void validateCrossEntityReferences(RelationshipQueryPlan plan) {
        if (plan == null) {
            return;
        }

        List<String> errors = new ArrayList<>();
        Set<String> presentEntityTypes = new HashSet<>();
        if (StringUtils.hasText(plan.getPrimaryEntityType())) {
            presentEntityTypes.add(plan.getPrimaryEntityType().trim());
        }
        if (!CollectionUtils.isEmpty(plan.getCandidateEntityTypes())) {
            presentEntityTypes.addAll(plan.getCandidateEntityTypes().stream().filter(StringUtils::hasText).map(String::trim).toList());
        }
        if (!CollectionUtils.isEmpty(plan.getRelationshipPaths())) {
            plan.getRelationshipPaths().forEach(path -> {
                if (path == null) {
                    return;
                }
                if (StringUtils.hasText(path.getFromEntityType())) {
                    presentEntityTypes.add(path.getFromEntityType().trim());
                }
                if (StringUtils.hasText(path.getToEntityType())) {
                    presentEntityTypes.add(path.getToEntityType().trim());
                }
            });
        }

        if (plan.getDirectFilters() != null && !plan.getDirectFilters().isEmpty()) {
            plan.getDirectFilters().values().forEach(filters -> collectCrossEntityReferenceErrors(filters, presentEntityTypes, errors));
        }
        if (plan.getRelationshipFilters() != null && !plan.getRelationshipFilters().isEmpty()) {
            plan.getRelationshipFilters().values().forEach(filters -> collectCrossEntityReferenceErrors(filters, presentEntityTypes, errors));
        }
        if (!CollectionUtils.isEmpty(plan.getRelationshipPaths())) {
            plan.getRelationshipPaths().forEach(path -> {
                if (path != null) {
                    collectCrossEntityReferenceErrors(path.getConditions(), presentEntityTypes, errors);
                }
            });
        }

        if (!errors.isEmpty()) {
            throw new RelationshipQueryValidationException("Planner emitted invalid cross-entity reference(s): " + String.join("; ", errors));
        }
    }

    private void collectCrossEntityReferenceErrors(List<ai.fabric.relationship.dto.FilterCondition> filters,
                                                   Set<String> presentEntityTypes,
                                                   List<String> errors) {
        if (CollectionUtils.isEmpty(filters)) {
            return;
        }
        for (var filter : filters) {
            if (filter == null) {
                continue;
            }
            collectCrossEntityReferenceErrors(filter.getValue(), presentEntityTypes, errors);
            collectCrossEntityReferenceErrors(filter.getSecondaryValue(), presentEntityTypes, errors);
        }
    }

    private void collectCrossEntityReferenceErrors(Object raw, Set<String> presentEntityTypes, List<String> errors) {
        if (raw == null) {
            return;
        }
        if (raw instanceof List<?> list) {
            list.forEach(item -> collectCrossEntityReferenceErrors(item, presentEntityTypes, errors));
            return;
        }
        if (!(raw instanceof String text)) {
            return;
        }
        String trimmed = text.trim();
        if (!trimmed.contains(".")) {
            return;
        }
        String[] parts = trimmed.split("\\.", 2);
        if (parts.length != 2) {
            return;
        }
        String head = parts[0].trim();
        if (!StringUtils.hasText(head)) {
            return;
        }
        if (schemaProvider.getEntitySchema(head).isEmpty()) {
            return; // not an entity type reference
        }
        if (presentEntityTypes == null || !presentEntityTypes.contains(head)) {
            errors.add("referencedEntityType='%s' value='%s'".formatted(head, trimmed));
        }
    }

    /**
     * Validates that every filter field emitted by the planner exists in the discovered schema.
     * If unknown fields are present (e.g. "product.type" when Product has no such field), we fail planning
     * deterministically so the retry/feedback loop can ask the LLM to correct the structure.
     */
    private void validateFilterFields(RelationshipQueryPlan plan) {
        if (plan == null) {
            return;
        }

        List<String> errors = new ArrayList<>();

        if (plan.getDirectFilters() != null && !plan.getDirectFilters().isEmpty()) {
            plan.getDirectFilters().forEach((entityKey, filters) -> {
                if (filters == null || filters.isEmpty()) {
                    return;
                }
                for (var filter : filters) {
                    if (filter == null) {
                        continue;
                    }
                    String entityType = filter.getEntityType() != null ? filter.getEntityType() : entityKey;
                    validateFieldReference(entityType, filter.getField(), errors);
                }
            });
        }

        if (plan.getRelationshipPaths() != null && !plan.getRelationshipPaths().isEmpty()) {
            plan.getRelationshipPaths().forEach(path -> {
                if (path == null || path.getConditions() == null || path.getConditions().isEmpty()) {
                    return;
                }
                for (var condition : path.getConditions()) {
                    if (condition == null) {
                        continue;
                    }
                    String entityType = condition.getEntityType() != null
                        ? condition.getEntityType()
                        : (path.getToEntityType() != null ? path.getToEntityType() : path.getFromEntityType());
                    validateFieldReference(entityType, condition.getField(), errors);
                }
            });
        }

        if (!errors.isEmpty()) {
            throw new RelationshipQueryValidationException(
                "Planner emitted unknown field(s): " + String.join("; ", errors)
            );
        }
    }

    private void validateFieldReference(String entityType, String field, List<String> errors) {
        if (!StringUtils.hasText(entityType) || !StringUtils.hasText(field)) {
            return;
        }
        Set<String> allowed = allowedFields(entityType);
        if (allowed.isEmpty()) {
            // Schema fields may be unavailable for some entity types; skip strict validation in that case.
            return;
        }

        String raw = field.trim();
        String fieldName = raw.contains(".") ? raw.substring(raw.lastIndexOf('.') + 1) : raw;
        if (fieldName.isBlank()) {
            return;
        }

        if (!allowed.contains(fieldName)) {
            errors.add("entityType='" + entityType + "' field='" + fieldName + "' allowed=" + allowed);
        }
    }

    private Set<String> allowedFields(String entityType) {
        if (!StringUtils.hasText(entityType)) {
            return Set.of();
        }
        return schemaProvider.getEntitySchema(entityType)
            .map(schema -> {
                if (schema.getFields() == null || schema.getFields().isEmpty()) {
                    return Set.<String>of();
                }
                Set<String> allowed = new HashSet<>();
                schema.getFields().forEach(field -> {
                    if (field != null && StringUtils.hasText(field.getName())) {
                        allowed.add(field.getName());
                    }
                });
                return allowed;
            })
            .orElse(Set.of());
    }

    private AIGenerationRequest buildRequest(String query, String prompt) {
        RelationshipQueryProperties.LlmProperties llm = properties.getLlm();
        Map<String, Object> parameters = new LinkedHashMap<>(StructuredJsonProviderHints.jsonObjectResponseParameters());
        parameters.put("min_confidence", llm.getMinConfidence());
        String systemPrompt = promptRenderer.render(
            promptTemplateResolver.resolve(TEMPLATE_FAMILY, TEMPLATE_SYSTEM).template(),
            Map.of()
        );
        return AIGenerationRequest.builder()
            .entityId("relationship-query-" + UUID.randomUUID())
            .entityType("relationship-query")
            .generationType("planning")
            .prompt(prompt)
            .systemPrompt(systemPrompt)
            .model(llm.getModel())
            .temperature(llm.getTemperature())
            .maxTokens(llm.getMaxTokens())
            .parameters(parameters)
            .purpose("relationship-query-plan")
            .authContext(AIAccessSubjectContexts.system("relationship-query-planner"))
            .build();
    }

    private String buildPrompt(String query, List<String> entityTypes) {
        return buildPrompt(query, entityTypes, Collections.emptyList());
    }

    private String buildPrompt(String query, List<String> entityTypes, List<String> feedback) {
        String schemaDescription = schemaProvider.getSchemaDescription(entityTypes);
        if (schemaDescription == null) {
            schemaDescription = "";
        }
        String safeQuery = query != null ? query : "";

        if (shouldUseCompactPrompt(feedback)) {
            List<String> allowedTypes = CollectionUtils.isEmpty(entityTypes)
                ? List.of()
                : entityTypes.stream().filter(StringUtils::hasText).toList();

            String allowedTypesLine = allowedTypes.isEmpty()
                ? ""
                : "Allowed entityTypes: " + String.join(", ", allowedTypes) + "\n";

            return promptRenderer.render(
                promptTemplateResolver.resolve(TEMPLATE_FAMILY, TEMPLATE_PLAN_COMPACT).template(),
                Map.of(
                    PLACEHOLDER_SCHEMA, schemaDescription,
                    PLACEHOLDER_ALLOWED_ENTITY_TYPES_LINE, allowedTypesLine,
                    PLACEHOLDER_USER_QUERY, safeQuery
                )
            );
        }

        String examples = promptRenderer.render(
            promptTemplateResolver.resolve(TEMPLATE_FAMILY, TEMPLATE_EXAMPLES).template(),
            Map.of()
        );
        String feedbackSection = buildFeedbackSection(feedback);
        return promptRenderer.render(
            promptTemplateResolver.resolve(TEMPLATE_FAMILY, TEMPLATE_PLAN).template(),
            Map.of(
                PLACEHOLDER_SCHEMA, schemaDescription,
                PLACEHOLDER_USER_QUERY, safeQuery,
                PLACEHOLDER_EXAMPLES, examples,
                PLACEHOLDER_FEEDBACK_SECTION, feedbackSection
            )
        );
    }

    private String buildFeedbackSection(List<String> feedback) {
        if (CollectionUtils.isEmpty(feedback)) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Previous attempt issues:\n");
        for (String issue : feedback) {
            if (StringUtils.hasText(issue)) {
                builder.append("- ").append(issue).append("\n");
            }
        }
        builder.append("Correct the issues above and return JSON only.\n");
        return builder.toString().trim();
    }

    private boolean shouldUseCompactPrompt(List<String> feedback) {
        if (CollectionUtils.isEmpty(feedback)) {
            return false;
        }
        for (String item : feedback) {
            if (!StringUtils.hasText(item)) {
                continue;
            }
            String normalized = item.toLowerCase(Locale.ROOT);
            if (normalized.contains("truncated json")
                || normalized.contains("unexpected end-of-input")
                || normalized.contains("eof")
                || normalized.contains("did not return json payload")) {
                return true;
            }
        }
        return false;
    }

    private RelationshipQueryPlan parsePlan(String rawResponse, String originalQuery, List<String> entityTypes) throws Exception {
        try {
            return parsePlanPayload(rawResponse);
        } catch (Exception parseException) {
            // Provider-agnostic resilience: attempt a bounded structural repair pass when parsing fails.
            // This helps when providers emit truncated or slightly malformed JSON (common in real API runs).
            try {
                String repaired = repairPlanPayload(rawResponse, originalQuery, entityTypes, parseException);
                if (StringUtils.hasText(repaired)) {
                    return parsePlanPayload(repaired);
                }
            } catch (Exception repairException) {
                parseException.addSuppressed(repairException);
            }
            throw parseException;
        }
    }

    private RelationshipQueryPlan parsePlanPayload(String rawResponse) throws Exception {
        StructuredJsonExtraction extraction = structuredJsonExtractor.extractFirstJson(rawResponse);
        if (!extraction.jsonFound() || !StringUtils.hasText(extraction.payload())) {
            throw new IllegalStateException("LLM did not return JSON payload");
        }
        String sanitizedPayload = sanitizePayload(extraction.payload());
        try {
            RelationshipQueryPlan plan = objectMapper.readValue(sanitizedPayload, RelationshipQueryPlan.class);
            plan.setConfidenceScore(normalizeConfidence(plan.getConfidenceScore()));
            return plan;
        } catch (Exception ex) {
            if (extraction.truncationSuspected()) {
                throw new IllegalStateException("LLM returned a truncated JSON payload", ex);
            }
            throw ex;
        }
    }

    private String repairPlanPayload(String rawResponse,
                                    String originalQuery,
                                    List<String> entityTypes,
                                    Exception failure) {
        if (!StringUtils.hasText(rawResponse) || !StringUtils.hasText(originalQuery)) {
            return null;
        }

        RelationshipQueryProperties.LlmProperties llm = properties.getLlm();
        Map<String, Object> parameters = new LinkedHashMap<>(StructuredJsonProviderHints.jsonObjectResponseParameters());
        parameters.put("min_confidence", llm.getMinConfidence());

        String schema = schemaProvider.getSchemaDescription(entityTypes);
        List<String> allowedTypes = CollectionUtils.isEmpty(entityTypes)
            ? List.of()
            : entityTypes.stream().filter(StringUtils::hasText).map(String::trim).toList();

        String allowedTypesLine = allowedTypes.isEmpty()
            ? ""
            : "Allowed entityTypes: " + String.join(", ", allowedTypes) + "\n";

        String safeSchema = schema != null ? schema : "";
        String safeMalformed = structuredJsonExtractor.stripCodeFences(rawResponse);
        String safePrimaryEntity = safePrimaryEntity(entityTypes);

        String prompt = promptRenderer.render(
            promptTemplateResolver.resolve(TEMPLATE_FAMILY, TEMPLATE_REPAIR).template(),
            Map.of(
                PLACEHOLDER_ALLOWED_ENTITY_TYPES_LINE, allowedTypesLine,
                PLACEHOLDER_SCHEMA, safeSchema,
                PLACEHOLDER_USER_QUERY, originalQuery,
                PLACEHOLDER_MALFORMED_RESPONSE, safeMalformed != null ? safeMalformed : "",
                PLACEHOLDER_SAFE_PRIMARY_ENTITY, safePrimaryEntity
            )
        );

        String systemPrompt = promptRenderer.render(
            promptTemplateResolver.resolve(TEMPLATE_FAMILY, TEMPLATE_SYSTEM_REPAIR).template(),
            Map.of()
        );

        AIGenerationRequest request = AIGenerationRequest.builder()
            .entityId("relationship-query-" + UUID.randomUUID() + "-repair")
            .entityType("relationship-query")
            .generationType("planning_repair")
            .prompt(prompt)
            .systemPrompt(systemPrompt)
            .model(llm.getModel())
            .temperature(0.0)
            .maxTokens(Math.min(1200, llm.getMaxTokens()))
            .parameters(parameters)
            .purpose("relationship-query-plan-repair")
            .authContext(AIAccessSubjectContexts.system("relationship-query-planner"))
            .build();

        try {
            AIGenerationResponse response = aiCoreService.generateContent(request);
            if (response == null || !StringUtils.hasText(response.getContent())) {
                return null;
            }
            StructuredJsonExtraction repaired = structuredJsonExtractor.extractFirstJson(response.getContent());
            return repaired.jsonFound() ? repaired.payload() : null;
        } catch (Exception ex) {
            log.warn("Relationship query plan repair failed: {} (originalFailure={})", ex.getMessage(), safeMessage(failure));
            return null;
        }
    }

    private String safePrimaryEntity(List<String> entityTypes) {
        if (!CollectionUtils.isEmpty(entityTypes)) {
            for (String type : entityTypes) {
                if (StringUtils.hasText(type)) {
                    return type.trim().toLowerCase(Locale.ROOT);
                }
            }
        }
        return "document";
    }

    private double normalizeConfidence(Double confidence) {
        if (confidence == null) {
            return properties.getPlanner().getMinConfidenceToExecute();
        }
        if (confidence < 0) {
            return 0;
        }
        if (confidence > 1) {
            return 1;
        }
        return confidence;
    }

    private void applyDefaults(RelationshipQueryPlan plan, RelationshipQueryPlan fallback) {
        plan.setOriginalQuery(fallback.getOriginalQuery());
        if (!StringUtils.hasText(plan.getPrimaryEntityType())) {
            plan.setPrimaryEntityType(fallback.getPrimaryEntityType());
        }
        if (CollectionUtils.isEmpty(plan.getCandidateEntityTypes())) {
            plan.setCandidateEntityTypes(fallback.getCandidateEntityTypes());
        }
        if (!StringUtils.hasText(plan.getSemanticQuery())) {
            plan.setSemanticQuery(fallback.getSemanticQuery());
        }
        if (plan.getQueryStrategy() == null) {
            plan.setQueryStrategy(QueryStrategy.RELATIONSHIP);
        }
    }

    /**
     * Provider-agnostic guardrails: remove filters that are likely copied from examples or hallucinated
     * when the user's query does not mention them.
     *
     * <p>This is intentionally conservative and only targets a few high-impact patterns that cause
     * false negatives (empty results) in real API runs across providers.</p>
     */
    private void sanitizeHallucinatedFilters(RelationshipQueryPlan plan, String query) {
        if (plan == null || query == null) {
            return;
        }

        String normalizedQuery = query.toLowerCase(Locale.ROOT);

        boolean mentionsTime = mentionsTimeConstraint(normalizedQuery);
        boolean mentionsExplicitRiskScore = normalizedQuery.contains("risk score") || normalizedQuery.contains("riskscore");
        boolean mentionsCrossEntityMatch = normalizedQuery.contains("same ")
            || normalizedQuery.contains("same-")
            || normalizedQuery.contains("same counterparty")
            || normalizedQuery.contains("counterparty")
            || normalizedQuery.contains("matches")
            || normalizedQuery.contains("match ")
            || normalizedQuery.contains("equal ")
            || normalizedQuery.contains("equals ")
            || normalizedQuery.contains("matching");

        // 1) Date/time filters: keep only if query mentions a timeframe.
        if (!mentionsTime && plan.getDirectFilters() != null && !plan.getDirectFilters().isEmpty()) {
            plan.getDirectFilters().forEach((entity, filters) -> {
                if (filters == null || filters.isEmpty()) {
                    return;
                }
                filters.removeIf(filter ->
                    filter != null
                        && isTemporalField(filter.getField())
                        && isTemporalOperator(filter.getOperator())
                        && looksLikeIsoTimestamp(filter.getValue())
                );
            });
        }

        // 2) Relationship path conditions guardrails.
        if (plan.getRelationshipPaths() != null && !plan.getRelationshipPaths().isEmpty()) {
            plan.getRelationshipPaths().forEach(path -> {
                if (path == null || path.getConditions() == null || path.getConditions().isEmpty()) {
                    return;
                }
                path.getConditions().removeIf(condition -> {
                    if (condition == null) {
                        return false;
                    }
                    String field = condition.getField() != null ? condition.getField().toLowerCase(Locale.ROOT) : "";
                    Object valueObj = condition.getValue();
                    String value = valueObj != null ? valueObj.toString() : "";

                    // Remove hallucinated riskScore constraints unless query explicitly asks for it.
                    if (!mentionsExplicitRiskScore && field.contains("riskscore")) {
                        return true;
                    }

                    // Remove cross-entity comparisons (value like "destination-account.ownerName") unless query asks for "same/matching".
                    if (!mentionsCrossEntityMatch && value.contains(".")) {
                        return true;
                    }

                    return false;
                });
            });
        }

        // 3) Generic literal-value leakage guardrail:
        // Remove string literal filter values that are NOT explicitly mentioned in the user's query.
        // This is provider-agnostic and prevents common example leakage like "Nike" -> "Nike, Adidas".
        sanitizeUnmentionedStringLiterals(plan, normalizedQuery);
    }

    private void sanitizeUnmentionedStringLiterals(RelationshipQueryPlan plan, String normalizedQuery) {
        if (plan == null || !StringUtils.hasText(normalizedQuery)) {
            return;
        }

        if (plan.getDirectFilters() != null && !plan.getDirectFilters().isEmpty()) {
            plan.getDirectFilters().forEach((entity, filters) -> {
                if (filters == null || filters.isEmpty()) {
                    return;
                }
                filters.removeIf(filter -> shouldRemoveFilterForUnmentionedStringValue(filter, normalizedQuery));
            });
        }

        if (plan.getRelationshipPaths() != null && !plan.getRelationshipPaths().isEmpty()) {
            plan.getRelationshipPaths().forEach(path -> {
                if (path == null || path.getConditions() == null || path.getConditions().isEmpty()) {
                    return;
                }
                path.getConditions().removeIf(condition -> shouldRemoveFilterForUnmentionedStringValue(condition, normalizedQuery));
            });
        }
    }

    private boolean shouldRemoveFilterForUnmentionedStringValue(ai.fabric.relationship.dto.FilterCondition condition,
                                                                String normalizedQuery) {
        if (condition == null || condition.getOperator() == null) {
            return false;
        }
        Object value = condition.getValue();
        if (value == null) {
            return false;
        }

        // Skip cross-entity comparisons like "destination-account.ownerName"
        if (value instanceof String s && s.contains(".")) {
            return false;
        }

        return switch (condition.getOperator()) {
            case EQUALS, NOT_EQUALS, LIKE, IN, ILIKE -> {
                boolean changedOrEmpty = sanitizeConditionValueInPlace(condition, normalizedQuery);
                yield changedOrEmpty;
            }
            default -> false;
        };
    }

    /**
     * Sanitizes condition.value for string literals:
     * - For String values: remove filter if value not mentioned.
     * - For List<String> values: drop unmentioned elements; remove filter if list becomes empty.
     *
     * @return true if the filter should be removed from the plan.
     */
    @SuppressWarnings("unchecked")
    private boolean sanitizeConditionValueInPlace(ai.fabric.relationship.dto.FilterCondition condition,
                                                  String normalizedQuery) {
        Object value = condition.getValue();
        if (value instanceof String text) {
            String normalizedValue = normalizeLiteralForQueryMatch(text);
            if (!StringUtils.hasText(normalizedValue)) {
                return false;
            }
            return !normalizedQuery.contains(normalizedValue);
        }

        if (value instanceof List<?> list) {
            List<Object> mutable = new ArrayList<>(list);
            mutable.removeIf(item -> {
                if (!(item instanceof String s)) {
                    return false;
                }
                String normalizedValue = normalizeLiteralForQueryMatch(s);
                if (!StringUtils.hasText(normalizedValue)) {
                    return false;
                }
                return !normalizedQuery.contains(normalizedValue);
            });

            if (mutable.isEmpty()) {
                return true;
            }
            condition.setValue(mutable);
            return false;
        }

        return false;
    }

    private String normalizeLiteralForQueryMatch(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        // remove common quoting / wildcard wrappers
        value = value.replace("\"", "").replace("'", "");
        value = value.replace("%", "").replace("*", "").trim();
        // collapse whitespace
        value = value.replaceAll("\\s+", " ").trim();
        return value;
    }

    private boolean mentionsTimeConstraint(String normalizedQuery) {
        // Obvious time/date signals. Keep this broad but simple.
        if (normalizedQuery.contains(" q1") || normalizedQuery.contains(" q2") || normalizedQuery.contains(" q3") || normalizedQuery.contains(" q4")) {
            return true;
        }
        if (normalizedQuery.matches(".*\\b(19|20)\\d{2}\\b.*")) {
            return true;
        }
        return normalizedQuery.contains("last ")
            || normalizedQuery.contains("this ")
            || normalizedQuery.contains("yesterday")
            || normalizedQuery.contains("today")
            || normalizedQuery.contains("tomorrow")
            || normalizedQuery.contains("before ")
            || normalizedQuery.contains("after ")
            || normalizedQuery.contains("since ")
            || normalizedQuery.contains("between ");
    }

    private boolean isTemporalField(String field) {
        if (field == null) {
            return false;
        }
        String normalized = field.toLowerCase(Locale.ROOT);
        return normalized.contains("creationdate")
            || normalized.contains("createdat")
            || normalized.contains("occurredat")
            || normalized.endsWith(".date")
            || normalized.endsWith("date");
    }

    private boolean looksLikeIsoTimestamp(Object value) {
        if (value == null) {
            return false;
        }
        String text = value.toString();
        // Very lightweight ISO-ish check: "2023-10-01" or "2023-10-01T00:00:00"
        return text.matches("\\d{4}-\\d{2}-\\d{2}.*");
    }

    private boolean isTemporalOperator(ai.fabric.relationship.dto.FilterOperator operator) {
        if (operator == null) {
            return false;
        }
        return switch (operator) {
            case GREATER_THAN, GREATER_THAN_OR_EQUAL, LESS_THAN, LESS_THAN_OR_EQUAL, BETWEEN -> true;
            default -> false;
        };
    }

    private RelationshipQueryPlan createFallbackPlan(String query, List<String> entityTypes) {
        List<String> available = new ArrayList<>();
        if (!CollectionUtils.isEmpty(entityTypes)) {
            available.addAll(entityTypes);
        } else {
            available.addAll(schemaProvider.getSchema().entities().keySet());
        }
        if (available.isEmpty()) {
            available.add("document");
        }
        String primary = available.get(0);
        return RelationshipQueryPlan.builder()
            .originalQuery(query)
            .semanticQuery(query)
            .primaryEntityType(primary)
            .candidateEntityTypes(available)
            .queryStrategy(QueryStrategy.RELATIONSHIP)
            .confidenceScore(0.25)
            .needsSemanticSearch(false)
            .build();
    }

    private RelationshipQueryErrorContext buildPlannerErrorContext(String query,
                                                                   List<String> entityTypes,
                                                                   RelationshipQueryPlan fallback,
                                                                   Exception ex) {
        List<String> candidates = !CollectionUtils.isEmpty(entityTypes)
            ? entityTypes
            : fallback.getCandidateEntityTypes();
        return RelationshipQueryErrorContext.builder()
            .originalQuery(query)
            .executionStage("PLAN_GENERATION")
            .primaryEntityType(fallback.getPrimaryEntityType())
            .candidateEntityTypes(candidates)
            .fallbackUsed(true)
            .attributes(Map.of(
                "reason", ex.getMessage() != null ? ex.getMessage() : "unknown",
                "plannerFallback", Boolean.TRUE
            ))
            .build();
    }

    private void cachePlan(String cacheKey, RelationshipQueryPlan plan) {
        if (queryCache.isEnabled() && cacheKey != null && plan != null) {
            queryCache.putPlan(cacheKey, plan);
        }
    }

    private void recordPlanMetrics(long startNano, boolean fromCache, boolean success) {
        if (queryMetrics != null && queryMetrics.isEnabled()) {
            long latencyMs = Math.max(0, (System.nanoTime() - startNano) / 1_000_000);
            queryMetrics.recordPlan(latencyMs, fromCache, success);
        }
    }

    private String sanitizePayload(String jsonPayload) {
        if (!StringUtils.hasText(jsonPayload)) {
            return jsonPayload;
        }

        String sanitized = jsonPayload;

        // Provider-agnostic repairs for common "almost JSON" mistakes seen in real API runs.
        // Keep this conservative: only fix patterns that are invalid JSON and commonly appear as typos.
        sanitized = sanitized.replaceAll("\"\\s*;(?=\\s*\\\")", "\",");
        sanitized = sanitized.replaceAll("\"\\s*;(?=\\s*[}\\]])", "\"");
        sanitized = sanitized.replaceAll(",\\s*([}\\]])", "$1");

        return sanitized;
    }

    private String safeMessage(Exception ex) {
        if (ex == null) {
            return "Unknown planner failure";
        }
        String message = ex.getMessage();
        return StringUtils.hasText(message) ? message : ex.getClass().getSimpleName();
    }
}
