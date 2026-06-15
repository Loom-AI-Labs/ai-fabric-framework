package com.ai.fabric.realapps.itsupport.controller;

import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.RAGOrchestrator;
import ai.fabric.intent.orchestration.attachment.OrchestrationAttachment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/bot")
@RequiredArgsConstructor
@Slf4j
public class BotController {

    private final ObjectProvider<RAGOrchestrator> orchestratorProvider;
    private final ObjectProvider<AIActionRegistry> actionRegistryProvider;

    @GetMapping("/actions")
    public ResponseEntity<Map<String, Object>> actions() {
        AIActionRegistry registry = actionRegistryProvider.getIfAvailable();
        if (registry == null) {
            return ResponseEntity.ok(Map.of(
                "enabled", false,
                "reason", "AIActionRegistry bean not available"
            ));
        }
        return ResponseEntity.ok(Map.of(
            "enabled", true,
            "actions", registry.getAllMetadata()
        ));
    }

    @PostMapping("/query")
    public ResponseEntity<OrchestrationResult> query(@Valid @RequestBody QueryRequest payload) {
        RAGOrchestrator orchestrator = orchestratorProvider.getIfAvailable();
        if (orchestrator == null) {
            return ResponseEntity.ok(OrchestrationResult.builder()
                .success(false)
                .message("Orchestrator not configured")
                .build());
        }

        String query = payload.getQuery();
        String userId = payload.getUserId();
        String sessionId = payload.getSessionId() != null ? payload.getSessionId() : UUID.randomUUID().toString();
        String conversationId = payload.getConversationId();
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = "chat-" + sessionId;
        }

        OrchestrationContext.OrchestrationContextBuilder builder = OrchestrationContext.builder()
            .conversationId(conversationId);

        if (payload.getPosition() != null && !payload.getPosition().isBlank()) {
            builder.position(payload.getPosition());
        } else {
            builder.position("support");
        }
        if (payload.getMode() != null && !payload.getMode().isBlank()) {
            builder.mode(payload.getMode());
        }
        if (payload.getAttachments() != null && !payload.getAttachments().isEmpty()) {
            builder.attachments(payload.getAttachments());
        }

        OrchestrationContext context = userId != null && !userId.isBlank()
            ? builder.userId(userId).sessionId(sessionId).build()
            : builder.sessionId(sessionId).build();

        return ResponseEntity.ok(orchestrator.orchestrate(query, context));
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
    }
}
