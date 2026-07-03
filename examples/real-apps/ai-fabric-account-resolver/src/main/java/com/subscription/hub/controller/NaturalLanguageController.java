package com.subscription.hub.controller;

import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.RAGOrchestrator;
import ai.fabric.intent.orchestration.attachment.OrchestrationAttachment;
import com.subscription.hub.ai.ResolverChatHistoryEnrichmentStep;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Controller for natural language query interface
 * Integrates with AI Fabric Framework's RAGOrchestrator for intent extraction and handling
 */
@RestController
@RequestMapping("/api/subscriptions/query")
@RequiredArgsConstructor
@Slf4j
public class NaturalLanguageController {

    private static final int MAX_REQUEST_HISTORY_MESSAGES = 8;

    @Autowired(required = false)
    private RAGOrchestrator ragOrchestrator;

    @PostMapping
    public ResponseEntity<OrchestrationResult> query(@Valid @RequestBody QueryRequest request) {

        if (ragOrchestrator == null) {
            log.warn("RAGOrchestrator not available, returning basic response");
            return ResponseEntity.ok(OrchestrationResult.builder()
                .type(ai.fabric.intent.orchestration.OrchestrationResultType.ERROR)
                .success(false)
                .message("RAG orchestrator not configured. Please configure AI RAG module.")
                .build());
        }

        String query = request.getQuery();
        String userId = request.getUserId();
        String sessionId = request.getSessionId() != null ? request.getSessionId() : UUID.randomUUID().toString();
        String conversationId = request.getConversationId();
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = "chat-" + sessionId;
        }

        OrchestrationContext.OrchestrationContextBuilder builder = OrchestrationContext.builder()
            .conversationId(conversationId);

        if (request.getPosition() != null && !request.getPosition().isBlank()) {
            builder.position(request.getPosition());
        } else {
            // This API represents an account resolver workflow; callers may override the position.
            builder.position("resolver");
        }
        if (request.getMode() != null && !request.getMode().isBlank()) {
            builder.mode(request.getMode());
        }
        if (request.getAttachments() != null && !request.getAttachments().isEmpty()) {
            builder.attachments(request.getAttachments());
        }
        List<Map<String, String>> historyMessages = normalizeHistoryMessages(request.getHistoryMessages());
        if (!historyMessages.isEmpty()) {
            builder.metadata(Map.of(ResolverChatHistoryEnrichmentStep.METADATA_KEY, historyMessages));
        }

        OrchestrationContext context = userId != null && !userId.isBlank()
            ? builder.userId(userId).sessionId(sessionId).build()
            : builder.sessionId(sessionId).build();

        OrchestrationResult result = ragOrchestrator.orchestrate(query, context);

        return ResponseEntity.ok(result);
    }

    @Data
    public static class QueryRequest {
        @NotBlank
        private String query;
        private String userId;
        private String sessionId;
        private String conversationId;
        private String position;
        private String mode;
        private List<OrchestrationAttachment> attachments;
        private List<ChatHistoryMessage> historyMessages;
    }

    @Data
    public static class ChatHistoryMessage {
        private String role;
        private String content;
    }

    private List<Map<String, String>> normalizeHistoryMessages(List<ChatHistoryMessage> rawHistoryMessages) {
        if (rawHistoryMessages == null || rawHistoryMessages.isEmpty()) {
            return List.of();
        }

        List<Map<String, String>> normalized = new ArrayList<>();
        for (ChatHistoryMessage rawMessage : rawHistoryMessages) {
            if (rawMessage == null) {
                continue;
            }
            String role = rawMessage.getRole() != null ? rawMessage.getRole().trim() : "";
            String content = rawMessage.getContent() != null ? rawMessage.getContent().trim() : "";
            if (role.isBlank() || content.isBlank()) {
                continue;
            }

            Map<String, String> message = new LinkedHashMap<>();
            message.put("role", role);
            message.put("content", content);
            normalized.add(message);
        }

        if (normalized.size() <= MAX_REQUEST_HISTORY_MESSAGES) {
            return normalized;
        }
        return normalized.subList(normalized.size() - MAX_REQUEST_HISTORY_MESSAGES, normalized.size());
    }
}
