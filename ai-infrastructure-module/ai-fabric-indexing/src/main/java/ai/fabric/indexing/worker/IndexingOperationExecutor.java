package ai.fabric.indexing.worker;

import ai.fabric.core.AIEmbeddingService;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.indexing.api.AIIndexAnalysisHandler;
import ai.fabric.indexing.model.AIIndexDocument;
import ai.fabric.service.VectorManagementService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Executes exactly one provider/vector operation for each durable work item.
 */
public class IndexingOperationExecutor {

    private final AIEmbeddingService embeddingService;
    private final VectorManagementService vectorManagementService;
    private final ObjectProvider<AIIndexAnalysisHandler> analysisHandlerProvider;
    private final ObjectMapper objectMapper;

    public IndexingOperationExecutor(
        AIEmbeddingService embeddingService,
        VectorManagementService vectorManagementService,
        ObjectProvider<AIIndexAnalysisHandler> analysisHandlerProvider,
        ObjectMapper objectMapper
    ) {
        this.embeddingService = Objects.requireNonNull(embeddingService);
        this.vectorManagementService = Objects.requireNonNull(vectorManagementService);
        this.analysisHandlerProvider = Objects.requireNonNull(analysisHandlerProvider);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    public String execute(AIIndexDocument document, long workId) {
        return switch (document.workType()) {
            case UPSERT -> {
                upsert(document, workId);
                yield null;
            }
            case DELETE -> {
                delete(document);
                yield null;
            }
            case ANALYZE -> analyze(document);
        };
    }

    private void upsert(AIIndexDocument document, long workId) {
        List<Double> embedding = generateEmbedding(document);
        Map<String, Object> metadata = new LinkedHashMap<>(document.vectorMetadata());
        metadata.put("_aifabricWorkId", workId);
        if (document.sourceVersion() != null) {
            metadata.put("_aifabricSourceVersion", document.sourceVersion());
        }

        String content = StringUtils.hasText(document.ragContextText())
            ? document.ragContextText()
            : document.semanticSearchText();
        try {
            String vectorId = vectorManagementService.storeVector(
                document.entityType(),
                document.entityId(),
                content,
                embedding,
                metadata
            );
            if (!StringUtils.hasText(vectorId)) {
                throw new IndexingExecutionException("VECTOR_UPSERT_EMPTY_ID");
            }
        } catch (IndexingExecutionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexingExecutionException("VECTOR_UPSERT_FAILED", exception);
        }
    }

    private List<Double> generateEmbedding(AIIndexDocument document) {
        try {
            AIEmbeddingResponse response = embeddingService.generateEmbedding(
                AIEmbeddingRequest.builder()
                    .text(document.semanticSearchText())
                    .entityType(document.entityType())
                    .entityId(document.entityId())
                    .build()
            );
            List<Double> embedding = response == null ? null : response.getEmbedding();
            if (embedding == null || embedding.isEmpty()) {
                throw new IndexingExecutionException("EMBEDDING_EMPTY");
            }
            List<Double> copy = new ArrayList<>(embedding.size());
            for (Double value : embedding) {
                if (value == null || !Double.isFinite(value)) {
                    throw new IndexingExecutionException("EMBEDDING_INVALID_VALUE");
                }
                copy.add(value);
            }
            return List.copyOf(copy);
        } catch (IndexingExecutionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexingExecutionException("EMBEDDING_PROVIDER_FAILED", exception);
        }
    }

    private void delete(AIIndexDocument document) {
        try {
            // Missing vectors are an idempotent success.
            vectorManagementService.removeVector(
                document.entityType(),
                document.entityId()
            );
        } catch (RuntimeException exception) {
            throw new IndexingExecutionException("VECTOR_DELETE_FAILED", exception);
        }
    }

    private String analyze(AIIndexDocument document) {
        AIIndexAnalysisHandler handler = analysisHandlerProvider.getIfAvailable();
        if (handler == null) {
            throw new IndexingExecutionException("ANALYSIS_HANDLER_UNAVAILABLE");
        }
        try {
            String analysis = handler.analyze(document);
            if (!StringUtils.hasText(analysis)) {
                throw new IndexingExecutionException("ANALYSIS_EMPTY");
            }
            return objectMapper.writeValueAsString(Map.of("analysis", analysis));
        } catch (IndexingExecutionException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw new IndexingExecutionException("ANALYSIS_SERIALIZATION_FAILED", exception);
        } catch (RuntimeException exception) {
            throw new IndexingExecutionException("ANALYSIS_PROVIDER_FAILED", exception);
        }
    }
}
