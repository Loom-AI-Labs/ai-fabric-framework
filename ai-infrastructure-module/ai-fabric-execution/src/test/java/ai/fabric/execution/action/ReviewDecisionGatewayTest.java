package ai.fabric.execution.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.review.ActionReviewRequest;
import ai.fabric.execution.review.ReviewDecisionGateway;
import ai.fabric.execution.review.ReviewSecurity;
import ai.fabric.execution.review.ReviewTaskCreationResult;
import ai.fabric.execution.review.ReviewTaskStatus;
import ai.fabric.execution.review.TrustedReviewerContext;
import ai.fabric.execution.review.auth.ReviewerAuthorization;
import ai.fabric.execution.review.auth.ReviewerAuthorizer;
import ai.fabric.execution.review.auth.ReviewerAuthorizerRegistry;
import ai.fabric.execution.review.continuation.ReviewCorrectionHandlerRegistry;
import ai.fabric.execution.review.continuation.ReviewInformationHandlerRegistry;
import ai.fabric.execution.review.decision.ReviewDecisionRequest;
import ai.fabric.execution.review.decision.ReviewDecisionResult;
import ai.fabric.execution.review.decision.ReviewDecisionType;
import ai.fabric.execution.review.dispatch.ReviewDispatchResult;
import ai.fabric.execution.review.dispatch.ReviewTaskDispatcher;
import ai.fabric.execution.review.dispatch.ReviewTaskDispatcherRegistry;
import ai.fabric.execution.review.persistence.InMemoryReviewDispatchRepository;
import ai.fabric.execution.review.persistence.InMemoryReviewTaskRepository;
import ai.fabric.execution.review.policy.DefaultReviewPolicyRegistry;
import ai.fabric.execution.review.policy.ReviewPolicyDefinition;
import ai.fabric.execution.review.policy.ReviewPolicyId;
import ai.fabric.execution.review.policy.ReviewType;
import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import ai.fabric.execution.specialist.manifest.SpecialistJsonSchemaRegistry;
import ai.fabric.execution.specialist.manifest.SpecialistJsonSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ReviewDecisionGatewayTest {

    private static final ReviewPolicyId POLICY =
        ReviewPolicyId.of("support-credit-review", "1");
    private static final String REVIEW_SCOPE = "review:support-credit";

    @Test
    void persistsBeforeDispatchAndApprovalUsesGovernedActionReceipt() {
        Fixture fixture = new Fixture();
        ActionProposalView proposal = fixture.action.propose();

        ReviewTaskCreationResult created = fixture.create(proposal);

        assertThat(created.created()).isTrue();
        assertThat(created.dispatchAccepted()).isTrue();
        assertThat(fixture.dispatches).hasValue(1);
        assertThat(fixture.action.confirmedInvocations).hasValue(0);
        assertThat(fixture.tasks.findById(created.task().taskId()))
            .isPresent();

        ReviewDecisionResult approved = fixture.gateway.decide(
            new ReviewDecisionRequest(
                created.task().taskId(),
                "decision-approve-1",
                ReviewDecisionType.APPROVE,
                created.task().version(),
                null
            ),
            fixture.reviewer("reviewer-1", "tenant-1")
        );

        assertThat(approved.failure()).isNull();
        assertThat(approved.task().status())
            .isEqualTo(ReviewTaskStatus.APPROVED);
        assertThat(approved.outcome().data())
            .containsEntry("updated", true)
            .doesNotContainKeys("subscriptionId", "streetAddress");
        assertThat(fixture.action.confirmedInvocations).hasValue(1);

        ReviewDecisionResult replay = fixture.gateway.decide(
            new ReviewDecisionRequest(
                created.task().taskId(),
                "decision-approve-1",
                ReviewDecisionType.APPROVE,
                created.task().version(),
                null
            ),
            fixture.reviewer("reviewer-1", "tenant-1")
        );
        assertThat(replay.task().status())
            .isEqualTo(ReviewTaskStatus.APPROVED);
        assertThat(fixture.action.confirmedInvocations).hasValue(1);
    }

    @Test
    void separationOfDutyAndTenantBindingFailClosed() {
        Fixture fixture = new Fixture();
        ReviewTaskCreationResult created = fixture.create(
            fixture.action.propose()
        );

        ReviewDecisionResult initiatorDecision = fixture.gateway.decide(
            new ReviewDecisionRequest(
                created.task().taskId(),
                "decision-initiator",
                ReviewDecisionType.APPROVE,
                created.task().version(),
                null
            ),
            fixture.reviewer("principal-1", "tenant-1")
        );
        ReviewDecisionResult otherTenantDecision = fixture.gateway.decide(
            new ReviewDecisionRequest(
                created.task().taskId(),
                "decision-other-tenant",
                ReviewDecisionType.APPROVE,
                created.task().version(),
                null
            ),
            fixture.reviewer("reviewer-1", "tenant-2")
        );

        assertThat(initiatorDecision.failure().reason())
            .isEqualTo("REVIEW_TASK_NOT_AVAILABLE");
        assertThat(otherTenantDecision.failure().reason())
            .isEqualTo("REVIEW_TASK_NOT_AVAILABLE");
        assertThat(fixture.action.confirmedInvocations).hasValue(0);
    }

    @Test
    void rejectionIsDurableAndNeverExecutesTheAction() {
        Fixture fixture = new Fixture();
        ReviewTaskCreationResult created = fixture.create(
            fixture.action.propose()
        );

        ReviewDecisionResult rejected = fixture.gateway.decide(
            new ReviewDecisionRequest(
                created.task().taskId(),
                "decision-reject-1",
                ReviewDecisionType.REJECT,
                created.task().version(),
                null
            ),
            fixture.reviewer("reviewer-1", "tenant-1")
        );

        assertThat(rejected.failure()).isNull();
        assertThat(rejected.task().status())
            .isEqualTo(ReviewTaskStatus.REJECTED);
        assertThat(fixture.action.confirmedInvocations).hasValue(0);
        assertThat(fixture.action.repository.findById(
            fixture.proposalReceiptId
        ).orElseThrow().status())
            .isEqualTo(ActionProposalReceiptStatus.REJECTED);
    }

    @Test
    void creationReplayIsExactAndDoesNotDispatchTwice() {
        Fixture fixture = new Fixture();
        ActionProposalView proposal = fixture.action.propose();
        ReviewTaskCreationResult first = fixture.create(proposal);
        ReviewTaskCreationResult replay = fixture.create(proposal);

        assertThat(replay.task().taskId())
            .isEqualTo(first.task().taskId());
        assertThat(fixture.dispatches).hasValue(1);

        assertThatThrownBy(() ->
            fixture.gateway.createActionReview(
                new ActionReviewRequest(
                    proposal.receiptId(),
                    POLICY,
                    "Different title",
                    "Review a low-risk support credit.",
                    "review-request-1"
                ),
                fixture.action.trustedContext
            )
        ).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("idempotency key");
    }

    private static final class Fixture {

        private final ObjectMapper objectMapper = new ObjectMapper();
        private final ActionProposalTestFixture action =
            new ActionProposalTestFixture();
        private final InMemoryReviewTaskRepository tasks =
            new InMemoryReviewTaskRepository();
        private final AtomicInteger dispatches = new AtomicInteger();
        private final SpecialistJsonSchemaValidator schemaValidator =
            new SpecialistJsonSchemaValidator();
        private final SpecialistJsonSchemaRegistry schemas =
            new SpecialistJsonSchemaRegistry(
                List.of(),
                schemaValidator
            );
        private final ReviewerAuthorizerRegistry authorizers =
            new ReviewerAuthorizerRegistry(List.of(authorizer()));
        private final ReviewTaskDispatcherRegistry dispatcherRegistry =
            new ReviewTaskDispatcherRegistry(List.of(dispatcher()));
        private final ReviewDecisionGateway gateway;
        private String proposalReceiptId;

        private Fixture() {
            ReviewPolicyDefinition policy = new ReviewPolicyDefinition(
                POLICY,
                ReviewType.OPERATIONAL_REVIEW,
                Set.of(
                    ReviewDecisionType.APPROVE,
                    ReviewDecisionType.REJECT
                ),
                "support-review-authorizer@1",
                "local-review-inbox@1",
                Set.of(REVIEW_SCOPE),
                true,
                Duration.ofMinutes(5),
                null,
                null,
                null,
                null,
                null,
                null
            );
            DefaultReviewPolicyRegistry policies =
                new DefaultReviewPolicyRegistry(
                    List.of(policy),
                    schemas,
                    new CanonicalJsonSupport(objectMapper),
                    authorizers,
                    dispatcherRegistry,
                    new ReviewCorrectionHandlerRegistry(List.of()),
                    new ReviewInformationHandlerRegistry(List.of())
                );
            gateway = new ReviewDecisionGateway(
                tasks,
                new InMemoryReviewDispatchRepository(),
                policies,
                authorizers,
                dispatcherRegistry,
                new ReviewCorrectionHandlerRegistry(List.of()),
                new ReviewInformationHandlerRegistry(List.of()),
                schemas,
                schemaValidator,
                action.repository,
                action.security,
                action.coordinator,
                new ReviewSecurity(
                    objectMapper,
                    "review-encryption-secret-at-least-32-characters",
                    "review-fingerprint-secret-at-least-32-characters"
                ),
                objectMapper,
                action.clock,
                Duration.ofSeconds(30),
                3,
                3
            );
        }

        private ReviewTaskCreationResult create(
            ActionProposalView proposal
        ) {
            proposalReceiptId = proposal.receiptId();
            return gateway.createActionReview(
                new ActionReviewRequest(
                    proposal.receiptId(),
                    POLICY,
                    "Approve a support credit",
                    "Review a low-risk support credit.",
                    "review-request-1"
                ),
                action.trustedContext
            );
        }

        private TrustedReviewerContext reviewer(
            String reviewerId,
            String tenantId
        ) {
            return new TrustedReviewerContext(
                new ExecutionPrincipal(
                    reviewerId,
                    ExecutionPrincipalType.END_USER
                ),
                tenantId,
                Set.of(REVIEW_SCOPE),
                "review-correlation-1",
                ActionProposalTestFixture.NOW
            );
        }

        private ReviewerAuthorizer authorizer() {
            return new ReviewerAuthorizer() {
                @Override
                public String id() {
                    return "support-review-authorizer@1";
                }

                @Override
                public ReviewerAuthorization authorize(
                    ai.fabric.execution.review.auth
                        .ReviewAuthorizationRequest request,
                    TrustedReviewerContext reviewer
                ) {
                    return ReviewerAuthorization.allow();
                }
            };
        }

        private ReviewTaskDispatcher dispatcher() {
            return new ReviewTaskDispatcher() {
                @Override
                public String id() {
                    return "local-review-inbox@1";
                }

                @Override
                public ReviewDispatchResult dispatch(
                    ai.fabric.execution.review.dispatch
                        .ReviewDispatchRequest request
                ) {
                    assertThat(tasks.findById(request.task().taskId()))
                        .isPresent();
                    dispatches.incrementAndGet();
                    return ReviewDispatchResult.accepted(
                        "local-inbox-" + request.task().taskId()
                    );
                }
            };
        }
    }
}
