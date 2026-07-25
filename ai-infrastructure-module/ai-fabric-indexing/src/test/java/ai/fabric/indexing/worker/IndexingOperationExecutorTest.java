package ai.fabric.indexing.worker;

import ai.fabric.core.AIEmbeddingService;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.indexing.api.AIIndexAnalysisHandler;
import ai.fabric.indexing.api.AIIndexWorkType;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.model.AIIndexDocument;
import ai.fabric.service.VectorManagementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IndexingOperationExecutorTest {

    @Test
    void upsertGeneratesOneEmbeddingAndPerformsOneVectorStore() {
        AIEmbeddingService embeddings = mock(AIEmbeddingService.class);
        VectorManagementService vectors = mock(VectorManagementService.class);
        when(embeddings.generateEmbedding(any())).thenReturn(
            AIEmbeddingResponse.builder().embedding(List.of(0.1, 0.2)).build()
        );
        when(vectors.storeVector(
            eq("product"),
            eq("p-1"),
            eq("rag evidence"),
            anyList(),
            anyMap()
        )).thenReturn("vector-1");
        IndexingOperationExecutor executor = executor(embeddings, vectors, null);

        executor.execute(upsert(), 17L);

        verify(embeddings).generateEmbedding(any());
        verify(vectors).storeVector(
            eq("product"),
            eq("p-1"),
            eq("rag evidence"),
            eq(List.of(0.1, 0.2)),
            org.mockito.ArgumentMatchers.argThat(metadata ->
                metadata.get("_aifabricWorkId").equals(17L)
                    && metadata.get("tenantId").equals("tenant-a")
            )
        );
        verify(vectors, never()).updateVector(
            any(), any(), any(), anyList(), anyMap()
        );
    }

    @Test
    void deleteIsOneIdempotentVectorCallAndNeverEmbeds() {
        AIEmbeddingService embeddings = mock(AIEmbeddingService.class);
        VectorManagementService vectors = mock(VectorManagementService.class);
        when(vectors.removeVector("product", "p-1")).thenReturn(false);
        IndexingOperationExecutor executor = executor(embeddings, vectors, null);

        executor.execute(delete(), 18L);

        verify(vectors).removeVector("product", "p-1");
        verify(embeddings, never()).generateEmbedding(any());
    }

    @Test
    void providerFailureEscapesWithStableCodeForRetry() {
        AIEmbeddingService embeddings = mock(AIEmbeddingService.class);
        VectorManagementService vectors = mock(VectorManagementService.class);
        when(embeddings.generateEmbedding(any())).thenThrow(
            new IllegalStateException("provider echoed private data")
        );

        assertThatThrownBy(() -> executor(embeddings, vectors, null)
            .execute(upsert(), 19L))
            .isInstanceOfSatisfying(
                IndexingExecutionException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo("EMBEDDING_PROVIDER_FAILED")
            )
            .hasMessageNotContaining("private data");
    }

    @Test
    void analysisUsesExplicitHandlerAndReturnsStructuredResult() {
        AIEmbeddingService embeddings = mock(AIEmbeddingService.class);
        VectorManagementService vectors = mock(VectorManagementService.class);
        AIIndexAnalysisHandler handler = ignored -> "Account is healthy";

        String result = executor(embeddings, vectors, handler)
            .execute(analyze(), 20L);

        assertThat(result).isEqualTo("{\"analysis\":\"Account is healthy\"}");
        verify(embeddings, never()).generateEmbedding(any());
    }

    private IndexingOperationExecutor executor(
        AIEmbeddingService embeddingService,
        VectorManagementService vectorManagementService,
        AIIndexAnalysisHandler handler
    ) {
        return new IndexingOperationExecutor(
            embeddingService,
            vectorManagementService,
            provider(AIIndexAnalysisHandler.class, handler),
            new ObjectMapper()
        );
    }

    private <T> ObjectProvider<T> provider(Class<T> type, T value) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        if (value != null) {
            factory.addBean("value", value);
        }
        return factory.getBeanProvider(type);
    }

    private AIIndexDocument upsert() {
        return document(AIIndexWorkType.UPSERT, "semantic", "rag evidence");
    }

    private AIIndexDocument delete() {
        return document(AIIndexWorkType.DELETE, null, null);
    }

    private AIIndexDocument analyze() {
        return document(AIIndexWorkType.ANALYZE, "semantic", "rag evidence");
    }

    private AIIndexDocument document(
        AIIndexWorkType workType,
        String semantic,
        String rag
    ) {
        return new AIIndexDocument(
            1,
            "a".repeat(64),
            "product",
            "p-1",
            workType,
            workType == AIIndexWorkType.DELETE
                ? AIProcessOperation.DELETE
                : AIProcessOperation.UPDATE,
            semantic,
            rag,
            workType == AIIndexWorkType.DELETE
                ? Map.of()
                : Map.of("tenantId", "tenant-a"),
            Map.of(),
            Map.of(),
            workType == AIIndexWorkType.DELETE ? null : 3L,
            "",
            Instant.parse("2026-07-24T12:00:00Z")
        );
    }
}
