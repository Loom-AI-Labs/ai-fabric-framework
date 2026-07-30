package com.ai.fabric.realapps.retrievallab.service;

import ai.fabric.core.AICoreService;
import ai.fabric.dto.RAGRequest;
import ai.fabric.dto.RAGResponse;
import ai.fabric.spi.RAGProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrievalBoundaryServiceTest {

    @Test
    void doesNotClaimGenerationWhenCoreServiceIsUnavailable() {
        RAGProvider ragProvider = mock(RAGProvider.class);
        RAGResponse.RAGDocument document =
            RAGResponse.RAGDocument.builder()
                .id("policy-1")
                .content("Approved policy evidence")
                .type("policy")
                .score(0.9d)
                .metadata(Map.of())
                .build();
        when(ragProvider.performRAGQuery(any(RAGRequest.class)))
            .thenReturn(RAGResponse.builder()
                .success(true)
                .context("Approved policy evidence")
                .documents(List.of(document))
                .metadata(Map.of())
                .build());

        @SuppressWarnings("unchecked")
        ObjectProvider<AICoreService> coreServiceProvider =
            mock(ObjectProvider.class);
        when(coreServiceProvider.getIfAvailable()).thenReturn(null);

        RetrievalBoundaryService service =
            new RetrievalBoundaryService(
                ragProvider,
                coreServiceProvider,
                false
            );

        RetrievalBoundaryService.BoundaryOutcome outcome = service.run(
            new RetrievalBoundaryService.BoundaryRequest(
                "VALID",
                "Can I return an opened laptop?"
            )
        );

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.retrievalAccepted()).isTrue();
        assertThat(outcome.generationInvoked()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("GENERATION_FAILED");
        assertThat(outcome.documents()).hasSize(1);
    }
}
