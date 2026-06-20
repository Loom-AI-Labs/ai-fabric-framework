package ai.fabric.intent.action.handlers;

import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.rag.VectorDatabaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
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
    void removeHandlerShouldRejectBlankReferencesWithoutCallingVectorService() {
        ActionResult result = removeHandler.execute(" ", null, new ActionContext(OrchestrationContext.forUser("user"), null));

        verify(vectorDatabaseService, never()).removeVector(Mockito.any(), Mockito.any());
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).isEqualTo("Entity type and entity id are required.");
        assertThat(result.getErrorCode()).isEqualTo("INVALID_VECTOR_REFERENCE");
        assertThat(result.getData().toMap())
            .containsEntry("entityType", " ")
            .containsEntry("removed", false)
            .containsEntry("reason", "entityType and entityId are required")
            .doesNotContainKey("entityId");
    }

    @Test
    void removeHandlerShouldBuildConfirmationMessage() {
        String message = removeHandler.confirm("doc", "123");
        assertThat(message).contains("doc:123");
    }
}
