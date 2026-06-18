package ai.fabric.rag.service;

import ai.fabric.core.AICoreService;
import ai.fabric.core.AIEmbeddingService;
import ai.fabric.core.AISearchService;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AdvancedRAGRequest;
import ai.fabric.dto.AdvancedRAGResponse;
import ai.fabric.dto.RAGRequest;
import ai.fabric.dto.RAGResponse;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import ai.fabric.spi.AdvancedRAGProvider;
import ai.fabric.spi.RAGProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Advanced RAG Service with query expansion, re-ranking, and context optimization.
 * 
 * <p>This service provides advanced RAG capabilities beyond basic retrieval:</p>
 * <ul>
 *   <li><strong>Query Expansion:</strong> Generates related queries using AI to improve recall</li>
 *   <li><strong>Multi-Strategy Search:</strong> Executes searches with different strategies in parallel</li>
 *   <li><strong>Re-ranking:</strong> Re-ranks results using semantic, hybrid, or diversity strategies</li>
 *   <li><strong>Context Optimization:</strong> Optimizes context for better generation quality</li>
 * </ul>
 * 
 * <p><strong>Usage:</strong></p>
 * <pre>{@code
 * AdvancedRAGRequest request = AdvancedRAGRequest.builder()
 *     .query("What are the best practices for microservices?")
 *     .expansionLevel(3)
 *     .rerankingStrategy("semantic")
 *     .contextOptimizationLevel("high")
 *     .build();
 *     
 * AdvancedRAGResponse response = advancedRAGService.performAdvancedRAG(request);
 * }</pre>
 * 
 * <p><strong>Thread Safety:</strong> This service is thread-safe and uses parallel
 * processing for improved performance.</p>
 * 
 * @author AI Infrastructure Team
 * @version 2.0.0
 * @since 1.0
 */
@Slf4j
@RequiredArgsConstructor
public class AdvancedRAGService implements AdvancedRAGProvider {

    // =========================================================================
    // Constants
    // =========================================================================
    
    // Re-ranking strategies
    private static final String STRATEGY_SEMANTIC = "semantic";
    private static final String STRATEGY_HYBRID = "hybrid";
    private static final String STRATEGY_DIVERSITY = "diversity";
    
    // Context optimization levels
    private static final String LEVEL_HIGH = "high";
    private static final String LEVEL_MEDIUM = "medium";
    private static final String LEVEL_LOW = "low";
    
    // Defaults
    private static final int TOP_DOCUMENTS_FOR_MEDIUM_OPTIMIZATION = 5;
    private static final int MAX_CONTEXT_LENGTH = 500;
    
    // Scoring weights
    private static final double SCORE_WEIGHT = 0.6;
    private static final double SIMILARITY_WEIGHT = 0.4;

    private static final String TEMPLATE_FAMILY = "rag/advanced";
    private static final String TEMPLATE_EXPAND = "expand";
    private static final String TEMPLATE_OPTIMIZE = "optimize";
    private static final String TEMPLATE_GENERATE = "generate";
    private static final String TEMPLATE_GENERATE_AUTHORITATIVE = "generate-authoritative";

    private static final String PLACEHOLDER_EXPANSION_LEVEL = "expansion_level";
    private static final String PLACEHOLDER_QUERY = "query";
    private static final String PLACEHOLDER_CONTEXT = "context";
    private static final String PLACEHOLDER_AUTHORITATIVE_CONTEXT = "authoritative_context";
    
    // Messages
    private static final String ERROR_MESSAGE_TEMPLATE = "Error processing request: %s";
    private static final String UNABLE_TO_GENERATE_MESSAGE = "Unable to generate response at this time.";
    
    // Metadata keys
    private static final String METADATA_KEY_TIMESTAMP = "timestamp";
    private static final String METADATA_KEY_PROCESSING_TIME_MS = "processingTimeMs";
    private static final String METADATA_KEY_EXPANSION_LEVEL = "expansionLevel";
    private static final String METADATA_KEY_RERANKING_STRATEGY = "rerankingStrategy";
    private static final String METADATA_KEY_CONTEXT_OPTIMIZATION_LEVEL = "contextOptimizationLevel";
    private static final String METADATA_KEY_MAX_DOCUMENTS = "maxDocuments";
    private static final String METADATA_KEY_ENABLE_HYBRID_SEARCH = "enableHybridSearch";
    private static final String METADATA_KEY_ENABLE_CONTEXTUAL_SEARCH = "enableContextualSearch";
    private static final String METADATA_KEY_USER_CONTEXT = "userContext";

    // =========================================================================
    // Dependencies
    // =========================================================================

    private final AISearchService aiSearchService;
    private final AIEmbeddingService aiEmbeddingService;
    private final AICoreService aiCoreService;
    private final RAGProvider ragProvider;
    private final PromptTemplateResolver promptTemplateResolver;
    private final PromptRenderer promptRenderer;

    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong successfulRequests = new AtomicLong();
    private final AtomicLong failedRequests = new AtomicLong();
    private final AtomicLong totalProcessingTimeMs = new AtomicLong();
    private final AtomicLong lastProcessingTimeMs = new AtomicLong();
    private final AtomicLong lastRequestTimestamp = new AtomicLong();
    private volatile String lastErrorMessage;

    // =========================================================================
    // Public Methods
    // =========================================================================

    /**
     * Perform advanced RAG with query expansion and re-ranking.
     * 
     * <p>This method executes a multi-stage RAG process:</p>
     * <ol>
     *   <li>Expand the query into multiple related queries</li>
     *   <li>Execute parallel searches with different strategies</li>
     *   <li>Re-rank results using the specified strategy</li>
     *   <li>Optimize context for generation</li>
     *   <li>Generate the final response</li>
     * </ol>
     * 
     * @param request the advanced RAG request with all configuration options
     * @return AdvancedRAGResponse with expanded queries, re-ranked documents, and generated response
     */
    public AdvancedRAGResponse performAdvancedRAG(AdvancedRAGRequest request) {
        long startTime = System.currentTimeMillis();
        totalRequests.incrementAndGet();

        if (request == null) {
            String message = "Advanced RAG request must not be null";
            recordFailure(System.currentTimeMillis() - startTime, message);
            return AdvancedRAGResponse.builder()
                .response(String.format(ERROR_MESSAGE_TEMPLATE, message))
                .success(false)
                .errorMessage(message)
                .build();
        }

        log.info("Performing advanced RAG for query: {}", request.getQuery());
        
        try {
            List<String> expandedQueries = expandQuery(request.getQuery(), expansionLevel(request));
            log.debug("Expanded queries: {}", expandedQueries);
            
            List<RAGResponse> searchResults = performMultiStrategySearch(expandedQueries, request);
            
            List<RAGResponse.RAGDocument> rerankedDocuments = rerankDocuments(
                searchResults, request.getQuery(), rerankingStrategy(request)
            );
            
            String optimizedContext = optimizeContext(
                rerankedDocuments, contextOptimizationLevel(request));
            
            String generatedResponse = generateResponse(
                request.getQuery(), optimizedContext, request);
            
            long processingTime = System.currentTimeMillis() - startTime;
            recordSuccess(processingTime);
            
            List<AdvancedRAGResponse.RAGDocument> convertedDocuments = rerankedDocuments.stream()
                .map(this::convertToAdvancedDocument)
                .collect(Collectors.toList());
            
            return AdvancedRAGResponse.builder()
                .query(request.getQuery())
                .expandedQueries(expandedQueries)
                .response(generatedResponse)
                .context(optimizedContext)
                .documents(convertedDocuments)
                .totalDocuments(convertedDocuments.size())
                .usedDocuments(Math.min(convertedDocuments.size(), maxDocuments(request)))
                .relevanceScores(extractRelevanceScores(rerankedDocuments))
                .confidenceScore(calculateConfidence(rerankedDocuments))
                .processingTimeMs(processingTime)
                .success(true)
                .rerankingStrategy(rerankingStrategy(request))
                .expansionLevel(expansionLevel(request))
                .contextOptimizationLevel(contextOptimizationLevel(request))
                .metadata(createMetadata(request, processingTime))
                .build();
                
        } catch (Exception e) {
            log.error("Error performing advanced RAG", e);
            recordFailure(System.currentTimeMillis() - startTime, e.getMessage());
            return AdvancedRAGResponse.builder()
                .query(request.getQuery())
                .response(String.format(ERROR_MESSAGE_TEMPLATE, e.getMessage()))
                .success(false)
                .errorMessage(e.getMessage())
                .build();
        }
    }

    public Map<String, Object> getStatistics() {
        long total = totalRequests.get();
        long successful = successfulRequests.get();
        long failed = failedRequests.get();
        long processingTime = totalProcessingTimeMs.get();

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalRequests", total);
        stats.put("successfulRequests", successful);
        stats.put("failedRequests", failed);
        stats.put("successRate", total > 0 ? (double) successful / total : 0.0);
        stats.put("averageProcessingTimeMs", total > 0 ? (double) processingTime / total : 0.0);
        stats.put("lastProcessingTimeMs", lastProcessingTimeMs.get());
        stats.put("lastRequestTimestamp", lastRequestTimestamp.get() > 0 ? lastRequestTimestamp.get() : null);
        stats.put("lastErrorMessage", lastErrorMessage);
        return Collections.unmodifiableMap(stats);
    }

    // =========================================================================
    // Private Methods - Query Expansion
    // =========================================================================

    private List<String> expandQuery(String originalQuery, int expansionLevel) {
        try {
            String safeQuery = originalQuery != null ? originalQuery : "";
            String expansionPrompt = promptRenderer.render(
                promptTemplateResolver.resolve(TEMPLATE_FAMILY, TEMPLATE_EXPAND).template(),
                Map.of(
                    PLACEHOLDER_EXPANSION_LEVEL, String.valueOf(expansionLevel),
                    PLACEHOLDER_QUERY, safeQuery
                )
            );
            
            String response = aiCoreService.generateText(expansionPrompt);
            List<String> expandedQueries = Arrays.stream(response.split("\n"))
                .map(String::trim)
                .filter(q -> !q.isEmpty() && !q.equals(safeQuery))
                .limit(expansionLevel)
                .collect(Collectors.toList());
            
            expandedQueries.add(0, safeQuery);
            return expandedQueries;
            
        } catch (Exception e) {
            log.warn("Query expansion failed, using original query only", e);
            String safeQuery = originalQuery != null ? originalQuery : "";
            return Collections.singletonList(safeQuery);
        }
    }

    // =========================================================================
    // Private Methods - Search
    // =========================================================================

    private List<RAGResponse> performMultiStrategySearch(List<String> queries, 
            AdvancedRAGRequest request) {
        List<CompletableFuture<RAGResponse>> futures = queries.stream()
            .map(query -> CompletableFuture.supplyAsync(() -> 
                executeSearch(query, request)))
            .collect(Collectors.toList());
        
        return futures.stream()
            .map(CompletableFuture::join)
            .filter(Objects::nonNull)
            .filter(response -> Boolean.TRUE.equals(response.getSuccess()))
            .collect(Collectors.toList());
    }
    
    private RAGResponse executeSearch(String query, AdvancedRAGRequest request) {
        try {
            RAGRequest.RAGRequestBuilder ragRequestBuilder = RAGRequest.builder()
                .query(query)
                .limit(maxResults(request))
                .enableHybridSearch(request.getEnableHybridSearch())
                .enableContextualSearch(request.getEnableContextualSearch())
                .categories(request.getCategories())
                .filters(request.getFilters())
                .authContext(request.getAuthContext());

            if (request.getEntityType() != null && !request.getEntityType().isBlank()) {
                ragRequestBuilder.entityType(request.getEntityType());
            }

            if (request.getSimilarityThreshold() != null) {
                ragRequestBuilder.threshold(request.getSimilarityThreshold());
            }

            if (request.getContext() != null && !request.getContext().isBlank()) {
                ragRequestBuilder.context(Map.of(METADATA_KEY_USER_CONTEXT, request.getContext()));
            }

            if (request.getMetadata() != null && !request.getMetadata().isEmpty()) {
                ragRequestBuilder.metadata(request.getMetadata());
            }

            RAGRequest ragRequest = ragRequestBuilder.build();
            
            return ragProvider.performRag(ragRequest);
        } catch (Exception e) {
            log.warn("Search failed for query: {}", query, e);
            return RAGResponse.builder()
                .originalQuery(query)
                .success(false)
                .errorMessage(e.getMessage())
                .build();
        }
    }

    // =========================================================================
    // Private Methods - Re-ranking
    // =========================================================================

    private List<RAGResponse.RAGDocument> rerankDocuments(
            List<RAGResponse> searchResults, String originalQuery, String strategy) {
        
        List<RAGResponse.RAGDocument> allDocuments = searchResults.stream()
            .filter(Objects::nonNull)
            .flatMap(response -> safeDocuments(response).stream())
            .distinct()
            .collect(Collectors.toList());
        
        return switch (rerankingStrategy(strategy).toLowerCase()) {
            case STRATEGY_SEMANTIC -> rerankBySemanticSimilarity(allDocuments, originalQuery);
            case STRATEGY_HYBRID -> rerankByHybridScore(allDocuments, originalQuery);
            case STRATEGY_DIVERSITY -> rerankByDiversity(allDocuments);
            default -> rerankByScore(allDocuments);
        };
    }

    private List<RAGResponse.RAGDocument> rerankBySemanticSimilarity(
            List<RAGResponse.RAGDocument> documents, String query) {
        
        try {
            AIEmbeddingRequest queryRequest = AIEmbeddingRequest.builder()
                .text(query)
                .build();
            List<Double> queryEmbedding = aiEmbeddingService.generateEmbedding(queryRequest)
                .getEmbedding();
            
            return documents.stream()
                .map(doc -> {
                    try {
                        AIEmbeddingRequest docRequest = AIEmbeddingRequest.builder()
                            .text(doc.getContent())
                            .build();
                        List<Double> docEmbedding = aiEmbeddingService
                            .generateEmbedding(docRequest).getEmbedding();
                        double similarity = calculateCosineSimilarity(queryEmbedding, docEmbedding);
                        doc.setSimilarity(similarity);
                        return doc;
                    } catch (Exception e) {
                        log.warn("Failed to calculate similarity for document: {}", doc.getId(), e);
                        return doc;
                    }
                })
                .sorted(Comparator.comparingDouble((RAGResponse.RAGDocument doc) -> safeDouble(doc.getSimilarity()))
                    .reversed())
                .collect(Collectors.toList());
                
        } catch (Exception e) {
            log.warn("Semantic re-ranking failed, using original order", e);
            return documents;
        }
    }

    private List<RAGResponse.RAGDocument> rerankByHybridScore(
            List<RAGResponse.RAGDocument> documents, String query) {
        
        return documents.stream()
            .map(doc -> {
                double hybridScore = calculateHybridScore(doc);
                doc.setSimilarity(hybridScore);
                return doc;
            })
            .sorted((d1, d2) -> Double.compare(d2.getSimilarity(), d1.getSimilarity()))
            .collect(Collectors.toList());
    }

    private List<RAGResponse.RAGDocument> rerankByDiversity(
            List<RAGResponse.RAGDocument> documents) {
        List<RAGResponse.RAGDocument> diverse = new ArrayList<>();
        Set<String> usedTypes = new HashSet<>();
        
        for (RAGResponse.RAGDocument doc : documents) {
            if (!usedTypes.contains(doc.getType())) {
                diverse.add(doc);
                usedTypes.add(doc.getType());
            }
        }
        
        for (RAGResponse.RAGDocument doc : documents) {
            if (!diverse.contains(doc)) {
                diverse.add(doc);
            }
        }
        
        return diverse;
    }

    private List<RAGResponse.RAGDocument> rerankByScore(
            List<RAGResponse.RAGDocument> documents) {
        return documents.stream()
            .sorted(Comparator.comparingDouble((RAGResponse.RAGDocument doc) -> safeDouble(doc.getScore()))
                .reversed())
            .collect(Collectors.toList());
    }

    // =========================================================================
    // Private Methods - Context Optimization
    // =========================================================================

    private String optimizeContext(List<RAGResponse.RAGDocument> documents, String level) {
        if (documents == null || documents.isEmpty()) {
            return "";
        }
        
        return switch (contextOptimizationLevel(level).toLowerCase()) {
            case LEVEL_HIGH -> optimizeContextHigh(documents);
            case LEVEL_MEDIUM -> optimizeContextMedium(documents);
            case LEVEL_LOW -> optimizeContextLow(documents);
            default -> documents.stream()
                .map(RAGResponse.RAGDocument::getContent)
                .collect(Collectors.joining("\n\n"));
        };
    }

    private String optimizeContextHigh(List<RAGResponse.RAGDocument> documents) {
        try {
            String context = documents.stream()
                .map(RAGResponse.RAGDocument::getContent)
                .collect(Collectors.joining("\n\n"));

            String safeContext = context != null ? context : "";
            String optimizationPrompt = promptRenderer.render(
                promptTemplateResolver.resolve(TEMPLATE_FAMILY, TEMPLATE_OPTIMIZE).template(),
                Map.of(PLACEHOLDER_CONTEXT, safeContext)
            );
            
            return aiCoreService.generateText(optimizationPrompt);
            
        } catch (Exception e) {
            log.warn("High-level context optimization failed", e);
            return optimizeContextMedium(documents);
        }
    }

    private String optimizeContextMedium(List<RAGResponse.RAGDocument> documents) {
        return documents.stream()
            .sorted(Comparator.comparingDouble((RAGResponse.RAGDocument doc) -> safeDouble(doc.getScore()))
                .reversed())
            .limit(TOP_DOCUMENTS_FOR_MEDIUM_OPTIMIZATION)
            .map(RAGResponse.RAGDocument::getContent)
            .collect(Collectors.joining("\n\n"));
    }

    private String optimizeContextLow(List<RAGResponse.RAGDocument> documents) {
        return documents.stream()
            .map(RAGResponse.RAGDocument::getContent)
            .collect(Collectors.joining("\n\n"));
    }

    // =========================================================================
    // Private Methods - Response Generation
    // =========================================================================

    private String generateResponse(String query, String context, AdvancedRAGRequest request) {
        try {
            String safeQuery = query != null ? query : "";
            String safeContext = context != null ? context : "";

            String authoritativeContext = request != null ? request.getContext() : null;
            String prompt = authoritativeContext != null && !authoritativeContext.isBlank()
                ? promptRenderer.render(
                    promptTemplateResolver.resolve(TEMPLATE_FAMILY, TEMPLATE_GENERATE_AUTHORITATIVE).template(),
                    Map.of(
                        PLACEHOLDER_QUERY, safeQuery,
                        PLACEHOLDER_AUTHORITATIVE_CONTEXT, authoritativeContext,
                        PLACEHOLDER_CONTEXT, safeContext
                    )
                )
                : promptRenderer.render(
                    promptTemplateResolver.resolve(TEMPLATE_FAMILY, TEMPLATE_GENERATE).template(),
                    Map.of(
                        PLACEHOLDER_QUERY, safeQuery,
                        PLACEHOLDER_CONTEXT, safeContext
                    )
                );
            
            return aiCoreService.generateText(prompt);
            
        } catch (Exception e) {
            log.error("Response generation failed", e);
            return UNABLE_TO_GENERATE_MESSAGE;
        }
    }

    // =========================================================================
    // Private Methods - Utility
    // =========================================================================

    private double calculateCosineSimilarity(List<Double> vector1, List<Double> vector2) {
        if (vector1 == null || vector2 == null || vector1.isEmpty()) {
            return 0.0;
        }
        if (vector1.size() != vector2.size()) {
            return 0.0;
        }
        
        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;
        
        for (int i = 0; i < vector1.size(); i++) {
            dotProduct += vector1.get(i) * vector2.get(i);
            norm1 += vector1.get(i) * vector1.get(i);
            norm2 += vector2.get(i) * vector2.get(i);
        }
        
        if (norm1 == 0.0 || norm2 == 0.0) {
            return 0.0;
        }
        
        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    private double calculateHybridScore(RAGResponse.RAGDocument doc) {
        double score = safeDouble(doc.getScore());
        double similarity = safeDouble(doc.getSimilarity());
        
        return (score * SCORE_WEIGHT) + (similarity * SIMILARITY_WEIGHT);
    }

    private List<Double> extractRelevanceScores(List<RAGResponse.RAGDocument> documents) {
        return documents.stream()
            .map(doc -> safeDouble(doc.getSimilarity()))
            .collect(Collectors.toList());
    }

    private double calculateConfidence(List<RAGResponse.RAGDocument> documents) {
        if (documents.isEmpty()) {
            return 0.0;
        }
        
        double avgScore = documents.stream()
            .mapToDouble(doc -> safeDouble(doc.getScore()))
            .average()
            .orElse(0.0);
        
        double avgSimilarity = documents.stream()
            .mapToDouble(doc -> safeDouble(doc.getSimilarity()))
            .average()
            .orElse(0.0);
        
        return (avgScore + avgSimilarity) / 2.0;
    }

    private static double safeDouble(Double value) {
        return value == null ? 0.0 : value.doubleValue();
    }

    private Map<String, Object> createMetadata(AdvancedRAGRequest request, long processingTime) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(METADATA_KEY_TIMESTAMP, System.currentTimeMillis());
        metadata.put(METADATA_KEY_PROCESSING_TIME_MS, processingTime);
        metadata.put(METADATA_KEY_EXPANSION_LEVEL, expansionLevel(request));
        metadata.put(METADATA_KEY_RERANKING_STRATEGY, rerankingStrategy(request));
        metadata.put(METADATA_KEY_CONTEXT_OPTIMIZATION_LEVEL, contextOptimizationLevel(request));
        metadata.put(METADATA_KEY_MAX_DOCUMENTS, maxDocuments(request));
        metadata.put(METADATA_KEY_ENABLE_HYBRID_SEARCH, request.getEnableHybridSearch());
        metadata.put(METADATA_KEY_ENABLE_CONTEXTUAL_SEARCH, request.getEnableContextualSearch());
        return metadata;
    }

    private void recordSuccess(long processingTimeMs) {
        successfulRequests.incrementAndGet();
        recordProcessing(processingTimeMs);
        lastErrorMessage = null;
    }

    private void recordFailure(long processingTimeMs, String errorMessage) {
        failedRequests.incrementAndGet();
        recordProcessing(processingTimeMs);
        lastErrorMessage = errorMessage;
    }

    private void recordProcessing(long processingTimeMs) {
        long safeProcessingTimeMs = Math.max(processingTimeMs, 0L);
        totalProcessingTimeMs.addAndGet(safeProcessingTimeMs);
        lastProcessingTimeMs.set(safeProcessingTimeMs);
        lastRequestTimestamp.set(System.currentTimeMillis());
    }
    
    private AdvancedRAGResponse.RAGDocument convertToAdvancedDocument(RAGResponse.RAGDocument doc) {
        return AdvancedRAGResponse.RAGDocument.builder()
            .id(doc.getId())
            .content(doc.getContent())
            .title(doc.getTitle())
            .type(doc.getType())
            .score(doc.getScore())
            .similarity(doc.getSimilarity())
            .metadata(doc.getMetadata())
            .source(doc.getSource())
            .createdAt(doc.getCreatedAt())
            .author(doc.getAuthor())
            .tags(doc.getTags())
            .category(doc.getType())
            .wordCount(doc.getWordCount())
            .language(doc.getLanguage())
            .build();
    }

    private List<RAGResponse.RAGDocument> safeDocuments(RAGResponse response) {
        return response.getDocuments() != null ? response.getDocuments() : List.of();
    }

    private int expansionLevel(AdvancedRAGRequest request) {
        return request.getExpansionLevel() != null ? request.getExpansionLevel() : 2;
    }

    private int maxDocuments(AdvancedRAGRequest request) {
        return request.getMaxDocuments() != null && request.getMaxDocuments() > 0
            ? request.getMaxDocuments()
            : 5;
    }

    private int maxResults(AdvancedRAGRequest request) {
        return request.getMaxResults() != null && request.getMaxResults() > 0
            ? request.getMaxResults()
            : 10;
    }

    private String rerankingStrategy(AdvancedRAGRequest request) {
        return rerankingStrategy(request.getRerankingStrategy());
    }

    private String rerankingStrategy(String strategy) {
        return strategy != null && !strategy.isBlank() ? strategy : STRATEGY_HYBRID;
    }

    private String contextOptimizationLevel(AdvancedRAGRequest request) {
        return contextOptimizationLevel(request.getContextOptimizationLevel());
    }

    private String contextOptimizationLevel(String level) {
        return level != null && !level.isBlank() ? level : LEVEL_MEDIUM;
    }
}
