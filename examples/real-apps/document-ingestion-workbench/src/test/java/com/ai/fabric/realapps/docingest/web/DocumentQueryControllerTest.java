package com.ai.fabric.realapps.docingest.web;

import com.ai.fabric.realapps.docingest.service.DocumentIngestionService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentQueryControllerTest {

    private final DocumentIngestionService service =
        mock(DocumentIngestionService.class);
    private final DocumentQueryController controller =
        new DocumentQueryController(service);

    @Test
    void delegatesTenantScopedEvidenceQuery() {
        var response = new DocumentIngestionService.QueryResult(
            "refund window",
            "tenant-a",
            0,
            List.of(),
            2L,
            "smoke-embedding"
        );
        when(service.query("refund window", "tenant-a", 5))
            .thenReturn(response);

        assertThat(controller.query("refund window", "tenant-a", 5))
            .isSameAs(response);
        verify(service).query("refund window", "tenant-a", 5);
    }
}
