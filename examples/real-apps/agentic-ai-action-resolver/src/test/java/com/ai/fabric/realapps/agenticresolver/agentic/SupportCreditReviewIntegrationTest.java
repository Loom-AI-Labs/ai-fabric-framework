package com.ai.fabric.realapps.agenticresolver.agentic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.execution.action.ActionProposalCoordinator;
import ai.fabric.execution.action.ActionProposalView;
import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.gateway.SpecialistCapabilityResolver;
import ai.fabric.execution.review.ActionReviewRequest;
import ai.fabric.execution.review.ReviewDecisionGateway;
import ai.fabric.execution.review.ReviewTaskCreationResult;
import ai.fabric.execution.specialist.SpecialistDefinition;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.invocation.ActionProposalCandidate;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilityProfile;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.pipeline.steps.OrchestrationPolicyResolutionStep;
import ai.fabric.intent.orchestration.request.ConversationPersistencePolicy;
import ai.fabric.intent.orchestration.request.OrchestrationRequest;
import ai.fabric.intent.orchestration.request.OrchestrationRequestPurpose;
import com.ai.fabric.realapps.agenticresolver.agentic.review.SupportCreditReviewPolicies;
import com.ai.fabric.realapps.agenticresolver.agentic.review.DemoReviewerSessionService;
import com.ai.fabric.realapps.agenticresolver.controller.AgenticResolverController;
import com.ai.fabric.realapps.agenticresolver.controller.SupportCreditReviewController;
import com.ai.fabric.realapps.agenticresolver.entity.RefundRequest;
import com.ai.fabric.realapps.agenticresolver.repository.RefundRequestRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:support-credit-review;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "ai.providers.openai.enabled=false",
    "ai.execution.receipts.encryption-secret=test-review-receipt-encryption-key-at-least-32",
    "ai.execution.receipts.fingerprint-secret=test-review-receipt-fingerprint-key-at-least-32",
    "ai.execution.reviews.enabled=true",
    "ai.execution.reviews.repository=JDBC",
    "ai.execution.reviews.initialize-schema=true",
    "ai.execution.reviews.cleanup-enabled=false",
    "ai.execution.reviews.encryption-secret=test-human-review-encryption-key-at-least-32",
    "ai.execution.reviews.fingerprint-secret=test-human-review-fingerprint-key-at-least-32",
    "app.agentic-resolver.reviews.reviewer-api-key=regular-review-key-0001",
    "app.agentic-resolver.reviews.senior-reviewer-api-key=senior-review-key-0002",
    "ai.vector-db.lucene.index-path=target/support-credit-review-index",
    "app.demo.cleanup.enabled=false",
    "logging.level.ai.fabric=WARN"
})
@AutoConfigureMockMvc
@Transactional
class SupportCreditReviewIntegrationTest {

    private static final String REGULAR_KEY = "regular-review-key-0001";
    private static final String SENIOR_KEY = "senior-review-key-0002";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AgenticResolverSessionService sessions;

    @Autowired
    private SpecialistRegistry specialists;

    @Autowired
    private SpecialistCapabilityResolver capabilities;

    @Autowired
    private OrchestrationPolicyResolutionStep policyResolution;

    @Autowired
    private ActionProposalCoordinator proposals;

    @Autowired
    private ReviewDecisionGateway reviews;

    @Autowired
    private RefundRequestRepository refunds;

    @Autowired
    private Clock clock;

    @Autowired
    private DemoReviewerSessionService demoReviewSessions;

    @Test
    void publicReviewerCredentialIsShortLivedAndDemoSessionBound()
        throws Exception {
        TestReview review = createReview(
            new BigDecimal("25.00"),
            RefundRequest.ResolutionType.ACCOUNT_CREDIT
        );
        demoReviewSessions.bindTask(
            review.sessionId(),
            review.task().task().taskId()
        );

        String reviewer = issueDemoReviewer(review.sessionId(), "REGULAR");
        mockMvc.perform(get("/api/agentic-resolver/demo-reviews")
                .header(
                    AgenticResolverController.SESSION_HEADER,
                    review.sessionId()
                )
                .header(
                    DemoReviewerSessionService.REVIEW_SESSION_HEADER,
                    reviewer
                ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].taskId")
                .value(review.task().task().taskId()));

        AgenticResolverSessionService.SessionView other = sessions.create();
        String otherReviewer = issueDemoReviewer(
            other.sessionId(),
            "REGULAR"
        );
        mockMvc.perform(get(
                "/api/agentic-resolver/demo-reviews/{taskId}",
                review.task().task().taskId()
            )
                .header(
                    AgenticResolverController.SESSION_HEADER,
                    other.sessionId()
                )
                .header(
                    DemoReviewerSessionService.REVIEW_SESSION_HEADER,
                    otherReviewer
                ))
            .andExpect(status().isNotFound());

        long before = refunds.count();
        mockMvc.perform(post(
                "/api/agentic-resolver/demo-reviews/{taskId}/decision",
                review.task().task().taskId()
            )
                .header(
                    AgenticResolverController.SESSION_HEADER,
                    review.sessionId()
                )
                .header(
                    DemoReviewerSessionService.REVIEW_SESSION_HEADER,
                    reviewer
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content(decisionBody(
                    "public-review-approve-1",
                    "APPROVE",
                    review.task().task().version(),
                    null
                )))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.task.status").value("APPROVED"));
        assertThat(refunds.count()).isEqualTo(before + 1);
    }

    @Test
    void reviewerApprovalExecutesExactlyOneGovernedBillingAction()
        throws Exception {
        TestReview review = createReview(
            new BigDecimal("25.00"),
            RefundRequest.ResolutionType.ACCOUNT_CREDIT
        );
        long before = refunds.count();

        String inbox = mockMvc.perform(get(
                "/api/agentic-resolver/reviews"
            )
                .header(
                    SupportCreditReviewController.REVIEW_KEY_HEADER,
                    REGULAR_KEY
                ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].taskId")
                .value(review.task().task().taskId()))
            .andExpect(jsonPath("$[0].status")
                .value("WAITING_FOR_REVIEW"))
            .andReturn()
            .getResponse()
            .getContentAsString();
        assertThat(inbox)
            .doesNotContain(
                review.proposal().receiptId(),
                review.subjectUserId().toString(),
                "\"amount\":25"
            );

        String body = decisionBody(
            "approve-credit-1",
            "APPROVE",
            review.task().task().version(),
            null
        );
        String approved = decide(
            review.task().task().taskId(),
            REGULAR_KEY,
            body
        );

        assertThat(approved)
            .contains("\"status\":\"APPROVED\"")
            .contains("\"created\":true")
            .contains("\"resolutionType\":\"ACCOUNT_CREDIT\"")
            .doesNotContain(
                review.proposal().receiptId(),
                review.subjectUserId().toString(),
                "subscriptionId",
                "refundRequestId"
            );
        assertThat(refunds.count()).isEqualTo(before + 1);

        String replay = decide(
            review.task().task().taskId(),
            REGULAR_KEY,
            body
        );
        assertThat(replay).contains("\"status\":\"APPROVED\"");
        assertThat(refunds.count()).isEqualTo(before + 1);
    }

    @Test
    void rejectionProducesNoBillingMutation() throws Exception {
        TestReview review = createReview(
            new BigDecimal("11.00"),
            RefundRequest.ResolutionType.REFUND
        );
        long before = refunds.count();

        String rejected = decide(
            review.task().task().taskId(),
            REGULAR_KEY,
            decisionBody(
                "reject-credit-1",
                "REJECT",
                review.task().task().version(),
                null
            )
        );

        assertThat(rejected).contains("\"status\":\"REJECTED\"");
        assertThat(refunds.count()).isEqualTo(before);
    }

    @Test
    void correctionCreatesAndExecutesOnlyTheRevisedProposal()
        throws Exception {
        TestReview review = createReview(
            new BigDecimal("125.00"),
            RefundRequest.ResolutionType.REFUND
        );
        long before = refunds.count();

        String corrected = decide(
            review.task().task().taskId(),
            REGULAR_KEY,
            decisionBody(
                "correct-credit-1",
                "CORRECT",
                review.task().task().version(),
                Map.of(
                    "resolutionType",
                    "ACCOUNT_CREDIT",
                    "amount",
                    new BigDecimal("20.00"),
                    "reason",
                    "Apply the approved support-credit limit"
                )
            )
        );
        String successor = objectMapper.readTree(corrected)
            .path("successorTaskId")
            .asText();

        assertThat(corrected)
            .contains("\"status\":\"CORRECTED\"")
            .doesNotContain(review.proposal().receiptId());
        assertThat(successor).startsWith("review-task-");
        assertThat(refunds.count()).isEqualTo(before);

        String successorDetail = mockMvc.perform(get(
                "/api/agentic-resolver/reviews/{taskId}",
                successor
            )
                .header(
                    SupportCreditReviewController.REVIEW_KEY_HEADER,
                    REGULAR_KEY
                ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.task.status")
                .value("WAITING_FOR_REVIEW"))
            .andReturn()
            .getResponse()
            .getContentAsString();
        long successorVersion = objectMapper.readTree(successorDetail)
            .at("/task/version")
            .asLong();

        String approved = decide(
            successor,
            REGULAR_KEY,
            decisionBody(
                "approve-corrected-credit-1",
                "APPROVE",
                successorVersion,
                null
            )
        );

        assertThat(approved)
            .contains("\"status\":\"APPROVED\"")
            .contains("\"resolutionType\":\"ACCOUNT_CREDIT\"")
            .contains("\"amount\":20");
        assertThat(refunds.count()).isEqualTo(before + 1);
    }

    @Test
    void informationAndEscalationRemainBoundToTrustedActors()
        throws Exception {
        TestReview informationReview = createReview(
            new BigDecimal("75.00"),
            RefundRequest.ResolutionType.REFUND
        );
        String requested = decide(
            informationReview.task().task().taskId(),
            REGULAR_KEY,
            decisionBody(
                "request-incident-1",
                "REQUEST_INFORMATION",
                informationReview.task().task().version(),
                Map.of("question", "Provide the incident reference.")
            )
        );
        long waitingVersion = objectMapper.readTree(requested)
            .at("/task/version")
            .asLong();
        assertThat(requested)
            .contains("\"status\":\"WAITING_FOR_INFORMATION\"")
            .doesNotContain("incidentReference");

        AgenticResolverSessionService.SessionView other = sessions.create();
        String denied = provideInformation(
            other.sessionId(),
            informationReview.task().task().taskId(),
            waitingVersion,
            "INC-DENIED"
        );
        assertThat(denied).contains("REVIEW_TASK_NOT_AVAILABLE");

        String supplied = provideInformation(
            informationReview.sessionId(),
            informationReview.task().task().taskId(),
            waitingVersion,
            "INC-2026-42"
        );
        assertThat(supplied)
            .contains("\"status\":\"WAITING_FOR_REVIEW\"")
            .doesNotContain("INC-2026-42");

        String detail = mockMvc.perform(get(
                "/api/agentic-resolver/reviews/{taskId}",
                informationReview.task().task().taskId()
            )
                .header(
                    SupportCreditReviewController.REVIEW_KEY_HEADER,
                    REGULAR_KEY
                ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.suppliedInformation.incidentReference")
                .value("INC-2026-42"))
            .andReturn()
            .getResponse()
            .getContentAsString();
        assertThat(detail)
            .doesNotContain(
                informationReview.proposal().receiptId(),
                informationReview.subjectUserId().toString()
            );

        TestReview escalationReview = createReview(
            new BigDecimal("125.00"),
            RefundRequest.ResolutionType.REFUND
        );
        String escalated = decide(
            escalationReview.task().task().taskId(),
            REGULAR_KEY,
            decisionBody(
                "escalate-credit-1",
                "ESCALATE",
                escalationReview.task().task().version(),
                null
            )
        );
        String successor = objectMapper.readTree(escalated)
            .path("successorTaskId")
            .asText();
        assertThat(successor).startsWith("review-task-");

        mockMvc.perform(get(
                "/api/agentic-resolver/reviews/{taskId}",
                successor
            )
                .header(
                    SupportCreditReviewController.REVIEW_KEY_HEADER,
                    REGULAR_KEY
                ))
            .andExpect(status().isNotFound());
        mockMvc.perform(get(
                "/api/agentic-resolver/reviews/{taskId}",
                successor
            )
                .header(
                    SupportCreditReviewController.REVIEW_KEY_HEADER,
                    SENIOR_KEY
                ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.task.policyId.name")
                .value("support-credit-senior-review"));
    }

    private TestReview createReview(
        BigDecimal amount,
        RefundRequest.ResolutionType resolutionType
    ) {
        AgenticResolverSessionService.SessionView created = sessions.create();
        sessions.select(created.sessionId(), "ready-account");
        AgenticResolverSessionService.ActiveSession active =
            sessions.active(created.sessionId());
        TrustedExecutionContext trusted = trustedContext(active);
        SpecialistDefinition<?, ?> specialist = specialists.require(
            AccountResolverSpecialists.SUPPORT_CREDIT_SPECIALIST_ID
        );
        OrchestrationContext context = OrchestrationContext.builder()
            .userId(active.subjectUserId().toString())
            .position(specialist.executionProfile().mode())
            .mode(specialist.executionProfile().mode())
            .build();
        OrchestrationRequest orchestrationRequest =
            new OrchestrationRequest(
                "Create a support credit proposal for review.",
                context,
                trusted,
                ConversationPersistencePolicy.NEVER,
                null,
                null,
                null,
                OrchestrationRequestPurpose.SPECIALIST
            );
        PipelineContext preflight = policyResolution.process(
            PipelineContext.from(orchestrationRequest)
        );
        EffectiveCapabilityProfile profile = capabilities.resolve(
            specialist,
            preflight,
            trusted
        );
        Map<String, Object> parameters = Map.of(
            "amount",
            amount,
            "reason",
            "Verified support incident",
            "resolutionType",
            resolutionType.name()
        );
        String unique = UUID.randomUUID().toString();
        ActionProposalView proposal = proposals.propose(
            "review-integration-" + unique,
            specialist,
            new ActionProposalCandidate(
                AccountResolverSpecialists.REQUEST_REFUND_ACTION,
                parameters,
                new ActionContext(context, preflight, parameters)
            ),
            trusted,
            profile,
            "review-proposal-" + unique,
            List.of(policyEvidence())
        );
        ReviewTaskCreationResult task = reviews.createActionReview(
            new ActionReviewRequest(
                proposal.receiptId(),
                SupportCreditReviewPolicies.STANDARD,
                "Review a billing resolution",
                "AI proposed a governed billing resolution.",
                "support-credit-review-" + unique
            ),
            trusted
        );
        assertThat(task.failure()).isNull();
        return new TestReview(
            created.sessionId(),
            active.subjectUserId(),
            proposal,
            task
        );
    }

    private TrustedExecutionContext trustedContext(
        AgenticResolverSessionService.ActiveSession session
    ) {
        return new TrustedExecutionContext(
            new ExecutionPrincipal(
                "agentic-account-resolver",
                ExecutionPrincipalType.SERVICE
            ),
            new ExecutionSubjectRef(
                "account",
                session.subjectUserId().toString()
            ),
            ExecutionSource.APPLICATION,
            "public-demo",
            "agentic-ai-action-resolver",
            Set.of(
                "specialist:support-credit-proposer@1",
                "action:request_refund",
                "vector:account-resolution-policy"
            ),
            null,
            clock.instant()
        );
    }

    private AIEvidenceReference policyEvidence() {
        return new AIEvidenceReference(
            "REFUND_OR_CREDIT_AVAILABLE",
            "A governed refund or account credit requires policy evidence.",
            1.0,
            "policy",
            null,
            AccountResolverSpecialists.POLICY_VECTOR_SPACE,
            Map.of("policyCode", "REFUND_OR_CREDIT_AVAILABLE")
        );
    }

    private String decide(
        String taskId,
        String reviewerKey,
        String body
    ) throws Exception {
        return mockMvc.perform(post(
                "/api/agentic-resolver/reviews/{taskId}/decision",
                taskId
            )
                .header(
                    SupportCreditReviewController.REVIEW_KEY_HEADER,
                    reviewerKey
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    }

    private String provideInformation(
        String sessionId,
        String taskId,
        long version,
        String incidentReference
    ) throws Exception {
        return mockMvc.perform(post(
                "/api/agentic-resolver/reviews/{taskId}/information",
                taskId
            )
                .header(
                    AgenticResolverController.SESSION_HEADER,
                    sessionId
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of(
                    "submissionId",
                    "information-" + UUID.randomUUID(),
                    "expectedVersion",
                    version,
                    "response",
                    Map.of("incidentReference", incidentReference)
                ))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    }

    private String decisionBody(
        String decisionId,
        String decision,
        long expectedVersion,
        Map<String, Object> response
    ) throws Exception {
        var body = objectMapper.createObjectNode()
            .put("decisionId", decisionId)
            .put("decision", decision)
            .put("expectedVersion", expectedVersion);
        if (response != null) {
            body.set("response", objectMapper.valueToTree(response));
        }
        return objectMapper.writeValueAsString(body);
    }

    private String issueDemoReviewer(String sessionId, String role)
        throws Exception {
        String response = mockMvc.perform(post(
                "/api/agentic-resolver/demo-reviews/sessions"
            )
                .header(
                    AgenticResolverController.SESSION_HEADER,
                    sessionId
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"" + role + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value(role))
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readTree(response).path("token").asText();
    }

    private record TestReview(
        String sessionId,
        UUID subjectUserId,
        ActionProposalView proposal,
        ReviewTaskCreationResult task
    ) {}
}
