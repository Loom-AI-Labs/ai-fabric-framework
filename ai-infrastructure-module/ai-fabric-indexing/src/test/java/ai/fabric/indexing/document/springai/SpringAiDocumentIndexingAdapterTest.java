package ai.fabric.indexing.document.springai;

import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.dto.AIEntityConfig;
import ai.fabric.dto.AIEntityIndexingPolicy;
import ai.fabric.entity.IndexingQueueEntry;
import ai.fabric.indexing.api.AIIndexWorkType;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.indexing.model.AIIndexDocument;
import ai.fabric.indexing.queue.IndexingQueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SpringAiDocumentIndexingAdapterTest {

    private static final LocalDateTime SCHEDULED_FOR =
        LocalDateTime.of(2026, 7, 24, 9, 30);

    private IndexingQueueService queueService;
    private AIEntityConfigurationLoader configurationLoader;
    private SpringAiDocumentIndexingAdapter adapter;

    @BeforeEach
    void setUp() {
        queueService = mock(IndexingQueueService.class);
        configurationLoader = mock(AIEntityConfigurationLoader.class);
        adapter = new SpringAiDocumentIndexingAdapter(
            queueService,
            configurationLoader
        );
        when(configurationLoader.getEntityConfig("kb")).thenReturn(
            AIEntityConfig.builder()
                .entityType("kb")
                .indexing(AIEntityIndexingPolicy.builder()
                    .enabled(true)
                    .build())
                .build()
        );
    }

    @Test
    void createsStableClassFreeDocumentsAndPropagatesSourceVersion() {
        Document source = Document.builder()
            .id("refund-policy")
            .text("Refunds are available within 30 days.")
            .metadata(Map.of(
                "tenant", "acme",
                "rank", 7,
                "secretToken", "do-not-store",
                "sourceUrl", "https://example.test/private",
                "nested", Map.of("unsafe", "shape")
            ))
            .build();
        SpringAiDocumentIndexingOptions options =
            SpringAiDocumentIndexingOptions.builder()
                .entityType("kb")
                .sourceId("policy-handbook")
                .sourceName("Policy Handbook")
                .strategy(IndexingStrategy.BATCH)
                .operation(AIProcessOperation.UPDATE)
                .splitWithTokenTextSplitter(false)
                .metadata("release", "2026.07")
                .metadata("sourceVersion", 7)
                .scheduledFor(SCHEDULED_FOR)
                .build();

        List<AIIndexDocument> first =
            adapter.toIndexDocuments(List.of(source), options);
        List<AIIndexDocument> second =
            adapter.toIndexDocuments(List.of(source), options);

        assertThat(first).hasSize(1);
        AIIndexDocument document = first.getFirst();
        assertThat(document.workType()).isEqualTo(AIIndexWorkType.UPSERT);
        assertThat(document.sourceOperation())
            .isEqualTo(AIProcessOperation.UPDATE);
        assertThat(document.entityId()).startsWith("springai-")
            .isEqualTo(second.getFirst().entityId());
        assertThat(document.semanticSearchText())
            .isEqualTo("Refunds are available within 30 days.");
        assertThat(document.sourceVersion()).isEqualTo(7L);
        assertThat(document.vectorMetadata())
            .containsEntry("tenant", "acme")
            .containsEntry("rank", 7)
            .containsEntry("release", "2026.07")
            .containsEntry("_springAiSourceId", "policy-handbook")
            .doesNotContainKeys(
                "secretToken",
                "sourceUrl",
                "nested"
            );
        assertThat(document.descriptorHash()).hasSize(64);
    }

    @Test
    void enqueuesTheApprovedDocumentWithRequestedScheduling() {
        SpringAiDocumentIndexingOptions options =
            SpringAiDocumentIndexingOptions.builder()
                .entityType("kb")
                .sourceId("handbook")
                .splitWithTokenTextSplitter(false)
                .strategy(IndexingStrategy.BATCH)
                .scheduledFor(SCHEDULED_FOR)
                .build();
        when(queueService.enqueue(
            any(AIIndexDocument.class),
            any(IndexingStrategy.class),
            any(LocalDateTime.class)
        )).thenReturn(new IndexingQueueEntry());

        adapter.enqueue(
            List.of(Document.builder()
                .id("doc")
                .text("Ready to index")
                .build()),
            options
        );

        ArgumentCaptor<AIIndexDocument> document =
            ArgumentCaptor.forClass(AIIndexDocument.class);
        verify(queueService).enqueue(
            document.capture(),
            org.mockito.ArgumentMatchers.eq(IndexingStrategy.BATCH),
            org.mockito.ArgumentMatchers.eq(SCHEDULED_FOR)
        );
        assertThat(document.getValue().entityType()).isEqualTo("kb");
    }

    @Test
    void createsPayloadFreeDeleteDocumentsWithTheSameProjectionHash() {
        SpringAiDocumentIndexingOptions options =
            SpringAiDocumentIndexingOptions.builder()
                .entityType("kb")
                .sourceId("handbook")
                .splitWithTokenTextSplitter(false)
                .build();
        AIIndexDocument upsert = adapter.toIndexDocuments(
            List.of(Document.builder().id("doc").text("content").build()),
            options
        ).getFirst();

        AIIndexDocument delete = adapter.toDeleteDocument(
            "kb",
            upsert.entityId(),
            Instant.parse("2026-07-24T12:00:00Z")
        );

        assertThat(delete.workType()).isEqualTo(AIIndexWorkType.DELETE);
        assertThat(delete.sourceOperation()).isEqualTo(AIProcessOperation.DELETE);
        assertThat(delete.descriptorHash()).isEqualTo(upsert.descriptorHash());
        assertThat(delete.semanticSearchText()).isNull();
        assertThat(delete.vectorMetadata()).isEmpty();
    }

    @Test
    void rejectsUnknownOrDisabledVectorSpacesBeforeQueueing() {
        when(configurationLoader.getEntityConfig("missing")).thenReturn(null);
        when(configurationLoader.getEntityConfig("archive")).thenReturn(
            AIEntityConfig.builder()
                .entityType("archive")
                .indexing(AIEntityIndexingPolicy.builder()
                    .enabled(false)
                    .build())
                .build()
        );

        assertThatThrownBy(() -> adapter.enqueue(
            List.of(Document.builder().id("doc").text("content").build()),
            options("missing")
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown AI Fabric entityType");
        assertThatThrownBy(() -> adapter.enqueue(
            List.of(Document.builder().id("doc").text("content").build()),
            options("archive")
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not indexable");
        verifyNoInteractions(queueService);
    }

    @Test
    void rejectsOversizedChunksAndChunkCounts() {
        SpringAiDocumentIndexingOptions oversized =
            SpringAiDocumentIndexingOptions.builder()
                .entityType("kb")
                .sourceId("source")
                .splitWithTokenTextSplitter(false)
                .maxContentLength(8)
                .build();
        assertThatThrownBy(() -> adapter.toIndexDocuments(
            List.of(Document.builder()
                .id("doc")
                .text("content that is too long")
                .build()),
            oversized
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxContentLength=8");

        SpringAiDocumentIndexingOptions tooMany =
            SpringAiDocumentIndexingOptions.builder()
                .entityType("kb")
                .sourceId("source")
                .splitWithTokenTextSplitter(false)
                .maxChunks(1)
                .build();
        assertThatThrownBy(() -> adapter.toIndexDocuments(
            List.of(
                Document.builder().id("a").text("A").build(),
                Document.builder().id("b").text("B").build()
            ),
            tooMany
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxChunks=1");
    }

    private SpringAiDocumentIndexingOptions options(String entityType) {
        return SpringAiDocumentIndexingOptions.builder()
            .entityType(entityType)
            .sourceId("source")
            .splitWithTokenTextSplitter(false)
            .build();
    }
}
