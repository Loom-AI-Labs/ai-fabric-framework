package com.ai.fabric.realapps.chat.web;

import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.RAGOrchestrator;
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
class ChatControllerQueryTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RAGOrchestrator ragOrchestrator;

    @Test
    void queryMapsStructuredActionDraftToOrchestrationContext() throws Exception {
        when(ragOrchestrator.orchestrate(
            eq("Continue with the checkout details I entered."),
            any(OrchestrationContext.class)
        )).thenReturn(OrchestrationResult.builder()
            .type(OrchestrationResultType.CONFIRMATION_REQUIRED)
            .success(true)
            .message("Confirm checkout")
            .build());

        mockMvc.perform(post("/api/chat/query")
                .contentType("application/json")
                .content("""
                    {
                      "query": "Continue with the checkout details I entered.",
                      "userId": "shopping-user-1",
                      "sessionId": "shopping-session-1",
                      "conversationId": "shopping-conversation-1",
                      "position": "checkout",
                      "mode": "executor",
                      "actionDraftSubmission": {
                        "action": "checkout_cart",
                        "parameters": {
                          "shippingAddress": "16 Dairy Drive",
                          "email": "shopper@example.com"
                        }
                      }
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.conversationId").value("shopping-conversation-1"));

        ArgumentCaptor<OrchestrationContext> contextCaptor =
            ArgumentCaptor.forClass(OrchestrationContext.class);
        verify(ragOrchestrator).orchestrate(
            eq("Continue with the checkout details I entered."),
            contextCaptor.capture()
        );

        OrchestrationContext context = contextCaptor.getValue();
        assertThat(context.getActionDraftSubmission()).isNotNull();
        assertThat(context.getActionDraftSubmission().action())
            .isEqualTo("checkout_cart");
        assertThat(context.getActionDraftSubmission().parameters())
            .containsEntry("shippingAddress", "16 Dairy Drive")
            .containsEntry("email", "shopper@example.com");
    }
}
