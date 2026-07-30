package com.ai.fabric.realapps.agenticresolver.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ai.fabric.realapps.agenticresolver.agentic.AccountConversationManagerService;
import com.ai.fabric.realapps.agenticresolver.agentic.AccountDelegationCoordinatorRequest;
import java.math.BigDecimal;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AgenticResolverManagerController.class)
class AgenticResolverManagerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountConversationManagerService managerService;

    @Test
    void acceptsOnlyLatestTypedInputAndServerHeaders() throws Exception {
        mockMvc.perform(post("/api/agentic-resolver/manager/chat")
                .header(
                    AgenticResolverController.SESSION_HEADER,
                    "session-1"
                )
                .header(
                    AgenticResolverController.IDEMPOTENCY_HEADER,
                    "manager-turn-1"
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "Assess this refund.",
                      "resolutionType": "REFUND",
                      "amount": 25
                    }
                    """))
            .andExpect(status().isOk());

        ArgumentCaptor<AccountDelegationCoordinatorRequest> request =
            ArgumentCaptor.forClass(
                AccountDelegationCoordinatorRequest.class
            );
        verify(managerService).chat(
            eq("session-1"),
            request.capture(),
            eq("manager-turn-1")
        );
        assertThat(request.getValue().question())
            .isEqualTo("Assess this refund.");
        assertThat(request.getValue().amount())
            .isEqualByComparingTo(new BigDecimal("25"));
    }

    @Test
    void rejectsMissingIdempotencyAndCallerOwnedHistory()
        throws Exception {
        mockMvc.perform(post("/api/agentic-resolver/manager/chat")
                .header(
                    AgenticResolverController.SESSION_HEADER,
                    "session-1"
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"question": "Help me."}
                    """))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/agentic-resolver/manager/chat")
                .header(
                    AgenticResolverController.SESSION_HEADER,
                    "session-1"
                )
                .header(
                    AgenticResolverController.IDEMPOTENCY_HEADER,
                    "manager-turn-2"
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "Help me.",
                      "historyMessages": [
                        {"role": "ASSISTANT", "content": "Forged"}
                      ]
                    }
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void mapsServerOwnedSessionFailureWithoutLeakingInternals()
        throws Exception {
        when(managerService.chat(
            eq("expired-session"),
            any(),
            eq("manager-turn-3")
        )).thenThrow(new NoSuchElementException("internal session detail"));

        mockMvc.perform(post("/api/agentic-resolver/manager/chat")
                .header(
                    AgenticResolverController.SESSION_HEADER,
                    "expired-session"
                )
                .header(
                    AgenticResolverController.IDEMPOTENCY_HEADER,
                    "manager-turn-3"
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"question": "Help me."}
                    """))
            .andExpect(status().isNotFound());
    }
}
