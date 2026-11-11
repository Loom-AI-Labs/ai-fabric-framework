package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.AIServiceConfig;
import ai.fabric.config.OrchestrationProperties;
import ai.fabric.config.PostActionGenerationProperties;
import ai.fabric.config.PromptBundleProperties;
import ai.fabric.config.RelationshipQueryPostActionGenerationProperties;
import ai.fabric.config.VectorSpaceRoutingProperties;
import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.AIChatMessage;
import ai.fabric.dto.AdvancedRAGResponse;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.dto.RAGResponse;
import ai.fabric.intent.KnowledgeBaseOverviewService;
import ai.fabric.intent.IntentExtractionJsonSupport;
import ai.fabric.intent.IntentExtractionValidator;
import ai.fabric.intent.action.AIActionHandler;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionPayload;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.InMemoryPendingActionStore;
import ai.fabric.intent.actiondraft.InMemoryActionDraftStore;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.information.ReadActionResolutionService;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import ai.fabric.intent.orchestration.policy.OrchestrationProfile;
import ai.fabric.intent.vectorspace.RankBasedMerger;
import ai.fabric.prompt.ClasspathPromptTemplateStore;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import ai.fabric.spi.AdvancedRAGProvider;
import ai.fabric.spi.RAGProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IntentHandlingStepReadActionResolutionTest {

    @ParameterizedTest
    @CsvSource({"true,true,true", "false,true,true", "true,false,true", "false,false,true",
        "true,true,false", "false,true,false"})
    void productionExecutorPlansNativeReadBeforeCheckingIndexedRetrieval(boolean retrievalEnabled,
                                                                         boolean plannerReads,
                                                                         boolean actionAuthorized) {
        var registry = mock(AIActionRegistry.class);
        var handler = mock(AIActionHandler.class);
        var metadata = AIActionMetaData.builder().name("inspect_public_guide")
            .accessMode(ActionAccessMode.READ).anonymousAllowed(true)
            .groundingEligible(true).readActionResolutionEligible(true)
            .inputSchema(Map.of("type", "object", "properties", Map.of(), "additionalProperties", false))
            .outputSchema(Map.of("type", "object", "properties", Map.of("guide", Map.of("type", "string"))))
            .build();
        when(registry.getAllMetadata()).thenReturn(List.of(metadata));
        when(registry.findMetadata(metadata.getName())).thenReturn(Optional.of(metadata));
        when(registry.findHandler(metadata.getName())).thenReturn(Optional.of(handler));
        when(handler.validateActionAllowed(any())).thenReturn(actionAuthorized);
        var actionResult = ActionResult.builder().success(true)
            .data(ActionPayload.object(Map.of("guide", "Use approved setup instructions."))).build();
        when(handler.executeAction(any(), any())).thenReturn(actionResult);
        when(handler.buildPostActionLlmFacts(eq(actionResult), any()))
            .thenReturn(Optional.of(Map.of("guide", "Use approved setup instructions.")));

        var properties = new OrchestrationProperties();
        properties.setProfile(OrchestrationProfile.PRODUCTION_CHAT);
        properties.setDefaultMode("executor");
        var mode = new OrchestrationProperties.ModeOverrides();
        mode.setActionsEnabled(true);
        mode.setRetrievalEnabled(retrievalEnabled);
        mode.setRetrievalAllowlistRequired(true);
        mode.setVectorSpaceSelectionRequired(false);
        mode.setKnowledgeBaseOverviewEnabled(false);
        mode.setRag(new OrchestrationProperties.RagModeOverrides());
        var reads = new OrchestrationProperties.ReadActionResolutionModeOverrides();
        reads.setEnabled(true);
        reads.setRequireAllowlist(true);
        reads.setAllowedReadActions(List.of(metadata.getName()));
        reads.setPlanningMode(OrchestrationProperties.ReadActionResolutionPlanningMode.ITERATIVE);
        reads.setRagCooperationMode(OrchestrationProperties.ReadActionResolutionRagCooperationMode.NONE);
        mode.setReadActionResolution(reads);
        properties.getModes().put("executor", mode);
        var policyStep = new OrchestrationPolicyResolutionStep(properties);
        ReflectionTestUtils.setField(policyStep, "actionRegistry", registry);
        String query = "Show me the public service guide.";
        var input = policyStep.process(PipelineContext.from(query, OrchestrationContext.anonymous()));
        var context = input.getOrchestrationContext();
        assertThat(input.getOrchestrationPolicy().profile()).isEqualTo(OrchestrationProfile.PRODUCTION_CHAT);
        assertThat(input.getOrchestrationPolicy().mode()).isEqualTo("executor");
        assertThat(input.getOrchestrationPolicy().ragBudgets().hasVectorSpaceAllowlist()).isFalse();
        var intent = Intent.builder().type(IntentType.INFORMATION).intent("public_guide")
            .requiresRetrieval(true).requiresGeneration(true).requiresTargetResolution(false)
            .needsAdvancedRAG(false).build();
        var response = MultiIntentResponse.builder().intents(List.of(intent)).build();
        assertThat(new IntentExtractionValidator(registry).validate(response, query, context).valid()).isTrue();
        assertThat(intent.getRequiresRetrieval()).isTrue();
        assertThat(intent.getVectorSpace()).isNull();

        var ai = mock(AICoreService.class);
        when(ai.generateContent(any(), eq(LlmPurpose.ORCHESTRATION))).thenReturn(AIGenerationResponse.builder()
            .content(plannerReads
                ? "{\"decision\":\"EXECUTE_READ_ACTIONS\",\"actions\":[{\"name\":\"inspect_public_guide\",\"params\":{}}]}"
                : "{\"decision\":\"USE_RAG_ONLY\",\"actions\":[]}").build());
        when(ai.generateTextResponse(anyString(), eq(LlmPurpose.GENERATION)))
            .thenReturn(AIGenerationResponse.builder().content("Use approved setup instructions.").build());
        var readService = new ReadActionResolutionService(ai, registry,
            new IntentExtractionJsonSupport(new ObjectMapper()), promptTemplateResolver(), new PromptRenderer(),
            mock(AIEntityConfigurationLoader.class));
        var rag = mock(RAGProvider.class);
        var step = buildStep(rag, ai);
        ReflectionTestUtils.setField(step, "readActionResolutionServiceProvider", providerOf(readService));
        var vectorStep = new VectorSpaceResolutionStep(new VectorSpaceRoutingProperties(),
            providerOf((KnowledgeBaseOverviewService) null));
        ReflectionTestUtils.setField(vectorStep, "actionRegistry", registry);
        var routed = vectorStep.process(input.toBuilder().intentResponse(response).build());
        assertThat(routed.isShouldTerminate()).isFalse();
        assertThat(intent.getVectorSpace()).isNull();
        var result = step.process(routed).getIntentResult();

        verify(ai).generateContent(any(), eq(LlmPurpose.ORCHESTRATION));
        verifyNoInteractions(rag);
        if (plannerReads && actionAuthorized) {
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
            assertThat(result.getMessage()).isEqualTo("Use approved setup instructions.");
            verify(handler).executeAction(eq(Map.of()), any());
        } else {
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getData()).containsEntry("reason", retrievalEnabled
                ? "RETRIEVAL_ALLOWLIST_REQUIRED" : "RETRIEVAL_DISABLED_BY_POLICY");
            verify(handler, never()).executeAction(any(), any());
            verify(ai, never()).generateTextResponse(anyString(), any());
        }
    }

    @Test
    void doesNotReportRawReadEvidenceAsSuccessWhenConversationGenerationFails() {
        RAGProvider ragProvider = mock(RAGProvider.class);
        AICoreService ai = mock(AICoreService.class);
        ReadActionResolutionService resolver = mock(ReadActionResolutionService.class);
        when(resolver.resolve(any(), any(), any())).thenReturn(
            ReadActionResolutionService.ResolutionOutcome.answerFromActionsOnly(
                "Internal current evidence: Alpha=42; Beta=70", List.of("records"), List.of(),
                Map.of("attempted", true)));
        IntentHandlingStep step = buildStep(ragProvider, ai);
        ReflectionTestUtils.setField(step, "readActionResolutionServiceProvider", providerOf(resolver));
        OrchestrationContext orchestration = OrchestrationContext.forUser("owner-1");
        orchestration.setConversationId("conversation-1");
        Intent intent = Intent.builder().type(IntentType.INFORMATION).intent("Which is lower?")
            .requiresRetrieval(true).requiresGeneration(true).vectorSpace("records").build();
        PipelineContext input = PipelineContext.from("Which is lower?", orchestration).toBuilder()
            .historyMessages(List.of(AIChatMessage.user("Compare Alpha and Beta")))
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build()).build();

        OrchestrationResult result = step.process(input).getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ERROR);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("GENERATION_FAILED");
        assertThat(result.getMessage()).doesNotContain("Alpha=42");
        assertThat(result.getData()).containsKey("generationError");
        verify(ai, never()).generateTextResponse(anyString(), any());
        verifyNoInteractions(ragProvider);
    }

    @Test
    void shouldAnswerFromReadActionEvidenceWithoutRag() {
        RAGProvider ragProvider = mock(RAGProvider.class);
        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateTextResponse(anyString(), any(LlmPurpose.class)))
            .thenReturn(AIGenerationResponse.builder().content("Alpha Record is ready right now.").build());

        ReadActionResolutionService readActionResolutionService = mock(ReadActionResolutionService.class);
        when(readActionResolutionService.resolve(any(), any(), any())).thenReturn(
            ReadActionResolutionService.ResolutionOutcome.answerFromActionsOnly(
                "READ ACTION EVIDENCE\n- action: check_status\n  success: true\n  evidence: Alpha Record is ready.",
                List.of("records"),
                List.of(new ReadActionResolutionService.ExecutedReadAction(
                    "check_status",
                    Map.of("recordId", "REC-001"),
                    null,
                    null,
                    true,
                    "Alpha Record is ready.",
                    null
                )),
                Map.of("attempted", true, "executedActionsCount", 1)
            )
        );

        IntentHandlingStep step = buildStep(ragProvider, aiCoreService);
        ReflectionTestUtils.setField(step, "readActionResolutionServiceProvider", providerOf(readActionResolutionService));

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("Is Alpha Record ready?")
            .requiresRetrieval(true)
            .requiresGeneration(true)
            .vectorSpace("records")
            .optimizedQuery("check Alpha Record status")
            .build();

        PipelineContext context = PipelineContext.from("Is Alpha Record ready?", OrchestrationContext.forUser("user-1"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        OrchestrationResult result = step.process(context).getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("Alpha Record is ready right now.");
        assertThat(result.getMetadata()).containsKey("readActionResolution");
        assertThat(result.getData()).containsKey("readActionResolution");
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiCoreService).generateTextResponse(promptCaptor.capture(), eq(LlmPurpose.GENERATION));
        assertThat(promptCaptor.getValue())
            .contains("READ ACTION EVIDENCE POLICY")
            .contains("If a named lookup failed or returned no matching record")
            .contains("Do not provide handoffs, next steps, or support references unless they are explicitly present in the evidence.");
        verify(ragProvider, never()).performRag(any());
        verify(ragProvider, never()).performRAGQuery(any());
    }

    @Test
    void shouldRunReadActionPlannerBeforeReturningInformationDirectAnswer() {
        RAGProvider ragProvider = mock(RAGProvider.class);
        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateTextResponse(anyString(), any(LlmPurpose.class)))
            .thenReturn(AIGenerationResponse.builder().content("SKU-0001 is available with 6 in stock.").build());

        ReadActionResolutionService readActionResolutionService = mock(ReadActionResolutionService.class);
        when(readActionResolutionService.resolve(any(), any(), any())).thenReturn(
            ReadActionResolutionService.ResolutionOutcome.answerFromActionsOnly(
                "READ ACTION EVIDENCE\n- action: check_availability\n  success: true\n  evidence: SKU-0001 has 6 in stock.",
                List.of("product"),
                List.of(new ReadActionResolutionService.ExecutedReadAction(
                    "check_availability",
                    Map.of("sku", "SKU-0001"),
                    null,
                    null,
                    true,
                    "SKU-0001 has 6 in stock.",
                    null
                )),
                Map.of("attempted", true, "executedActionsCount", 1)
            )
        );

        IntentHandlingStep step = buildStep(ragProvider, aiCoreService);
        ReflectionTestUtils.setField(step, "readActionResolutionServiceProvider", providerOf(readActionResolutionService));

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("Check live availability for SKU-0001.")
            .requiresRetrieval(false)
            .requiresGeneration(false)
            .directAnswer("SKU-0001 is not present in the live store data.")
            .build();

        PipelineContext context = PipelineContext.from("Check live availability for SKU-0001.", OrchestrationContext.forUser("user-1"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        OrchestrationResult result = step.process(context).getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("SKU-0001 is available with 6 in stock.");
        assertThat(result.getMetadata()).containsKey("readActionResolution");
        assertThat(result.getData()).containsKey("readActionResolution");
        verify(readActionResolutionService).resolve(any(), any(), any());
        verify(aiCoreService).generateTextResponse(anyString(), eq(LlmPurpose.GENERATION));
        verify(ragProvider, never()).performRag(any());
        verify(ragProvider, never()).performRAGQuery(any());
    }

    @Test
    void shouldForceRagWhenReadActionPlannerRequestsAdditionalGrounding() {
        RAGProvider ragProvider = mock(RAGProvider.class);
        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateTextResponse(anyString(), any(LlmPurpose.class)))
            .thenReturn(AIGenerationResponse.builder().content("Refunds are allowed within 30 days.").build());
        when(ragProvider.performRAGQuery(any())).thenReturn(RAGResponse.builder()
            .documents(List.of(
                RAGResponse.RAGDocument.builder()
                    .id("policy-1")
                    .title("Refund Policy")
                    .content("Customers may request a refund within 30 days of delivery.")
                    .metadata(Map.of("vectorSpace", "policy"))
                    .build()
            ))
            .context("Customers may request a refund within 30 days of delivery.")
            .success(true)
            .build());

        ReadActionResolutionService readActionResolutionService = mock(ReadActionResolutionService.class);
        when(readActionResolutionService.resolve(any(), any(), any())).thenReturn(
            ReadActionResolutionService.ResolutionOutcome.continueWithRag(
                "READ ACTION EVIDENCE\n- action: get_policy\n  success: true\n  evidence: refund policy found.",
                List.of("policy"),
                List.of(new ReadActionResolutionService.ExecutedReadAction(
                    "get_policy",
                    Map.of("query", "refund policy"),
                    null,
                    null,
                    true,
                    "refund policy found.",
                    null
                )),
                Map.of("attempted", true, "useRag", true, "executedActionsCount", 1)
            )
        );

        IntentHandlingStep step = buildStep(ragProvider, aiCoreService);
        ReflectionTestUtils.setField(step, "readActionResolutionServiceProvider", providerOf(readActionResolutionService));

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("What is the refund policy?")
            .requiresRetrieval(false)
            .requiresGeneration(true)
            .optimizedQuery("refund policy")
            .build();

        PipelineContext context = PipelineContext.from("What is the refund policy?", OrchestrationContext.forUser("user-1"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        OrchestrationResult result = step.process(context).getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("Refunds are allowed within 30 days.");
        assertThat(result.getMetadata()).containsKey("readActionResolution");
        assertThat(result.getData()).containsKey("ragResponse");
        verify(ragProvider).performRAGQuery(any());
    }

    @ParameterizedTest(name = "generation={0}, retrievalEnabled={1}, allowlistRequired={2}")
    @CsvSource({
        "true, false, false, RETRIEVAL_DISABLED_BY_POLICY",
        "false, false, false, RETRIEVAL_DISABLED_BY_POLICY",
        "true, true, true, RETRIEVAL_ALLOWLIST_REQUIRED",
        "false, true, true, RETRIEVAL_ALLOWLIST_REQUIRED"
    })
    void shouldRejectPlannerPromotedRetrievalWhenServerPolicyDisallowsIt(boolean needsGeneration,
                                                                        boolean retrievalEnabled,
                                                                        boolean retrievalAllowlistRequired,
                                                                        String expectedReason) {
        RAGProvider ragProvider = mock(RAGProvider.class);
        AICoreService aiCoreService = mock(AICoreService.class);
        ReadActionResolutionService readActionResolutionService = mock(ReadActionResolutionService.class);
        when(readActionResolutionService.resolve(any(), any(), any())).thenReturn(
            ReadActionResolutionService.ResolutionOutcome.continueWithRag(
                null,
                List.of("policy"),
                List.of(),
                Map.of("attempted", true, "useRag", true)
            )
        );

        IntentHandlingStep step = buildStep(ragProvider, aiCoreService);
        ReflectionTestUtils.setField(step, "readActionResolutionServiceProvider", providerOf(readActionResolutionService));

        OrchestrationPolicy policy = new OrchestrationPolicy(
            null,
            "resolver",
            null,
            OrchestrationProperties.InformationMode.LLM_DRIVEN,
            new OrchestrationPolicy.OrchestrationCapabilities(
                true,
                retrievalEnabled,
                false,
                true,
                false,
                false,
                true,
                retrievalAllowlistRequired,
                false,
                true,
                false,
                false,
                false
            ),
            OrchestrationPolicy.RagBudgets.defaults()
        );
        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("What is the refund policy?")
            .requiresRetrieval(false)
            .requiresGeneration(needsGeneration)
            .directAnswer("Refund policy information is not available yet.")
            .optimizedQuery("refund policy")
            .build();
        PipelineContext context = PipelineContext.from("What is the refund policy?", OrchestrationContext.forUser("user-1"))
            .toBuilder()
            .orchestrationPolicy(policy)
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        OrchestrationResult result = step.process(context).getIntentResult();

        verify(readActionResolutionService).resolve(any(), any(), any());
        verifyNoInteractions(ragProvider, aiCoreService);
        assertThat(result.getType()).isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getData()).containsEntry("reason", expectedReason);
    }

    @Test
    void shouldGenerateFinalAnswerWhenReadActionPlannerUsesRagEvenIfExtractorDidNotRequestGeneration() {
        RAGProvider ragProvider = mock(RAGProvider.class);
        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateTextResponse(anyString(), any(LlmPurpose.class)))
            .thenReturn(AIGenerationResponse.builder()
                .content("No negative review or safety complaint evidence was found in the live store data.")
                .build());
        when(ragProvider.performRAGQuery(any())).thenReturn(RAGResponse.builder()
            .documents(List.of(
                RAGResponse.RAGDocument.builder()
                    .id("product-1")
                    .title("Alpha Snowboard")
                    .content("Alpha Snowboard catalog record.")
                    .metadata(Map.of("vectorSpace", "product"))
                    .build()
            ))
            .context("Alpha Snowboard catalog record.")
            .success(true)
            .build());

        ReadActionResolutionService readActionResolutionService = mock(ReadActionResolutionService.class);
        when(readActionResolutionService.resolve(any(), any(), any())).thenReturn(
            ReadActionResolutionService.ResolutionOutcome.continueWithRag(
                "READ ACTION EVIDENCE\n- action: relationship_query\n  success: true\n  evidence: No review records matched the requested complaint query.",
                List.of("product"),
                List.of(new ReadActionResolutionService.ExecutedReadAction(
                    "relationship_query",
                    Map.of("query", "negative reviews or safety complaints for Alpha Snowboard"),
                    null,
                    null,
                    true,
                    "No review records matched the requested complaint query.",
                    null
                )),
                Map.of("attempted", true, "useRag", true, "executedActionsCount", 1)
            )
        );

        IntentHandlingStep step = buildStep(ragProvider, aiCoreService);
        ReflectionTestUtils.setField(step, "readActionResolutionServiceProvider", providerOf(readActionResolutionService));

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("Are there any negative reviews or safety complaints for Alpha Snowboard?")
            .requiresRetrieval(true)
            .requiresGeneration(false)
            .optimizedQuery("negative reviews or safety complaints for Alpha Snowboard")
            .vectorSpace("product")
            .build();

        PipelineContext context = PipelineContext.from(
                "Are there any negative reviews or safety complaints for Alpha Snowboard?",
                OrchestrationContext.forUser("user-1")
            )
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        OrchestrationResult result = step.process(context).getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("No negative review or safety complaint evidence was found in the live store data.");
        assertThat(result.getData()).containsEntry("requiresGeneration", true);
        assertThat(result.getData()).containsKey("ragResponse");
        assertThat(result.getMetadata()).containsKey("readActionResolution");
        verify(ragProvider).performRAGQuery(any());
        verify(ragProvider, never()).performRag(any());
    }

    @Test
    void shouldUseReadActionEvidenceInsteadOfFanoutClarificationWhenRagIsAmbiguous() {
        RAGProvider ragProvider = mock(RAGProvider.class);
        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateTextResponse(anyString(), any(LlmPurpose.class)))
            .thenReturn(AIGenerationResponse.builder()
                .content("SKU-001 is in stock, but I could not find a refund policy in the indexed knowledge base.")
                .build());
        when(ragProvider.performRAGQuery(any())).thenReturn(RAGResponse.builder()
            .documents(List.of())
            .context("")
            .success(true)
            .build());

        ReadActionResolutionService readActionResolutionService = mock(ReadActionResolutionService.class);
        when(readActionResolutionService.resolve(any(), any(), any())).thenReturn(
            ReadActionResolutionService.ResolutionOutcome.continueWithRag(
                "READ ACTION EVIDENCE\n- action: check_availability\n  success: true\n  evidence: SKU-001 is in stock.\n"
                    + "- action: get_policy\n  success: true\n  evidence: No refund policy was found in live policy search.",
                List.of("policies", "commerce"),
                List.of(
                    new ReadActionResolutionService.ExecutedReadAction(
                        "check_availability",
                        Map.of("sku", "SKU-001"),
                        null,
                        null,
                        true,
                        "SKU-001 is in stock.",
                        null
                    ),
                    new ReadActionResolutionService.ExecutedReadAction(
                        "get_policy",
                        Map.of("query", "refund policy"),
                        null,
                        null,
                        true,
                        "No refund policy was found in live policy search.",
                        null
                    )
                ),
                Map.of("attempted", true, "useRag", true, "executedActionsCount", 2)
            )
        );

        IntentHandlingStep step = buildStep(ragProvider, aiCoreService);
        ReflectionTestUtils.setField(step, "readActionResolutionServiceProvider", providerOf(readActionResolutionService));

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("Check live availability for SKU-001 and summarize the refund policy.")
            .requiresRetrieval(true)
            .requiresGeneration(true)
            .optimizedQuery("SKU-001 availability; refund policy")
            .build();

        PipelineContext context = PipelineContext.from(
                "Check live availability for SKU-001 and summarize the refund policy.",
                OrchestrationContext.forUser("user-1")
            )
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        OrchestrationResult result = step.process(context).getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo(
            "SKU-001 is in stock, but I could not find a refund policy in the indexed knowledge base."
        );
        assertThat(result.getMetadata()).containsKey("readActionResolution");
        verify(ragProvider, times(2)).performRAGQuery(any());
    }

    @Test
    void shouldRegenerateAdvancedRagResponseWhenReadActionEvidenceExists() {
        RAGProvider ragProvider = mock(RAGProvider.class);
        AdvancedRAGProvider advancedRagProvider = mock(AdvancedRAGProvider.class);
        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateTextResponse(anyString(), any(LlmPurpose.class)))
            .thenReturn(AIGenerationResponse.builder()
                .content("Alpha Record has score 7.5 and status ready.")
                .build());
        when(advancedRagProvider.performAdvancedRAG(any())).thenReturn(
            AdvancedRAGResponse.builder()
                .success(true)
                .response("The retrieved context does not provide score or status.")
                .context("Alpha Record is a candidate.")
                .confidenceScore(0.9)
                .documents(List.of(AdvancedRAGResponse.RAGDocument.builder()
                    .id("record-1")
                    .title("Alpha Record")
                    .content("Alpha Record is a candidate.")
                    .type("record")
                    .build()))
                .build()
        );

        ReadActionResolutionService readActionResolutionService = mock(ReadActionResolutionService.class);
        when(readActionResolutionService.resolve(any(), any(), any())).thenReturn(
            ReadActionResolutionService.ResolutionOutcome.continueWithRag(
                "READ ACTION EVIDENCE\n- action: get_record_details\n  success: true\n  evidence: {\"name\":\"Alpha Record\",\"score\":7.5,\"status\":\"ready\"}",
                List.of("records"),
                List.of(new ReadActionResolutionService.ExecutedReadAction(
                    "get_record_details",
                    Map.of("query", "Alpha Record"),
                    null,
                    null,
                    true,
                    "{\"name\":\"Alpha Record\",\"score\":7.5,\"status\":\"ready\"}",
                    null
                )),
                Map.of("attempted", true, "useRag", true, "executedActionsCount", 1)
            )
        );

        IntentHandlingStep step = buildStep(ragProvider, aiCoreService, advancedRagProvider);
        ReflectionTestUtils.setField(step, "readActionResolutionServiceProvider", providerOf(readActionResolutionService));

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("Is Alpha Record under 10 and ready?")
            .requiresRetrieval(true)
            .requiresGeneration(true)
            .needsAdvancedRAG(true)
            .optimizedQuery("Alpha Record score status")
            .vectorSpace("records")
            .build();

        PipelineContext context = PipelineContext.from(
                "Is Alpha Record under 10 and ready?",
                OrchestrationContext.forUser("user-1")
            )
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        OrchestrationResult result = step.process(context).getIntentResult();

        assertThat(result.getMessage()).isEqualTo(
            "Alpha Record has score 7.5 and status ready."
        );
        verify(advancedRagProvider).performAdvancedRAG(any());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiCoreService).generateTextResponse(promptCaptor.capture(), eq(LlmPurpose.GENERATION));
        assertThat(promptCaptor.getValue())
            .contains("READ ACTION EVIDENCE POLICY")
            .contains("\"score\":7.5")
            .contains("\"status\":\"ready\"")
            .contains("Alpha Record is a candidate.");
    }

    private IntentHandlingStep buildStep(RAGProvider ragProvider, AICoreService aiCoreService) {
        return buildStep(ragProvider, aiCoreService, null);
    }

    private IntentHandlingStep buildStep(RAGProvider ragProvider,
                                         AICoreService aiCoreService,
                                         AdvancedRAGProvider advancedRagProvider) {
        AIServiceConfig aiServiceConfig = new AIServiceConfig();
        aiServiceConfig.getFeatures().setEnableGeneration(true);
        return new IntentHandlingStep(
            mock(AIActionRegistry.class),
            providerOf(ragProvider),
            aiCoreService,
            aiServiceConfig,
            providerOf(advancedRagProvider),
            new VectorSpaceRoutingProperties(),
            new RankBasedMerger(),
            new RelationshipQueryPostActionGenerationProperties(),
            new PostActionGenerationProperties(),
            providerOf(new ObjectMapper()),
            new OrchestrationProperties(),
            providerOf((KnowledgeBaseOverviewService) null),
            null,
            new InMemoryPendingActionStore(),
            new InMemoryActionDraftStore(),
            promptTemplateResolver(),
            new PromptRenderer()
        );
    }

    private <T> ObjectProvider<T> providerOf(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private PromptTemplateResolver promptTemplateResolver() {
        return new PromptTemplateResolver(
            new ClasspathPromptTemplateStore(new DefaultResourceLoader()),
            new PromptBundleProperties()
        );
    }
}
