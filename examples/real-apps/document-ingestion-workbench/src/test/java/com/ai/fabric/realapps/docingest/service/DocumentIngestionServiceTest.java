package com.ai.fabric.realapps.docingest.service;

import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.dto.AIEntityConfig;
import ai.fabric.dto.AIEntityIndexingPolicy;
import ai.fabric.entity.IndexingQueueEntry;
import ai.fabric.indexing.api.AIIndexWorkType;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.indexing.document.springai.SpringAiDocumentIndexingAdapter;
import ai.fabric.indexing.document.springai.SpringAiDocumentReaderFactory;
import ai.fabric.indexing.model.AIIndexDocument;
import ai.fabric.indexing.queue.IndexingQueueService;
import com.ai.fabric.realapps.docingest.domain.DocumentChunkManifest;
import com.ai.fabric.realapps.docingest.domain.DocumentSource;
import com.ai.fabric.realapps.docingest.repo.DocumentChunkManifestRepository;
import com.ai.fabric.realapps.docingest.repo.DocumentSourceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DataJpaTest
@EntityScan(basePackageClasses = DocumentSource.class)
@EnableJpaRepositories(basePackageClasses = DocumentSourceRepository.class)
class DocumentIngestionServiceTest {

    @TempDir
    Path trustedRoot;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final IndexingQueueService queueService = mock(IndexingQueueService.class);
    private final AIEntityConfigurationLoader configurationLoader = mock(AIEntityConfigurationLoader.class);
    private final List<AIIndexDocument> queuedDocuments = new ArrayList<>();

    @jakarta.annotation.Resource
    private DocumentSourceRepository sourceRepository;

    @jakarta.annotation.Resource
    private DocumentChunkManifestRepository chunkManifestRepository;

    private DocumentIngestionService service;

    @BeforeEach
    void setUp() {
        when(configurationLoader.getEntityConfig("kb")).thenReturn(AIEntityConfig.builder()
            .entityType("kb")
            .indexing(AIEntityIndexingPolicy.builder().enabled(true).build())
            .build());
        when(queueService.enqueue(
            any(AIIndexDocument.class),
            any(IndexingStrategy.class)
        )).thenAnswer(invocation -> {
            queuedDocuments.add(invocation.getArgument(0));
            return new IndexingQueueEntry();
        });

        service = new DocumentIngestionService(
            sourceRepository,
            chunkManifestRepository,
            new SpringAiDocumentReaderFactory(),
            new SpringAiDocumentIndexingAdapter(queueService, configurationLoader),
            queueService,
            objectMapper,
            trustedRoot.toString(),
            "kb"
        );
    }

    @Test
    void previewBuildsChunksWithoutQueueingIndexWork() {
        DocumentIngestionService.SourceSummary source = service.createSource(textCommand("Runbook", "runbook.txt",
            "Reset credentials and notify the account owner."));

        DocumentIngestionService.PreviewResult preview = service.preview(source.id());

        assertThat(preview.chunkCount()).isPositive();
        assertThat(preview.previewChunks()).isNotEmpty();
        assertThat(preview.previewChunks().getFirst().metadata())
            .containsEntry("sourceId", source.id())
            .containsEntry("tenantId", "default")
            .containsEntry("visibility", "internal");
        verifyNoInteractions(queueService);
    }

    @Test
    void indexQueuesChunksAndStoresManifest() {
        DocumentIngestionService.SourceSummary source = service.createSource(textCommand("Runbook", "runbook.txt",
            "Reset credentials and notify the account owner."));

        DocumentIngestionService.IndexResult result = service.index(source.id());

        assertThat(result.indexedChunks()).isPositive();
        assertThat(result.replacedChunks()).isZero();
        assertThat(result.source().status()).isEqualTo(DocumentSource.Status.INDEXED);
        assertThat(chunkManifestRepository.countBySourceId(source.id())).isEqualTo(result.indexedChunks());
        assertThat(queuedDocuments)
            .hasSize(result.indexedChunks())
            .allSatisfy(document -> {
                assertThat(document.workType()).isEqualTo(AIIndexWorkType.UPSERT);
                assertThat(document.sourceOperation())
                    .isEqualTo(AIProcessOperation.UPDATE);
            });
    }

    @Test
    void reindexDeletesOldChunksBeforeSavingReplacementManifest() {
        DocumentIngestionService.SourceSummary source = service.createSource(textCommand("Runbook", "runbook.txt",
            "Reset credentials and notify the account owner."));
        DocumentIngestionService.IndexResult firstIndex = service.index(source.id());
        List<String> firstChunkIds = firstIndex.indexedEntityIds();
        queuedDocuments.clear();

        service.replaceSource(source.id(), textCommand("Runbook v2", "runbook.txt",
            "Reset credentials, notify the account owner, and create a follow-up audit task."));
        DocumentIngestionService.IndexResult secondIndex = service.index(source.id());

        assertThat(secondIndex.replacedChunks()).isEqualTo(firstChunkIds.size());
        assertThat(secondIndex.source().sourceVersion()).isEqualTo(2);
        assertThat(queuedDocuments.stream().map(AIIndexDocument::workType))
            .startsWith(AIIndexWorkType.DELETE)
            .contains(AIIndexWorkType.UPSERT);
        assertThat(queuedDocuments.stream()
            .filter(document -> document.workType() == AIIndexWorkType.DELETE)
            .map(AIIndexDocument::entityId))
            .containsExactlyElementsOf(firstChunkIds);
        assertThat(chunkManifestRepository.findBySourceIdOrderByChunkIndexAsc(source.id()))
            .extracting(DocumentChunkManifest::getSourceVersion)
            .containsOnly(2);
    }

    @Test
    void deleteQueuesDeletesAndMarksSourceDeleted() {
        DocumentIngestionService.SourceSummary source = service.createSource(textCommand("Runbook", "runbook.txt",
            "Reset credentials and notify the account owner."));
        DocumentIngestionService.IndexResult indexed = service.index(source.id());
        queuedDocuments.clear();

        DocumentIngestionService.DeleteResult deleted = service.delete(source.id());

        assertThat(deleted.deletedChunks()).isEqualTo(indexed.indexedChunks());
        assertThat(deleted.source().status()).isEqualTo(DocumentSource.Status.DELETED);
        assertThat(chunkManifestRepository.countBySourceId(source.id())).isZero();
        assertThat(queuedDocuments)
            .hasSize(indexed.indexedChunks())
            .allSatisfy(document -> {
                assertThat(document.workType()).isEqualTo(AIIndexWorkType.DELETE);
                assertThat(document.sourceOperation())
                    .isEqualTo(AIProcessOperation.DELETE);
            });
    }

    @Test
    void unsupportedDocumentTypesFailClosed() {
        DocumentIngestionService.CreateSourceCommand command = new DocumentIngestionService.CreateSourceCommand(
            "Binary",
            "tool.exe",
            "application/octet-stream",
            "default",
            "internal",
            "not trusted text".getBytes(StandardCharsets.UTF_8)
        );

        assertThatThrownBy(() -> service.createSource(command))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported document type");
    }

    private static DocumentIngestionService.CreateSourceCommand textCommand(String title, String filename, String content) {
        return new DocumentIngestionService.CreateSourceCommand(
            title,
            filename,
            "text/plain",
            "default",
            "internal",
            content.getBytes(StandardCharsets.UTF_8)
        );
    }
}
