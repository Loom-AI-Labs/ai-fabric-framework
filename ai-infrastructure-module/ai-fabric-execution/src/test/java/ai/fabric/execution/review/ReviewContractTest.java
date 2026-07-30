package ai.fabric.execution.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.fabric.execution.review.decision.ReviewDecisionRequest;
import ai.fabric.execution.review.decision.ReviewDecisionType;
import ai.fabric.execution.review.policy.ReviewPolicyDefinition;
import ai.fabric.execution.review.policy.ReviewPolicyId;
import ai.fabric.execution.review.policy.ReviewType;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReviewContractTest {

    @Test
    void publicDecisionCannotCarryIdentityTenantAuthorityOrDispatcher() {
        Set<String> fields = Arrays.stream(
            ReviewDecisionRequest.class.getRecordComponents()
        ).map(java.lang.reflect.RecordComponent::getName).collect(
            java.util.stream.Collectors.toSet()
        );

        assertThat(fields).containsExactlyInAnyOrder(
            "taskId",
            "decisionId",
            "decision",
            "expectedVersion",
            "response"
        );
        assertThat(fields).doesNotContain(
            "reviewer",
            "reviewerId",
            "tenantId",
            "scopes",
            "dispatcher",
            "recipient"
        );
        assertThat(Arrays.stream(ReviewType.values()).map(Enum::name))
            .doesNotContain("CONFIRMATION");
    }

    @Test
    void optionalDecisionsRequireTheirTypedContinuations() {
        ReviewPolicyId id = ReviewPolicyId.of("support-review", "1");

        assertThatThrownBy(() -> new ReviewPolicyDefinition(
            id,
            ReviewType.CORRECTION,
            Set.of(ReviewDecisionType.CORRECT),
            "support-authorizer@1",
            "support-inbox@1",
            Set.of(),
            true,
            Duration.ofMinutes(5),
            null,
            null,
            null,
            null,
            null,
            null
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("CORRECT requires");

        assertThatThrownBy(() -> new ReviewPolicyDefinition(
            id,
            ReviewType.OPERATIONAL_REVIEW,
            Set.of(ReviewDecisionType.REQUEST_INFORMATION),
            "support-authorizer@1",
            "support-inbox@1",
            Set.of(),
            true,
            Duration.ofMinutes(5),
            null,
            null,
            null,
            null,
            "information-handler@1",
            null
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("request/response schemas");

        assertThatThrownBy(() -> new ReviewPolicyDefinition(
            id,
            ReviewType.ESCALATION,
            Set.of(ReviewDecisionType.ESCALATE),
            "support-authorizer@1",
            "support-inbox@1",
            Set.of(),
            true,
            Duration.ofMinutes(5),
            null,
            null,
            null,
            null,
            null,
            null
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("escalation policy");
    }

    @Test
    void publicTaskDecisionsHaveStableApiOrder() {
        ReviewTaskView view = new ReviewTaskView(
            "review-task-1",
            ReviewPolicyId.of("support-review", "1"),
            ReviewType.OPERATIONAL_REVIEW,
            "Review support outcome",
            "Review the proposed support outcome.",
            new HashSet<>(List.of(
                ReviewDecisionType.ESCALATE,
                ReviewDecisionType.CORRECT,
                ReviewDecisionType.APPROVE,
                ReviewDecisionType.REQUEST_INFORMATION,
                ReviewDecisionType.REJECT
            )),
            ReviewTaskStatus.WAITING_FOR_REVIEW,
            Instant.parse("2026-07-30T12:00:00Z"),
            Instant.parse("2026-07-31T12:00:00Z"),
            0
        );

        assertThat(view.allowedDecisions()).containsExactly(
            ReviewDecisionType.APPROVE,
            ReviewDecisionType.REJECT,
            ReviewDecisionType.CORRECT,
            ReviewDecisionType.REQUEST_INFORMATION,
            ReviewDecisionType.ESCALATE
        );
    }
}
