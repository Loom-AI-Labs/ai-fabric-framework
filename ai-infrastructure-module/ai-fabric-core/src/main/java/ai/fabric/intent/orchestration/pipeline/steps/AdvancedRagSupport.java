package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.AIServiceConfig;
import ai.fabric.config.OrchestrationProperties;
import ai.fabric.dto.AdvancedRAGRequest;
import ai.fabric.dto.AdvancedRAGResponse;
import ai.fabric.dto.Intent;
import ai.fabric.dto.RAGResponse;
import ai.fabric.intent.orchestration.OrchestrationAuthContextResolver;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationContextMetadataKeys;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import ai.fabric.spi.AdvancedRAGProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

final class AdvancedRagSupport {

    private static final String CONTEXT_METADATA_KEY_ADVANCED_EXPANSION_LEVEL = "advancedRagExpansionLevel";
    private static final String CONTEXT_METADATA_KEY_ADVANCED_RERANKING_STRATEGY = "advancedRagRerankingStrategy";
    private static final String CONTEXT_METADATA_KEY_ADVANCED_CONTEXT_OPTIMIZATION_LEVEL = "advancedRagContextOptimizationLevel";

    private static final int ADVANCED_QUERY_LENGTH_THRESHOLD = 50;
    private static final int ADVANCED_QUERY_WORD_THRESHOLD = 8;

    private final ObjectProvider<AdvancedRAGProvider> advancedRagProvider;
    private final AIServiceConfig aiServiceConfig;
    private final OrchestrationProperties orchestrationProperties;

    AdvancedRagSupport(ObjectProvider<AdvancedRAGProvider> advancedRagProvider,
                       AIServiceConfig aiServiceConfig,
                       OrchestrationProperties orchestrationProperties) {
        this.advancedRagProvider = advancedRagProvider;
        this.aiServiceConfig = aiServiceConfig;
        this.orchestrationProperties = orchestrationProperties;
    }

    AdvancedRAGProvider provider() {
        return advancedRagProvider != null ? advancedRagProvider.getIfAvailable() : null;
    }

    boolean isDeterministicInformationMode(PipelineContext pipelineContext) {
        OrchestrationPolicy policy = pipelineContext != null ? pipelineContext.getOrchestrationPolicy() : null;
        if (policy != null && policy.informationMode() != null) {
            return policy.informationMode() == OrchestrationProperties.InformationMode.DETERMINISTIC_RAG_GENERATE;
        }
        return orchestrationProperties != null
            && orchestrationProperties.getInformationMode() == OrchestrationProperties.InformationMode.DETERMINISTIC_RAG_GENERATE;
    }

    boolean shouldUseAdvancedRag(Intent intent,
                                 boolean needsGeneration,
                                 String query,
                                 OrchestrationContext context,
                                 PipelineContext pipelineContext) {
        if (provider() == null) {
            return false;
        }

        AIServiceConfig.FeatureFlags features = aiServiceConfig != null ? aiServiceConfig.getFeatures() : null;
        if (features != null && Boolean.FALSE.equals(features.getEnableAdvancedRAG())) {
            return false;
        }

        Map<String, Object> ctxMetadata = context != null ? context.getMetadata() : null;
        Object advancedOverride = ctxMetadata != null ? ctxMetadata.get(OrchestrationContextMetadataKeys.USE_ADVANCED_RAG) : null;
        if (advancedOverride instanceof Boolean bool) {
            return bool;
        }

        if (isDeterministicInformationMode(pipelineContext)) {
            return false;
        }

        if (!needsGeneration) {
            return false;
        }

        Boolean llmDecision = intent != null ? intent.getNeedsAdvancedRAG() : null;
        if (llmDecision != null) {
            return llmDecision;
        }

        boolean autoEnable = features != null
            && Boolean.TRUE.equals(features.getAutoEnableAdvancedRAGForComplexQueries());
        return autoEnable && isComplexQuery(query);
    }

    AdvancedRAGRequest buildAdvancedRagRequest(Intent intent,
                                               OrchestrationContext context,
                                               String query,
                                               Map<String, Object> metadata,
                                               PipelineContext pipelineContext,
                                               int defaultLimit,
                                               double defaultThreshold) {
        Map<String, Object> ctxMetadata = context != null ? context.getMetadata() : null;

        AdvancedRAGRequest.AdvancedRAGRequestBuilder builder = AdvancedRAGRequest.builder()
            .query(query)
            .entityType(intent != null ? intent.getVectorSpace() : null)
            .maxResults(defaultLimit)
            .maxDocuments(defaultLimit)
            .similarityThreshold(resolveSimilarityThreshold(
                pipelineContext != null && pipelineContext.getOrchestrationPolicy() != null
                    ? pipelineContext.getOrchestrationPolicy().ragBudgets()
                    : null,
                defaultThreshold
            ))
            .authContext(context != null ? OrchestrationAuthContextResolver.from(context) : null)
            .metadata(metadata != null ? Collections.unmodifiableMap(new LinkedHashMap<>(metadata)) : Map.of());

        String pinnedTargetsContext = RagContextSupport.prependPinnedTargetsContext(null, pipelineContext);
        if (StringUtils.hasText(pinnedTargetsContext)) {
            builder.context(pinnedTargetsContext);
        }

        Integer expansionLevel = readInteger(ctxMetadata, CONTEXT_METADATA_KEY_ADVANCED_EXPANSION_LEVEL);
        if (expansionLevel != null) {
            builder.expansionLevel(expansionLevel);
        }

        String reranking = readString(ctxMetadata, CONTEXT_METADATA_KEY_ADVANCED_RERANKING_STRATEGY);
        if (StringUtils.hasText(reranking)) {
            builder.rerankingStrategy(reranking);
        }

        String optimization = readString(ctxMetadata, CONTEXT_METADATA_KEY_ADVANCED_CONTEXT_OPTIMIZATION_LEVEL);
        if (StringUtils.hasText(optimization)) {
            builder.contextOptimizationLevel(optimization);
        }

        return builder.build();
    }

    double resolveSimilarityThreshold(OrchestrationPolicy.RagBudgets ragBudgets, double defaultThreshold) {
        if (ragBudgets != null && ragBudgets.similarityThreshold() != null) {
            return ragBudgets.similarityThreshold();
        }
        return defaultThreshold;
    }

    boolean isComplexQuery(String query) {
        if (!StringUtils.hasText(query)) {
            return false;
        }
        if (query.length() >= ADVANCED_QUERY_LENGTH_THRESHOLD) {
            return true;
        }
        if (query.contains("?")) {
            return true;
        }
        int words = query.trim().split("\\s+").length;
        return words >= ADVANCED_QUERY_WORD_THRESHOLD;
    }

    Integer readInteger(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null) {
            return null;
        }
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String str && StringUtils.hasText(str)) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    String readString(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null) {
            return null;
        }
        Object value = metadata.get(key);
        if (value instanceof String str) {
            String trimmed = str.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
        return value != null ? value.toString() : null;
    }

    List<RAGResponse.RAGDocument> convertToRagDocuments(List<AdvancedRAGResponse.RAGDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        return documents.stream()
            .filter(java.util.Objects::nonNull)
            .map(doc -> RAGResponse.RAGDocument.builder()
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
                .wordCount(doc.getWordCount())
                .language(doc.getLanguage())
                .build())
            .collect(Collectors.toList());
    }

    RAGResponse convertToRagResponse(AdvancedRAGResponse advanced,
                                     List<RAGResponse.RAGDocument> documents,
                                     String originalQuery,
                                     String entityType) {
        return RAGResponse.builder()
            .documents(documents != null ? documents : List.of())
            .context(advanced != null ? advanced.getContext() : null)
            .totalDocuments(advanced != null ? advanced.getTotalDocuments() : null)
            .usedDocuments(advanced != null ? advanced.getUsedDocuments() : null)
            .relevanceScores(advanced != null ? advanced.getRelevanceScores() : null)
            .confidenceScore(advanced != null ? advanced.getConfidenceScore() : null)
            .processingTimeMs(advanced != null ? advanced.getProcessingTimeMs() : null)
            .requestId(advanced != null ? advanced.getRequestId() : null)
            .originalQuery(originalQuery)
            .entityType(entityType)
            .metadata(advanced != null ? advanced.getMetadata() : null)
            .timestamp(advanced != null ? advanced.getTimestamp() : null)
            .success(advanced != null ? advanced.getSuccess() : null)
            .errorMessage(advanced != null ? advanced.getErrorMessage() : null)
            .build();
    }
}
