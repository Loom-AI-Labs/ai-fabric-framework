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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

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

    public AICoreService(AIProviderConfig aiProviderConfig,
                         AIProviderManager providerManager,
                         ObjectProvider<AIEmbeddingService> embeddingServiceProvider,
                         ObjectProvider<AISearchService> searchServiceProvider,
                         PromptTemplateResolver promptTemplateResolver,
                         PromptRenderer promptRenderer) {
        this.aiProviderConfig = aiProviderConfig;
        this.providerManager = providerManager;
        this.embeddingServiceProvider = embeddingServiceProvider;
        this.searchServiceProvider = searchServiceProvider;
        this.promptTemplateResolver = promptTemplateResolver;
        this.promptRenderer = promptRenderer;
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

            log.debug("Generating AI content via provider manager for purpose={} prompt={}",
                effectivePurpose, generationRequest.getPrompt());

            AIGenerationResponse response = providerManager.generateContent(generationRequest, defaults.providerName());

            log.debug("Successfully generated AI content using model={} purpose={}",
                response != null ? response.getModel() : null, effectivePurpose);

            return response;

        } catch (Exception e) {
            log.error("Error generating AI content for purpose={}", purpose, e);
            throw new AIServiceException("Failed to generate AI content: " + e.getMessage(), e);
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
        // Simple JSON parsing - in production, use proper JSON parser
        try {
            // This is a simplified implementation
            // In production, use Jackson or Gson for proper JSON parsing
            return Map.of(
                "valid", aiResponse.contains("\"valid\": true"),
                "errors", List.of(),
                "suggestions", List.of(aiResponse)
            );
        } catch (Exception e) {
            log.warn("Failed to parse AI validation result", e);
            return Map.of(
                "valid", false,
                "errors", List.of("Failed to parse validation result"),
                "suggestions", List.of("Please check the content manually")
            );
        }
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
            log.error("Error generating text: {}", e.getMessage(), e);
            throw new AIServiceException("Failed to generate text: " + e.getMessage(), e);
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

        if (!requiresDefaults) {
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
            .parameters(applyPurposeConnectionOverrides(request.getParameters(), purpose))
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

        if (request.getModel() != null) {
            return request;
        }

        AIProviderConfig.EmbeddingDefaults defaults = aiProviderConfig.resolveEmbeddingDefaults();

        return AIEmbeddingRequest.builder()
            .text(request.getText())
            .entityType(request.getEntityType())
            .entityId(request.getEntityId())
            .metadata(request.getMetadata())
            .model(defaults.model())
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
