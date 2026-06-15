package com.ai.fabric.realapps.chat.web;

import ai.fabric.chat.domain.ChatSession;
import ai.fabric.chat.domain.SessionStatus;
import ai.fabric.chat.service.ChatSessionService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatController.class)
class ChatControllerConversationsTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChatSessionService chatSessionService;

    @Test
    void listConversations_acceptsUserIdParam() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        when(chatSessionService.getUserConversations("u1")).thenReturn(List.of(ChatSession.builder()
            .id("chat-1")
            .ownerId("u1")
            .status(SessionStatus.ACTIVE)
            .createdAt(now)
            .lastInteractionAt(now)
            .turns(List.of())
            .build()));

        mockMvc.perform(get("/api/chat/conversations").param("userId", "u1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value("chat-1"))
            .andExpect(jsonPath("$[0].ownerId").value("u1"));

        verify(chatSessionService).getUserConversations("u1");
    }

    @Test
    void listConversations_acceptsOwnerIdParamForBackCompat() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        when(chatSessionService.getUserConversations("legacy")).thenReturn(List.of(ChatSession.builder()
            .id("chat-2")
            .ownerId("legacy")
            .status(SessionStatus.ACTIVE)
            .createdAt(now)
            .lastInteractionAt(now)
            .turns(List.of())
            .build()));

        mockMvc.perform(get("/api/chat/conversations").param("ownerId", "legacy"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value("chat-2"));

        verify(chatSessionService).getUserConversations("legacy");
    }

    @Test
    void listConversations_requiresUserOrOwnerId() throws Exception {
        mockMvc.perform(get("/api/chat/conversations"))
            .andExpect(status().isBadRequest());
    }
}
