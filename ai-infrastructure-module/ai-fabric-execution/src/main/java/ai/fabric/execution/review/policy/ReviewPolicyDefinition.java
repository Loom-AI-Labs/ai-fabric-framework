package ai.fabric.execution.review.policy;

import ai.fabric.execution.review.decision.ReviewDecisionType;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaId;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable application-owned rules for one durable review path.
 */
public record ReviewPolicyDefinition(
    ReviewPolicyId id,
    ReviewType type,
    Set<ReviewDecisionType> allowedDecisions,
    String reviewerAuthorizerId,
    String dispatcherId,
    Set<String> requiredReviewerScopes,
    boolean separationOfDuty,
    Duration taskTtl,
    SpecialistSchemaId correctionSchemaId,
    String correctionHandlerId,
    SpecialistSchemaId informationRequestSchemaId,
    SpecialistSchemaId informationResponseSchemaId,
    String informationHandlerId,
    ReviewPolicyId escalationPolicyId
) {

    public ReviewPolicyDefinition {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(type, "type is required");
        allowedDecisions = immutableDecisions(allowedDecisions);
        reviewerAuthorizerId = requireText(
            reviewerAuthorizerId,
            "reviewerAuthorizerId"
        );
        dispatcherId = requireText(dispatcherId, "dispatcherId");
        requiredReviewerScopes = immutableScopes(requiredReviewerScopes);
        if (taskTtl == null || taskTtl.isZero() || taskTtl.isNegative()) {
            throw new IllegalArgumentException("taskTtl must be positive");
        }
        correctionHandlerId = optionalText(correctionHandlerId);
        informationHandlerId = optionalText(informationHandlerId);
        validateDecisionRequirements(
            allowedDecisions,
            correctionSchemaId,
            correctionHandlerId,
            informationRequestSchemaId,
            informationResponseSchemaId,
            informationHandlerId,
            escalationPolicyId
        );
    }

    private static Set<ReviewDecisionType> immutableDecisions(
        Set<ReviewDecisionType> decisions
    ) {
        if (decisions == null || decisions.isEmpty()) {
            throw new IllegalArgumentException(
                "allowedDecisions must not be empty"
            );
        }
        LinkedHashSet<ReviewDecisionType> normalized =
            new LinkedHashSet<>();
        for (ReviewDecisionType decision : decisions) {
            normalized.add(
                Objects.requireNonNull(
                    decision,
                    "allowed decision must not be null"
                )
            );
        }
        return Collections.unmodifiableSet(normalized);
    }

    private static Set<String> immutableScopes(Set<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String scope : scopes) {
            normalized.add(requireText(scope, "reviewer scope"));
        }
        return Collections.unmodifiableSet(normalized);
    }

    private static void validateDecisionRequirements(
        Set<ReviewDecisionType> decisions,
        SpecialistSchemaId correctionSchemaId,
        String correctionHandlerId,
        SpecialistSchemaId informationRequestSchemaId,
        SpecialistSchemaId informationResponseSchemaId,
        String informationHandlerId,
        ReviewPolicyId escalationPolicyId
    ) {
        boolean correction = decisions.contains(ReviewDecisionType.CORRECT);
        if (correction
            != (correctionSchemaId != null && correctionHandlerId != null)) {
            throw new IllegalArgumentException(
                "CORRECT requires both correction schema and handler"
            );
        }
        boolean information = decisions.contains(
            ReviewDecisionType.REQUEST_INFORMATION
        );
        if (information
            != (informationRequestSchemaId != null
                && informationResponseSchemaId != null
                && informationHandlerId != null)) {
            throw new IllegalArgumentException(
                "REQUEST_INFORMATION requires request/response schemas and a handler"
            );
        }
        boolean escalation = decisions.contains(
            ReviewDecisionType.ESCALATE
        );
        if (escalation != (escalationPolicyId != null)) {
            throw new IllegalArgumentException(
                "ESCALATE requires an escalation policy"
            );
        }
    }

    private static String requireText(String value, String field) {
        String normalized = optionalText(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (normalized.length() > 160) {
            throw new IllegalArgumentException(
                field + " must not exceed 160 characters"
            );
        }
        return normalized;
    }

    private static String optionalText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
