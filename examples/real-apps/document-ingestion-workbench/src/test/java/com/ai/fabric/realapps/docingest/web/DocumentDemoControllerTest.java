package com.ai.fabric.realapps.docingest.web;

import com.ai.fabric.realapps.docingest.domain.DocumentSource;
import com.ai.fabric.realapps.docingest.service.DocumentIngestionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentDemoControllerTest {

    private final DocumentIngestionService service =
        mock(DocumentIngestionService.class);
    private final DocumentDemoController controller =
        new DocumentDemoController(service);

    @Test
    void createsIsolatedSessionAndIndexesRealSeedSources() {
        AtomicInteger sequence = new AtomicInteger();
        when(service.createSource(any())).thenAnswer(invocation -> summary(
            "doc-" + sequence.incrementAndGet(),
            invocation.getArgument(
                0,
                DocumentIngestionService.CreateSourceCommand.class
            ).tenantId()
        ));
        when(service.listSources(any())).thenReturn(List.of());

        var result = controller.createSession();

        assertThat(result.sessionId()).matches("docs-demo-[a-f0-9]{32}");
        assertThat(result.tenantId()).isEqualTo("tenant-" + result.sessionId());
        ArgumentCaptor<DocumentIngestionService.CreateSourceCommand> commands =
            ArgumentCaptor.forClass(
                DocumentIngestionService.CreateSourceCommand.class
            );
        verify(service, times(3)).createSource(commands.capture());
        assertThat(commands.getAllValues()).allSatisfy(command ->
            assertThat(command.tenantId()).isEqualTo(result.tenantId())
        );
        verify(service).index("doc-1");
        verify(service).index("doc-2");
        verify(service).index("doc-3");
    }

    @Test
    void derivesTenantFromSessionInsteadOfAcceptingItFromTheBrowser() {
        String sessionId = "docs-demo-0123456789abcdef0123456789abcdef";
        when(service.createSource(any())).thenReturn(summary(
            "doc-upload",
            "tenant-" + sessionId
        ));

        controller.createSource(
            sessionId,
            new MockMultipartFile(
                "file",
                "guide.txt",
                "text/plain",
                "Safe guide".getBytes(StandardCharsets.UTF_8)
            ),
            "Guide",
            "internal"
        );

        ArgumentCaptor<DocumentIngestionService.CreateSourceCommand> command =
            ArgumentCaptor.forClass(
                DocumentIngestionService.CreateSourceCommand.class
            );
        verify(service).createSource(command.capture());
        assertThat(command.getValue().tenantId())
            .isEqualTo("tenant-" + sessionId);
    }

    @Test
    void rejectsSourceOwnedByAnotherSession() {
        String sessionId = "docs-demo-0123456789abcdef0123456789abcdef";
        when(service.status("doc-other")).thenReturn(
            new DocumentIngestionService.LifecycleResult(
                summary("doc-other", "tenant-docs-demo-other"),
                List.of()
            )
        );

        assertThatThrownBy(() -> controller.preview(sessionId, "doc-other"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not belong");
    }

    @Test
    void rejectsMalformedSessionIdentifiers() {
        assertThatThrownBy(() -> controller.session("tenant-from-browser"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid demo session");
    }

    private static DocumentIngestionService.SourceSummary summary(
        String id,
        String tenantId
    ) {
        return new DocumentIngestionService.SourceSummary(
            id,
            "Document",
            "document.txt",
            tenantId,
            "internal",
            "hash",
            1,
            null,
            DocumentSource.Status.PENDING,
            0,
            null,
            null
        );
    }
}
