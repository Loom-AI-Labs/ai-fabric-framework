package ai.fabric.execution.review;

import ai.fabric.execution.action.ActionProposalReceipt;
import ai.fabric.execution.action.ActionProposalSecurity;
import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.review.decision.ReviewDecisionRequest;
import ai.fabric.execution.review.policy.ReviewPolicyId;
import ai.fabric.execution.specialist.SpecialistId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Encryption and keyed binding support for review state. Review keys are
 * intentionally independent from action-receipt and durable-job keys.
 */
public final class ReviewSecurity {

    private static final SpecialistId REVIEW_SCOPE =
        SpecialistId.of("durable-human-review", "1");

    private final ActionProposalSecurity delegate;
    private final ObjectMapper objectMapper;

    public ReviewSecurity(
        ObjectMapper objectMapper,
        String encryptionSecret,
        String fingerprintSecret
    ) {
        this.objectMapper = Objects.requireNonNull(
            objectMapper,
            "objectMapper is required"
        ).copy();
        this.delegate = new ActionProposalSecurity(
            objectMapper,
            encryptionSecret,
            fingerprintSecret
        );
    }

    public String protectSource(
        String taskId,
        String receiptId,
        TrustedExecutionContext context
    ) {
        Objects.requireNonNull(context, "trusted context is required");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("receiptId", requireText(receiptId, "receiptId"));
        payload.put("initiatorId", context.initiator().principalId());
        payload.put(
            "initiatorType",
            context.initiator().principalType().name()
        );
        if (context.subject() != null) {
            payload.put("subjectType", context.subject().subjectType());
            payload.put("subjectId", context.subject().subjectId());
        }
        payload.put("source", context.source().name());
        putOptional(payload, "tenantId", context.tenantId());
        putOptional(payload, "deploymentId", context.deploymentId());
        payload.put("grantedScopes", List.copyOf(context.grantedScopes()));
        payload.put("correlationId", context.correlationId());
        if (context.authenticatedAt() != null) {
            payload.put(
                "authenticatedAt",
                context.authenticatedAt().toString()
            );
        }
        return delegate.protect(payload, sourceBinding(taskId));
    }

    public ReviewSourceEnvelope unprotectSource(
        String taskId,
        String protectedSource
    ) {
        Map<String, Object> payload = delegate.unprotect(
            protectedSource,
            sourceBinding(taskId)
        );
        ExecutionPrincipal initiator = new ExecutionPrincipal(
            text(payload, "initiatorId"),
            ExecutionPrincipalType.valueOf(text(payload, "initiatorType"))
        );
        String subjectType = optionalText(payload, "subjectType");
        String subjectId = optionalText(payload, "subjectId");
        ExecutionSubjectRef subject =
            subjectType == null && subjectId == null
                ? null
                : new ExecutionSubjectRef(
                    requireText(subjectType, "subjectType"),
                    requireText(subjectId, "subjectId")
                );
        TrustedExecutionContext context = new TrustedExecutionContext(
            initiator,
            subject,
            ExecutionSource.valueOf(text(payload, "source")),
            optionalText(payload, "tenantId"),
            optionalText(payload, "deploymentId"),
            stringSet(payload.get("grantedScopes")),
            optionalText(payload, "correlationId"),
            instant(payload, "authenticatedAt")
        );
        return new ReviewSourceEnvelope(
            text(payload, "receiptId"),
            context
        );
    }

    public String protectPresentation(
        String taskId,
        String title,
        String summary
    ) {
        return delegate.protect(
            Map.of(
                "title",
                requireText(title, "title"),
                "summary",
                requireText(summary, "summary")
            ),
            presentationBinding(taskId)
        );
    }

    public ReviewPresentation unprotectPresentation(
        String taskId,
        String protectedPresentation
    ) {
        Map<String, Object> payload = delegate.unprotect(
            protectedPresentation,
            presentationBinding(taskId)
        );
        return new ReviewPresentation(
            text(payload, "title"),
            text(payload, "summary")
        );
    }

    public String protectDecision(
        String taskId,
        ReviewDecisionRequest request,
        TrustedReviewerContext reviewer
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decisionId", request.decisionId());
        payload.put("decision", request.decision().name());
        payload.put("expectedVersion", request.expectedVersion());
        JsonNode response = request.response();
        if (response != null) {
            payload.put("response", response);
        }
        payload.put("reviewerId", reviewer.reviewer().principalId());
        payload.put(
            "reviewerType",
            reviewer.reviewer().principalType().name()
        );
        putOptional(payload, "tenantId", reviewer.tenantId());
        payload.put("grantedScopes", List.copyOf(reviewer.grantedScopes()));
        payload.put("correlationId", reviewer.correlationId());
        payload.put(
            "authenticatedAt",
            reviewer.authenticatedAt().toString()
        );
        return delegate.protect(payload, decisionBinding(taskId));
    }

    public ReviewDecisionEnvelope unprotectDecision(
        String taskId,
        String protectedDecision
    ) {
        Map<String, Object> payload = delegate.unprotect(
            protectedDecision,
            decisionBinding(taskId)
        );
        JsonNode response = payload.containsKey("response")
            ? objectMapper.valueToTree(payload.get("response"))
            : null;
        TrustedReviewerContext reviewer = new TrustedReviewerContext(
            new ExecutionPrincipal(
                text(payload, "reviewerId"),
                ExecutionPrincipalType.valueOf(
                    text(payload, "reviewerType")
                )
            ),
            optionalText(payload, "tenantId"),
            stringSet(payload.get("grantedScopes")),
            optionalText(payload, "correlationId"),
            instantRequired(payload, "authenticatedAt")
        );
        return new ReviewDecisionEnvelope(
            text(payload, "decisionId"),
            ai.fabric.execution.review.decision.ReviewDecisionType.valueOf(
                text(payload, "decision")
            ),
            longValue(payload, "expectedVersion"),
            response,
            reviewer
        );
    }

    public String protectResult(
        String taskId,
        Map<String, Object> safeResult
    ) {
        return delegate.protect(
            safeResult == null ? Map.of() : safeResult,
            resultBinding(taskId)
        );
    }

    public Map<String, Object> unprotectResult(
        String taskId,
        String protectedResult
    ) {
        return delegate.unprotect(
            protectedResult,
            resultBinding(taskId)
        );
    }

    public String sourceFingerprint(ActionProposalReceipt receipt) {
        Objects.requireNonNull(receipt, "receipt is required");
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("receiptId", receipt.receiptId());
        source.put("specialistId", receipt.specialistId().toString());
        source.put(
            "specialistContentHash",
            receipt.specialistContentHash()
        );
        source.put("effectiveProfileHash", receipt.effectiveProfileHash());
        source.put("actionName", receipt.actionName());
        source.put("parameterHash", receipt.parameterHash());
        source.put("parameterSchemaHash", receipt.parameterSchemaHash());
        source.put("evidenceHashes", receipt.evidenceHashes());
        source.put("expiresAt", receipt.expiresAt().toString());
        return delegate.canonicalHash(source);
    }

    public String initiatorFingerprint(TrustedExecutionContext context) {
        return delegate.principalFingerprint(context);
    }

    public String subjectFingerprint(TrustedExecutionContext context) {
        return delegate.subjectFingerprint(context);
    }

    public String tenantFingerprint(TrustedExecutionContext context) {
        return delegate.tenantFingerprint(context);
    }

    public String deploymentFingerprint(TrustedExecutionContext context) {
        return delegate.deploymentFingerprint(context);
    }

    public String tenantFingerprint(TrustedReviewerContext reviewer) {
        return delegate.tenantFingerprint(asExecutionContext(reviewer));
    }

    public String reviewerFingerprint(TrustedReviewerContext reviewer) {
        return delegate.principalFingerprint(asExecutionContext(reviewer));
    }

    public String idempotencyFingerprint(
        TrustedExecutionContext context,
        ReviewPolicyId policyId,
        String idempotencyKey
    ) {
        return delegate.idempotencyFingerprint(
            context,
            REVIEW_SCOPE,
            policyId + ":" + requireText(
                idempotencyKey,
                "idempotencyKey"
            )
        );
    }

    public String decisionFingerprint(
        String taskId,
        ReviewDecisionRequest request
    ) {
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("taskId", taskId);
        decision.put("decisionId", request.decisionId());
        decision.put("decision", request.decision().name());
        decision.put("expectedVersion", request.expectedVersion());
        decision.put("response", request.response());
        return delegate.canonicalHash(decision);
    }

    public String creationFingerprint(
        ReviewPolicyId policyId,
        String sourceFingerprint,
        String title,
        String summary
    ) {
        return delegate.canonicalHash(Map.of(
            "policyId",
            Objects.requireNonNull(policyId, "policyId is required")
                .toString(),
            "sourceFingerprint",
            requireText(sourceFingerprint, "sourceFingerprint"),
            "title",
            requireText(title, "title"),
            "summary",
            requireText(summary, "summary")
        ));
    }

    public boolean sameFingerprint(String left, String right) {
        return delegate.sameFingerprint(left, right);
    }

    private TrustedExecutionContext asExecutionContext(
        TrustedReviewerContext reviewer
    ) {
        ExecutionSource source =
            reviewer.reviewer().principalType()
                == ExecutionPrincipalType.END_USER
                ? ExecutionSource.INTERACTIVE
                : ExecutionSource.APPLICATION;
        return new TrustedExecutionContext(
            reviewer.reviewer(),
            null,
            source,
            reviewer.tenantId(),
            null,
            reviewer.grantedScopes(),
            reviewer.correlationId(),
            reviewer.authenticatedAt()
        );
    }

    private Set<String> stringSet(Object value) {
        if (!(value instanceof List<?> list)) {
            return Set.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (Object item : list) {
            if (item != null) {
                values.add(item.toString());
            }
        }
        return Set.copyOf(values);
    }

    private long longValue(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException(
                "Protected review field is invalid: " + field
            );
        }
        return number.longValue();
    }

    private Instant instant(Map<String, Object> payload, String field) {
        String value = optionalText(payload, field);
        return value == null ? null : Instant.parse(value);
    }

    private Instant instantRequired(
        Map<String, Object> payload,
        String field
    ) {
        Instant value = instant(payload, field);
        if (value == null) {
            throw new IllegalStateException(
                "Protected review field is missing: " + field
            );
        }
        return value;
    }

    private String text(Map<String, Object> payload, String field) {
        return requireText(optionalText(payload, field), field);
    }

    private String optionalText(
        Map<String, Object> payload,
        String field
    ) {
        Object value = payload.get(field);
        if (value == null) {
            return null;
        }
        String normalized = value.toString().trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private void putOptional(
        Map<String, Object> payload,
        String field,
        String value
    ) {
        if (value != null) {
            payload.put(field, value);
        }
    }

    private String sourceBinding(String taskId) {
        return requireText(taskId, "taskId") + ":source";
    }

    private String presentationBinding(String taskId) {
        return requireText(taskId, "taskId") + ":presentation";
    }

    private String decisionBinding(String taskId) {
        return requireText(taskId, "taskId") + ":decision";
    }

    private String resultBinding(String taskId) {
        return requireText(taskId, "taskId") + ":result";
    }

    private String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(
            value,
            field + " is required"
        ).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    public record ReviewSourceEnvelope(
        String receiptId,
        TrustedExecutionContext context
    ) {}

    public record ReviewPresentation(String title, String summary) {}

    public record ReviewDecisionEnvelope(
        String decisionId,
        ai.fabric.execution.review.decision.ReviewDecisionType decision,
        long expectedVersion,
        JsonNode response,
        TrustedReviewerContext reviewer
    ) {

        public ReviewDecisionEnvelope {
            response = response == null ? null : response.deepCopy();
        }

        @Override
        public JsonNode response() {
            return response == null ? null : response.deepCopy();
        }
    }
}
