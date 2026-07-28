package ai.fabric.core;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.exception.AIServiceException;
import ai.fabric.provider.AIProviderManager;
import ai.fabric.provider.ProviderRequestOverrideSupport;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Core AI service providing generic AI capabilities
 * 
 * This service provides the foundation for all AI features including:
 * - Text generation using OpenAI GPT models
 * - Embedding generation for vector search
 * - Semantic search capabilities
 * - AI-powered content generation
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Slf4j
@Service
public class AICoreService {

    private static final String TEMPLATE_FAMILY = "core/content-validation";
    private static final String TEMPLATE_SYSTEM = "system";
    private static final String TEMPLATE_USER = "user";
    private static final String PLACEHOLDER_CONTENT = "content";
    private static final String PLACEHOLDER_VALIDATION_RULES = "validation_rules";
    
    private final AIProviderConfig aiProviderConfig;
    private final AIProviderManager providerManager;
    private final ObjectProvider<AIEmbeddingService> embeddingServiceProvider;
    private final ObjectProvider<AISearchService> searchServiceProvider;
    private final PromptTemplateResolver promptTemplateResolver;
    private final PromptRenderer promptRenderer;
    private final ObjectMapper objectMapper;

    @Autowired
    public AICoreService(AIProviderConfig aiProviderConfig,
                         AIProviderManager providerManager,
                         ObjectProvider<AIEmbeddingService> embeddingServiceProvider,
                         ObjectProvider<AISearchService> searchServiceProvider,
                         PromptTemplateResolver promptTemplateResolver,
                         PromptRenderer promptRenderer,
                         ObjectProvider<ObjectMapper> objectMapperProvider) {
        this.aiProviderConfig = aiProviderConfig;
        this.providerManager = providerManager;
        this.embeddingServiceProvider = embeddingServiceProvider;
        this.searchServiceProvider = searchServiceProvider;
        this.promptTemplateResolver = promptTemplateResolver;
        this.promptRenderer = promptRenderer;
        this.objectMapper = objectMapperProvider != null
            ? objectMapperProvider.getIfAvailable(this::defaultObjectMapper)
            : defaultObjectMapper();
    }

    public AICoreService(AIProviderConfig aiProviderConfig,
                         AIProviderManager providerManager,
                         ObjectProvider<AIEmbeddingService> embeddingServiceProvider,
                         ObjectProvider<AISearchService> searchServiceProvider,
                         PromptTemplateResolver promptTemplateResolver,
                         PromptRenderer promptRenderer) {
        this(aiProviderConfig, providerManager, embeddingServiceProvider, searchServiceProvider,
            promptTemplateResolver, promptRenderer, null);
    }
    
    /**
     * Generate AI content based on prompt
     * 
     * @param request the generation request
     * @return generated content response
     */
    public AIGenerationResponse generateContent(AIGenerationRequest request) {
        return generateContent(request, LlmPurpose.DEFAULT);
    }

    /**
     * Generate AI content for a specific purpose (enables purpose-specific provider configuration).
     *
     * @param request the generation request
     * @param purpose the purpose of the request
     * @return generated content response
     */
    public AIGenerationResponse generateContent(AIGenerationRequest request, LlmPurpose purpose) {
        try {
            LlmPurpose effectivePurpose = purpose != null ? purpose : LlmPurpose.DEFAULT;
            AIProviderConfig.GenerationDefaults defaults = resolveDefaultsForPurpose(effectivePurpose);
            AIGenerationRequest generationRequest = applyGenerationDefaults(request, defaults, effectivePurpose);

            log.debug(
                "Generating AI content via provider manager for purpose={} entityType={} generationType={}",
                effectivePurpose,
                generationRequest.getEntityType(),
                generationRequest.getGenerationType()
            );

            AIGenerationResponse response = providerManager.generateContent(generationRequest, defaults.providerName());

            log.debug("Successfully generated AI content using model={} purpose={}",
                response != null ? response.getModel() : null, effectivePurpose);

            return response;

        } catch (Exception e) {
            log.error(
                "Error generating AI content for purpose={} cause={}",
                purpose,
                e.getClass().getSimpleName()
            );
            throw new AIServiceException("Failed to generate AI content", e);
        }
    }
    
    /**
     * Generate embeddings for text content
     * 
     * @param request the embedding request
     * @return embedding response with vector data
     */
    public AIEmbeddingResponse generateEmbedding(AIEmbeddingRequest request) {
        try {
            AIEmbeddingRequest embeddingRequest = applyEmbeddingDefaults(request);

            log.debug("Generating embedding via embedding service for entityType={} entityId={}",
                embeddingRequest.getEntityType(), embeddingRequest.getEntityId());

            AIEmbeddingService embeddingService = requireEmbeddingService();
            AIEmbeddingResponse response = embeddingService.generateEmbedding(embeddingRequest);

            log.debug("Successfully generated embedding with {} dimensions using provider {}",
                response.getDimensions(), response.getModel());

            return response;

        } catch (Exception e) {
            log.error("Error generating embedding", e);
            throw new AIServiceException("Failed to generate embedding", e);
        }
    }
    
    /**
     * Perform semantic search across indexed content
     * 
     * @param request the search request
     * @return search results with relevance scores
     */
    public AISearchResponse performSearch(AISearchRequest request) {
        try {
            log.debug("Performing semantic search for query: {}", request.getQuery());
            
            // Generate embedding for search query
            AIEmbeddingRequest embeddingRequest = AIEmbeddingRequest.builder()
                .text(request.getQuery())
                .build();
            
            AIEmbeddingResponse embedding = generateEmbedding(embeddingRequest);
            
            // Perform vector search
            AISearchService searchService = requireSearchService();
            return searchService.search(embedding.getEmbedding(), request);
            
        } catch (Exception e) {
            log.error("Error performing semantic search", e);
            throw new AIServiceException("Failed to perform semantic search", e);
        }
    }
    
    /**
     * Generate AI recommendations based on context
     * 
     * @param entityType the type of entity to recommend
     * @param context the context for recommendations
     * @param limit maximum number of recommendations
     * @return list of recommended entities
     */
    public List<Map<String, Object>> generateRecommendations(String entityType, String context, int limit) {
        try {
            log.debug("Generating recommendations for entity type: {} with context: {}", entityType, context);
            
            // Generate embedding for context
            AIEmbeddingRequest embeddingRequest = AIEmbeddingRequest.builder()
                .text(context)
                .build();
            
            AIEmbeddingResponse embedding = generateEmbedding(embeddingRequest);
            
            // Find similar entities
            AISearchRequest searchRequest = AISearchRequest.builder()
                .query(context)
                .entityType(entityType)
                .limit(limit)
                .build();
            
            AISearchService searchService = requireSearchService();
            AISearchResponse searchResponse = searchService.search(embedding.getEmbedding(), searchRequest);
            
            log.debug("Generated {} recommendations", searchResponse.getResults().size());
            
            return searchResponse.getResults();
            
        } catch (Exception e) {
            log.error("Error generating recommendations", e);
            throw new AIServiceException("Failed to generate recommendations", e);
        }
    }
    
    /**
     * Validate content using AI
     * 
     * @param content the content to validate
     * @param validationRules the validation rules to apply
     * @return validation result with suggestions
     */
    public Map<String, Object> validateContent(String content, Map<String, Object> validationRules) {
        try {
            log.debug("Validating content using AI");

            String safeContent = content != null ? content : "";
            String validationRulesText = formatValidationRules(validationRules);
            String prompt = promptRenderer.render(
                promptTemplateResolver.resolve(TEMPLATE_FAMILY, TEMPLATE_USER).template(),
                Map.of(
                    PLACEHOLDER_CONTENT, safeContent,
                    PLACEHOLDER_VALIDATION_RULES, validationRulesText
                )
            );
            String systemPrompt = promptRenderer.render(
                promptTemplateResolver.resolve(TEMPLATE_FAMILY, TEMPLATE_SYSTEM).template(),
                Map.of()
            );

            AIGenerationRequest request = AIGenerationRequest.builder()
                .prompt(prompt)
                .systemPrompt(systemPrompt)
                .build();
            
            AIGenerationResponse response = generateContent(request);
            
            // Parse validation result from AI response
            return parseValidationResult(response.getContent());
            
        } catch (Exception e) {
            log.error("Error validating content", e);
            throw new AIServiceException("Failed to validate content", e);
        }
    }
    
    /**
     * Format validation rules for AI.
     */
    private String formatValidationRules(Map<String, Object> validationRules) {
        if (validationRules == null || validationRules.isEmpty()) {
            return "";
        }
        StringBuilder formatted = new StringBuilder();
        validationRules.forEach((key, value) -> formatted.append("- ").append(key).append(": ").append(value).append("\n"));
        return formatted.toString().trim();
    }
    
    /**
     * Parse validation result from AI response
     */
    private Map<String, Object> parseValidationResult(String aiResponse) {
        try {
            if (!hasText(aiResponse)) {
                return invalidValidationResult(
                    "AI validation response was empty",
                    "Review the content manually because AI validation returned no JSON result."
                );
            }

            JsonNode root = readValidationJson(aiResponse);
            if (root == null || !root.isObject()) {
                return invalidValidationResult(
                    "AI validation response was not a JSON object",
                    "Review the content manually because AI validation returned an unsupported shape."
                );
            }

            Map<String, Object> result = new LinkedHashMap<>();
            boolean hasValid = root.has("valid") && !root.get("valid").isNull();
            result.put("valid", hasValid && root.get("valid").asBoolean(false));
            result.put("errors", stringList(root.get("errors")));
            result.put("suggestions", stringList(root.get("suggestions")));

            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!result.containsKey(field.getKey())) {
                    result.put(field.getKey(), objectMapper.convertValue(field.getValue(), Object.class));
                }
            }

            if (!hasValid) {
                @SuppressWarnings("unchecked")
                List<String> errors = new ArrayList<>((List<String>) result.get("errors"));
                errors.add("AI validation response did not include required 'valid' field");
                result.put("errors", List.copyOf(errors));
            }
            return result;
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse AI validation JSON: {}", e.getOriginalMessage());
            return invalidValidationResult(
                "Failed to parse AI validation JSON",
                "Review the content manually because AI validation returned malformed JSON."
            );
        }
    }

    private JsonNode readValidationJson(String aiResponse) throws JsonProcessingException {
        return objectMapper.readTree(extractJsonObject(aiResponse));
    }

    private String extractJsonObject(String aiResponse) {
        String trimmed = aiResponse.trim();
        int firstObject = trimmed.indexOf('{');
        int lastObject = trimmed.lastIndexOf('}');
        if (firstObject >= 0 && lastObject > firstObject) {
            return trimmed.substring(firstObject, lastObject + 1);
        }
        return trimmed;
    }

    private List<String> stringList(JsonNode node) throws JsonProcessingException {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return List.of();
        }
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            for (JsonNode item : node) {
                values.add(stringValue(item));
            }
            return List.copyOf(values);
        }
        return List.of(stringValue(node));
    }

    private String stringValue(JsonNode node) throws JsonProcessingException {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText();
        }
        return objectMapper.writeValueAsString(node);
    }

    private Map<String, Object> invalidValidationResult(String error, String suggestion) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", false);
        result.put("errors", List.of(error));
        result.put("suggestions", List.of(suggestion));
        return result;
    }

    private ObjectMapper defaultObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
    
    /**
     * Generate text using AI with simple string input
     */
    public String generateText(String prompt) {
        return generateText(prompt, LlmPurpose.DEFAULT);
    }

    /**
     * Generate text using AI with a specific purpose.
     */
    public String generateText(String prompt, LlmPurpose purpose) {
        AIGenerationResponse response = generateTextResponse(prompt, purpose);
        return response != null ? response.getContent() : null;
    }

    /**
     * Generate text using AI and return the full generation response.
     */
    public AIGenerationResponse generateTextResponse(String prompt) {
        return generateTextResponse(prompt, LlmPurpose.DEFAULT);
    }

    /**
     * Generate text using AI for a specific purpose and return the full generation response.
     */
    public AIGenerationResponse generateTextResponse(String prompt, LlmPurpose purpose) {
        try {
            LlmPurpose effectivePurpose = purpose != null ? purpose : LlmPurpose.DEFAULT;
            AIProviderConfig.GenerationDefaults defaults = resolveDefaultsForPurpose(effectivePurpose);

            AIGenerationRequest request = AIGenerationRequest.builder()
                .entityId("adhoc-" + UUID.randomUUID())
                .entityType("adhoc")
                .generationType("text")
                .prompt(prompt)
                .model(defaults.model())
                .maxTokens(defaults.maxTokens() != null ? Math.min(defaults.maxTokens(), 1000) : null)
                .temperature(defaults.temperature())
                .build();

            return generateContent(request, effectivePurpose);
                
        } catch (Exception e) {
            log.error(
                "Error generating text cause={}",
                e.getClass().getSimpleName()
            );
            throw new AIServiceException("Failed to generate text", e);
        }
    }

    private AIGenerationRequest applyGenerationDefaults(AIGenerationRequest request,
                                                        AIProviderConfig.GenerationDefaults defaults,
                                                        LlmPurpose purpose) {
        if (request == null) {
            throw new AIServiceException("Generation request cannot be null");
        }

        boolean requiresDefaults = request.getModel() == null
            || request.getMaxTokens() == null
            || request.getTemperature() == null;
        Map<String, Object> mergedParameters = applyPurposeConnectionOverrides(request.getParameters(), purpose);
        boolean parametersChanged = mergedParameters != request.getParameters();

        if (!requiresDefaults && !parametersChanged) {
            return request;
        }

        return AIGenerationRequest.builder()
            .entityId(request.getEntityId())
            .entityType(request.getEntityType())
            .generationType(request.getGenerationType())
            .prompt(request.getPrompt())
            .context(request.getContext())
            .systemPrompt(request.getSystemPrompt())
            .messages(request.getMessages())
            .inputParts(request.getInputParts())
            .transientInputPolicy(request.getTransientInputPolicy())
            .purpose(request.getPurpose())
            .parameters(mergedParameters)
            .authContext(request.getAuthContext())
            .model(request.getModel() != null ? request.getModel() : defaults.model())
            .maxTokens(request.getMaxTokens() != null ? request.getMaxTokens() : defaults.maxTokens())
            .temperature(request.getTemperature() != null ? request.getTemperature() : defaults.temperature())
            .build();
    }

    private Map<String, Object> applyPurposeConnectionOverrides(Map<String, Object> parameters, LlmPurpose purpose) {
        AIProviderConfig.PurposeLlmConnectionConfig connectionConfig = switch (purpose) {
            case ORCHESTRATION -> aiProviderConfig.getOrchestration();
            case GENERATION -> aiProviderConfig.getGeneration();
            case EMBEDDINGS, DEFAULT -> null;
        };
        return ProviderRequestOverrideSupport.mergeLlmConnectionOverride(parameters, connectionConfig);
    }

    private AIProviderConfig.GenerationDefaults resolveDefaultsForPurpose(LlmPurpose purpose) {
        return switch (purpose) {
            case ORCHESTRATION -> aiProviderConfig.resolveOrchestrationLlmDefaults();
            case GENERATION -> aiProviderConfig.resolveGenerationLlmDefaults();
            case EMBEDDINGS, DEFAULT -> aiProviderConfig.resolveLlmDefaults();
        };
    }

    private AIEmbeddingRequest applyEmbeddingDefaults(AIEmbeddingRequest request) {
        if (request == null) {
            throw new AIServiceException("Embedding request cannot be null");
        }

        Map<String, Object> mergedParameters = ProviderRequestOverrideSupport.mergeEmbeddingConnectionOverride(
            request.getParameters(),
            aiProviderConfig
        );
        boolean parametersChanged = mergedParameters != request.getParameters();

        if (request.getModel() != null && !parametersChanged) {
            return request;
        }

        AIProviderConfig.EmbeddingDefaults defaults = aiProviderConfig.resolveEmbeddingDefaults();

        return AIEmbeddingRequest.builder()
            .text(request.getText())
            .entityType(request.getEntityType())
            .entityId(request.getEntityId())
            .metadata(request.getMetadata())
            .parameters(mergedParameters)
            .model(request.getModel() != null ? request.getModel() : defaults.model())
            .build();
    }

    private AIEmbeddingService requireEmbeddingService() {
        AIEmbeddingService embeddingService = embeddingServiceProvider.getIfAvailable();
        if (embeddingService == null) {
            throw new AIServiceException(
                "Embeddings are not available. Enable embeddings (ai.service.features.enable-embeddings=true) " +
                    "and configure an embedding provider (ai.providers.embedding-provider)."
            );
        }
        return embeddingService;
    }

    private AISearchService requireSearchService() {
        AISearchService searchService = searchServiceProvider.getIfAvailable();
        if (searchService == null) {
            throw new AIServiceException(
                "Semantic search is not available. Ensure a VectorDatabaseService is configured and " +
                    "search is enabled (ai.service.features.enable-search=true)."
            );
        }
        return searchService;
    }
}
