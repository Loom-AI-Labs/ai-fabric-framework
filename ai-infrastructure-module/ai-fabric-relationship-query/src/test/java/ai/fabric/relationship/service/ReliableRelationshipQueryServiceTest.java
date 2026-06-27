package ai.fabric.relationship.service;

import ai.fabric.dto.RAGResponse;
import ai.fabric.relationship.exception.QueryPlanningException;
import ai.fabric.relationship.exception.RelationshipQueryErrorContext;
import ai.fabric.relationship.model.QueryOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReliableRelationshipQueryServiceTest {

    @Mock
    private LLMDrivenJPAQueryService llmService;

    @Test
    void shouldDelegateToPrimaryService() {
        RAGResponse expected = RAGResponse.builder()
            .documents(List.of(RAGResponse.RAGDocument.builder().id("1").build()))
            .build();
        when(llmService.executeRelationshipQuery(anyString(), anyList(), any())).thenReturn(expected);

        ReliableRelationshipQueryService service = new ReliableRelationshipQueryService(llmService);
        RAGResponse actual = service.execute("query", List.of("document"), QueryOptions.defaults());

        assertThat(actual).isSameAs(expected);
        verify(llmService).executeRelationshipQuery(anyString(), anyList(), any());
    }

    @Test
    void shouldReturnFailedResponseForBlankQueryWithoutCallingPrimaryService() {
        ReliableRelationshipQueryService service = new ReliableRelationshipQueryService(llmService);

        RAGResponse response = service.execute(" ", List.of("document"), QueryOptions.defaults());

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getErrorMessage()).contains("query text is required");
        assertThat(response.getDocuments()).isEmpty();
        assertThat(response.getMetadata()).containsEntry("errorCode", "INVALID_QUERY");
        verify(llmService, never()).executeRelationshipQuery(anyString(), anyList(), any());
    }

    @Test
    void shouldReturnFailedResponseWhenPrimaryServiceThrowsRuntimeException() {
        when(llmService.executeRelationshipQuery(anyString(), anyList(), any()))
            .thenThrow(new IllegalStateException("planner unavailable"));

        ReliableRelationshipQueryService service = new ReliableRelationshipQueryService(llmService);

        RAGResponse response = service.execute("find documents", List.of("document"), QueryOptions.defaults());

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getErrorMessage()).isEqualTo("planner unavailable");
        assertThat(response.getMetadata())
            .containsEntry("errorCode", "EXECUTION_FAILED")
            .containsEntry("errorType", "IllegalStateException")
            .containsEntry("entityTypes", List.of("document"));
    }

    @Test
    void shouldPreserveStructuredRelationshipQueryFailureContext() {
        RelationshipQueryErrorContext context = RelationshipQueryErrorContext.builder()
            .originalQuery("find documents")
            .executionStage("planning")
            .primaryEntityType("document")
            .candidateEntityTypes(List.of("document", "case"))
            .fallbackUsed(true)
            .attributes(Map.of("finishReason", "MAX_TOKENS"))
            .timestamp(Instant.parse("2026-02-12T00:00:00Z"))
            .build();
        when(llmService.executeRelationshipQuery(anyString(), anyList(), any()))
            .thenThrow(new QueryPlanningException("invalid plan", context, new RuntimeException("bad json")));

        ReliableRelationshipQueryService service = new ReliableRelationshipQueryService(llmService);

        RAGResponse response = service.execute("find documents", List.of("document"), QueryOptions.defaults());

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getMetadata())
            .containsEntry("errorCode", "EXECUTION_FAILED")
            .containsEntry("executionStage", "planning")
            .containsEntry("primaryEntityType", "document")
            .containsEntry("candidateEntityTypes", List.of("document", "case"))
            .containsEntry("fallbackUsed", true)
            .containsEntry("timestamp", "2026-02-12T00:00:00Z");
        assertThat(response.getMetadata().get("errorAttributes"))
            .isEqualTo(Map.of("finishReason", "MAX_TOKENS"));
    }
}
