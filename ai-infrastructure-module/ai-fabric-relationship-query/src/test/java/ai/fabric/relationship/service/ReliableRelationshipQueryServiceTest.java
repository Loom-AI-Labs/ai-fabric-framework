package ai.fabric.relationship.service;

import ai.fabric.dto.RAGResponse;
import ai.fabric.relationship.model.QueryOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
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
}

