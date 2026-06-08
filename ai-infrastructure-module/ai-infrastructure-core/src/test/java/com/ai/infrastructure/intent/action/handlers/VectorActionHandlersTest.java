package com.ai.infrastructure.intent.action.handlers;

import com.ai.infrastructure.intent.action.ActionContext;
import com.ai.infrastructure.intent.action.ActionResult;
import com.ai.infrastructure.intent.orchestration.OrchestrationContext;
import com.ai.infrastructure.rag.VectorDatabaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VectorActionHandlersTest {

    private VectorDatabaseService vectorDatabaseService;
    private ClearVectorIndexActionHandler clearHandler;
    private RemoveVectorActionHandler removeHandler;

    @BeforeEach
    void setUp() {
        vectorDatabaseService = Mockito.mock(VectorDatabaseService.class);
        clearHandler = new ClearVectorIndexActionHandler(vectorDatabaseService);
        removeHandler = new RemoveVectorActionHandler(vectorDatabaseService);
    }

    @Test
    void clearHandlerShouldReportRemovedCount() {
        when(vectorDatabaseService.clearVectors()).thenReturn(3L);

        ActionResult result = clearHandler.execute(new ActionContext(OrchestrationContext.forUser("user"), null));

        verify(vectorDatabaseService).clearVectors();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).contains("Cleared 3 vectors");
        assertThat(result.getData().toMap().get("removed")).isEqualTo(3L);
    }

    @Test
    void removeHandlerShouldCallVectorService() {
        when(vectorDatabaseService.removeVector("doc", "123")).thenReturn(true);

        ActionResult result = removeHandler.execute("doc", "123", new ActionContext(OrchestrationContext.forUser("user"), null));

        verify(vectorDatabaseService).removeVector("doc", "123");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("Vector removed.");
    }

    @Test
    void removeHandlerShouldBuildConfirmationMessage() {
        String message = removeHandler.confirm("doc", "123");
        assertThat(message).contains("doc:123");
    }
}
