package ai.fabric.it;

import ai.fabric.chat.exception.ChatSessionAccessDeniedException;
import ai.fabric.chat.service.ChatSessionService;
import ai.fabric.chat.spi.ChatSessionAccessControlPolicy;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionParamSchema;
import ai.fabric.intent.action.AIActionParamType;
import ai.fabric.intent.action.AIActionRegistryContributor;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionNativeSchemaSupport;
import ai.fabric.intent.action.ActionPayload;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.connector.ActionConnectorExecutor;
import ai.fabric.intent.action.connector.ConnectorAIActionHandler;
import ai.fabric.intent.action.connector.ConnectorActionLlmFactsDefinition;
import ai.fabric.intent.action.connector.McpActionExecutor;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.RAGOrchestrator;
import ai.fabric.intent.orchestration.conversation.ApprovedConversationSnapshot;
import ai.fabric.dto.AIChatMessage;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.RAGResponse;
import ai.fabric.core.AIEmbeddingService;
import ai.fabric.rag.VectorDatabaseService;
import java.time.Instant;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.intent.orchestration.information.ReadActionResolutionService;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.pipeline.steps.OrchestrationPolicyResolutionStep;
import ai.fabric.it.support.RealAPITestSupport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.StringUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * Real extraction, planning, confirmation, embeddings and search over fictional fixture data.
 * Twenty-four scenario invocations use twenty-eight orchestration turns and one direct planner call.
 * This entry-call cap is not a count of the framework's internal provider requests.
 */
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("real-api-test")
@Import(RealAPINativeActionSchemaIntegrationTest.FixtureConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
    "ai.actions.sources[0].type=FILE",
    "ai.actions.sources[0].path=classpath:actions/native-schema-realapi.yml",
    "ai.actions.builtin.vector-management.enabled=false",
    "ai.chat.enabled=true",
    "ai.chat.auto-create-sessions=true",
    "ai.intent-extraction.progressive.enabled=true",
    "ai.providers.llm-provider=openai",
    "ai.providers.embedding-provider=openai",
    "ai.providers.onnx.enabled=false",
    "ai.providers.enable-fallback=false",
    "ai.vector-db.type=memory",
    "ai.pii-detection.enabled=false",
    "ai.behavior.enabled=false",
    "ai.service.features.enable-embeddings=true",
    "ai.service.features.enable-search=true",
    "ai.service.features.enable-rag=true",
    "ai.orchestration.profile=PRODUCTION_CHAT",
    "ai.orchestration.default-mode=executor",
    "ai.orchestration.modes.executor.actions-enabled=true",
    "ai.orchestration.modes.executor.actions-preferred=true",
    "ai.orchestration.modes.executor.retrieval-enabled=false",
    "ai.orchestration.modes.executor.retrieval-allowlist-required=true",
    "ai.orchestration.modes.executor.vector-space-selection-required=false",
    "ai.orchestration.modes.executor.knowledge-base-overview-enabled=false",
    "ai.orchestration.modes.executor.suggestions-enabled=false",
    "ai.orchestration.modes.executor.semantic-recovery.confirmation-without-pending=LLM_REPLAN_THEN_FAIL",
    "ai.orchestration.modes.executor.read-action-resolution.enabled=true",
    "ai.orchestration.modes.executor.read-action-resolution.planning-mode=ITERATIVE",
    "ai.orchestration.modes.executor.read-action-resolution.require-allowlist=true",
    "ai.orchestration.modes.executor.read-action-resolution.allowed-read-actions[0]=native_read_setting",
    "ai.orchestration.modes.executor.read-action-resolution.allowed-read-actions[1]=native_read_private_setting",
    "ai.orchestration.modes.executor.read-action-resolution.allowed-read-actions[2]=native_read_public_guide",
    "ai.orchestration.modes.executor.read-action-resolution.allowed-read-actions[3]=native_read_subscription_status",
    "ai.orchestration.modes.executor.read-action-resolution.max-total-actions=1",
    "ai.orchestration.modes.executor.read-action-resolution.rag-cooperation-mode=NONE",
    "ai.orchestration.modes.guide-without-index.actions-enabled=true",
    "ai.orchestration.modes.guide-without-index.retrieval-enabled=true",
    "ai.orchestration.modes.guide-without-index.retrieval-allowlist-required=true",
    "ai.orchestration.modes.guide-without-index.vector-space-selection-required=false",
    "ai.orchestration.modes.guide-without-index.knowledge-base-overview-enabled=false",
    "ai.orchestration.modes.guide-without-index.suggestions-enabled=false",
    "ai.orchestration.modes.guide-without-index.read-action-resolution.enabled=true",
    "ai.orchestration.modes.guide-without-index.read-action-resolution.planning-mode=ITERATIVE",
    "ai.orchestration.modes.guide-without-index.read-action-resolution.require-allowlist=true",
    "ai.orchestration.modes.guide-without-index.read-action-resolution.allowed-read-actions[0]=native_read_public_guide",
    "ai.orchestration.modes.guide-without-index.read-action-resolution.max-total-actions=1",
    "ai.orchestration.modes.guide-without-index.read-action-resolution.rag-cooperation-mode=NONE",
    "ai.orchestration.modes.history-parallel.actions-enabled=false",
    "ai.orchestration.modes.history-parallel.retrieval-enabled=true",
    "ai.orchestration.modes.history-parallel.retrieval-allowlist-required=false",
    "ai.orchestration.modes.history-parallel.vector-space-selection-required=false",
    "ai.orchestration.modes.history-parallel.knowledge-base-overview-enabled=false",
    "ai.orchestration.modes.history-parallel.suggestions-enabled=false",
    "ai.orchestration.modes.history-parallel.rag.similarity-threshold=0.5",
    "ai.orchestration.modes.history-parallel.semantic-recovery.confirmation-without-pending=LLM_REPLAN_THEN_FAIL",
    "ai.orchestration.modes.history-parallel.read-action-resolution.enabled=true",
    "ai.orchestration.modes.history-parallel.read-action-resolution.planning-mode=ITERATIVE",
    "ai.orchestration.modes.history-parallel.read-action-resolution.require-allowlist=true",
    "ai.orchestration.modes.history-parallel.read-action-resolution.allowed-read-actions[0]=native_read_public_guide",
    "ai.orchestration.modes.history-parallel.read-action-resolution.max-total-actions=1",
    "ai.orchestration.modes.history-parallel.read-action-resolution.rag-cooperation-mode=PARALLEL_ACTIONS_AND_RAG"
})
class RealAPINativeActionSchemaIntegrationTest {
    private static final int MAX_QUERIES = 29;
    private static final String PRIVATE_STATUS_ACTION = "native_read_subscription_status";
    private static final String NATURAL_STATUS_QUESTION = "What is the current status of my subscription?";
    private static final String PUBLIC_GUIDE = "In this fictional service, subscription cancellation takes effect "
        + "at the end of the current billing period. The documented customer steps are to open Account, "
        + "select Subscription, review the billing-period end date, then request cancellation through the service. "
        + "Reading the guide does not cancel a subscription.";
    static {
        RealAPITestSupport.ensureProviderConfigured();
        RealAPITestSupport.ensureLLMProviderSet();
    }

    @Autowired private RAGOrchestrator orchestrator;
    @Autowired private AIActionRegistry registry;
    @Autowired private RecordingMcpExecutor receiver;
    @Autowired private ChatSessionService chatSessions;
    @Autowired private AIEmbeddingService embeddings;
    @Autowired private VectorDatabaseService vectors;
    @Autowired private ReadActionResolutionService readPlanner;
    @Autowired private OrchestrationPolicyResolutionStep policyResolution;
    @Autowired private ai.fabric.config.AIEntityConfigurationLoader entityConfiguration;
    @Value("${ai.providers.openai.model}") private String model;
    private final AtomicInteger queries = new AtomicInteger();

    @BeforeAll
    void requireRealOpenAiAndNativeCatalogue() {
        assertThat(StringUtils.hasText(System.getProperty("OPENAI_API_KEY"))
            || StringUtils.hasText(System.getenv("OPENAI_API_KEY")))
            .as("This gate requires a privately injected real OpenAI key; no fake LLM or skipped qualification")
            .isTrue();
        assertThat(entityConfiguration.getSupportedEntityTypes()).containsExactly("test-article");
        assertThat(registry.findMetadata("native_read_setting").orElseThrow().getInputSchema()).isNotEmpty();
        assertThat(registry.findMetadata("native_read_setting").orElseThrow().getGroundingVectorSpaces()).isEmpty();
        var privateStatus = registry.findMetadata(PRIVATE_STATUS_ACTION).orElseThrow();
        assertThat(privateStatus.isAnonymousAllowed()).isFalse();
        assertThat(ActionNativeSchemaSupport.publicInputSchema(privateStatus).get("properties"))
            .isEqualTo(Map.of());
        var embedding = embeddings.generateEmbedding(AIEmbeddingRequest.builder().text(PUBLIC_GUIDE).build());
        assertThat(embedding.getEmbedding()).isNotEmpty();
        var vectorId = vectors.storeVector("test-article", "native-approved-guide", PUBLIC_GUIDE, embedding.getEmbedding(),
            Map.of("title", "Approved cancellation guide", "published", true));
        assertThat(vectors.getVector(vectorId)).isPresent();
        var queryEmbedding = embeddings.generateEmbedding(AIEmbeddingRequest.builder().text(
            "Cancellation steps from the approved indexed cancellation guide, including how to cancel and any "
                + "billing-period timing details. cancellation steps approved indexed guide").build()).getEmbedding();
        double dot = 0, queryNorm = 0, guideNorm = 0;
        for (int i = 0; i < queryEmbedding.size(); i++) {
            double queryValue = queryEmbedding.get(i), guideValue = embedding.getEmbedding().get(i);
            dot += queryValue * guideValue;
            queryNorm += queryValue * queryValue;
            guideNorm += guideValue * guideValue;
        }
        double calibrationScore = dot / Math.sqrt(queryNorm * guideNorm);
        assertThat(calibrationScore).isBetween(0.5d, 1.0d);
        System.out.printf("Native indexed fixture calibration: cosine=%.6f configuredThreshold=0.5%n", calibrationScore);
        System.out.printf("Native schema qualification: provider=openai model=%s scenarios=24 "
            + "maxQueries=%d orchestrationTurns=28 directPlannerCalls=1 syntheticReceiver=true indexedFixture=true%n", model, MAX_QUERIES);
    }

    @BeforeEach
    void clearSyntheticReceiver() {
        receiver.calls.clear();
    }

    @Test
    void publicReadWithoutVectorsPreservesNestedNullableArguments() {
        var context = OrchestrationContext.builder().sessionId("public-" + UUID.randomUUID()).build();
        var result = query("Use native_read_setting with requestKey public-sample and options "
            + "{\"threshold\":0.5,\"note\":null}. Execute this public read; do not search documents.", context);
        assertThat(result.isSuccess()).as("Public READ result: %s", result.getType()).isTrue();
        assertThat(receiver.calls).hasSize(1);
        var call = receiver.calls.getFirst();
        assertThat(call.action()).isEqualTo("native_read_setting");
        assertThat(call.mode()).isEqualTo(ActionAccessMode.READ);
        assertThat(call.parameters()).containsEntry("requestKey", "public-sample");
        assertThat(((Map<?, ?>) call.parameters().get("options")).containsKey("note")).isTrue();
        assertThat(((Map<?, ?>) call.parameters().get("options")).get("note")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "How does subscription cancellation work?",
        "What are the cancellation steps and when does billing stop?",
        "Can you explain the service's cancellation procedure?"
    })
    void publicInformationUsesNativeReadWithIndexedRetrievalDisabled(String question) {
        assertPublicGuideInformation(OrchestrationContext.builder()
            .sessionId("public-guide-" + UUID.randomUUID()).build(), question);
    }

    @Test
    void greetingDoesNotRequireAPolicyOrAccountRead() {
        var result = query("Hello!", OrchestrationContext.builder()
            .sessionId("greeting-" + UUID.randomUUID()).build());
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.getMessage()).isNotBlank();
        assertThat(receiver.calls).isEmpty();
    }

    @Test
    void publicInformationUsesNativeReadWithoutRequiredVectorAllowlist() {
        assertPublicGuideInformation(OrchestrationContext.builder().mode("guide-without-index")
            .sessionId("public-guide-" + UUID.randomUUID()).build());
    }

    private void assertPublicGuideInformation(OrchestrationContext context) {
        assertPublicGuideInformation(context, "How does subscription cancellation work?");
    }

    private void assertPublicGuideInformation(OrchestrationContext context, String question) {
        var result = query(question, context);
        System.out.printf("Native public INFO diagnostics: policy=%s extraction=%s%n",
            result.getMetadata() != null ? result.getMetadata().get("orchestrationPolicy") : null,
            result.getMetadata() != null ? result.getMetadata().get("extractionDiagnostics") : null);
        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.isSuccess()).isTrue();
        assertThat(receiver.calls).hasSize(1);
        assertThat(receiver.calls.getFirst().action()).isEqualTo("native_read_public_guide");
        assertThat(receiver.calls.getFirst().parameters()).isEmpty();
        assertThat(result.getMessage()).containsIgnoringCase("billing");
        assertThat(result.getData().get("ragResponse")).isNull();
        assertThat(result.getData().get("readActionResolution")).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) result.getData().get("readActionResolution")).get("useRag"))
            .isEqualTo(false);
        var plan = (Map<?, ?>) result.getData().get("readActionResolution");
        assertThat(plan.get("knowledgeSourceCatalog")).isInstanceOf(Map.class);
        var sources = (Map<?, ?>) plan.get("knowledgeSourceCatalog");
        assertThat(sources.get("ragAvailable")).isEqualTo(false);
        assertThat(sources.get("vectorSpaces")).isEqualTo(List.of());
        assertThat(sources.get("availableDecisions"))
            .isEqualTo(List.of("ANSWER_FROM_CONTEXT", "EXECUTE_READ_ACTIONS"));
        assertThat(sources.get("ragUnavailableReasons")).isInstanceOf(List.class);
        var unavailableReasons = ((List<?>) sources.get("ragUnavailableReasons")).stream()
            .map(String.class::cast).toList();
        assertThat(unavailableReasons).contains("RAG_COOPERATION_DISABLED", "NO_AUTHORIZED_VECTOR_SOURCES");
        assertThat(plan.get("iterations")).isInstanceOf(List.class);
        for (Object rawIteration : (List<?>) plan.get("iterations")) {
            assertThat(rawIteration).isInstanceOf(Map.class);
            var iteration = (Map<?, ?>) rawIteration;
            assertThat(iteration.get("decision")).as("An unavailable RAG route must not be selected")
                .isIn("ANSWER_FROM_CONTEXT", "EXECUTE_READ_ACTIONS");
        }
    }

    @Test
    void privateReadResolvesTrustedContextAndPreservesPublicNull() {
        var context = signedInContext();
        var result = query("Read my private synthetic setting with native_read_private_setting for requestKey private-sample. "
            + "Use explicitly null selection. This is a read, not a preference change.", context);
        assertThat(result.isSuccess()).as("Private READ result: %s", result.getType()).isTrue();
        assertThat(receiver.calls).hasSize(1);
        assertThat(receiver.calls.getFirst().action()).isEqualTo("native_read_private_setting");
        assertThat(receiver.calls.getFirst().parameters()).containsEntry("selection", null)
            .containsEntry("scopeKey", context.getUserId());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        NATURAL_STATUS_QUESTION,
        "Use the connected subscription tool to read my subscription and its current status. "
            + "This is read-only; do not cancel or change anything."
    })
    void samePrivateQuestionChallengesAnonymouslyAndExecutesAfterSignIn(String question) {
        var anonymous = OrchestrationContext.builder().sessionId("private-auth-" + UUID.randomUUID()).build();
        var challenge = query(question, anonymous);
        var anonymousCalls = List.copyOf(receiver.calls);
        // The host supplies verified identity and an owned conversation after sign-in; no borrowed chat history.
        var authenticated = signedInContext().toBuilder().sessionId(anonymous.getSessionId()).build();
        var result = query(question, authenticated);
        var authenticatedCalls = List.copyOf(receiver.calls.subList(anonymousCalls.size(), receiver.calls.size()));

        assertAll("The exact same question must work on both sides of the authentication boundary",
            () -> {
                assertThat(challenge.isSuccess()).isFalse();
                assertThat(challenge.getErrorCode()).isEqualTo("AUTHENTICATION_REQUIRED");
                assertThat(challenge.getData()).containsKey("authenticationRequirement")
                    .containsEntry("action", PRIVATE_STATUS_ACTION);
                assertThat(anonymousCalls).as("No private receiver call before sign-in").isEmpty();
            },
            () -> {
                assertThat(result.isSuccess()).as("Signed-in result: %s / %s", result.getType(), result.getErrorCode())
                    .isTrue();
                assertThat(result.getData()).doesNotContainKey("authenticationRequirement");
                assertThat(authenticatedCalls).as("Exactly one real private READ after sign-in").hasSize(1);
                var call = authenticatedCalls.getFirst();
                assertThat(call.action()).isEqualTo(PRIVATE_STATUS_ACTION);
                assertThat(call.mode()).isEqualTo(ActionAccessMode.READ);
                assertThat(call.parameters()).containsOnlyKeys("scopeKey")
                    .containsEntry("scopeKey", authenticated.getUserId());
                assertThat(call.result().isSuccess()).isTrue();
                assertThat(call.result().getData().toMap()).containsEntry("status", "ACTIVE");
            });
    }

    @Test
    void realReadSelectionDerivesAuthenticationForSuppliedInformationIntent() {
        int turn = nextQuery();
        var input = policyResolution.process(PipelineContext.from(NATURAL_STATUS_QUESTION, OrchestrationContext.anonymous()));
        var intent = Intent.builder().type(IntentType.INFORMATION).intent("current_subscription_status")
            .requiresRetrieval(false).requiresGeneration(false).build();
        var outcome = readPlanner.resolve(intent, input.getOrchestrationContext(), input);

        System.out.printf("Native real READ planner component turn %d: intent=%s diagnostics=%s receiver=%s%n",
            turn, intent.getType(), outcome.diagnostics(), receiver.calls);
        assertThat(outcome.diagnostics()).containsEntry("modelDecision", "EXECUTE_READ_ACTIONS");
        assertThat(outcome.diagnostics().get("iterations")).isInstanceOf(List.class);
        var iterations = (List<?>) outcome.diagnostics().get("iterations");
        assertThat(iterations).hasSize(1);
        assertThat(((Map<?, ?>) iterations.getFirst()).get("decision")).isEqualTo("EXECUTE_READ_ACTIONS");
        assertThat(outcome.authenticationRequiredAction()).isEqualTo(PRIVATE_STATUS_ACTION);
        assertThat(outcome.useRag()).isFalse();
        assertThat(outcome.executedActions()).isEmpty();
        assertThat(receiver.calls).isEmpty();
    }

    @Test
    void collectsRequiredNullableArgumentAndResolvesTrustedContextBeforeConfirmedWrite() {
        var context = signedInContext();
        var missing = query("Save my preference using native_save_preference for requestKey collection-sample. "
            + "I have not chosen a selection yet; ask me for it before preparing the change.", context);
        assertThat(missing.getType()).isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);
        assertThat(receiver.calls).isEmpty();
        assertThatThrownBy(() -> chatSessions.getSession(context.getConversationId(), "different-fixture-owner"))
            .isInstanceOf(ChatSessionAccessDeniedException.class);
        var proposal = query("Clear the selection: selection must explicitly be JSON null, not an omitted field. "
            + "Keep requestKey collection-sample. Prepare the change for confirmation.", context);
        assertThat(proposal.getType()).isEqualTo(OrchestrationResultType.CONFIRMATION_REQUIRED);
        assertThat(receiver.calls).isEmpty();
        var confirmed = query("Yes, confirm and save that change.", context);
        assertThat(confirmed.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(confirmed.isSuccess()).isTrue();
        assertThat(receiver.calls).hasSize(1);
        assertThat(receiver.calls.getFirst().parameters()).containsEntry("selection", null)
            .containsEntry("scopeKey", context.getUserId()).doesNotContainKey("optional");
    }

    @Test
    void rejectedNativeWriteNeverReachesReceiver() {
        var context = signedInContext();
        var proposal = query("Use native_save_preference for requestKey rejected-sample with selection blue. "
            + "Prepare the change and request my confirmation.", context);
        assertThat(proposal.getType()).isEqualTo(OrchestrationResultType.CONFIRMATION_REQUIRED);
        var rejected = query("No. Reject and cancel this change; do not save it.", context);
        assertThat(rejected.isSuccess()).isTrue();
        assertThat(rejected.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(receiver.calls).isEmpty();
    }

    @Test
    void declaredNoConfirmationWriteUsesTrustedBindingWithoutAskingUser() {
        var context = signedInContext();
        var result = query("Execute native_save_without_confirmation with requestKey direct-sample. "
            + "Its declared policy permits this synthetic operation without confirmation.", context);
        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(result.isSuccess()).isTrue();
        assertThat(receiver.calls).hasSize(1);
        assertThat(receiver.calls.getFirst().parameters()).containsEntry("scopeKey", context.getUserId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"yes please do", "do that please", "please continue", "go ahead with the checklist"})
    void acceptsAnInformationalOfferWithoutConfirmingAnAbsentAction(String followup) {
        assertInformationalOffer(followup, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"yes please do", "do that please", "please continue", "go ahead with the checklist"})
    void acceptsAnInformationalOfferWithParallelRagCooperation(String followup) {
        assertInformationalOffer(followup, "history-parallel");
    }

    @Test
    void parallelReadSelectsAuthorizedIndexedSourceWithoutAskingCustomerForADomain() {
        var result = query("Use native_read_public_guide and the approved indexed cancellation guide to "
            + "explain the cancellation steps. Do not perform a cancellation.",
            signedInContext().toBuilder().mode("history-parallel").build());
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.getMessage()).containsIgnoringCase("billing");
        assertThat(receiver.calls).anyMatch(call -> call.action().equals("native_read_public_guide"));
        assertThat(result.getData().get("ragResponse")).isInstanceOf(RAGResponse.class);
        var indexed = (RAGResponse) result.getData().get("ragResponse");
        assertThat(indexed.getSuccess()).isTrue();
        assertThat(indexed.getDocuments()).extracting(RAGResponse.RAGDocument::getId)
            .contains("native-approved-guide");
        assertThat(result.getData().get("readActionResolution")).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) result.getData().get("readActionResolution")).get("useRag")).isEqualTo(true);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "How does subscription cancellation work? Please explain the steps using the approved guide.",
        "What should I do to cancel my subscription, and when would it take effect? Explain only; do not cancel anything."
    })
    void ordinaryChatFindsTheIndexedGuideWithoutAnOptionalSourceAllowlist(String question) {
        var result = query(question, signedInContext().toBuilder().mode("history-parallel").build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.getMessage()).containsIgnoringCase("billing");
        assertThat(result.getData().get("ragResponse")).isInstanceOf(RAGResponse.class);
        var indexed = (RAGResponse) result.getData().get("ragResponse");
        assertThat(indexed.getSuccess()).isTrue();
        assertThat(indexed.getDocuments()).extracting(RAGResponse.RAGDocument::getId)
            .contains("native-approved-guide");
        var plan = (Map<?, ?>) result.getData().get("readActionResolution");
        var catalogue = (Map<?, ?>) plan.get("knowledgeSourceCatalog");
        assertThat(catalogue.get("vectorSpaces")).isEqualTo(entityConfiguration.getSupportedEntityTypes().stream()
            .map(name -> name.trim().toLowerCase(java.util.Locale.ROOT)).distinct().sorted().toList());
        assertThat(catalogue.get("ragAvailable")).isEqualTo(true);
        assertThat(catalogue.get("ragRequiredWithReadActions")).isEqualTo(true);
        assertThat(receiver.calls).allMatch(call -> call.mode().isReadOnly());
    }

    private void assertInformationalOffer(String followup, String mode) {
        var context = signedInContext().toBuilder().mode(mode).build();
        var history = new ApprovedConversationSnapshot("followup-" + UUID.randomUUID(), context.getUserId(),
            context.getConversationId(), "support", "a".repeat(64), 1,
            List.of(AIChatMessage.user("Explain the subscription cancellation policy."),
                AIChatMessage.assistant(PUBLIC_GUIDE + " I can turn this into a short step-by-step checklist for you.")),
            Instant.now());
        var result = query(followup, context.toBuilder().approvedConversationSnapshot(history).build());
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.getMessage()).doesNotContain("There is no pending action to confirm.");
        assertThat(result.getMessage()).containsIgnoringCase("billing")
            .doesNotContain("extract intents", "analyze the user's question");
        assertThat(result.getMessage()).satisfiesAnyOf(
            answer -> assertThat(answer).containsIgnoringCase("checklist"),
            answer -> assertThat(answer.lines().filter(line -> line.matches("\\s*(?:[-*]|[0-9]+[.)])\\s+.*")).count())
                .isGreaterThanOrEqualTo(2L));
        assertThat(receiver.calls).allMatch(call -> call.mode().isReadOnly());
        System.out.printf("Informational follow-up real-provider answer: followup=%s answer=%s%n", followup, result.getMessage());
    }

    private OrchestrationResult query(String text, OrchestrationContext context) {
        int turn = nextQuery();
        int callsBefore = receiver.calls.size();
        System.out.printf("Native schema real-provider turn %d: question=%s authenticated=%s%n",
            turn, text, context.isAuthenticated());
        var result = orchestrator.orchestrate(text, context);
        assertThat(result).isNotNull();
        System.out.printf("Native schema real-provider turn %d: type=%s success=%s errorCode=%s receiver=%s%n",
            turn, result.getType(), result.isSuccess(), result.getErrorCode(),
            receiver.calls.subList(callsBefore, receiver.calls.size()));
        System.out.printf("Native schema turn diagnostics: metadata=%s data=%s%n", result.getMetadata(), result.getData());
        return result;
    }

    private int nextQuery() {
        int turn = queries.incrementAndGet();
        assertThat(turn).as("Bounded native qualification entry calls").isLessThanOrEqualTo(MAX_QUERIES);
        return turn;
    }

    private OrchestrationContext signedInContext() {
        return OrchestrationContext.builder().userId("native-owner-" + UUID.randomUUID())
            .conversationId("native-chat-" + UUID.randomUUID()).build();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureConfiguration {
        @Bean
        @Primary
        ai.fabric.config.AIEntityConfigurationLoader nativeEntityConfiguration() throws java.io.IOException {
            var environment = new StandardEnvironment();
            var sources = new YamlPropertySourceLoader().load("native-schema-entities",
                new ClassPathResource("ai-entity-config-native-schema.yml"));
            sources.forEach(source -> environment.getPropertySources().addLast(source));
            return new ai.fabric.config.AIEntityConfigurationLoader(environment);
        }

        @Bean RecordingMcpExecutor nativeSchemaMcpExecutor() { return new RecordingMcpExecutor(); }

        @Bean
        AIActionRegistryContributor nativeSubscriptionStatus(ActionConnectorExecutor executor) {
            var inputSchema = Map.<String, Object>of("type", "object",
                "properties", Map.of("scopeKey", Map.of("type", "string")),
                "required", List.of("scopeKey"), "additionalProperties", false);
            var outputSchema = Map.<String, Object>of("type", "object",
                "properties", Map.of("status", Map.of("type", "string")), "required", List.of("status"));
            var metadata = AIActionMetaData.builder().name(PRIVATE_STATUS_ACTION)
                .description("Read the signed-in customer's current subscription status. No user-supplied inputs "
                    + "are needed. Account scope comes only from trusted runtime identity. This does not change "
                    + "the subscription or explain general cancellation policy.")
                .category("subscriptions").accessMode(ActionAccessMode.READ).anonymousAllowed(false)
                .groundingEligible(true).readActionResolutionEligible(true)
                .parameters(Map.of("scopeKey", "STRING")).requiredParameters(Set.of("scopeKey"))
                .parameterSchemas(Map.of("scopeKey", AIActionParamSchema.builder().name("scopeKey")
                    .type(AIActionParamType.STRING).required(true).askUser(false).visibility("INTERNAL")
                    .resolveFrom(Map.of("source", "RUNTIME_CONTEXT", "field", "userId")).build()))
                .inputSchema(inputSchema).outputSchema(outputSchema).build();
            var handler = new ConnectorAIActionHandler(metadata, false, null, Set.of(), executor,
                new ConnectorActionLlmFactsDefinition(null, List.of("status"), List.of(), List.of()),
                Map.of("adapterType", "mcp-tool", "accessMode", "READ", "inputSchema", inputSchema,
                    "outputSchema", outputSchema, "execution",
                    Map.of("mcp", Map.of("connectionId", "native-schema-fixture", "toolName", "subscriptions.status"))));
            return () -> List.of(handler);
        }

        @Bean
        ChatSessionAccessControlPolicy nativeSchemaChatPolicy() {
            // Test-owned admission; ChatSessionService still checks the persisted conversation owner.
            return new ChatSessionAccessControlPolicy() {
                @Override public boolean canCreateConversation(String ownerId) { return StringUtils.hasText(ownerId); }
                @Override public boolean canAccessConversation(String ownerId, String conversationId) {
                    return valid(ownerId, conversationId);
                }
                @Override public boolean canRecordTurn(String ownerId, String conversationId) {
                    return valid(ownerId, conversationId);
                }
                @Override public boolean canDeleteConversation(String ownerId, String conversationId) {
                    return valid(ownerId, conversationId);
                }
                private boolean valid(String ownerId, String conversationId) {
                    return StringUtils.hasText(ownerId) && StringUtils.hasText(conversationId);
                }
            };
        }
    }

    static final class RecordingMcpExecutor implements McpActionExecutor {
        final List<Call> calls = new ArrayList<>();

        @Override public boolean isAvailable() { return true; }

        @Override
        public ActionResult execute(String actionId, ActionAccessMode mode, Map<String, Object> params,
                                    ActionContext context, Map<String, Object> config) {
            ActionResult result;
            if (actionId.equals("native_read_public_guide")) {
                result = ActionResult.builder().success(true)
                    .data(ActionPayload.object(Map.of("guide", PUBLIC_GUIDE)))
                    .build();
            } else if (actionId.equals(PRIVATE_STATUS_ACTION)) {
                // Receiver-owned account isolation remains necessary even after planner authorization.
                if (context == null || !context.isAuthenticated() || !StringUtils.hasText(context.userId())
                    || !context.userId().equals(params.get("scopeKey"))) {
                    result = ActionResult.builder().success(false).errorCode("FIXTURE_SCOPE_DENIED")
                        .message("The trusted account scope is missing or does not match.").build();
                } else {
                    result = ActionResult.builder().success(true)
                        .data(ActionPayload.object(Map.of("status", "ACTIVE"))).build();
                }
            } else {
                result = ActionResult.builder().success(true).message("Synthetic fixture accepted the setting.")
                    .data(ActionPayload.object(Map.of("accepted", true))).build();
            }
            calls.add(new Call(actionId, mode, Collections.unmodifiableMap(new LinkedHashMap<>(params)), result));
            return result;
        }
    }

    record Call(String action, ActionAccessMode mode, Map<String, Object> parameters, ActionResult result) {}
}
