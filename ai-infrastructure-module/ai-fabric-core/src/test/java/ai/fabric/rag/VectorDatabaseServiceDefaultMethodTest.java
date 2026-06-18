package ai.fabric.rag;

import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

class VectorDatabaseServiceDefaultMethodTest {

    @Test
    void keywordSearchReturnsEmptyResponseWhenProviderDoesNotOverrideIt() {
        VectorDatabaseService service = mock(VectorDatabaseService.class, CALLS_REAL_METHODS);
        AISearchRequest request = AISearchRequest.builder()
            .query("refund window")
            .entityType("policy")
            .limit(5)
            .build();

        AISearchResponse response = service.keywordSearch(null, request);

        assertThat(response.getResults()).isEmpty();
        assertThat(response.getTotalResults()).isZero();
        assertThat(response.getMaxScore()).isZero();
        assertThat(response.getProcessingTimeMs()).isZero();
        assertThat(response.getQuery()).isEqualTo("refund window");
        assertThat(service.supportsKeywordSearch()).isFalse();
    }
}
