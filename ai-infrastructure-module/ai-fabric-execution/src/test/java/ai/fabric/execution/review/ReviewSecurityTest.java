package ai.fabric.execution.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.review.decision.ReviewDecisionRequest;
import ai.fabric.execution.review.decision.ReviewDecisionType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReviewSecurityTest {

    private static final Instant NOW =
        Instant.parse("2026-07-29T12:00:00Z");
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReviewSecurity security = new ReviewSecurity(
        objectMapper,
        "review-encryption-secret-with-at-least-32-characters",
        "review-fingerprint-secret-with-at-least-32-characters"
    );

    @Test
    void protectsSourcePresentationAndReviewerDecisionWithTaskBinding() {
        TrustedExecutionContext source = source();
        TrustedReviewerContext reviewer = reviewer();
        String protectedSource = security.protectSource(
            "task-1",
            "receipt-secret-1",
            source
        );
        String presentation = security.protectPresentation(
            "task-1",
            "Approve a support credit",
            "Customer experienced a verified incident."
        );
        ReviewDecisionRequest decision = new ReviewDecisionRequest(
            "task-1",
            "decision-1",
            ReviewDecisionType.APPROVE,
            0,
            objectMapper.createObjectNode().put("reason", "verified")
        );
        String protectedDecision = security.protectDecision(
            "task-1",
            decision,
            reviewer
        );

        assertThat(protectedSource)
            .doesNotContain(
                "receipt-secret-1",
                "account-1",
                "principal-1"
            );
        assertThat(presentation)
            .doesNotContain("support credit", "verified incident");
        assertThat(protectedDecision)
            .doesNotContain("reviewer-1", "verified");
        assertThat(security.unprotectSource(
            "task-1",
            protectedSource
        ).context()).isEqualTo(source);
        assertThat(security.unprotectPresentation(
            "task-1",
            presentation
        ).title()).isEqualTo("Approve a support credit");
        assertThat(security.unprotectDecision(
            "task-1",
            protectedDecision
        ).reviewer()).isEqualTo(reviewer);
        assertThat(security.decisionFingerprint(
            "task-1",
            decision
        )).isNotEqualTo(security.decisionFingerprint(
            "task-1",
            new ReviewDecisionRequest(
                "task-1",
                "decision-1",
                ReviewDecisionType.APPROVE,
                1,
                objectMapper.createObjectNode().put("reason", "verified")
            )
        ));

        assertThatThrownBy(() ->
            security.unprotectSource("task-2", protectedSource)
        ).isInstanceOf(IllegalStateException.class);
    }

    private TrustedExecutionContext source() {
        return new TrustedExecutionContext(
            new ExecutionPrincipal(
                "principal-1",
                ExecutionPrincipalType.END_USER
            ),
            new ExecutionSubjectRef("account", "account-1"),
            ExecutionSource.INTERACTIVE,
            "tenant-1",
            "deployment-1",
            Set.of("action:update_address"),
            "correlation-1",
            NOW
        );
    }

    private TrustedReviewerContext reviewer() {
        return new TrustedReviewerContext(
            new ExecutionPrincipal(
                "reviewer-1",
                ExecutionPrincipalType.END_USER
            ),
            "tenant-1",
            Set.of("review:support"),
            "review-correlation-1",
            NOW
        );
    }
}
