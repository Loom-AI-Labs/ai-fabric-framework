package com.ai.fabric.realapps.chat.web;

import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.ActionAccessMode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatController.class)
@Import(CommerceModeResolver.class)
class ChatControllerSuggestionsTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AICoreService aiCoreService;

    @MockitoBean
    private AIActionRegistry aiActionRegistry;

    @Test
    void suggestionsIncludeActionsAndAttachmentsInPrompt() throws Exception {
        when(aiActionRegistry.getAllMetadata()).thenReturn(List.of(AIActionMetaData.builder()
            .name("list_products")
            .description("List products")
            .category("commerce")
            .accessMode(ActionAccessMode.READ)
            .parameters(java.util.Map.of("query", "Search query (required)"))
            .requiredParameters(java.util.Set.of("query"))
            .build()));

        when(aiCoreService.generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.GENERATION)))
            .thenReturn(AIGenerationResponse.builder()
                .content("[\"a\",\"b\",\"c\",\"d\",\"e\"]")
                .build());

        mockMvc.perform(post("/api/chat/suggestions")
                .contentType("application/json")
                .content("""
                    {
                      "content": "help me shop",
                      "userId": "u1",
                      "maxSuggestions": 5,
                      "attachments": [
                        {"id":"att-1","vectorSpace":"product","contentText":"iPhone 15 Pro details","source":"ui-card"}
                      ]
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.suggestions.length()").value(5));

        ArgumentCaptor<AIGenerationRequest> captor = ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(aiCoreService).generateContent(captor.capture(), eq(LlmPurpose.GENERATION));

        AIGenerationRequest sent = captor.getValue();
        assertThat(sent.getAuthContext()).isNotNull();
        assertThat(sent.getAuthContext().getSubjectId()).isEqualTo("u1");
        assertThat(sent.getAuthContext().getSubjectType()).isEqualTo("END_USER");
        assertThat(sent.getPrompt()).contains("list_products");
        assertThat(sent.getPrompt()).contains("iPhone 15 Pro details");
    }

    @Test
    void suggestionsOmitAuthContextWhenUserIdIsBlank() throws Exception {
        when(aiActionRegistry.getAllMetadata()).thenReturn(List.of());
        when(aiCoreService.generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.GENERATION)))
            .thenReturn(AIGenerationResponse.builder()
                .content("[\"a\",\"b\",\"c\"]")
                .build());

        mockMvc.perform(post("/api/chat/suggestions")
                .contentType("application/json")
                .content("""
                    {
                      "content": "help me shop",
                      "userId": " ",
                      "maxSuggestions": 3
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.suggestions.length()").value(3));

        ArgumentCaptor<AIGenerationRequest> captor = ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(aiCoreService).generateContent(captor.capture(), eq(LlmPurpose.GENERATION));

        assertThat(captor.getValue().getAuthContext()).isNull();
    }
}
