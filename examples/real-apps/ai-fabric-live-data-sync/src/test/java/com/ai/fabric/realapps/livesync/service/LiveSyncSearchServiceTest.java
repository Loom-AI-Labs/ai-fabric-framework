package com.ai.fabric.realapps.livesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.fabric.core.AICoreService;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LiveSyncSearchServiceTest {

    @Test
    void overfetchesProviderCandidatesWhilePreservingWorkspaceScopeAndCallerLimit() {
        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.performSearch(any())).thenReturn(AISearchResponse.builder()
            .results(List.of())
            .totalResults(0)
            .processingTimeMs(1L)
            .build());
        LiveSyncSearchService service = new LiveSyncSearchService(aiCoreService, new ObjectMapper());

        var response = service.search("workspace-1", "amber recovery", 2);

        ArgumentCaptor<AISearchRequest> requestCaptor = ArgumentCaptor.forClass(AISearchRequest.class);
        org.mockito.Mockito.verify(aiCoreService, org.mockito.Mockito.times(EntityKind.values().length))
            .performSearch(requestCaptor.capture());
        assertThat(requestCaptor.getAllValues())
            .allSatisfy(request -> {
                assertThat(request.getLimit()).isEqualTo(100);
                assertThat(request.getMetadata()).containsEntry("workspaceId", "workspace-1");
            });
        assertThat(response.hits()).hasSizeLessThanOrEqualTo(2);
    }
}
