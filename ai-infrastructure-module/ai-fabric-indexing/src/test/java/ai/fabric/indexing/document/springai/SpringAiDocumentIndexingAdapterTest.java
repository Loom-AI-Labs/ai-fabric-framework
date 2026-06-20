package ai.fabric.indexing.document.springai;

import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.dto.AIEntityConfig;
import ai.fabric.entity.IndexingQueueEntry;
import ai.fabric.indexing.IndexingOperation;
import ai.fabric.indexing.IndexingRequest;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.indexing.queue.IndexingQueueService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;

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

    private static final LocalDateTime SCHEDULED_FOR = LocalDateTime.of(2026, 6, 20, 9, 30);

    private ObjectMapper objectMapper;
    private IndexingQueueService queueService;
    private AIEntityConfigurationLoader configurationLoader;
    private SpringAiDocumentIndexingAdapter adapter;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        queueService = mock(IndexingQueueService.class);
        configurationLoader = mock(AIEntityConfigurationLoader.class);
        adapter = new SpringAiDocumentIndexingAdapter(objectMapper, queueService, configurationLoader);

        when(configurationLoader.getEntityConfig("kb")).thenReturn(AIEntityConfig.builder()
            .entityType("kb")
            .indexable(true)
            .build());
    }

    @Test
    void convertsTrustedSpringAiDocumentsIntoStableIndexingRequests() throws Exception {
        Document document = Document.builder()
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

        SpringAiDocumentIndexingOptions options = SpringAiDocumentIndexingOptions.builder()
            .entityType("kb")
            .sourceId("policy-handbook")
            .sourceName("Policy Handbook")
            .strategy(IndexingStrategy.BATCH)
            .operation(IndexingOperation.UPDATE)
            .splitWithTokenTextSplitter(false)
            .metadata("release", "2026.06")
            .metadata("optional", null)
            .scheduledFor(SCHEDULED_FOR)
            .maxRetries(9)
            .build();

        List<IndexingRequest> first = adapter.toIndexingRequests(List.of(document), options);
        List<IndexingRequest> second = adapter.toIndexingRequests(List.of(document), options);

        assertThat(first).hasSize(1);
        IndexingRequest request = first.getFirst();
        assertThat(request.entityType()).isEqualTo("kb");
        assertThat(request.entityId()).startsWith("springai-");
        assertThat(request.entityId()).isEqualTo(second.getFirst().entityId());
        assertThat(request.entityClassName()).isEqualTo(SpringAiIndexingDocument.class.getName());
        assertThat(request.operation()).isEqualTo(IndexingOperation.UPDATE);
        assertThat(request.strategy()).isEqualTo(IndexingStrategy.BATCH);
        assertThat(request.scheduledFor()).isEqualTo(SCHEDULED_FOR);
        assertThat(request.maxRetries()).isEqualTo(9);

        SpringAiIndexingDocument payload = objectMapper.readValue(
            request.payload(),
            SpringAiIndexingDocument.class
        );
        assertThat(payload.getId()).isEqualTo(request.entityId());
        assertThat(payload.getContent()).isEqualTo("Refunds are available within 30 days.");
        assertThat(payload.getSourceId()).isEqualTo("policy-handbook");
        assertThat(payload.getSourceName()).isEqualTo("Policy Handbook");
        assertThat(payload.getDocumentId()).isEqualTo("refund-policy");
        assertThat(payload.getChunkIndex()).isZero();
        assertThat(payload.getChunkCount()).isEqualTo(1);
        assertThat(payload.getContentFingerprint()).hasSize(64);
        assertThat(payload.getMetadata())
            .containsEntry("tenant", "acme")
            .containsEntry("rank", 7)
            .containsEntry("release", "2026.06")
            .doesNotContainKeys("secretToken", "sourceUrl", "nested", "optional");
        assertThat(payload.getMetadataDroppedCount()).isGreaterThanOrEqualTo(4);
    }

    @Test
    void appliesCustomTransformersBeforeCreatingQueueRequests() throws Exception {
        SpringAiDocumentIndexingOptions options = SpringAiDocumentIndexingOptions.builder()
            .entityType("kb")
            .sourceId("catalog")
            .splitWithTokenTextSplitter(false)
            .addTransformer(documents -> List.of(
                Document.builder().id("doc-a").text("Chunk A").metadata("section", "a").build(),
                Document.builder().id("doc-b").text("Chunk B").metadata("section", "b").build()
            ))
            .scheduledFor(SCHEDULED_FOR)
            .build();

        List<IndexingRequest> requests = adapter.toIndexingRequests(
            List.of(Document.builder().id("ignored").text("ignored").build()),
            options
        );

        assertThat(requests).hasSize(2);
        SpringAiIndexingDocument firstPayload = objectMapper.readValue(
            requests.get(0).payload(),
            SpringAiIndexingDocument.class
        );
        SpringAiIndexingDocument secondPayload = objectMapper.readValue(
            requests.get(1).payload(),
            SpringAiIndexingDocument.class
        );
        assertThat(firstPayload.getContent()).isEqualTo("Chunk A");
        assertThat(firstPayload.getChunkIndex()).isZero();
        assertThat(firstPayload.getChunkCount()).isEqualTo(2);
        assertThat(firstPayload.getMetadata()).containsEntry("section", "a");
        assertThat(secondPayload.getContent()).isEqualTo("Chunk B");
        assertThat(secondPayload.getChunkIndex()).isEqualTo(1);
        assertThat(secondPayload.getChunkCount()).isEqualTo(2);
        assertThat(secondPayload.getMetadata()).containsEntry("section", "b");
    }

    @Test
    void enqueuesPreparedRequestsThroughIndexingQueueService() {
        SpringAiDocumentIndexingOptions options = SpringAiDocumentIndexingOptions.builder()
            .entityType("kb")
            .sourceId("handbook")
            .splitWithTokenTextSplitter(false)
            .scheduledFor(SCHEDULED_FOR)
            .build();
        when(queueService.enqueue(any(IndexingRequest.class))).thenAnswer(invocation -> {
            IndexingRequest request = invocation.getArgument(0);
            IndexingQueueEntry entry = new IndexingQueueEntry();
            entry.setEntityType(request.entityType());
            entry.setEntityId(request.entityId());
            entry.setPayload(request.payload());
            return entry;
        });

        List<IndexingQueueEntry> entries = adapter.enqueue(
            List.of(Document.builder().id("doc").text("Ready to index").build()),
            options
        );

        ArgumentCaptor<IndexingRequest> requestCaptor = ArgumentCaptor.forClass(IndexingRequest.class);
        verify(queueService).enqueue(requestCaptor.capture());
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().getEntityId()).isEqualTo(requestCaptor.getValue().entityId());
    }

    @Test
    void rejectsUnknownVectorSpacesBeforeEnqueueing() {
        when(configurationLoader.getEntityConfig("missing")).thenReturn(null);
        SpringAiDocumentIndexingOptions options = SpringAiDocumentIndexingOptions.builder()
            .entityType("missing")
            .sourceId("source")
            .splitWithTokenTextSplitter(false)
            .build();

        assertThatThrownBy(() -> adapter.enqueue(
            List.of(Document.builder().id("doc").text("content").build()),
            options
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown AI Fabric entityType");

        verifyNoInteractions(queueService);
    }

    @Test
    void rejectsNonIndexableVectorSpacesBeforeEnqueueing() {
        when(configurationLoader.getEntityConfig("archive")).thenReturn(AIEntityConfig.builder()
            .entityType("archive")
            .indexable(false)
            .build());
        SpringAiDocumentIndexingOptions options = SpringAiDocumentIndexingOptions.builder()
            .entityType("archive")
            .sourceId("source")
            .splitWithTokenTextSplitter(false)
            .build();

        assertThatThrownBy(() -> adapter.enqueue(
            List.of(Document.builder().id("doc").text("content").build()),
            options
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not indexable");

        verifyNoInteractions(queueService);
    }

    @Test
    void rejectsOversizedChunksInsteadOfTruncatingSilently() {
        SpringAiDocumentIndexingOptions options = SpringAiDocumentIndexingOptions.builder()
            .entityType("kb")
            .sourceId("source")
            .splitWithTokenTextSplitter(false)
            .maxContentLength(8)
            .build();

        assertThatThrownBy(() -> adapter.toIndexingRequests(
            List.of(Document.builder().id("doc").text("content that is too long").build()),
            options
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxContentLength=8");
    }

    @Test
    void rejectsChunkCountsAboveConfiguredLimit() {
        SpringAiDocumentIndexingOptions options = SpringAiDocumentIndexingOptions.builder()
            .entityType("kb")
            .sourceId("source")
            .splitWithTokenTextSplitter(false)
            .maxChunks(1)
            .build();

        assertThatThrownBy(() -> adapter.toIndexingRequests(
            List.of(
                Document.builder().id("doc-a").text("A").build(),
                Document.builder().id("doc-b").text("B").build()
            ),
            options
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxChunks=1");
    }
}
