package com.ai.fabric.realapps.agenticresolver.agentic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.fabric.chat.service.ChatSessionService;
import ai.fabric.execution.action.ActionProposalCoordinator;
import ai.fabric.execution.action.ActionProposalDecision;
import ai.fabric.execution.action.ActionProposalReceiptRepository;
import ai.fabric.execution.action.ActionProposalReceiptStatus;
import ai.fabric.execution.action.ActionProposalValidationException;
import ai.fabric.execution.action.ActionProposalView;
import ai.fabric.execution.action.JdbcActionProposalReceiptRepository;
import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.gateway.SpecialistCapabilityResolver;
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
import com.ai.fabric.realapps.agenticresolver.controller.AgenticResolverController;
import com.ai.fabric.realapps.agenticresolver.entity.Subscription;
import com.ai.fabric.realapps.agenticresolver.repository.AgenticResolverDemoSessionRepository;
import com.ai.fabric.realapps.agenticresolver.repository.SubscriptionRepository;
import com.ai.fabric.realapps.agenticresolver.service.AccountResolutionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:agentic-write-integration;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "ai.providers.openai.enabled=false",
    "ai.execution.receipts.encryption-secret=test-agentic-receipt-encryption-key-at-least-32",
    "ai.execution.receipts.fingerprint-secret=test-agentic-receipt-fingerprint-key-at-least-32",
    "ai.vector-db.lucene.index-path=target/agentic-write-integration-index",
    "app.demo.cleanup.enabled=false",
    "logging.level.ai.fabric=WARN"
})
@AutoConfigureMockMvc
@Transactional
class AgenticResolverWriteIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AgenticResolverSessionService sessionService;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private SpecialistRegistry specialistRegistry;

    @Autowired
    private SpecialistCapabilityResolver capabilityResolver;

    @Autowired
    private OrchestrationPolicyResolutionStep policyResolutionStep;

    @Autowired
    private ActionProposalCoordinator proposalCoordinator;

    @Autowired
    private ActionProposalReceiptRepository receiptRepository;

    @Autowired
    private Clock clock;

    @Autowired
    private AccountResolutionService accountResolutionService;

    @Autowired
    private AgenticResolverDemoSessionRepository demoSessionRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void missingBillingAmountSerializesPortableJsonSchema() throws Exception {
        AgenticResolverSessionService.SessionView session =
            sessionService.create();

        mockMvc.perform(post(
                "/api/agentic-resolver/billing-assessment"
            )
                .header(
                    AgenticResolverController.SESSION_HEADER,
                    session.sessionId()
                )
                .header(
                    AgenticResolverController.IDEMPOTENCY_HEADER,
                    "integration-billing-wait"
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of(
                    "question",
                    "What path would this refund take?",
                    "resolutionType",
                    "REFUND"
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("WAITING_FOR_INPUT"))
            .andExpect(jsonPath("$.output").doesNotExist())
            .andExpect(jsonPath("$.needsUserInput.purposeCode")
                .value("MISSING_BILLING_AMOUNT"))
            .andExpect(jsonPath(
                "$.needsUserInput.responseContract.schema.type"
            ).value("object"))
            .andExpect(jsonPath(
                "$.needsUserInput.responseContract.schema.properties.amount.type"
            ).value("number"))
            .andExpect(jsonPath(
                "$.needsUserInput.responseContract.schema.properties.amount"
                    + ".exclusiveMinimum"
            ).value(0))
            .andExpect(jsonPath(
                "$.needsUserInput.responseContract.schema.array"
            ).doesNotExist());
    }

    @Test
    void confirmedHttpDecisionChangesStateOnceAndReturnsOnlySafeOutcome()
        throws Exception {
        TestSession testSession = missingAddressSession();
        Subscription before = activeSubscription(testSession);
        assertThat(before.getBillingAddress()).isNull();

        ActionProposalView proposal = proposeAddressUpdate(testSession);

        assertThat(activeSubscription(testSession).getBillingAddress())
            .as("proposal creation must not mutate application state")
            .isNull();
        assertThat(receiptRepository)
            .isInstanceOf(JdbcActionProposalReceiptRepository.class);
        assertThat(receiptRepository.findById(proposal.receiptId()))
            .get()
            .extracting(receipt -> receipt.status())
            .isEqualTo(ActionProposalReceiptStatus.PROPOSED);

        String response = mockMvc.perform(post(
                "/api/agentic-resolver/actions/decide"
            )
                .header(
                    AgenticResolverController.SESSION_HEADER,
                    testSession.sessionId()
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of(
                    "receiptId",
                    proposal.receiptId(),
                    "decision",
                    ActionProposalDecision.CONFIRM
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.outcome.actionName")
                .value("update_address"))
            .andExpect(jsonPath("$.outcome.data.updated").value(true))
            .andExpect(jsonPath("$.outcome.data.subscriptionId")
                .doesNotExist())
            .andExpect(jsonPath("$.outcome.data.streetAddress")
                .doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();

        assertThat(response)
            .doesNotContain(
                "10 Downing Street",
                testSession.subjectUserId().toString()
            );
        Subscription updated = activeSubscription(testSession);
        assertThat(updated.getBillingAddress()).isNotNull();
        assertThat(updated.getBillingAddress().getStreetAddress())
            .isEqualTo("10 Downing Street");
        assertThat(updated.getBillingAddress().getIsValidated()).isTrue();

        mockMvc.perform(post("/api/agentic-resolver/actions/decide")
                .header(
                    AgenticResolverController.SESSION_HEADER,
                    testSession.sessionId()
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of(
                    "receiptId",
                    proposal.receiptId(),
                    "decision",
                    ActionProposalDecision.CONFIRM
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    void rejectionAndCrossSessionConfirmationCannotChangeState()
        throws Exception {
        TestSession owner = missingAddressSession();
        TestSession attacker = missingAddressSession();
        ActionProposalView proposal = proposeAddressUpdate(owner);

        mockMvc.perform(post("/api/agentic-resolver/actions/decide")
                .header(
                    AgenticResolverController.SESSION_HEADER,
                    attacker.sessionId()
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of(
                    "receiptId",
                    proposal.receiptId(),
                    "decision",
                    ActionProposalDecision.CONFIRM
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").doesNotExist())
            .andExpect(jsonPath("$.failure.reason")
                .value("RECEIPT_NOT_AVAILABLE"));
        assertThat(activeSubscription(owner).getBillingAddress()).isNull();

        mockMvc.perform(post("/api/agentic-resolver/actions/decide")
                .header(
                    AgenticResolverController.SESSION_HEADER,
                    owner.sessionId()
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of(
                    "receiptId",
                    proposal.receiptId(),
                    "decision",
                    ActionProposalDecision.REJECT
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("REJECTED"));
        assertThat(activeSubscription(owner).getBillingAddress()).isNull();
    }

    @Test
    void oversizedAddressIsRejectedBeforeReceiptPersistence() {
        TestSession testSession = missingAddressSession();

        assertThatThrownBy(() -> proposeAddressUpdate(
            testSession,
            "x".repeat(201)
        ))
            .isInstanceOf(ActionProposalValidationException.class)
            .extracting(error ->
                ((ActionProposalValidationException) error).reason()
            )
            .isEqualTo("ACTION_PARAMETER_INVALID");
        assertThat(activeSubscription(testSession).getBillingAddress()).isNull();
    }

    @Test
    void sessionOwnershipBindingReloadsAfterServiceRecreation() {
        AgenticResolverSessionService.SessionView created =
            sessionService.create();
        sessionService.select(created.sessionId(), "missing-address");
        AgenticResolverSessionService.ActiveSession beforeRestart =
            sessionService.active(created.sessionId());
        demoSessionRepository.flush();
        entityManager.clear();

        AgenticResolverSessionService restartedService =
            new AgenticResolverSessionService(
                accountResolutionService,
                clock,
                Duration.ofHours(6),
                500,
                applicationContext.getBeanProvider(ChatSessionService.class),
                applicationContext.getBeanProvider(
                    AgenticResolverDemoSessionRepository.class
                )
            );

        AgenticResolverSessionService.SessionView reloaded =
            restartedService.get(created.sessionId());
        AgenticResolverSessionService.ActiveSession afterRestart =
            restartedService.active(created.sessionId());

        assertThat(reloaded.activeScenarioId()).isEqualTo("missing-address");
        assertThat(reloaded.toString())
            .doesNotContain(beforeRestart.subjectUserId().toString());
        assertThat(afterRestart.subjectUserId())
            .isEqualTo(beforeRestart.subjectUserId());
        assertThat(afterRestart.conversationOwnerId())
            .isEqualTo(beforeRestart.conversationOwnerId());
    }

    private TestSession missingAddressSession() {
        AgenticResolverSessionService.SessionView created =
            sessionService.create();
        sessionService.select(created.sessionId(), "missing-address");
        AgenticResolverSessionService.ActiveSession active =
            sessionService.active(created.sessionId());
        return new TestSession(
            created.sessionId(),
            active.subjectUserId(),
            active.conversationOwnerId()
        );
    }

    private ActionProposalView proposeAddressUpdate(TestSession testSession) {
        return proposeAddressUpdate(testSession, "10 Downing Street");
    }

    private ActionProposalView proposeAddressUpdate(
        TestSession testSession,
        String streetAddress
    ) {
        TrustedExecutionContext trustedContext = trustedContext(testSession);
        SpecialistDefinition<?, ?> definition = specialistRegistry.require(
            AccountResolverSpecialists.SPECIALIST_ID
        );
        OrchestrationContext baseContext = OrchestrationContext.builder()
            .userId(testSession.subjectUserId().toString())
            .position(definition.executionProfile().mode())
            .mode(definition.executionProfile().mode())
            .build();
        OrchestrationRequest request = new OrchestrationRequest(
            "Validate specialist action proposal.",
            baseContext,
            trustedContext,
            ConversationPersistencePolicy.NEVER,
            null,
            null,
            null,
            OrchestrationRequestPurpose.SPECIALIST
        );
        PipelineContext preflight = policyResolutionStep.process(
            PipelineContext.from(request)
        );
        EffectiveCapabilityProfile profile = capabilityResolver.resolve(
            definition,
            preflight,
            trustedContext
        );
        Map<String, Object> parameters = Map.of(
            "addressType",
            "BILLING",
            "streetAddress",
            streetAddress,
            "city",
            "London",
            "state",
            "London",
            "postalCode",
            "SW1A 2AA",
            "country",
            "GB"
        );
        return proposalCoordinator.propose(
            "integration-" + java.util.UUID.randomUUID(),
            definition,
            new ActionProposalCandidate(
                "update_address",
                parameters,
                new ActionContext(baseContext, preflight, parameters)
            ),
            trustedContext,
            profile,
            "integration-key-" + java.util.UUID.randomUUID(),
            List.of()
        );
    }

    private TrustedExecutionContext trustedContext(
        TestSession testSession
    ) {
        return new TrustedExecutionContext(
            new ExecutionPrincipal(
                testSession.conversationOwnerId(),
                ExecutionPrincipalType.END_USER
            ),
            new ExecutionSubjectRef(
                "account",
                testSession.subjectUserId().toString()
            ),
            ExecutionSource.INTERACTIVE,
            "public-demo",
            "agentic-ai-action-resolver",
            Set.of(
                "specialist:account-resolver@1",
                "action:get_account_profile",
                "action:update_address",
                "vector:account-resolution-policy"
            ),
            null,
            clock.instant()
        );
    }

    private Subscription activeSubscription(TestSession testSession) {
        return subscriptionRepository.findByUserIdAndStatus(
            testSession.subjectUserId(),
            Subscription.SubscriptionStatus.ACTIVE
        ).orElseThrow();
    }

    private record TestSession(
        String sessionId,
        java.util.UUID subjectUserId,
        String conversationOwnerId
    ) {}
}
