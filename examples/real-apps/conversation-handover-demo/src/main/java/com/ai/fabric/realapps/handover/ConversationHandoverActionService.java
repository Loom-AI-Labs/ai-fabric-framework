package com.ai.fabric.realapps.handover;

import ai.fabric.chat.control.ConversationControlCommand;
import ai.fabric.chat.control.ConversationControlCommandType;
import ai.fabric.chat.control.ConversationControlGateway;
import ai.fabric.chat.control.ConversationControlResult;
import ai.fabric.chat.control.ConversationControlSnapshot;
import ai.fabric.chat.control.ConversationControlState;
import ai.fabric.chat.control.ConversationMessageCommand;
import ai.fabric.chat.control.ConversationParticipantRole;
import ai.fabric.chat.control.ConversationScope;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
class ConversationHandoverActionService {

    private final ConversationControlGateway gateway;
    private final DemoConversationRegistry registry;

    ConversationHandoverActionService(
        ConversationControlGateway gateway,
        DemoConversationRegistry registry
    ) {
        this.gateway = gateway;
        this.registry = registry;
    }

    boolean canRequestHuman(String ownerId, String conversationId) {
        if (!StringUtils.hasText(ownerId) || !StringUtils.hasText(conversationId)) {
            return false;
        }
        try {
            return gateway.status(ownerScope(ownerId, conversationId)).state()
                == ConversationControlState.AI_ACTIVE;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    HandoverAdmission requestHuman(
        String ownerId,
        String conversationId,
        String requestId,
        HandoverRequestProvenance provenance
    ) {
        ConversationScope scope = ownerScope(ownerId, conversationId);
        ConversationControlSnapshot current = gateway.status(scope);
        String idempotencyKey = boundedKey(
            StringUtils.hasText(requestId) ? requestId : "handover-ai-" + UUID.randomUUID()
        );
        ConversationControlResult result = gateway.transition(new ConversationControlCommand(
            scope,
            ConversationControlCommandType.REQUEST_HUMAN,
            current.version(),
            idempotencyKey,
            ownerId,
            "AI_SELECTED_HANDOVER",
            "handover-" + idempotencyKey,
            ConversationHandoverDemoService.CHANNEL_ID
        ));
        if (!result.applied()) {
            throw new IllegalStateException(result.code() + ": " + result.message());
        }

        Map<String, Object> metadata = Map.of(
            "demo", true,
            "action", "request_conversation_handover",
            "selectedBy", "AI_FABRIC_ORCHESTRATOR",
            "requestProvenance", provenance.name()
        );
        gateway.appendMessage(new ConversationMessageCommand(
            scope,
            messageIdentity(scope, "event-" + idempotencyKey),
            null,
            ConversationParticipantRole.SYSTEM,
            "system",
            "AI Fabric selected the governed handover action. Human support was requested and AI responses are paused.",
            metadata
        ), null);
        return new HandoverAdmission(result.snapshot().state(), result.snapshot().version());
    }

    private ConversationScope ownerScope(String ownerId, String conversationId) {
        DemoConversationRegistry.Entry entry = registry.require(conversationId);
        if (!entry.ownerId().equals(ownerId)) {
            throw new IllegalStateException("Conversation owner mismatch.");
        }
        return new ConversationScope(
            ConversationHandoverDemoService.TENANT_ID,
            ConversationHandoverDemoService.DEPLOYMENT_ID,
            entry.conversationId(),
            entry.ownerId()
        );
    }

    private String boundedKey(String value) {
        if (!StringUtils.hasText(value) || value.length() > 120 || !value.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException("A bounded idempotency key is required.");
        }
        return value.trim();
    }

    private String messageIdentity(ConversationScope scope, String localMessageId) {
        String seed = scope.tenantId() + ":" + scope.deploymentId() + ":"
            + scope.conversationId() + ":" + localMessageId;
        return "message-" + UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    record HandoverAdmission(ConversationControlState controlState, long controlVersion) {}
}
