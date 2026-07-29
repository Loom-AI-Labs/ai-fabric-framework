package ai.fabric.execution.action;

import static org.assertj.core.api.Assertions.assertThat;

import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.review.ActionReviewRequest;
import ai.fabric.execution.review.ReviewDecisionGateway;
import ai.fabric.execution.review.ReviewRecoveryService;
import ai.fabric.execution.review.ReviewSecurity;
import ai.fabric.execution.review.ReviewTaskCreationResult;
import ai.fabric.execution.review.ReviewTaskStatus;
import ai.fabric.execution.review.TrustedReviewerContext;
import ai.fabric.execution.review.auth.ReviewerAuthorization;
import ai.fabric.execution.review.auth.ReviewerAuthorizer;
import ai.fabric.execution.review.auth.ReviewerAuthorizerRegistry;
import ai.fabric.execution.review.continuation.ReviewCorrectionHandler;
import ai.fabric.execution.review.continuation.ReviewCorrectionHandlerRegistry;
import ai.fabric.execution.review.continuation.ReviewCorrectionOutcome;
import ai.fabric.execution.review.continuation.ReviewInformationHandler;
import ai.fabric.execution.review.continuation.ReviewInformationHandlerRegistry;
import ai.fabric.execution.review.continuation.ReviewInformationRequestOutcome;
import ai.fabric.execution.review.continuation.ReviewInformationSubmissionOutcome;
import ai.fabric.execution.review.decision.ReviewDecisionRequest;
import ai.fabric.execution.review.decision.ReviewDecisionResult;
import ai.fabric.execution.review.decision.ReviewDecisionType;
import ai.fabric.execution.review.dispatch.ReviewDispatchResult;
import ai.fabric.execution.review.dispatch.ReviewTaskDispatcher;
import ai.fabric.execution.review.dispatch.ReviewTaskDispatcherRegistry;
import ai.fabric.execution.review.input.ReviewInformationResult;
import ai.fabric.execution.review.input.ReviewInformationSubmission;
import ai.fabric.execution.review.persistence.InMemoryReviewDispatchRepository;
import ai.fabric.execution.review.persistence.InMemoryReviewTaskRepository;
import ai.fabric.execution.review.persistence.ReviewTaskRecord;
import ai.fabric.execution.review.policy.DefaultReviewPolicyRegistry;
import ai.fabric.execution.review.policy.ReviewPolicyDefinition;
import ai.fabric.execution.review.policy.ReviewPolicyId;
import ai.fabric.execution.review.policy.ReviewType;
import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import ai.fabric.execution.specialist.manifest.SpecialistJsonSchemaRegistry;
import ai.fabric.execution.specialist.manifest.SpecialistJsonSchemaValidator;
import ai.fabric.execution.specialist.manifest.SpecialistResourceMetadata;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaDefinition;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaDirection;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaId;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ReviewDecisionContinuationTest {

    private static final ReviewPolicyId POLICY =
        ReviewPolicyId.of("support-credit-review", "1");
    private static final ReviewPolicyId SENIOR_POLICY =
        ReviewPolicyId.of("support-credit-senior-review", "1");
    private static final SpecialistSchemaId CORRECTION_SCHEMA =
        new SpecialistSchemaId("review-correction", "1");
    private static final SpecialistSchemaId INFORMATION_REQUEST_SCHEMA =
        new SpecialistSchemaId("review-information-request", "1");
    private static final SpecialistSchemaId INFORMATION_RESPONSE_SCHEMA =
        new SpecialistSchemaId("review-information-response", "1");
    private static final String REVIEW_SCOPE = "review:support-credit";

    @Test
    void correctionRetiresOriginalAndCreatesOneSuccessorReview() {
        Fixture fixture = new Fixture();
        ReviewTaskCreationResult created = fixture.create();

        ReviewDecisionResult corrected = fixture.gateway.decide(
            fixture.decision(
                created,
                "decision-correct-1",
                ReviewDecisionType.CORRECT,
                fixture.objectMapper.createObjectNode()
                    .put("reason", "Cap the credit at the policy limit.")
            ),
            fixture.reviewer(REVIEW_SCOPE)
        );

        assertThat(corrected.failure()).isNull();
        assertThat(corrected.task().status())
            .isEqualTo(ReviewTaskStatus.CORRECTED);
        assertThat(corrected.successorTaskId()).isNotBlank();
        assertThat(fixture.corrections).hasValue(1);
        assertThat(fixture.dispatches).hasValue(2);
        assertThat(fixture.action.confirmedInvocations).hasValue(0);
        assertThat(fixture.action.repository.findById(
            fixture.originalReceiptId
        ).orElseThrow().status())
            .isEqualTo(ActionProposalReceiptStatus.REJECTED);
        assertThat(fixture.tasks.findById(
            corrected.successorTaskId()
        ).orElseThrow().status())
            .isEqualTo(ReviewTaskStatus.WAITING_FOR_REVIEW);

        ReviewDecisionResult replay = fixture.gateway.decide(
            fixture.decision(
                created,
                "decision-correct-1",
                ReviewDecisionType.CORRECT,
                fixture.objectMapper.createObjectNode()
                    .put("reason", "Cap the credit at the policy limit.")
            ),
            fixture.reviewer(REVIEW_SCOPE)
        );

        assertThat(replay.successorTaskId())
            .isEqualTo(corrected.successorTaskId());
        assertThat(fixture.corrections).hasValue(1);
        assertThat(fixture.dispatches).hasValue(2);
    }

    @Test
    void informationRoundTripUsesSeparateTypedContracts() {
        Fixture fixture = new Fixture();
        ReviewTaskCreationResult created = fixture.create();

        ReviewDecisionResult requested = fixture.gateway.decide(
            fixture.decision(
                created,
                "decision-information-1",
                ReviewDecisionType.REQUEST_INFORMATION,
                fixture.objectMapper.createObjectNode()
                    .put("question", "Provide the support incident ID.")
            ),
            fixture.reviewer(REVIEW_SCOPE)
        );

        assertThat(requested.failure()).isNull();
        assertThat(requested.task().status())
            .isEqualTo(ReviewTaskStatus.WAITING_FOR_INFORMATION);
        assertThat(fixture.requestedInformation.get().get("question")
            .asText()).contains("incident");

        ReviewInformationResult malformed =
            fixture.gateway.provideInformation(
                new ReviewInformationSubmission(
                    created.task().taskId(),
                    "submission-invalid",
                    requested.task().version(),
                    fixture.objectMapper.createObjectNode()
                        .put("wrong", "INC-42")
                ),
                fixture.action.trustedContext
            );
        assertThat(malformed.failure().reason())
            .isEqualTo("REVIEW_INFORMATION_PAYLOAD_INVALID");

        ReviewInformationResult supplied =
            fixture.gateway.provideInformation(
                new ReviewInformationSubmission(
                    created.task().taskId(),
                    "submission-1",
                    requested.task().version(),
                    fixture.objectMapper.createObjectNode()
                        .put("answer", "INC-42")
                ),
                fixture.action.trustedContext
            );

        assertThat(supplied.failure()).isNull();
        assertThat(supplied.task().status())
            .isEqualTo(ReviewTaskStatus.WAITING_FOR_REVIEW);
        assertThat(supplied.message())
            .isEqualTo("The incident reference was attached.");
        assertThat(fixture.suppliedInformation.get().get("answer")
            .asText()).isEqualTo("INC-42");
        assertThat(fixture.dispatches).hasValue(2);
        assertThat(fixture.gateway.findDetail(
            supplied.task().taskId(),
            fixture.reviewer(REVIEW_SCOPE)
        )).get().satisfies(detail -> {
            assertThat(detail.requestedInformation().get("question")
                .asText()).contains("incident");
            assertThat(detail.suppliedInformation().get("answer")
                .asText()).isEqualTo("INC-42");
            assertThat(detail.message())
                .isEqualTo("The incident reference was attached.");
        });

        ReviewDecisionResult approved = fixture.gateway.decide(
            new ReviewDecisionRequest(
                supplied.task().taskId(),
                "decision-after-information",
                ReviewDecisionType.APPROVE,
                supplied.task().version(),
                null
            ),
            fixture.reviewer(REVIEW_SCOPE)
        );
        assertThat(approved.failure()).isNull();
        assertThat(approved.task().status())
            .isEqualTo(ReviewTaskStatus.APPROVED);
        assertThat(fixture.action.confirmedInvocations).hasValue(1);
    }

    @Test
    void escalationCreatesOneHigherAuthorityTaskWithoutExecuting() {
        Fixture fixture = new Fixture();
        ReviewTaskCreationResult created = fixture.create();

        ReviewDecisionResult escalated = fixture.gateway.decide(
            fixture.decision(
                created,
                "decision-escalate-1",
                ReviewDecisionType.ESCALATE,
                null
            ),
            fixture.reviewer(REVIEW_SCOPE)
        );

        assertThat(escalated.failure()).isNull();
        assertThat(escalated.task().status())
            .isEqualTo(ReviewTaskStatus.ESCALATED);
        assertThat(escalated.successorTaskId()).isNotBlank();
        assertThat(fixture.tasks.findById(
            escalated.successorTaskId()
        ).orElseThrow().policyId()).isEqualTo(SENIOR_POLICY);
        assertThat(fixture.action.repository.findById(
            fixture.originalReceiptId
        ).orElseThrow().status())
            .isEqualTo(ActionProposalReceiptStatus.PROPOSED);
        assertThat(fixture.action.confirmedInvocations).hasValue(0);
        assertThat(fixture.dispatches).hasValue(2);
    }

    @Test
    void malformedDecisionPayloadFailsBeforeClaimingTheTask() {
        Fixture fixture = new Fixture();
        ReviewTaskCreationResult created = fixture.create();

        ReviewDecisionResult invalid = fixture.gateway.decide(
            fixture.decision(
                created,
                "decision-invalid-correction",
                ReviewDecisionType.CORRECT,
                fixture.objectMapper.createObjectNode()
                    .put("unexpected", "value")
            ),
            fixture.reviewer(REVIEW_SCOPE)
        );

        assertThat(invalid.failure().reason())
            .isEqualTo("REVIEW_DECISION_PAYLOAD_INVALID");
        assertThat(invalid.task().status())
            .isEqualTo(ReviewTaskStatus.WAITING_FOR_REVIEW);
        assertThat(fixture.corrections).hasValue(0);
        assertThat(fixture.action.repository.findById(
            fixture.originalReceiptId
        ).orElseThrow().status())
            .isEqualTo(ActionProposalReceiptStatus.PROPOSED);
    }

    @Test
    void expiredDecisionLeaseRecoversOnceWithoutDuplicateAction() {
        Fixture fixture = new Fixture();
        ReviewTaskCreationResult created = fixture.create();
        TrustedReviewerContext reviewer = fixture.reviewer(REVIEW_SCOPE);
        ReviewDecisionRequest request = fixture.decision(
            created,
            "decision-recovery-1",
            ReviewDecisionType.APPROVE,
            null
        );
        ReviewTaskRecord waiting = fixture.tasks.findById(
            created.task().taskId()
        ).orElseThrow();
        ReviewTaskRecord claimed = waiting.claim(
            request.decision(),
            fixture.security.decisionFingerprint(
                waiting.taskId(),
                request
            ),
            fixture.security.reviewerFingerprint(reviewer),
            fixture.security.protectDecision(
                waiting.taskId(),
                request,
                reviewer
            ),
            "crashed-review-worker",
            fixture.action.clock.instant(),
            fixture.action.clock.instant().plusSeconds(30)
        );
        assertThat(fixture.tasks.compareAndSet(waiting, claimed)).isTrue();
        fixture.action.clock.advance(Duration.ofSeconds(31));

        ReviewRecoveryService recovery = new ReviewRecoveryService(
            fixture.tasks,
            fixture.dispatchRepository,
            fixture.gateway,
            fixture.action.clock,
            10,
            false,
            Duration.ofDays(30)
        );
        ReviewRecoveryService.RecoverySummary first = recovery.recover();
        ReviewRecoveryService.RecoverySummary second = recovery.recover();

        assertThat(first.recoveredDecisions()).isEqualTo(1);
        assertThat(second.recoveredDecisions()).isZero();
        assertThat(fixture.tasks.findById(
            created.task().taskId()
        ).orElseThrow().status())
            .isEqualTo(ReviewTaskStatus.APPROVED);
        assertThat(fixture.action.confirmedInvocations).hasValue(1);
    }

    @Test
    void failedDispatchIsRetriedAndAcceptedOnceByRecovery() {
        Fixture fixture = new Fixture(1);

        ReviewTaskCreationResult created = fixture.create();
        assertThat(created.dispatchAccepted()).isFalse();
        assertThat(created.failure().retryable()).isTrue();
        assertThat(fixture.dispatches).hasValue(1);

        ReviewRecoveryService recovery = new ReviewRecoveryService(
            fixture.tasks,
            fixture.dispatchRepository,
            fixture.gateway,
            fixture.action.clock,
            10,
            false,
            Duration.ofDays(30)
        );
        ReviewRecoveryService.RecoverySummary first = recovery.recover();
        ReviewRecoveryService.RecoverySummary second = recovery.recover();

        assertThat(first.recoveredDispatches()).isEqualTo(1);
        assertThat(second.recoveredDispatches()).isZero();
        assertThat(fixture.dispatches).hasValue(2);
        assertThat(fixture.dispatchRepository.findByTaskId(
            created.task().taskId()
        )).extracting(dispatch -> dispatch.status().name())
            .containsExactly("FAILED", "ACCEPTED");
    }

    @Test
    void untouchedTaskExpiresWithoutChangingItsActionProposal() {
        Fixture fixture = new Fixture();
        ReviewTaskCreationResult created = fixture.create();
        fixture.action.clock.advance(Duration.ofMinutes(6));

        ReviewRecoveryService recovery = new ReviewRecoveryService(
            fixture.tasks,
            fixture.dispatchRepository,
            fixture.gateway,
            fixture.action.clock,
            10,
            false,
            Duration.ofDays(30)
        );
        ReviewRecoveryService.RecoverySummary summary = recovery.recover();

        assertThat(summary.expiredTasks()).isEqualTo(1);
        assertThat(fixture.tasks.findById(
            created.task().taskId()
        ).orElseThrow().status())
            .isEqualTo(ReviewTaskStatus.EXPIRED);
        assertThat(fixture.action.repository.findById(
            fixture.originalReceiptId
        ).orElseThrow().status())
            .isEqualTo(ActionProposalReceiptStatus.PROPOSED);
        assertThat(fixture.action.confirmedInvocations).hasValue(0);
    }

    @Test
    void cleanupRemovesTerminalTaskAndItsDispatchHistory() {
        Fixture fixture = new Fixture();
        ReviewTaskCreationResult created = fixture.create();
        ReviewDecisionResult rejected = fixture.gateway.decide(
            fixture.decision(
                created,
                "decision-cleanup-reject",
                ReviewDecisionType.REJECT,
                null
            ),
            fixture.reviewer(REVIEW_SCOPE)
        );
        assertThat(rejected.task().status())
            .isEqualTo(ReviewTaskStatus.REJECTED);
        fixture.action.clock.advance(Duration.ofDays(31));

        ReviewRecoveryService recovery = new ReviewRecoveryService(
            fixture.tasks,
            fixture.dispatchRepository,
            fixture.gateway,
            fixture.action.clock,
            10,
            true,
            Duration.ofDays(30)
        );
        ReviewRecoveryService.RecoverySummary summary = recovery.recover();

        assertThat(summary.deletedAfterRetention()).isEqualTo(1);
        assertThat(fixture.tasks.findById(
            created.task().taskId()
        )).isEmpty();
        assertThat(fixture.dispatchRepository.findByTaskId(
            created.task().taskId()
        )).isEmpty();
    }

    private static final class Fixture {

        private final ObjectMapper objectMapper = new ObjectMapper();
        private final ActionProposalTestFixture action =
            new ActionProposalTestFixture();
        private final InMemoryReviewTaskRepository tasks =
            new InMemoryReviewTaskRepository();
        private final InMemoryReviewDispatchRepository dispatchRepository =
            new InMemoryReviewDispatchRepository();
        private final ReviewSecurity security = new ReviewSecurity(
            objectMapper,
            "review-encryption-secret-at-least-32-characters",
            "review-fingerprint-secret-at-least-32-characters"
        );
        private final AtomicInteger dispatches = new AtomicInteger();
        private final AtomicInteger dispatchFailuresRemaining;
        private final AtomicInteger corrections = new AtomicInteger();
        private final AtomicReference<JsonNode> requestedInformation =
            new AtomicReference<>();
        private final AtomicReference<JsonNode> suppliedInformation =
            new AtomicReference<>();
        private final ReviewDecisionGateway gateway;
        private String originalReceiptId;

        private Fixture() {
            this(0);
        }

        private Fixture(int dispatchFailures) {
            this.dispatchFailuresRemaining = new AtomicInteger(
                dispatchFailures
            );
            SpecialistJsonSchemaValidator schemaValidator =
                new SpecialistJsonSchemaValidator();
            SpecialistJsonSchemaRegistry schemas =
                new SpecialistJsonSchemaRegistry(
                    List.of(
                        schema(
                            CORRECTION_SCHEMA,
                            "reason",
                            "string"
                        ),
                        schema(
                            INFORMATION_REQUEST_SCHEMA,
                            "question",
                            "string"
                        ),
                        schema(
                            INFORMATION_RESPONSE_SCHEMA,
                            "answer",
                            "string"
                        )
                    ),
                    schemaValidator
                );
            ReviewerAuthorizerRegistry authorizers =
                new ReviewerAuthorizerRegistry(List.of(authorizer()));
            ReviewTaskDispatcherRegistry dispatchers =
                new ReviewTaskDispatcherRegistry(List.of(dispatcher()));
            ReviewCorrectionHandlerRegistry correctionHandlers =
                new ReviewCorrectionHandlerRegistry(
                    List.of(correctionHandler())
                );
            ReviewInformationHandlerRegistry informationHandlers =
                new ReviewInformationHandlerRegistry(
                    List.of(informationHandler())
                );
            DefaultReviewPolicyRegistry policies =
                new DefaultReviewPolicyRegistry(
                    List.of(policy(), seniorPolicy()),
                    schemas,
                    new CanonicalJsonSupport(objectMapper),
                    authorizers,
                    dispatchers,
                    correctionHandlers,
                    informationHandlers
                );
            gateway = new ReviewDecisionGateway(
                tasks,
                dispatchRepository,
                policies,
                authorizers,
                dispatchers,
                correctionHandlers,
                informationHandlers,
                schemas,
                schemaValidator,
                action.repository,
                action.security,
                action.coordinator,
                security,
                objectMapper,
                action.clock,
                Duration.ofSeconds(30),
                3,
                3
            );
        }

        private ReviewTaskCreationResult create() {
            ActionProposalView proposal = action.propose();
            originalReceiptId = proposal.receiptId();
            return gateway.createActionReview(
                new ActionReviewRequest(
                    proposal.receiptId(),
                    POLICY,
                    "Review a support credit",
                    "Review an AI-proposed account credit.",
                    "review-request-1"
                ),
                action.trustedContext
            );
        }

        private ReviewDecisionRequest decision(
            ReviewTaskCreationResult created,
            String decisionId,
            ReviewDecisionType decision,
            JsonNode response
        ) {
            return new ReviewDecisionRequest(
                created.task().taskId(),
                decisionId,
                decision,
                created.task().version(),
                response
            );
        }

        private TrustedReviewerContext reviewer(String... scopes) {
            return new TrustedReviewerContext(
                new ExecutionPrincipal(
                    "reviewer-1",
                    ExecutionPrincipalType.END_USER
                ),
                "tenant-1",
                Set.of(scopes),
                "review-correlation-1",
                ActionProposalTestFixture.NOW
            );
        }

        private ReviewPolicyDefinition policy() {
            return new ReviewPolicyDefinition(
                POLICY,
                ReviewType.OPERATIONAL_REVIEW,
                Set.of(
                    ReviewDecisionType.APPROVE,
                    ReviewDecisionType.REJECT,
                    ReviewDecisionType.CORRECT,
                    ReviewDecisionType.REQUEST_INFORMATION,
                    ReviewDecisionType.ESCALATE
                ),
                "support-review-authorizer@1",
                "local-review-inbox@1",
                Set.of(REVIEW_SCOPE),
                true,
                Duration.ofMinutes(5),
                CORRECTION_SCHEMA,
                "support-credit-correction@1",
                INFORMATION_REQUEST_SCHEMA,
                INFORMATION_RESPONSE_SCHEMA,
                "support-credit-information@1",
                SENIOR_POLICY
            );
        }

        private ReviewPolicyDefinition seniorPolicy() {
            return new ReviewPolicyDefinition(
                SENIOR_POLICY,
                ReviewType.OPERATIONAL_REVIEW,
                Set.of(
                    ReviewDecisionType.APPROVE,
                    ReviewDecisionType.REJECT
                ),
                "support-review-authorizer@1",
                "local-review-inbox@1",
                Set.of(REVIEW_SCOPE, "review:support-credit:senior"),
                true,
                Duration.ofMinutes(5),
                null,
                null,
                null,
                null,
                null,
                null
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
                    if (dispatchFailuresRemaining.getAndUpdate(
                            value -> Math.max(0, value - 1)
                        ) > 0) {
                        return ReviewDispatchResult.failed(
                            "TEMPORARY_INBOX_FAILURE"
                        );
                    }
                    return ReviewDispatchResult.accepted(
                        "local-inbox-" + request.task().taskId()
                    );
                }
            };
        }

        private ReviewCorrectionHandler correctionHandler() {
            return new ReviewCorrectionHandler() {
                @Override
                public String id() {
                    return "support-credit-correction@1";
                }

                @Override
                public ReviewCorrectionOutcome correct(
                    ai.fabric.execution.review.continuation
                        .ReviewCorrectionContext context
                ) {
                    corrections.incrementAndGet();
                    return ReviewCorrectionOutcome.successor(
                        action.propose(
                            "corrected-" + context.task().taskId()
                        ).receiptId()
                    );
                }
            };
        }

        private ReviewInformationHandler informationHandler() {
            return new ReviewInformationHandler() {
                @Override
                public String id() {
                    return "support-credit-information@1";
                }

                @Override
                public ReviewInformationRequestOutcome requestInformation(
                    ai.fabric.execution.review.continuation
                        .ReviewInformationRequestContext context
                ) {
                    requestedInformation.set(context.request());
                    return new ReviewInformationRequestOutcome(
                        "The support incident ID is required."
                    );
                }

                @Override
                public ReviewInformationSubmissionOutcome receiveInformation(
                    ai.fabric.execution.review.continuation
                        .ReviewInformationSubmissionContext context
                ) {
                    suppliedInformation.set(
                        context.suppliedInformation()
                    );
                    return new ReviewInformationSubmissionOutcome(
                        "The incident reference was attached."
                    );
                }
            };
        }

        private SpecialistSchemaDefinition schema(
            SpecialistSchemaId id,
            String requiredProperty,
            String type
        ) {
            ObjectNode schema = objectMapper.createObjectNode();
            schema.put(
                "$schema",
                "https://json-schema.org/draft/2020-12/schema"
            );
            schema.put("type", "object");
            schema.put("additionalProperties", false);
            schema.putArray("required").add(requiredProperty);
            schema.putObject("properties")
                .putObject(requiredProperty)
                .put("type", type)
                .put("minLength", 1)
                .put("maxLength", 500);
            return new SpecialistSchemaDefinition(
                "ai.fabric/v1",
                "SpecialistSchema",
                new SpecialistResourceMetadata(
                    id.name(),
                    id.version()
                ),
                new SpecialistSchemaSpec(
                    SpecialistSchemaDirection.INPUT,
                    "2020-12",
                    schema
                )
            );
        }
    }
}
