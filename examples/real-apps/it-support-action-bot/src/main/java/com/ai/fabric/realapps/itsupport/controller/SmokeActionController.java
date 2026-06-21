package com.ai.fabric.realapps.itsupport.controller;

import ai.fabric.intent.action.AIActionHandler;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionPayload;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.orchestration.OrchestrationContext;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@Profile("smoke")
@RequestMapping("/api/smoke/actions")
@RequiredArgsConstructor
public class SmokeActionController {

    private final AIActionRegistry actionRegistry;

    @GetMapping
    public ResponseEntity<Map<String, Object>> actions() {
        Map<String, Object> actions = new LinkedHashMap<>();
        for (AIActionMetaData metadata : actionRegistry.getAllMetadata()) {
            actions.put(metadata.getName(), metadataSummary(metadata));
        }
        return ResponseEntity.ok(Map.of(
            "enabled", true,
            "actions", actions
        ));
    }

    @PostMapping("/{actionName}")
    public ResponseEntity<Map<String, Object>> execute(@PathVariable String actionName,
                                                       @RequestBody(required = false) SmokeActionRequest request) {
        SmokeActionRequest effectiveRequest = request != null ? request : new SmokeActionRequest();
        return actionRegistry.findHandler(actionName)
            .map(handler -> ResponseEntity.ok(executeWithHandler(handler, effectiveRequest)))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private Map<String, Object> executeWithHandler(AIActionHandler handler, SmokeActionRequest request) {
        Map<String, Object> params = request.params() != null ? request.params() : Map.of();
        ActionContext context = new ActionContext(orchestrationContext(request), null, params);
        AIActionMetaData metadata = handler.getActionMetadata();
        boolean allowed = handler.validateActionAllowed(context);
        boolean requiresConfirmation = handler.requiresConfirmation()
            || (metadata != null && metadata.isConfirmationRequired());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("action", metadata != null ? metadata.getName() : null);
        response.put("metadata", metadataSummary(metadata));
        response.put("allowed", allowed);
        response.put("confirmationRequired", requiresConfirmation);

        if (!allowed) {
            response.put("success", false);
            response.put("outcome", "ACTION_NOT_ALLOWED");
            response.put("errorCode", "ACTION_NOT_ALLOWED");
            return response;
        }

        if (requiresConfirmation) {
            response.put("confirmationMessage", handler.getConfirmationMessage(params, context));
            if (!Boolean.TRUE.equals(request.confirmed())) {
                response.put("success", true);
                response.put("outcome", "CONFIRMATION_REQUIRED");
                return response;
            }
        }

        ActionResult result = handler.executeAction(params, context);
        response.put("success", result != null && result.isSuccess());
        response.put("outcome", "ACTION_EXECUTED");
        response.put("result", actionResult(result));
        return response;
    }

    private OrchestrationContext orchestrationContext(SmokeActionRequest request) {
        OrchestrationContext.OrchestrationContextBuilder builder = OrchestrationContext.builder()
            .conversationId(textOrNull(request.conversationId()))
            .requestId(textOrNull(request.requestId()))
            .position("support")
            .mode("executor");

        String userId = textOrNull(request.userId());
        String sessionId = textOrNull(request.sessionId());
        if (userId != null) {
            builder.userId(userId);
        }
        if (sessionId != null) {
            builder.sessionId(sessionId);
        }
        return builder.build();
    }

    private Map<String, Object> metadataSummary(AIActionMetaData metadata) {
        if (metadata == null) {
            return Map.of();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", metadata.getName());
        summary.put("category", metadata.getCategory());
        summary.put("accessMode", metadata.getAccessMode() != null ? metadata.getAccessMode().name() : null);
        summary.put("confirmationRequired", metadata.isConfirmationRequired());
        summary.put("anonymousAllowed", metadata.isAnonymousAllowed());
        summary.put("requiredParameters", metadata.getRequiredParameters());
        summary.put("parameters", metadata.getParameters());
        return summary;
    }

    private Map<String, Object> actionResult(ActionResult result) {
        if (result == null) {
            return Map.of();
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", result.isSuccess());
        response.put("message", result.getMessage());
        response.put("errorCode", result.getErrorCode());
        ActionPayload data = result.getData();
        if (data != null) {
            response.put("data", data.toMap());
        }
        if (result.getPinnedTargets() != null) {
            response.put("pinnedTargets", result.getPinnedTargets());
        }
        return response;
    }

    private String textOrNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    public record SmokeActionRequest(
        String userId,
        String sessionId,
        String conversationId,
        String requestId,
        Boolean confirmed,
        Map<String, Object> params
    ) {
        public SmokeActionRequest() {
            this(null, null, null, null, null, Map.of());
        }
    }
}
