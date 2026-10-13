package com.ai.fabric.realapps.handover;

import ai.fabric.chat.control.ConversationControlCommand;
import ai.fabric.chat.control.ConversationControlCommandType;
import ai.fabric.chat.control.ConversationControlGateway;
import ai.fabric.chat.control.ConversationControlResult;
import ai.fabric.chat.control.ConversationControlSnapshot;
import ai.fabric.chat.control.ConversationControlState;
import ai.fabric.chat.control.ConversationControlViolationException;
import ai.fabric.chat.control.ConversationMessage;
import ai.fabric.chat.control.ConversationMessageCommand;
import ai.fabric.chat.control.ConversationParticipantRole;
import ai.fabric.chat.control.ConversationScope;
import ai.fabric.chat.control.ConversationTurnLease;
import ai.fabric.chat.control.HandoverIntakeSchema;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.RAGOrchestrator;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
class ConversationHandoverDemoService {

    static final String TENANT_ID = "demo-tenant";
    static final String DEPLOYMENT_ID = "conversation-handover-demo";
    static final String CHANNEL_ID = "demo-human-channel";

    private final ConversationControlGateway gateway;
    private final DemoConversationRegistry registry;
    private final RAGOrchestrator orchestrator;
    private final DemoHandoverIntakePolicy intakePolicy;

    ConversationHandoverDemoService(
        ConversationControlGateway gateway,
        DemoConversationRegistry registry,
        RAGOrchestrator orchestrator,
        DemoHandoverIntakePolicy intakePolicy
    ) {
        this.gateway = gateway;
        this.registry = registry;
        this.orchestrator = orchestrator;
        this.intakePolicy = intakePolicy;
    }

    ConversationView create(String ownerId) {
        String conversationId = "conversation-" + UUID.randomUUID();
        registry.create(conversationId, ownerId, "Demo visitor", Instant.now());
        ConversationScope scope = scope(registry.require(conversationId));
        gateway.status(scope);
        append(scope, ConversationParticipantRole.SYSTEM, "system", "conversation-created", null,
            "This conversation starts with AI response authority.", null);
        return view(scope);
    }

    ConversationView ownerView(String ownerId, String conversationId) {
        return view(ownerScope(ownerId, conversationId));
    }

    ConversationView userMessage(
        String ownerId,
        String conversationId,
        String text,
        String clientMessageId
    ) {
        ConversationScope scope = ownerScope(ownerId, conversationId);
        String safeText = requiredText(text, "Message", 20_000);
        String key = requiredKey(clientMessageId);
        append(scope, ConversationParticipantRole.USER, ownerId, "user-" + key, key, safeText, null);

        ConversationControlSnapshot status = gateway.status(scope);
        if (status.state() == ConversationControlState.AI_ACTIVE) {
            String requestId = "handover-turn-" + key;
            OrchestrationResult result = orchestrator.orchestrate(
                safeText,
                OrchestrationContext.builder()
                    .requestId(requestId)
                    .sessionId(ownerId)
                    .conversationId(conversationId)
                    .position("conversation")
                    .mode("handover")
                    .build()
            );

            ConversationControlSnapshot afterOrchestration = gateway.status(scope);
            if (afterOrchestration.state() != ConversationControlState.AI_ACTIVE) {
                return view(scope);
            }

            ConversationTurnLease lease = gateway.acquireAiTurnLease(scope, Duration.ofMinutes(1));
            try {
                append(
                    scope,
                    ConversationParticipantRole.AI,
                    "demo-ai",
                    "ai-" + key,
                    "ai-" + key,
                    responseText(result),
                    lease,
                    Map.of(
                        "demo", true,
                        "orchestrationResultType", result != null && result.getType() != null
                            ? result.getType().name()
                            : "UNKNOWN",
                        "requestId", requestId
                    )
                );
            } catch (ConversationControlViolationException ignored) {
                // A concurrent handover correctly invalidated this AI turn.
            }
        }
        return view(scope);
    }

    @Transactional
    ConversationView requestHuman(
        String ownerId,
        String conversationId,
        HandoverRequest request
    ) {
        ConversationScope scope = ownerScope(ownerId, conversationId);
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A handover request is required.");
        }
        DemoHandoverIntakePolicy.ValidatedIntake intake = intakePolicy.validate(
            request.intake(),
            request.consentedFields()
        );
        String handoverId = "handover-" + requiredKey(request.idempotencyKey());
        transition(
            scope,
            ConversationControlCommandType.REQUEST_HUMAN,
            new TransitionRequest(request.expectedVersion(), request.idempotencyKey()),
            ownerId,
            "VISITOR_REQUEST",
            handoverId,
            CHANNEL_ID
        );
        registry.saveIntake(conversationId, intake, Instant.now());
        append(scope, ConversationParticipantRole.SYSTEM, "system", "event-" + request.idempotencyKey(), null,
            "Required contact intake was accepted. Human support requested; AI responses are paused while the request is pending.", null,
            Map.of(
                "demo", true,
                "intakeSchemaVersion", intakePolicy.schema().schemaVersion(),
                "requiredContactAccepted", true
            ));
        return view(scope);
    }

    ConversationView cancelHuman(
        String ownerId,
        String conversationId,
        TransitionRequest request
    ) {
        ConversationScope scope = ownerScope(ownerId, conversationId);
        transition(scope, ConversationControlCommandType.CANCEL_HUMAN_REQUEST, request, ownerId,
            "VISITOR_CANCEL", null, null);
        registry.clearIntake(conversationId);
        append(scope, ConversationParticipantRole.SYSTEM, "system", "event-" + request.idempotencyKey(), null,
            "Human support request cancelled. AI response authority is active again.", null);
        return view(scope);
    }

    List<QueueItem> queue() {
        return registry.list().stream()
            .map(entry -> {
                ConversationControlSnapshot control = gateway.status(scope(entry));
                return new QueueItem(
                    entry.conversationId(),
                    entry.safeLabel(),
                    control.state(),
                    control.version(),
                    control.activeHandoverId(),
                    control.updatedAt()
                );
            })
            .toList();
    }

    ConversationView operatorView(String conversationId) {
        return view(scope(registry.require(conversationId)));
    }

    ConversationView claim(String conversationId, TransitionRequest request, String operatorId) {
        ConversationScope scope = scope(registry.require(conversationId));
        transition(scope, ConversationControlCommandType.ACTIVATE_HUMAN, request, operatorId,
            "OPERATOR_CLAIM", null, null);
        append(scope, ConversationParticipantRole.SYSTEM, "system", "event-" + request.idempotencyKey(), null,
            "A human operator joined the conversation.", null);
        return view(scope);
    }

    ConversationView humanMessage(
        String conversationId,
        MessageRequest request,
        String operatorId
    ) {
        ConversationScope scope = scope(registry.require(conversationId));
        String key = requiredKey(request.clientMessageId());
        append(scope, ConversationParticipantRole.HUMAN, operatorId, "human-" + key, key,
            requiredText(request.text(), "Reply", 20_000), null);
        return view(scope);
    }

    ConversationView returnToAi(String conversationId, TransitionRequest request, String operatorId) {
        ConversationScope scope = scope(registry.require(conversationId));
        ConversationControlResult pending = transition(
            scope,
            ConversationControlCommandType.REQUEST_AI_RESUME,
            request,
            operatorId,
            "OPERATOR_RETURN",
            null,
            null
        );
        TransitionRequest complete = new TransitionRequest(
            pending.snapshot().version(),
            request.idempotencyKey() + "-complete"
        );
        transition(scope, ConversationControlCommandType.COMPLETE_AI_RESUME, complete, "system",
            "AI_RESUME_COMPLETE", null, null);
        registry.clearIntake(conversationId);
        append(scope, ConversationParticipantRole.SYSTEM, "system", "event-" + request.idempotencyKey(), null,
            "The operator returned response authority to AI.", null);
        return view(scope);
    }

    ConversationView close(String conversationId, TransitionRequest request, String operatorId) {
        ConversationScope scope = scope(registry.require(conversationId));
        transition(scope, ConversationControlCommandType.CLOSE, request, operatorId,
            "OPERATOR_CLOSE", null, null);
        registry.clearIntake(conversationId);
        append(scope, ConversationParticipantRole.SYSTEM, "system", "event-" + request.idempotencyKey(), null,
            "The conversation was closed.", null);
        return view(scope);
    }

    private ConversationControlResult transition(
        ConversationScope scope,
        ConversationControlCommandType type,
        TransitionRequest request,
        String actor,
        String reason,
        String handoverId,
        String channelId
    ) {
        if (request == null || request.expectedVersion() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A non-negative expectedVersion is required.");
        }
        ConversationControlResult result = gateway.transition(new ConversationControlCommand(
            scope,
            type,
            request.expectedVersion(),
            requiredKey(request.idempotencyKey()),
            actor,
            reason,
            handoverId,
            channelId
        ));
        if (!result.applied()) {
            HttpStatus status = result.outcome().name().contains("CONFLICT")
                ? HttpStatus.CONFLICT
                : HttpStatus.UNPROCESSABLE_ENTITY;
            throw new ResponseStatusException(status, result.code() + ": " + result.message());
        }
        return result;
    }

    private ConversationScope ownerScope(String ownerId, String conversationId) {
        DemoConversationRegistry.Entry entry = registry.require(requiredId(conversationId));
        if (!entry.ownerId().equals(ownerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Conversation owner mismatch.");
        }
        return scope(entry);
    }

    private ConversationScope scope(DemoConversationRegistry.Entry entry) {
        return new ConversationScope(TENANT_ID, DEPLOYMENT_ID, entry.conversationId(), entry.ownerId());
    }

    private ConversationView view(ConversationScope scope) {
        DemoConversationRegistry.Entry entry = registry.require(scope.conversationId());
        ContactIntakeView contact = StringUtils.hasText(entry.handoverEmail())
            ? new ContactIntakeView(
                entry.handoverEmail(),
                entry.handoverName(),
                entry.handoverPhone(),
                entry.handoverIntakeAt()
            )
            : null;
        return new ConversationView(
            gateway.status(scope),
            gateway.messages(scope, 0, 500),
            intakePolicy.schema(),
            entry.handoverIntakeRequested(),
            contact
        );
    }

    private void append(
        ConversationScope scope,
        ConversationParticipantRole role,
        String actor,
        String messageId,
        String clientMessageId,
        String content,
        ConversationTurnLease lease
    ) {
        append(scope, role, actor, messageId, clientMessageId, content, lease, Map.of("demo", true));
    }

    private void append(
        ConversationScope scope,
        ConversationParticipantRole role,
        String actor,
        String messageId,
        String clientMessageId,
        String content,
        ConversationTurnLease lease,
        Map<String, Object> metadata
    ) {
        gateway.appendMessage(new ConversationMessageCommand(
            scope,
            messageIdentity(scope, messageId),
            clientMessageId,
            role,
            actor,
            content,
            metadata
        ), lease);
    }

    private String responseText(OrchestrationResult result) {
        if (result != null && StringUtils.hasText(result.getMessage())) {
            return result.getMessage().trim();
        }
        return "The AI provider returned no response for this turn.";
    }

    private String messageIdentity(ConversationScope scope, String localMessageId) {
        String seed = scope.tenantId() + ":" + scope.deploymentId() + ":"
            + scope.conversationId() + ":" + localMessageId;
        return "message-" + UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    private String requiredId(String value) {
        if (!StringUtils.hasText(value) || value.length() > 160) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid conversation id is required.");
        }
        return value.trim();
    }

    private String requiredKey(String value) {
        if (!StringUtils.hasText(value) || value.length() > 120 || !value.matches("[A-Za-z0-9._:-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A bounded idempotency key is required.");
        }
        return value.trim();
    }

    private String requiredText(String value, String label, int maximum) {
        if (!StringUtils.hasText(value) || value.length() > maximum) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " is required and exceeds no more than " + maximum + " characters.");
        }
        return value.trim();
    }

    record TransitionRequest(long expectedVersion, String idempotencyKey) {}

    record HandoverRequest(
        long expectedVersion,
        String idempotencyKey,
        Map<String, String> intake,
        List<String> consentedFields
    ) {}

    record MessageRequest(String text, String clientMessageId) {}

    record ConversationView(
        ConversationControlSnapshot control,
        List<ConversationMessage> messages,
        HandoverIntakeSchema handoverIntakeSchema,
        boolean handoverIntakeRequired,
        ContactIntakeView contactIntake
    ) {}

    record ContactIntakeView(String email, String name, String phone, Instant acceptedAt) {}

    record QueueItem(
        String conversationId,
        String safeLabel,
        ConversationControlState state,
        long version,
        String handoverId,
        Instant updatedAt
    ) {}
}
