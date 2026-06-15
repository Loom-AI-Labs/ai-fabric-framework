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
import ai.fabric.dto.AdvancedRAGResponse;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.dto.RAGResponse;
import ai.fabric.intent.KnowledgeBaseOverviewService;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.InMemoryPendingActionStore;
import ai.fabric.intent.actiondraft.InMemoryActionDraftStore;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.information.ReadActionResolutionService;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.vectorspace.RankBasedMerger;
import ai.fabric.prompt.ClasspathPromptTemplateStore;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import ai.fabric.spi.AdvancedRAGProvider;
import ai.fabric.spi.RAGProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntentHandlingStepReadActionResolutionTest {

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
