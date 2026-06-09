package com.subscription.hub.controller;

import com.ai.infrastructure.intent.action.AIActionHandler;
import com.ai.infrastructure.intent.action.AIActionRegistry;
import com.ai.infrastructure.intent.action.ActionContext;
import com.ai.infrastructure.intent.action.ActionResult;
import com.ai.infrastructure.intent.orchestration.OrchestrationContext;
import com.ai.infrastructure.intent.orchestration.OrchestrationResult;
import com.ai.infrastructure.intent.orchestration.RAGOrchestrator;
import com.ai.infrastructure.intent.orchestration.attachment.OrchestrationAttachment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    @Autowired(required = false)
    private RAGOrchestrator ragOrchestrator;

    @Autowired(required = false)
    private AIActionRegistry actionRegistry;

    @PostMapping
    public ResponseEntity<OrchestrationResult> query(@Valid @RequestBody QueryRequest request) {

        if (ragOrchestrator == null) {
            log.warn("RAGOrchestrator not available, returning basic response");
            return ResponseEntity.ok(OrchestrationResult.builder()
                .type(com.ai.infrastructure.intent.orchestration.OrchestrationResultType.ERROR)
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
            // This API represents a support-style workflow; default to the support position when not provided.
            builder.position("support");
        }
        if (request.getMode() != null && !request.getMode().isBlank()) {
            builder.mode(request.getMode());
        }
        if (request.getAttachments() != null && !request.getAttachments().isEmpty()) {
            builder.attachments(request.getAttachments());
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
