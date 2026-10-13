package com.ai.fabric.realapps.handover;

import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.ActionResultContracts;
import ai.fabric.intent.action.annotation.AIAction;
import ai.fabric.intent.action.annotation.ActionAllowed;
import ai.fabric.intent.action.annotation.ActionConfirmation;
import ai.fabric.intent.action.annotation.ActionConfirmationRequired;
import ai.fabric.intent.action.annotation.ActionExecute;
import ai.fabric.intent.action.annotation.Param;
import java.util.LinkedHashMap;
import java.util.Map;

@AIAction(
    name = "request_conversation_handover",
    description = "Transfer response authority for this conversation to a human operator. Select this action only when the user explicitly asks to speak with a person or when the user accepts a proposed human escalation.",
    category = "conversation-control",
    accessMode = ActionAccessMode.WRITE_ONLY,
    requiresConfirmation = true,
    anonymousAllowed = true,
    terminal = true
)
class RequestConversationHandoverAction {

    private final ConversationHandoverActionService service;

    RequestConversationHandoverAction(ConversationHandoverActionService service) {
        this.service = service;
    }

    @ActionAllowed
    public boolean allowed(ActionContext context) {
        return context != null
            && context.hasConversation()
            && service.canRequestHuman(context.sessionId(), context.conversationId());
    }

    @ActionConfirmation
    public String confirm() {
        return "Would you like me to transfer this conversation to a human operator?";
    }

    @ActionConfirmationRequired
    public boolean confirmationRequired(
        @Param(
            value = "requestProvenance",
            description = "Use EXPLICIT_USER_REQUEST only when the user directly asked to speak with a human; otherwise use MODEL_RECOMMENDATION",
            required = false
        ) HandoverRequestProvenance requestProvenance
    ) {
        return requestProvenance != HandoverRequestProvenance.EXPLICIT_USER_REQUEST;
    }

    @ActionExecute
    public ActionResult execute(
        @Param(
            value = "requestProvenance",
            description = "Use EXPLICIT_USER_REQUEST only when the user directly asked to speak with a human; otherwise use MODEL_RECOMMENDATION",
            required = false
        ) HandoverRequestProvenance requestProvenance,
        ActionContext context
    ) {
        try {
            HandoverRequestProvenance effectiveProvenance = requestProvenance == null
                ? HandoverRequestProvenance.MODEL_RECOMMENDATION
                : requestProvenance;
            ConversationHandoverActionService.HandoverAdmission admission = service.requestHuman(
                context.sessionId(),
                context.conversationId(),
                context.requestId(),
                effectiveProvenance
            );

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("conversationId", context.conversationId());
            data.put("controlState", admission.controlState().name());
            data.put("controlVersion", admission.controlVersion());
            data.put("requestProvenance", effectiveProvenance.name());
            return ActionResult.builder()
                .success(true)
                .message("Response authority was transferred to the human support queue.")
                .data(ActionResultContracts.object(data))
                .build();
        } catch (RuntimeException exception) {
            return ActionResult.builder()
                .success(false)
                .errorCode("CONVERSATION_HANDOVER_FAILED")
                .message("The conversation could not be transferred to a human operator.")
                .build();
        }
    }
}
