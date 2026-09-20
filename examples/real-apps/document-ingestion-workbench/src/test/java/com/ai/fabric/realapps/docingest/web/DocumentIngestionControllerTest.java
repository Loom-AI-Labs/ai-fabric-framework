package com.ai.fabric.realapps.docingest.web;

import com.ai.fabric.realapps.docingest.domain.DocumentSource;
import com.ai.fabric.realapps.docingest.service.DocumentIngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentIngestionControllerTest {

    private final DocumentIngestionService service =
        mock(DocumentIngestionService.class);
    private final DocumentIngestionController controller =
        new DocumentIngestionController(service);

    @Test
    void createsSourceFromMultipartFile() {
        var summary = summary(DocumentSource.Status.PENDING, 1, null, 0);
        when(service.createSource(any())).thenReturn(summary);
        MockMultipartFile file = file("Reset credentials");

        var result = controller.createSource(
            file,
            "Runbook",
            "tenant-a",
            "internal"
        );

        assertThat(result).isSameAs(summary);
        verify(service).createSource(any());
    }

    @Test
    void delegatesPreviewStatusIndexReplaceAndDelete() {
        var preview = new DocumentIngestionService.PreviewResult(
            "aiplan-1",
            summary(DocumentSource.Status.PENDING, 1, null, 0),
            1,
            1,
            17,
            false,
            List.of(),
            0,
            List.of()
        );
        var status = new DocumentIngestionService.LifecycleResult(
            summary(DocumentSource.Status.INDEXED, 1, 1L, 1),
            List.of()
        );
        var index = new DocumentIngestionService.IndexResult(
            summary(DocumentSource.Status.INDEXING, 1, null, 0),
            "aimanifest-1",
            1,
            0,
            List.of("11"),
            List.of("aidoc-1")
        );
        var delete = new DocumentIngestionService.DeleteResult(
            summary(DocumentSource.Status.DELETING, 1, 1L, 0),
            "aimanifest-1",
            1,
            List.of("12"),
            List.of("aidoc-1")
        );
        when(service.preview("doc-1")).thenReturn(preview);
        when(service.status("doc-1")).thenReturn(status);
        when(service.index("doc-1")).thenReturn(index);
        when(service.delete("doc-1")).thenReturn(delete);
        when(service.replaceSource(eq("doc-1"), any()))
            .thenReturn(summary(DocumentSource.Status.PENDING, 2, 1L, 1));

        assertThat(controller.preview("doc-1")).isSameAs(preview);
        assertThat(controller.status("doc-1")).isSameAs(status);
        assertThat(controller.index("doc-1")).isSameAs(index);
        assertThat(controller.replaceSource(
            "doc-1",
            file("Reset credentials again"),
            "Runbook v2",
            "tenant-a",
            "internal"
        ).sourceVersion()).isEqualTo(2);
        assertThat(controller.delete("doc-1")).isSameAs(delete);
    }

    private static MockMultipartFile file(String content) {
        return new MockMultipartFile(
            "file",
            "runbook.txt",
            "text/plain",
            content.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static DocumentIngestionService.SourceSummary summary(
        DocumentSource.Status status,
        long version,
        Long activeVersion,
        long activeChunks
    ) {
        return new DocumentIngestionService.SourceSummary(
            "doc-1",
            "Runbook",
            "runbook.txt",
            "tenant-a",
            "internal",
            "hash",
            version,
            activeVersion,
            status,
            activeChunks,
            null,
            null
        );
    }
}
