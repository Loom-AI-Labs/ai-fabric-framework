package ai.fabric.execution.state;

import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.execution.action.ActionProposalView;
import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.gateway.AIExecutionFailure;
import ai.fabric.execution.gateway.AIExecutionRequest;
import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.gateway.AIExecutionStatus;
import ai.fabric.execution.input.NeedsUserInput;
import ai.fabric.execution.specialist.RegisteredSpecialist;
import ai.fabric.execution.specialist.SpecialistRegistry;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Converts durable request/result envelopes without trusting stored class names.
 */
public final class DurableExecutionPayloadCodec {

    private final ObjectMapper objectMapper;
    private final SpecialistRegistry specialistRegistry;
    private final DurableExecutionSecurity security;

    public DurableExecutionPayloadCodec(
        ObjectMapper objectMapper,
        SpecialistRegistry specialistRegistry,
        DurableExecutionSecurity security
    ) {
        this.objectMapper = Objects.requireNonNull(
            objectMapper,
            "objectMapper is required"
        ).copy();
        this.specialistRegistry = Objects.requireNonNull(
            specialistRegistry,
            "specialistRegistry is required"
        );
        this.security = Objects.requireNonNull(
            security,
            "security is required"
        );
    }

    public String protectRequest(
        String invocationId,
        AIExecutionRequest<?> request
    ) {
        Objects.requireNonNull(request, "request is required");
        TrustedExecutionContext context =
            request.trustedExecutionContext();
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
        payload.put(
            "authenticatedAt",
            text(context.authenticatedAt())
        );
        payload.put("deadline", text(request.deadline()));
        payload.put("idempotencyKey", request.idempotencyKey());
        return security.protect(payload, binding(invocationId, "request"));
    }

    public AIExecutionRequest<Object> unprotectRequest(
        DurableExecutionRecord record
    ) {
        Map<String, Object> payload = security.unprotect(
            record.protectedRequest(),
            binding(record.invocationId(), "request")
        );
        RegisteredSpecialist specialist =
            specialistRegistry.requireRegistered(record.specialistId());
        Object input = convert(
            payload.get("input"),
            specialist.definition().inputAdapter().inputType()
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
        return new AIExecutionRequest<>(
            record.specialistId(),
            input,
            context,
            null,
            instant(payload, "deadline"),
            optional(payload, "idempotencyKey")
        );
    }

    public String protectResult(
        DurableExecutionRecord record,
        AIExecutionResult<?> result
    ) {
        Objects.requireNonNull(result, "result is required");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", result.status().name());
        payload.put("output", objectMapper.valueToTree(result.output()));
        payload.put("evidence", objectMapper.valueToTree(result.evidence()));
        payload.put(
            "diagnostics",
            objectMapper.valueToTree(result.diagnostics())
        );
        payload.put("failure", objectMapper.valueToTree(result.failure()));
        payload.put(
            "actionProposal",
            objectMapper.valueToTree(result.actionProposal())
        );
        payload.put(
            "needsUserInput",
            objectMapper.valueToTree(result.needsUserInput())
        );
        payload.put("startedAt", result.startedAt().toString());
        payload.put("completedAt", result.completedAt().toString());
        return security.protect(
            payload,
            binding(record.invocationId(), "result")
        );
    }

    public AIExecutionResult<?> unprotectResult(
        DurableExecutionRecord record
    ) {
        if (record.protectedResult() == null) {
            return null;
        }
        Map<String, Object> payload = security.unprotect(
            record.protectedResult(),
            binding(record.invocationId(), "result")
        );
        RegisteredSpecialist specialist =
            specialistRegistry.requireRegistered(record.specialistId());
        Object output = null;
        Object rawOutput = payload.get("output");
        if (rawOutput != null && !node(rawOutput).isNull()) {
            output = convert(
                rawOutput,
                specialist.definition().outputAdapter().outputType()
            );
        }
        return new AIExecutionResult<>(
            record.invocationId(),
            record.specialistId(),
            AIExecutionStatus.valueOf(required(payload, "status")),
            output,
            list(payload.get("evidence"), AIEvidenceReference.class),
            map(payload.get("diagnostics")),
            nullable(payload.get("failure"), AIExecutionFailure.class),
            instant(payload, "startedAt"),
            instant(payload, "completedAt"),
            nullable(payload.get("actionProposal"), ActionProposalView.class),
            nullable(payload.get("needsUserInput"), NeedsUserInput.class)
        );
    }

    private Object convert(Object value, Class<?> targetType) {
        JsonNode valueNode = node(value);
        if (JsonNode.class.isAssignableFrom(targetType)) {
            return valueNode.deepCopy();
        }
        return objectMapper.convertValue(valueNode, targetType);
    }

    private <T> T nullable(Object value, Class<T> targetType) {
        if (value == null || node(value).isNull()) {
            return null;
        }
        return objectMapper.convertValue(value, targetType);
    }

    private <T> List<T> list(Object value, Class<T> elementType) {
        if (value == null || node(value).isNull()) {
            return List.of();
        }
        JavaType type = objectMapper.getTypeFactory()
            .constructCollectionType(List.class, elementType);
        List<T> converted = objectMapper.convertValue(value, type);
        return converted == null ? List.of() : List.copyOf(converted);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        if (value == null || node(value).isNull()) {
            return Map.of();
        }
        Map<String, Object> converted = objectMapper.convertValue(
            value,
            Map.class
        );
        return converted == null
            ? Map.of()
            : Collections.unmodifiableMap(
                new LinkedHashMap<>(converted)
            );
    }

    private Set<String> scopes(Object value) {
        if (value == null) {
            return Set.of();
        }
        List<?> values = objectMapper.convertValue(value, List.class);
        LinkedHashSet<String> scopes = new LinkedHashSet<>();
        for (Object item : values == null ? new ArrayList<>() : values) {
            if (item != null && !item.toString().isBlank()) {
                scopes.add(item.toString().trim());
            }
        }
        return Set.copyOf(scopes);
    }

    private JsonNode node(Object value) {
        return value instanceof JsonNode jsonNode
            ? jsonNode
            : objectMapper.valueToTree(value);
    }

    private String required(Map<String, Object> payload, String field) {
        String value = optional(payload, field);
        if (value == null) {
            throw new IllegalArgumentException(
                "Protected execution payload is missing " + field
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

    private String binding(String invocationId, String payloadType) {
        if (invocationId == null || invocationId.isBlank()) {
            throw new IllegalArgumentException(
                "invocationId is required"
            );
        }
        return invocationId.trim() + ":" + payloadType;
    }
}
