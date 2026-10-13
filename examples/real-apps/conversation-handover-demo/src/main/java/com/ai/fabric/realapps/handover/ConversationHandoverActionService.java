package com.ai.fabric.realapps.handover;

import ai.fabric.chat.control.ConversationControlGateway;
import ai.fabric.chat.control.ConversationControlSnapshot;
import ai.fabric.chat.control.ConversationControlState;
import ai.fabric.chat.control.ConversationMessageCommand;
import ai.fabric.chat.control.ConversationParticipantRole;
import ai.fabric.chat.control.ConversationScope;
import ai.fabric.chat.control.HandoverAdmissionResult;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
class ConversationHandoverActionService {

    private final ConversationControlGateway gateway;
    private final DemoConversationRegistry registry;
    private final DemoHandoverIntakePolicy intakePolicy;

    ConversationHandoverActionService(
        ConversationControlGateway gateway,
        DemoConversationRegistry registry,
        DemoHandoverIntakePolicy intakePolicy
    ) {
        this.gateway = gateway;
        this.registry = registry;
        this.intakePolicy = intakePolicy;
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
        if (current.state() != ConversationControlState.AI_ACTIVE) {
            throw new IllegalStateException("Handover intake can begin only while AI is active.");
        }
        registry.markIntakeRequested(conversationId);
        HandoverAdmissionResult admission = intakePolicy.intakeRequired();

        Map<String, Object> metadata = Map.of(
            "demo", true,
            "action", "request_conversation_handover",
            "selectedBy", "AI_FABRIC_ORCHESTRATOR",
            "requestProvenance", provenance.name(),
            "outcome", admission.outcome().name()
        );
        gateway.appendMessage(new ConversationMessageCommand(
            scope,
            messageIdentity(scope, "event-" + idempotencyKey),
            null,
            ConversationParticipantRole.SYSTEM,
            "system",
            "AI Fabric selected the governed handover action. Required contact intake must be accepted before response authority changes.",
            metadata
        ), null);
        return new HandoverAdmission(admission, current.state(), current.version());
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

    record HandoverAdmission(
        HandoverAdmissionResult admission,
        ConversationControlState controlState,
        long controlVersion
    ) {}
}
