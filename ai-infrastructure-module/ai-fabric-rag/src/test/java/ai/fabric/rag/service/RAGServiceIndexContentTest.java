package ai.fabric.rag.service;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.core.AIEmbeddingService;
import ai.fabric.core.AISearchService;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.dto.VectorRecord;
import ai.fabric.rag.VectorDatabaseService;
import ai.fabric.vector.VectorDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RAGServiceIndexContentTest {

    @Mock
    private AIEmbeddingService embeddingService;
    @Mock
    private VectorDatabaseService vectorDatabaseService;
    @Mock
    private VectorDatabase vectorDatabase;
    @Mock
    private AISearchService searchService;

    private RAGService ragService;

    @BeforeEach
    void setUp() {
        when(embeddingService.generateEmbedding(any())).thenReturn(AIEmbeddingResponse.builder()
            .embedding(List.of(0.1, 0.2, 0.3))
            .model("test-embedding")
            .build());

        ragService = new RAGService(
            new AIProviderConfig(),
            embeddingService,
            vectorDatabaseService,
            vectorDatabase,
            searchService,
            null
        );
    }

    @Test
    void indexContentStoresVectorWhenEntityIsNew() {
        Map<String, Object> metadata = Map.of("category", "audio");
        when(vectorDatabaseService.getVectorByEntity("faq", "faq-1")).thenReturn(Optional.empty());

        ragService.indexContent("faq", "faq-1", "new headset guide", metadata);

        verify(vectorDatabaseService).storeVector(
            "faq",
            "faq-1",
            "new headset guide",
            List.of(0.1, 0.2, 0.3),
            metadata
        );
        verify(vectorDatabaseService, never()).updateVector(any(), any(), any(), any(), any(), any());
        verify(vectorDatabaseService, never()).removeVector(any(), any());
        assertEmbeddingRequest("faq", "faq-1", "new headset guide", metadata);
    }

    @Test
    void indexContentUpdatesExistingVectorInsteadOfDuplicating() {
        Map<String, Object> metadata = Map.of("category", "audio");
        when(vectorDatabaseService.getVectorByEntity("faq", "faq-1")).thenReturn(Optional.of(
            VectorRecord.builder().vectorId("vector-1").entityType("faq").entityId("faq-1").build()
        ));
        when(vectorDatabaseService.updateVector(
            eq("vector-1"),
            eq("faq"),
            eq("faq-1"),
            eq("updated headset guide"),
            eq(List.of(0.1, 0.2, 0.3)),
            eq(metadata)
        )).thenReturn(true);

        ragService.indexContent("faq", "faq-1", "updated headset guide", metadata);

        verify(vectorDatabaseService).updateVector(
            "vector-1",
            "faq",
            "faq-1",
            "updated headset guide",
            List.of(0.1, 0.2, 0.3),
            metadata
        );
        verify(vectorDatabaseService, never()).storeVector(any(), any(), any(), any(), any());
        verify(vectorDatabaseService, never()).removeVector(any(), any());
    }

    @Test
    void indexContentReplacesExistingEntityWhenUpdateMisses() {
        Map<String, Object> metadata = Map.of("category", "audio");
        when(vectorDatabaseService.getVectorByEntity("faq", "faq-1")).thenReturn(Optional.of(
            VectorRecord.builder().vectorId("vector-1").entityType("faq").entityId("faq-1").build()
        ));
        when(vectorDatabaseService.updateVector(
            eq("vector-1"),
            eq("faq"),
            eq("faq-1"),
            eq("replacement headset guide"),
            eq(List.of(0.1, 0.2, 0.3)),
            eq(metadata)
        )).thenReturn(false);

        ragService.indexContent("faq", "faq-1", "replacement headset guide", metadata);

        verify(vectorDatabaseService).removeVector("faq", "faq-1");
        verify(vectorDatabaseService).storeVector(
            "faq",
            "faq-1",
            "replacement headset guide",
            List.of(0.1, 0.2, 0.3),
            metadata
        );
    }

    @Test
    void indexContentRemovesVectorWithoutIdBeforeReindexing() {
        Map<String, Object> metadata = Map.of("category", "audio");
        when(vectorDatabaseService.getVectorByEntity("faq", "faq-1")).thenReturn(Optional.of(
            VectorRecord.builder().entityType("faq").entityId("faq-1").build()
        ));
        when(vectorDatabaseService.removeVector("faq", "faq-1")).thenReturn(true);

        ragService.indexContent("faq", "faq-1", "replacement headset guide", metadata);

        verify(vectorDatabaseService, never()).updateVector(any(), any(), any(), any(), any(), any());
        verify(vectorDatabaseService).removeVector("faq", "faq-1");
        verify(vectorDatabaseService).storeVector(
            "faq",
            "faq-1",
            "replacement headset guide",
            List.of(0.1, 0.2, 0.3),
            metadata
        );
    }

    private void assertEmbeddingRequest(String entityType,
                                        String entityId,
                                        String content,
                                        Map<String, Object> metadata) {
        ArgumentCaptor<AIEmbeddingRequest> captor = ArgumentCaptor.forClass(AIEmbeddingRequest.class);
        verify(embeddingService).generateEmbedding(captor.capture());
        assertThat(captor.getValue())
            .extracting(
                AIEmbeddingRequest::getEntityType,
                AIEmbeddingRequest::getEntityId,
                AIEmbeddingRequest::getText,
                AIEmbeddingRequest::getMetadata
            )
            .containsExactly(entityType, entityId, content, metadata.toString());
    }
}
