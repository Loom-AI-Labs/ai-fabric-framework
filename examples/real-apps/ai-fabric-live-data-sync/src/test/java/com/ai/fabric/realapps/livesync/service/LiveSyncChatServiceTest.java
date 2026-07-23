package com.ai.fabric.realapps.livesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import com.ai.fabric.realapps.livesync.web.DemoModels.ChatRequest;
import com.ai.fabric.realapps.livesync.web.DemoModels.SearchResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LiveSyncChatServiceTest {

    @Test
    void providerFailureIsVisibleAndNeverReplacedWithFallbackContent() {
        AICoreService aiCoreService = mock(AICoreService.class);
        LiveSyncSearchService searchService = mock(LiveSyncSearchService.class);
        when(searchService.search("workspace-1", "What is the battery life?", 6))
            .thenReturn(new SearchResponse("What is the battery life?", List.of(), Map.of(), 1L, "test"));
        when(aiCoreService.generateContent(any(), eq(LlmPurpose.GENERATION)))
            .thenThrow(new IllegalStateException("provider unavailable"));

        LiveSyncChatService service = new LiveSyncChatService(aiCoreService, searchService);
        var response = service.query(
            "workspace-1",
            new ChatRequest("What is the battery life?", null, "rag", "knowledge_sync")
        );

        assertThat(response.result().type()).isEqualTo("ERROR");
        assertThat(response.result().success()).isFalse();
        assertThat(response.result().errorCode()).isEqualTo("LIVE_AI_REQUEST_FAILED");
        assertThat(response.result().message())
            .contains("no fallback answer was substituted")
            .contains("provider unavailable");
    }
}
