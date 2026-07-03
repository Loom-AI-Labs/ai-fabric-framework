package com.subscription.hub.controller;

import ai.fabric.intent.action.AIActionHandler;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.PendingAction;
import ai.fabric.intent.action.PendingActionStore;
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

    @Autowired(required = false)
    private AIActionRegistry actionRegistry;

    @Autowired(required = false)
    private PendingActionStore pendingActionStore;

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

    @PostMapping("/actions/execute")
    public ResponseEntity<ActionResult> executeAction(
            @RequestBody Map<String, Object> request) {

        if (actionRegistry == null) {
            return ResponseEntity.badRequest()
                .body(ActionResult.builder()
                    .success(false)
                    .message("AIActionRegistry not configured")
                    .build());
        }

        String action = (String) request.get("action");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) request.get("params");

        Object userIdObj = request.get("userId");
        String userId = userIdObj != null ? userIdObj.toString() : null;
        String sessionId = request.get("sessionId") != null ? request.get("sessionId").toString() : UUID.randomUUID().toString();

        boolean confirmed = Boolean.TRUE.equals(request.get("confirmed"));

        OrchestrationContext context = userId != null
            ? OrchestrationContext.builder().userId(userId).sessionId(sessionId).build()
            : OrchestrationContext.forSession(sessionId);

        ActionContext actionContext = new ActionContext(context, null);

        AIActionHandler handler = actionRegistry.findHandler(action).orElse(null);
        if (handler == null) {
            return ResponseEntity.badRequest()
                .body(ActionResult.builder()
                    .success(false)
                    .message("Action handler not found: " + action)
                    .build());
        }

        if (!handler.validateActionAllowed(actionContext)) {
            return ResponseEntity.status(403)
                .body(ActionResult.builder()
                    .success(false)
                    .message("Action not allowed")
                    .errorCode("ACTION_DENIED")
                    .build());
        }

        if (handler.requiresConfirmation() && !confirmed) {
            String confirmationMessage = handler.getConfirmationMessage(params != null ? params : Map.of(), actionContext);
            return ResponseEntity.badRequest()
                .body(ActionResult.builder()
                    .success(false)
                    .message(confirmationMessage != null ? confirmationMessage : "Action requires confirmation")
                    .errorCode("CONFIRMATION_REQUIRED")
                    .build());
        }

        try {
            ActionResult result = handler.executeAction(params != null ? params : Map.of(), actionContext);
            return ResponseEntity.ok(result);
        } catch (RuntimeException ex) {
            return ResponseEntity.badRequest()
                .body(ActionResult.builder()
                    .success(false)
                    .message(ex.getMessage() != null ? ex.getMessage() : "Action failed")
                    .errorCode("ACTION_EXECUTION_FAILED")
                    .build());
        }
    }

    @PostMapping("/actions/confirm")
    public ResponseEntity<ActionResult> confirmPendingAction(
            @RequestBody Map<String, Object> request) {

        if (actionRegistry == null || pendingActionStore == null) {
            return ResponseEntity.badRequest()
                .body(ActionResult.builder()
                    .success(false)
                    .message("AI action confirmation is not configured")
                    .build());
        }

        Object userIdObj = request.get("userId");
        String userId = userIdObj != null ? userIdObj.toString() : null;
        String sessionId = request.get("sessionId") != null ? request.get("sessionId").toString() : UUID.randomUUID().toString();
        String conversationId = request.get("conversationId") != null ? request.get("conversationId").toString() : null;
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = "chat-" + sessionId;
        }

        OrchestrationContext.OrchestrationContextBuilder builder = OrchestrationContext.builder()
            .sessionId(sessionId)
            .conversationId(conversationId);
        OrchestrationContext context = userId != null && !userId.isBlank()
            ? builder.userId(userId).build()
            : builder.build();

        PendingAction pending = pendingActionStore.popPendingAction(conversationId, context.getIdentifier()).orElse(null);
        if (pending == null) {
            return ResponseEntity.badRequest()
                .body(ActionResult.builder()
                    .success(false)
                    .message("No pending action to confirm")
                    .errorCode("NO_PENDING_ACTION")
                    .build());
        }

        boolean confirmed = Boolean.TRUE.equals(request.get("confirmed"));
        if (!confirmed) {
            return ResponseEntity.ok(ActionResult.builder()
                .success(false)
                .message("Action rejected. No account changes were made.")
                .errorCode("ACTION_DENIED")
                .build());
        }

        AIActionHandler handler = actionRegistry.findHandler(pending.action()).orElse(null);
        if (handler == null) {
            return ResponseEntity.badRequest()
                .body(ActionResult.builder()
                    .success(false)
                    .message("Action handler not found: " + pending.action())
                    .build());
        }

        ActionContext actionContext = new ActionContext(context, null);
        if (!handler.validateActionAllowed(actionContext)) {
            return ResponseEntity.status(403)
                .body(ActionResult.builder()
                    .success(false)
                    .message("Action not allowed")
                    .errorCode("ACTION_DENIED")
                    .build());
        }

        try {
            ActionResult result = handler.executeAction(pending.actionParams() != null ? pending.actionParams() : Map.of(), actionContext);
            return ResponseEntity.ok(result);
        } catch (RuntimeException ex) {
            return ResponseEntity.badRequest()
                .body(ActionResult.builder()
                    .success(false)
                    .message(ex.getMessage() != null ? ex.getMessage() : "Action failed")
                    .errorCode("ACTION_EXECUTION_FAILED")
                    .build());
        }
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
