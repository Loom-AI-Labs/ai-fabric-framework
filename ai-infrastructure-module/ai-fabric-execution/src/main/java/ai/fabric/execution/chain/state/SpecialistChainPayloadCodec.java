package ai.fabric.execution.chain.state;

import ai.fabric.execution.chain.RegisteredSpecialistChain;
import ai.fabric.execution.chain.SpecialistChainExecutionRequest;
import ai.fabric.execution.chain.SpecialistChainExecutionResult;
import ai.fabric.execution.chain.SpecialistChainRegistry;
import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.gateway.ConversationBinding;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Encodes durable chain envelopes without trusting persisted class names. */
public final class SpecialistChainPayloadCodec {

    private final ObjectMapper objectMapper;
    private final SpecialistChainRegistry chainRegistry;
    private final SpecialistChainSecurity security;

    public SpecialistChainPayloadCodec(
        ObjectMapper objectMapper,
        SpecialistChainRegistry chainRegistry,
        SpecialistChainSecurity security
    ) {
        this.objectMapper = Objects.requireNonNull(
            objectMapper,
            "objectMapper is required"
        ).copy();
        this.chainRegistry = Objects.requireNonNull(
            chainRegistry,
            "chainRegistry is required"
        );
        this.security = Objects.requireNonNull(
            security,
            "security is required"
        );
    }

    public String protectRequest(
        String executionId,
        SpecialistChainExecutionRequest<?> request
    ) {
        Objects.requireNonNull(request, "request is required");
        TrustedExecutionContext context = request.trustedExecutionContext();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("input", objectMapper.valueToTree(request.input()));
        payload.put("principalId", context.initiator().principalId());
        payload.put(
            "principalType",
            context.initiator().principalType().name()
        );
        payload.put("subjectType", context.subject().subjectType());
        payload.put("subjectId", context.subject().subjectId());
        payload.put("source", context.source().name());
        payload.put("tenantId", context.tenantId());
        payload.put("deploymentId", context.deploymentId());
        payload.put("grantedScopes", context.grantedScopes());
        payload.put("correlationId", context.correlationId());
        payload.put("authenticatedAt", text(context.authenticatedAt()));
        payload.put("deadline", text(request.deadline()));
        payload.put("idempotencyKey", request.idempotencyKey());
        ConversationBinding binding = request.conversationBinding();
        payload.put(
            "conversationUserId",
            binding == null ? null : binding.userId()
        );
        payload.put(
            "conversationId",
            binding == null ? null : binding.conversationId()
        );
        return security.protect(
            payload,
            binding(executionId, "request")
        );
    }

    public SpecialistChainExecutionRequest<Object> unprotectRequest(
        SpecialistChainExecutionRecord record
    ) {
        Map<String, Object> payload = security.unprotect(
            record.protectedRequest(),
            binding(record.executionId(), "request")
        );
        RegisteredSpecialistChain chain = chainRegistry.require(
            record.chainId()
        );
        Object input = convert(
            payload.get("input"),
            chain.definition().inputType()
        );
        TrustedExecutionContext context = new TrustedExecutionContext(
            new ExecutionPrincipal(
                required(payload, "principalId"),
                ExecutionPrincipalType.valueOf(
                    required(payload, "principalType")
                )
            ),
            new ExecutionSubjectRef(
                required(payload, "subjectType"),
                required(payload, "subjectId")
            ),
            ExecutionSource.valueOf(required(payload, "source")),
            optional(payload, "tenantId"),
            optional(payload, "deploymentId"),
            scopes(payload.get("grantedScopes")),
            optional(payload, "correlationId"),
            instant(payload, "authenticatedAt")
        );
        String conversationUserId = optional(
            payload,
            "conversationUserId"
        );
        String conversationId = optional(payload, "conversationId");
        ConversationBinding conversation = conversationUserId == null
            ? null
            : new ConversationBinding(conversationUserId, conversationId);
        return new SpecialistChainExecutionRequest<>(
            record.chainId(),
            input,
            context,
            conversation,
            instant(payload, "deadline"),
            required(payload, "idempotencyKey")
        );
    }

    public String protectCheckpoint(
        String executionId,
        SpecialistChainCheckpoint checkpoint
    ) {
        Map<String, Object> payload = Map.of(
            "checkpoint",
            objectMapper.valueToTree(
                Objects.requireNonNull(
                    checkpoint,
                    "checkpoint is required"
                )
            )
        );
        return security.protect(
            payload,
            binding(executionId, "checkpoint")
        );
    }

    public SpecialistChainCheckpoint unprotectCheckpoint(
        SpecialistChainExecutionRecord record
    ) {
        Map<String, Object> payload = security.unprotect(
            record.protectedCheckpoint(),
            binding(record.executionId(), "checkpoint")
        );
        return objectMapper.convertValue(
            requiredValue(payload, "checkpoint"),
            SpecialistChainCheckpoint.class
        );
    }

    public String protectResult(
        String executionId,
        SpecialistChainExecutionResult result
    ) {
        Map<String, Object> payload = Map.of(
            "result",
            objectMapper.valueToTree(
                Objects.requireNonNull(result, "result is required")
            )
        );
        return security.protect(
            payload,
            binding(executionId, "result")
        );
    }

    public SpecialistChainExecutionResult unprotectResult(
        SpecialistChainExecutionRecord record
    ) {
        if (record.protectedResult() == null) {
            return null;
        }
        Map<String, Object> payload = security.unprotect(
            record.protectedResult(),
            binding(record.executionId(), "result")
        );
        return objectMapper.convertValue(
            requiredValue(payload, "result"),
            SpecialistChainExecutionResult.class
        );
    }

    private Object convert(Object value, Class<?> type) {
        JsonNode node = value instanceof JsonNode jsonNode
            ? jsonNode
            : objectMapper.valueToTree(value);
        if (JsonNode.class.isAssignableFrom(type)) {
            return node.deepCopy();
        }
        return objectMapper.convertValue(node, type);
    }

    private Set<String> scopes(Object value) {
        if (value == null) {
            return Set.of();
        }
        List<?> values = objectMapper.convertValue(value, List.class);
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Object item : values == null ? new ArrayList<>() : values) {
            if (item != null && !item.toString().isBlank()) {
                result.add(item.toString().trim());
            }
        }
        return Set.copyOf(result);
    }

    private Object requiredValue(
        Map<String, Object> payload,
        String field
    ) {
        Object value = payload.get(field);
        if (value == null) {
            throw new IllegalArgumentException(
                "Protected chain payload is missing " + field
            );
        }
        return value;
    }

    private String required(Map<String, Object> payload, String field) {
        String value = optional(payload, field);
        if (value == null) {
            throw new IllegalArgumentException(
                "Protected chain payload is missing " + field
            );
        }
        return value;
    }

    private String optional(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        if (value == null) {
            return null;
        }
        String normalized = value.toString().trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private Instant instant(Map<String, Object> payload, String field) {
        String value = optional(payload, field);
        return value == null ? null : Instant.parse(value);
    }

    private String text(Instant value) {
        return value == null ? null : value.toString();
    }

    private String binding(String executionId, String kind) {
        String id = Objects.requireNonNull(
            executionId,
            "executionId is required"
        ).trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("executionId is required");
        }
        return id + ":" + kind;
    }
}
