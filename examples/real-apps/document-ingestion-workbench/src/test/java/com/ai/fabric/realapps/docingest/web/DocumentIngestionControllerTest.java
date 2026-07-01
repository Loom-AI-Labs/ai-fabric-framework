package com.ai.fabric.realapps.docingest.web;

import com.ai.fabric.realapps.docingest.domain.DocumentSource;
import com.ai.fabric.realapps.docingest.service.DocumentIngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentIngestionControllerTest {

    private final DocumentIngestionService service = mock(DocumentIngestionService.class);
    private final DocumentIngestionController controller = new DocumentIngestionController(service);

    @Test
    void createsSourceFromMultipartFile() {
        DocumentIngestionService.SourceSummary summary = summary("doc-1", DocumentSource.Status.PENDING, 1, 0);
        when(service.createSource(any(DocumentIngestionService.CreateSourceCommand.class))).thenReturn(summary);

        MockMultipartFile file = new MockMultipartFile(
            "file",
            "runbook.txt",
            "text/plain",
            "Reset credentials".getBytes(StandardCharsets.UTF_8)
        );

        DocumentIngestionService.SourceSummary result = controller.createSource(file, "Runbook", "tenant-a", "internal");

        assertThat(result).isSameAs(summary);
        verify(service).createSource(any(DocumentIngestionService.CreateSourceCommand.class));
    }

    @Test
    void delegatesPreviewIndexReplaceAndDelete() {
        DocumentIngestionService.PreviewResult preview = new DocumentIngestionService.PreviewResult(
            summary("doc-1", DocumentSource.Status.PENDING, 1, 0),
            1,
            List.of(new DocumentIngestionService.ChunkPreview(
                "chunk-1",
                "doc-a",
                0,
                1,
                "Reset credentials",
                "hash",
                Map.of("tenantId", "tenant-a"),
                0
            )),
            0
        );
        DocumentIngestionService.IndexResult index = new DocumentIngestionService.IndexResult(
            summary("doc-1", DocumentSource.Status.INDEXED, 1, 1),
            1,
            0,
            List.of("chunk-1")
        );
        DocumentIngestionService.DeleteResult delete = new DocumentIngestionService.DeleteResult(
            summary("doc-1", DocumentSource.Status.DELETED, 1, 0),
            1,
            List.of("chunk-1")
        );
        when(service.preview("doc-1")).thenReturn(preview);
        when(service.index("doc-1")).thenReturn(index);
        when(service.delete("doc-1")).thenReturn(delete);
        when(service.replaceSource(eq("doc-1"), any(DocumentIngestionService.CreateSourceCommand.class)))
            .thenReturn(summary("doc-1", DocumentSource.Status.PENDING, 2, 1));

        MockMultipartFile file = new MockMultipartFile(
            "file",
            "runbook.txt",
            "text/plain",
            "Reset credentials again".getBytes(StandardCharsets.UTF_8)
        );

        assertThat(controller.preview("doc-1")).isSameAs(preview);
        assertThat(controller.index("doc-1")).isSameAs(index);
        assertThat(controller.replaceSource("doc-1", file, "Runbook v2", "tenant-a", "internal").sourceVersion())
            .isEqualTo(2);
        assertThat(controller.delete("doc-1")).isSameAs(delete);
    }

    private static DocumentIngestionService.SourceSummary summary(String id,
                                                                  DocumentSource.Status status,
                                                                  int version,
                                                                  long indexedChunks) {
        return new DocumentIngestionService.SourceSummary(
            id,
            "Runbook",
            "runbook.txt",
            "tenant-a",
            "internal",
            "hash",
            version,
            status,
            indexedChunks
        );
    }
}
