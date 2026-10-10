package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.AIServiceConfig;
import ai.fabric.config.OrchestrationProperties;
import ai.fabric.config.PostActionGenerationProperties;
import ai.fabric.config.PromptBundleProperties;
import ai.fabric.config.RelationshipQueryPostActionGenerationProperties;
import ai.fabric.config.VectorSpaceRoutingProperties;
import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.dto.AdvancedRAGRequest;
import ai.fabric.dto.AdvancedRAGResponse;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.dto.RAGRequest;
import ai.fabric.dto.RAGResponse;
import ai.fabric.intent.KnowledgeBaseOverviewService;
import ai.fabric.intent.action.AIActionHandler;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.ActionResultContracts;
import ai.fabric.intent.action.InMemoryPendingActionStore;
import ai.fabric.intent.actiondraft.InMemoryActionDraftStore;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.information.ReadActionExecutionScope;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

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
import static org.mockito.Mockito.when;

class IntentHandlingStepCompoundReadEvidenceTest {

    @Test
    void scopesEachInformationPlannerToItsCompoundObligation() {
        RAGProvider ragProvider = mock(RAGProvider.class);
        when(ragProvider.performRag(any(RAGRequest.class))).thenReturn(response(document(
            "evidence-1",
            "document",
            "knowledge-source",
            "Grounded evidence."
        )));
        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateTextResponse(anyString(), eq(LlmPurpose.GENERATION)))
            .thenReturn(AIGenerationResponse.builder().content("Combined grounded answer.").build());
        ReadActionResolutionService resolutionService = mock(ReadActionResolutionService.class);
        when(resolutionService.resolve(any(), any(), any(), any(ReadActionExecutionScope.class)))
            .thenReturn(ReadActionResolutionService.ResolutionOutcome.skipped("NO_ELIGIBLE_READ_ACTIONS"));

        IntentHandlingStep step = newStep(mock(AIActionRegistry.class), ragProvider, aiCoreService);
        ReflectionTestUtils.setField(step, "readActionResolutionServiceProvider", providerOf(resolutionService));

        Intent inventory = information("Find electric inventory", "dealer-vehicle");
        inventory.setOptimizedQuery("available electric vehicle inventory");
        Intent policy = information("Explain the delivery policy", "document");
        policy.setOptimizedQuery("delivery policy charges requirements");
        String originalQuery = "What electric cars do you have, and what is your delivery policy?";

        step.process(context(originalQuery, inventory, policy));

        ArgumentCaptor<PipelineContext> contextCaptor = ArgumentCaptor.forClass(PipelineContext.class);
        verify(resolutionService, times(2)).resolve(
            any(),
            any(),
            contextCaptor.capture(),
            any(ReadActionExecutionScope.class)
        );
        assertThat(contextCaptor.getAllValues())
            .extracting(PipelineContext::getEffectiveQuery)
            .containsExactly("available electric vehicle inventory", "delivery policy charges requirements");
        assertThat(contextCaptor.getAllValues())
            .extracting(PipelineContext::getOriginalQuery)
            .containsOnly(originalQuery);
    }

    @Test
    void synthesizesTwoInformationIntentsOnceAndPreservesPerIntentQueries() {
        RAGProvider ragProvider = mock(RAGProvider.class);
        when(ragProvider.performRag(any(RAGRequest.class))).thenAnswer(invocation -> {
            RAGRequest request = invocation.getArgument(0);
            if ("dealer-vehicle".equals(request.getEntityType())) {
                return response(document(
                    "vehicle-1",
                    "dealer-vehicle",
                    "autotrader-stock",
                    "Aster E1 is available at Northfield Riverside."
                ));
            }
            return response(document(
                "policy-1",
                "document",
                "dealership-policy",
                "Home delivery is available within 30 miles."
            ));
        });

        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateTextResponse(anyString(), eq(LlmPurpose.GENERATION)))
            .thenReturn(AIGenerationResponse.builder().content("The Aster E1 is available and qualifies for local delivery.").build());

        IntentHandlingStep step = newStep(mock(AIActionRegistry.class), ragProvider, aiCoreService);
        Intent inventory = information("Find the available Aster E1", "dealer-vehicle");
        Intent policy = information("What is the delivery policy?", "document");

        PipelineContext resultContext = step.process(context(
            "Is the Aster E1 available and what is the delivery policy?",
            inventory,
            policy
        ));
        OrchestrationResult result = resultContext.getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.getMessage()).contains("available", "delivery");
        assertThat(result.getChildren()).hasSize(2);
        assertThat(result.getData()).containsKeys("documents", "sources", "compoundEvidence");
        @SuppressWarnings("unchecked")
        List<RAGResponse.RAGDocument> documents = (List<RAGResponse.RAGDocument>) result.getData().get("documents");
        assertThat(documents).extracting(RAGResponse.RAGDocument::getId)
            .containsExactly("vehicle-1", "policy-1");

        ArgumentCaptor<RAGRequest> requestCaptor = ArgumentCaptor.forClass(RAGRequest.class);
        verify(ragProvider, times(2)).performRag(requestCaptor.capture());
        assertThat(requestCaptor.getAllValues())
            .extracting(RAGRequest::getEntityType)
            .containsExactly("dealer-vehicle", "document");
        assertThat(requestCaptor.getAllValues())
            .extracting(RAGRequest::getQuery)
            .containsExactly("Find the available Aster E1", "What is the delivery policy?");
        verify(ragProvider, never()).performRAGQuery(any(RAGRequest.class));

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiCoreService, times(1)).generateTextResponse(promptCaptor.capture(), eq(LlmPurpose.GENERATION));
        assertThat(promptCaptor.getValue())
            .contains("Aster E1 is available", "Home delivery is available", "OBLIGATION 1", "OBLIGATION 2");
    }

    @Test
    void combinesGroundingEligibleReadActionWithDocumentEvidenceWithoutChildGeneration() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionHandler handler = mock(AIActionHandler.class);
        AIActionMetaData metadata = AIActionMetaData.builder()
            .name("inventory_lookup")
            .accessMode(ActionAccessMode.READ)
            .groundingEligible(true)
            .build();
        when(registry.findMetadata("inventory_lookup")).thenReturn(Optional.of(metadata));
        when(registry.findHandler("inventory_lookup")).thenReturn(Optional.of(handler));
        when(handler.validateActionAllowed(any())).thenReturn(true);
        ActionResult actionResult = ActionResult.builder()
            .success(true)
            .message("Inventory lookup completed.")
            .data(ActionResultContracts.object(Map.of("vehicle", "Aster E1", "status", "AVAILABLE")))
            .build();
        when(handler.executeAction(any(), any())).thenReturn(actionResult);
        when(handler.buildPostActionLlmFacts(eq(actionResult), any())).thenReturn(Optional.of(Map.of(
            "vehicle", "Aster E1",
            "status", "AVAILABLE"
        )));

        RAGProvider ragProvider = mock(RAGProvider.class);
        when(ragProvider.performRag(any(RAGRequest.class))).thenReturn(response(document(
            "policy-1",
            "document",
            "dealership-policy",
            "Home delivery is available within 30 miles."
        )));

        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateTextResponse(anyString(), eq(LlmPurpose.GENERATION)))
            .thenReturn(AIGenerationResponse.builder().content("The vehicle is available and local delivery is supported.").build());

        IntentHandlingStep step = newStep(registry, ragProvider, aiCoreService);
        Intent action = Intent.builder()
            .type(IntentType.ACTION)
            .action("inventory_lookup")
            .generationInstructions("Summarize availability.")
            .requiresGeneration(true)
            .build();
        Intent policy = information("What is the delivery policy?", "document");

        OrchestrationResult result = step.process(context(
            "Is the Aster E1 available and can it be delivered?",
            action,
            policy
        )).getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.getMessage()).contains("available", "delivery");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) result.getData().get("actions");
        assertThat(actions).hasSize(1);
        assertThat(actions.getFirst())
            .containsEntry("action", "inventory_lookup")
            .containsKey("actionExecutionId");
        @SuppressWarnings("unchecked")
        Map<String, Object> diagnostics = (Map<String, Object>) result.getData().get("compoundEvidence");
        assertThat((List<?>) diagnostics.get("actionExecutionIds")).hasSize(1);
        verify(handler, times(1)).executeAction(any(), any());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiCoreService, times(1)).generateTextResponse(promptCaptor.capture(), eq(LlmPurpose.GENERATION));
        assertThat(promptCaptor.getValue())
            .contains("Aster E1", "AVAILABLE", "Home delivery is available", "OBLIGATION 1", "OBLIGATION 2");
    }

    @Test
    void rejectsMismatchedDirectReadActionAndRetrievesTheValidatedObligationSpace() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionHandler handler = mock(AIActionHandler.class);
        AIActionMetaData metadata = AIActionMetaData.builder()
            .name("inventory_lookup")
            .accessMode(ActionAccessMode.READ)
            .groundingEligible(true)
            .groundingVectorSpaces(List.of("dealer-vehicle"))
            .build();
        when(registry.findMetadata("inventory_lookup")).thenReturn(Optional.of(metadata));
        when(registry.findHandler("inventory_lookup")).thenReturn(Optional.of(handler));
        when(handler.validateActionAllowed(any())).thenReturn(true);
        ActionResult actionResult = ActionResult.builder()
            .success(true)
            .message("Inventory lookup completed.")
            .data(ActionResultContracts.object(Map.of("vehicle", "Aster E1")))
            .build();
        when(handler.executeAction(any(), any())).thenReturn(actionResult);
        when(handler.buildPostActionLlmFacts(eq(actionResult), any())).thenReturn(Optional.of(Map.of(
            "vehicle", "Aster E1"
        )));

        RAGProvider ragProvider = mock(RAGProvider.class);
        when(ragProvider.performRag(any(RAGRequest.class))).thenReturn(response(document(
            "policy-1",
            "document",
            "dealership-policy",
            "Home delivery is available within 30 miles."
        )));
        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateTextResponse(anyString(), eq(LlmPurpose.GENERATION)))
            .thenReturn(AIGenerationResponse.builder()
                .content("The Aster E1 is available and local delivery is supported.")
                .build());

        Intent inventory = Intent.builder()
            .type(IntentType.ACTION)
            .action("inventory_lookup")
            .intent("Find available electric inventory")
            .optimizedQuery("available electric vehicle inventory")
            .vectorSpace("dealer-vehicle")
            .build();
        Intent misclassifiedPolicy = Intent.builder()
            .type(IntentType.ACTION)
            .action("inventory_lookup")
            .intent("Explain the delivery policy")
            .optimizedQuery("delivery policy charges requirements")
            .vectorSpace("document")
            .build();
        IntentHandlingStep step = newStep(registry, ragProvider, aiCoreService);

        OrchestrationResult result = step.process(context(
            "Which electric cars are available and what is the delivery policy?",
            inventory,
            misclassifiedPolicy
        )).getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.getChildren()).hasSize(2);
        assertThat(result.getChildren().get(1).getMetadata())
            .containsKey("actionGroundingScopeMismatch");
        @SuppressWarnings("unchecked")
        List<RAGResponse.RAGDocument> documents =
            (List<RAGResponse.RAGDocument>) result.getData().get("documents");
        assertThat(documents).extracting(RAGResponse.RAGDocument::getId)
            .containsExactly("policy-1");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) result.getData().get("actions");
        assertThat(actions).hasSize(1);
        verify(handler, times(1)).executeAction(any(), any());
        ArgumentCaptor<RAGRequest> requestCaptor = ArgumentCaptor.forClass(RAGRequest.class);
        verify(ragProvider, times(1)).performRag(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getEntityType()).isEqualTo("document");
        assertThat(requestCaptor.getValue().getQuery()).isEqualTo("delivery policy charges requirements");
    }

    @Test
    void retainsSuccessfulEvidenceAndMarksAnEmptyObligationExplicitly() {
        RAGProvider ragProvider = mock(RAGProvider.class);
        when(ragProvider.performRag(any(RAGRequest.class))).thenAnswer(invocation -> {
            RAGRequest request = invocation.getArgument(0);
            if ("dealer-vehicle".equals(request.getEntityType())) {
                return response(document(
                    "vehicle-1",
                    "dealer-vehicle",
                    "autotrader-stock",
                    "Aster E1 is available."
                ));
            }
            return RAGResponse.builder().documents(List.of()).success(true).build();
        });

        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateTextResponse(anyString(), eq(LlmPurpose.GENERATION)))
            .thenReturn(AIGenerationResponse.builder()
                .content("The Aster E1 is available, but no delivery-policy evidence was returned.")
                .build());

        IntentHandlingStep step = newStep(mock(AIActionRegistry.class), ragProvider, aiCoreService);
        OrchestrationResult result = step.process(context(
            "Is the Aster E1 available and what is the delivery policy?",
            information("Find the available Aster E1", "dealer-vehicle"),
            information("What is the delivery policy?", "document")
        )).getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.getMessage()).contains("available", "no delivery-policy evidence");
        @SuppressWarnings("unchecked")
        Map<String, Object> diagnostics = (Map<String, Object>) result.getData().get("compoundEvidence");
        assertThat(diagnostics)
            .containsEntry("obligationCount", 2)
            .containsEntry("completedObligationCount", 1)
            .containsEntry("emptyObligationCount", 1)
            .containsEntry("finalSynthesisPerformed", true);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiCoreService).generateTextResponse(promptCaptor.capture(), eq(LlmPurpose.GENERATION));
        assertThat(promptCaptor.getValue())
            .contains("Aster E1 is available", "No sufficient grounded evidence was returned for this obligation");
    }

    @Test
    void clauseOrderDoesNotDropEitherEvidenceDomain() {
        RAGProvider ragProvider = mock(RAGProvider.class);
        when(ragProvider.performRag(any(RAGRequest.class))).thenAnswer(invocation -> {
            RAGRequest request = invocation.getArgument(0);
            return "document".equals(request.getEntityType())
                ? response(document("policy-1", "document", "dealership-policy", "Delivery costs GBP 49."))
                : response(document("vehicle-1", "dealer-vehicle", "autotrader-stock", "Aster E1 is electric."));
        });
        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateTextResponse(anyString(), eq(LlmPurpose.GENERATION)))
            .thenReturn(AIGenerationResponse.builder().content("Electric stock and delivery policy are both covered.").build());

        IntentHandlingStep step = newStep(mock(AIActionRegistry.class), ragProvider, aiCoreService);
        OrchestrationResult result = step.process(context(
            "What is the delivery policy, and which electric cars are available?",
            information("What is the delivery policy?", "document"),
            information("Which electric cars are available?", "dealer-vehicle")
        )).getIntentResult();

        @SuppressWarnings("unchecked")
        List<RAGResponse.RAGDocument> documents = (List<RAGResponse.RAGDocument>) result.getData().get("documents");
        assertThat(documents).extracting(RAGResponse.RAGDocument::getId)
            .containsExactly("policy-1", "vehicle-1");
        verify(ragProvider, times(2)).performRag(any(RAGRequest.class));
        verify(aiCoreService, times(1)).generateTextResponse(anyString(), eq(LlmPurpose.GENERATION));
    }

    @Test
    void preservesAdvancedRetrievalForCompoundEvidenceWithoutGeneratingChildAnswers() {
        AdvancedRAGProvider advancedRagProvider = mock(AdvancedRAGProvider.class);
        when(advancedRagProvider.performAdvancedRAG(any(AdvancedRAGRequest.class))).thenAnswer(invocation -> {
            AdvancedRAGRequest request = invocation.getArgument(0);
            String vectorSpace = request.getEntityType();
            return AdvancedRAGResponse.builder()
                .success(true)
                .documents(List.of(AdvancedRAGResponse.RAGDocument.builder()
                    .id(vectorSpace + "-1")
                    .type(vectorSpace)
                    .source(vectorSpace + "-source")
                    .content(vectorSpace + " advanced evidence")
                    .metadata(Map.of("vectorSpace", vectorSpace, "sourceId", vectorSpace + "-source"))
                    .build()))
                .build();
        });

        RAGProvider basicRagProvider = mock(RAGProvider.class);
        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateTextResponse(anyString(), eq(LlmPurpose.GENERATION)))
            .thenReturn(AIGenerationResponse.builder().content("Combined advanced evidence.").build());

        Intent inventory = information("Find electric inventory", "dealer-vehicle");
        inventory.setNeedsAdvancedRAG(true);
        Intent policy = information("Find delivery policy", "document");
        policy.setNeedsAdvancedRAG(true);
        IntentHandlingStep step = newStep(
            mock(AIActionRegistry.class),
            basicRagProvider,
            aiCoreService,
            advancedRagProvider
        );

        OrchestrationResult result = step.process(context(
            "Find electric inventory and the delivery policy",
            inventory,
            policy
        )).getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        @SuppressWarnings("unchecked")
        List<RAGResponse.RAGDocument> documents = (List<RAGResponse.RAGDocument>) result.getData().get("documents");
        assertThat(documents).extracting(RAGResponse.RAGDocument::getId)
            .containsExactly("dealer-vehicle-1", "document-1");
        verify(advancedRagProvider, times(2)).performAdvancedRAG(any(AdvancedRAGRequest.class));
        verify(basicRagProvider, never()).performRag(any(RAGRequest.class));
        verify(aiCoreService, times(1)).generateTextResponse(anyString(), eq(LlmPurpose.GENERATION));
    }

    @Test
    void writeIntentKeepsConfirmationSemanticsAndIsNeverCollectedAsReadEvidence() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionHandler handler = mock(AIActionHandler.class);
        AIActionMetaData metadata = AIActionMetaData.builder()
            .name("request_delivery")
            .accessMode(ActionAccessMode.WRITE_ONLY)
            .confirmationRequired(true)
            .build();
        when(registry.findMetadata("request_delivery")).thenReturn(Optional.of(metadata));
        when(registry.findHandler("request_delivery")).thenReturn(Optional.of(handler));
        when(handler.validateActionAllowed(any())).thenReturn(true);
        when(handler.requiresConfirmation()).thenReturn(true);
        when(handler.getConfirmationMessage(any(), any())).thenReturn("Confirm delivery request?");

        RAGProvider ragProvider = mock(RAGProvider.class);
        RAGResponse policyResponse = response(document(
            "policy-1",
            "document",
            "dealership-policy",
            "Delivery costs GBP 49."
        ));
        when(ragProvider.performRag(any(RAGRequest.class))).thenReturn(policyResponse);
        when(ragProvider.performRAGQuery(any(RAGRequest.class))).thenReturn(policyResponse);

        IntentHandlingStep step = newStep(registry, ragProvider, mock(AICoreService.class));
        Intent write = Intent.builder().type(IntentType.ACTION).action("request_delivery").build();
        OrchestrationContext orchestrationContext = OrchestrationContext.builder()
            .userId("user-1")
            .conversationId("chat-write-confirmation")
            .build();
        PipelineContext pipelineContext = PipelineContext.from(
                "What is the policy and request delivery?",
                orchestrationContext
            )
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder()
                .intents(List.of(information("What is the delivery policy?", "document"), write))
                .build())
            .build();

        OrchestrationResult result = step.process(pipelineContext).getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.COMPOUND_HANDLED);
        assertThat(result.getChildren()).extracting(OrchestrationResult::getType)
            .containsExactly(OrchestrationResultType.INFORMATION_PROVIDED, OrchestrationResultType.CONFIRMATION_REQUIRED);
        verify(handler, never()).executeAction(any(), any());
    }

    @Test
    void excludesFailedOrGroundingUnusableActionSummariesFromFinalSynthesis() {
        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateTextResponse(anyString(), eq(LlmPurpose.GENERATION)))
            .thenReturn(AIGenerationResponse.builder().content("Only approved policy evidence was available.").build());
        RagResponseGenerationSupport generationSupport = new RagResponseGenerationSupport(
            aiCoreService,
            mock(AIServiceConfig.class),
            promptTemplateResolver(),
            new PromptRenderer()
        );
        Intent inventory = information("Find current inventory", "dealer-vehicle");
        Intent policy = information("Find delivery policy", "document");
        OrchestrationResult unusableAction = OrchestrationResult.builder()
            .type(OrchestrationResultType.INFORMATION_PROVIDED)
            .success(true)
            .data(Map.of(
                "readActionResolution", Map.of(
                    "executedActions", List.of(Map.of(
                        "action", "inventory_lookup",
                        "actionExecutionId", "read-action-rejected",
                        "success", false,
                        "groundingUsable", false,
                        "evidenceSummary", "Do not expose this failed action payload."
                    ))
                ),
                "documents", List.of()
            ))
            .build();
        OrchestrationResult policyEvidence = OrchestrationResult.builder()
            .type(OrchestrationResultType.INFORMATION_PROVIDED)
            .success(true)
            .data(Map.of("documents", List.of(document(
                "policy-1",
                "document",
                "dealership-policy",
                "Delivery costs GBP 49."
            ))))
            .build();

        OrchestrationResult result = CompoundReadEvidenceSupport.synthesize(
            List.of(inventory, policy),
            List.of(unusableAction, policyEvidence),
            List.of(),
            context("Find inventory and delivery policy", inventory, policy),
            generationSupport,
            true
        );

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiCoreService).generateTextResponse(promptCaptor.capture(), eq(LlmPurpose.GENERATION));
        assertThat(promptCaptor.getValue())
            .contains("Delivery costs GBP 49", "No sufficient grounded evidence was returned")
            .doesNotContain("Do not expose this failed action payload");
        @SuppressWarnings("unchecked")
        Map<String, Object> diagnostics = (Map<String, Object>) result.getData().get("compoundEvidence");
        assertThat(diagnostics).containsEntry("emptyObligationCount", 1);
        assertThat((List<?>) diagnostics.get("actionExecutionIds")).isEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> readActionResolution =
            (Map<String, Object>) result.getData().get("readActionResolution");
        assertThat(readActionResolution)
            .containsEntry("compound", true)
            .containsEntry("executedActionsCount", 1)
            .containsEntry("groundingUsableActionCount", 0L)
            .containsEntry("insufficientActionEvidenceCount", 1L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> executedActions =
            (List<Map<String, Object>>) readActionResolution.get("executedActions");
        assertThat(executedActions).hasSize(1);
        assertThat(executedActions.getFirst())
            .containsEntry("actionExecutionId", "read-action-rejected")
            .containsEntry("intentIndex", 0);
        assertThat(result.getMetadata()).containsEntry("readActionResolution", readActionResolution);
    }

    @Test
    void fairlyPacksBoundedCompoundDocumentsAndReportsOnlyDocumentsActuallyUsed() {
        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateTextResponse(anyString(), eq(LlmPurpose.GENERATION)))
            .thenReturn(AIGenerationResponse.builder()
                .content("Delivery costs GBP 49 and matching inventory is available.")
                .build());
        RagResponseGenerationSupport generationSupport = new RagResponseGenerationSupport(
            aiCoreService,
            mock(AIServiceConfig.class),
            promptTemplateResolver(),
            new PromptRenderer()
        );
        Intent policy = information("delivery policy charge and distance", "document");
        Intent inventory = information("electric inventory under GBP 40000", "dealer-vehicle");

        String longIrrelevantPolicy = "Reservation information without delivery terms. ".repeat(80);
        String relevantPolicy = "General operations. ".repeat(30)
            + "\n\nStandard local delivery is available within 25 miles for GBP 49. "
            + "Cleared funds and insurance are required before handover.\n\n"
            + "Aftercare information. ".repeat(30);
        OrchestrationResult policyEvidence = OrchestrationResult.builder()
            .type(OrchestrationResultType.INFORMATION_PROVIDED)
            .success(true)
            .data(Map.of(
                "documents", List.of(
                    document("policy-reservation", "document", "policies", longIrrelevantPolicy),
                    document("policy-delivery", "document", "policies", relevantPolicy),
                    document("policy-warranty", "document", "policies", "Warranty information. ".repeat(80))
                ),
                "readActionResolution", Map.of(
                    "executedActions", List.of(Map.of(
                        "action", "unrelated_read",
                        "success", true,
                        "groundingUsable", true,
                        "evidenceSummary", "Unrelated live evidence. ".repeat(40)
                    ))
                )
            ))
            .build();
        OrchestrationResult inventoryEvidence = OrchestrationResult.builder()
            .type(OrchestrationResultType.INFORMATION_PROVIDED)
            .success(true)
            .data(Map.of("documents", List.of(
                document("vehicle-1", "dealer-vehicle", "inventory", "Aster E1 costs GBP 31950."),
                document("vehicle-2", "dealer-vehicle", "inventory", "Morrow C2 costs GBP 22750."),
                document("vehicle-3", "dealer-vehicle", "inventory", "Aster E2 costs GBP 39250.")
            )))
            .build();

        OrchestrationResult result = CompoundReadEvidenceSupport.synthesize(
            List.of(policy, inventory),
            List.of(policyEvidence, inventoryEvidence),
            List.of(),
            context("Explain delivery and list electric inventory", policy, inventory),
            generationSupport,
            true
        );

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(aiCoreService).generateTextResponse(prompt.capture(), eq(LlmPurpose.GENERATION));
        assertThat(prompt.getValue())
            .contains("Standard local delivery is available within 25 miles for GBP 49")
            .contains("Aster E1 costs GBP 31950");

        @SuppressWarnings("unchecked")
        Map<String, Object> diagnostics = (Map<String, Object>) result.getData().get("compoundEvidence");
        assertThat(diagnostics)
            .containsEntry("returnedDocumentCount", 6)
            .containsEntry("usedDocumentCount", 4);
        assertThat(((Number) diagnostics.get("generationContextChars")).intValue()).isLessThanOrEqualTo(3_000);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> obligations = (List<Map<String, Object>>) diagnostics.get("obligations");
        assertThat(obligations).extracting(item -> item.get("documentsUsed"))
            .containsExactly(2L, 2L);
        assertThat(result.getData().get("ragResponse")).isInstanceOf(RAGResponse.class);
        assertThat(((RAGResponse) result.getData().get("ragResponse")).getUsedDocuments()).isEqualTo(4);
    }

    @Test
    void ranksAllAuthorizedDocumentsBeforeApplyingGlobalGenerationDocumentBudget() {
        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateTextResponse(anyString(), eq(LlmPurpose.GENERATION)))
            .thenReturn(AIGenerationResponse.builder().content("Grounded compound answer.").build());
        RagResponseGenerationSupport generationSupport = new RagResponseGenerationSupport(
            aiCoreService,
            mock(AIServiceConfig.class),
            promptTemplateResolver(),
            new PromptRenderer()
        );
        Intent policy = information("delivery charge distance handover", "document");
        Intent inventory = information("available electric inventory", "dealer-vehicle");
        List<RAGResponse.RAGDocument> policyDocuments = new java.util.ArrayList<>();
        for (int index = 0; index < 5; index++) {
            policyDocuments.add(document(
                "unrelated-" + index,
                "document",
                "policies",
                "General warranty and account administration reference " + index
            ));
        }
        policyDocuments.add(document(
            "delivery-policy",
            "document",
            "policies",
            "Local delivery charge is GBP 49 within 25 miles; insurance is required before handover."
        ));
        OrchestrationResult policyEvidence = OrchestrationResult.builder()
            .type(OrchestrationResultType.INFORMATION_PROVIDED)
            .success(true)
            .data(Map.of("documents", policyDocuments))
            .build();
        OrchestrationResult inventoryEvidence = OrchestrationResult.builder()
            .type(OrchestrationResultType.INFORMATION_PROVIDED)
            .success(true)
            .data(Map.of("documents", List.of(document(
                "vehicle-1", "dealer-vehicle", "inventory", "Aster E1 electric inventory is available."
            ))))
            .build();
        OrchestrationPolicy policyWithTightBudget = new OrchestrationPolicy(
            OrchestrationProfile.PRODUCTION_CHAT,
            "executor",
            "search",
            OrchestrationProperties.InformationMode.LLM_DRIVEN,
            OrchestrationPolicy.OrchestrationCapabilities.defaults(),
            new OrchestrationPolicy.RagBudgets(true, 2, null, 10, 2, 1_600, List.of("document", "dealer-vehicle"))
        );
        PipelineContext pipelineContext = context(
            "Which electric inventory is available and what are the delivery terms?",
            policy,
            inventory
        ).toBuilder().orchestrationPolicy(policyWithTightBudget).build();

        OrchestrationResult result = CompoundReadEvidenceSupport.synthesize(
            List.of(policy, inventory),
            List.of(policyEvidence, inventoryEvidence),
            List.of(),
            pipelineContext,
            generationSupport,
            true
        );

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(aiCoreService).generateTextResponse(prompt.capture(), eq(LlmPurpose.GENERATION));
        assertThat(prompt.getValue())
            .contains("Local delivery charge is GBP 49", "Aster E1 electric inventory is available")
            .doesNotContain("General warranty and account administration reference");
        @SuppressWarnings("unchecked")
        Map<String, Object> diagnostics = (Map<String, Object>) result.getData().get("compoundEvidence");
        assertThat(diagnostics)
            .containsEntry("returnedDocumentCount", 7)
            .containsEntry("usedDocumentCount", 2);
    }

    @Test
    void retainsSuccessfulEvidenceWhenAnotherReadChildHasSoftExtractionFailure() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionMetaData metadata = AIActionMetaData.builder()
            .name("misclassified_inventory_lookup")
            .accessMode(ActionAccessMode.READ)
            .groundingEligible(true)
            .build();
        when(registry.findMetadata("misclassified_inventory_lookup")).thenReturn(Optional.of(metadata));
        when(registry.findHandler("misclassified_inventory_lookup")).thenReturn(Optional.empty());

        RAGProvider ragProvider = mock(RAGProvider.class);
        when(ragProvider.performRag(any(RAGRequest.class))).thenReturn(response(document(
            "policy-1",
            "document",
            "dealership-policy",
            "Home delivery is available within 30 miles."
        )));

        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateTextResponse(anyString(), eq(LlmPurpose.GENERATION)))
            .thenReturn(AIGenerationResponse.builder()
                .content("Delivery is available, but the inventory clause could not be completed.")
                .build());

        Intent failedAction = Intent.builder()
            .type(IntentType.ACTION)
            .action("misclassified_inventory_lookup")
            .build();
        Intent policy = information("What is the delivery policy?", "document");
        OrchestrationResult result = newStep(registry, ragProvider, aiCoreService).process(context(
            "Find inventory and explain the delivery policy",
            failedAction,
            policy
        )).getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getChildren()).extracting(OrchestrationResult::getType)
            .containsExactly(OrchestrationResultType.ERROR, OrchestrationResultType.INFORMATION_PROVIDED);
        @SuppressWarnings("unchecked")
        Map<String, Object> diagnostics = (Map<String, Object>) result.getData().get("compoundEvidence");
        assertThat(diagnostics)
            .containsEntry("completedObligationCount", 1)
            .containsEntry("failedObligationCount", 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> obligations = (List<Map<String, Object>>) diagnostics.get("obligations");
        assertThat(obligations.getFirst())
            .containsEntry("evidenceStatus", "FAILED")
            .containsEntry("childErrorCode", "ACTION_NOT_FOUND");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) result.getData().get("actions");
        assertThat(actions).isEmpty();

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiCoreService).generateTextResponse(promptCaptor.capture(), eq(LlmPurpose.GENERATION));
        assertThat(promptCaptor.getValue())
            .contains("This obligation could not be completed", "Home delivery is available")
            .doesNotContain("No action handler registered");
    }

    @Test
    void refusesFinalSynthesisWhenAChildHasHardBoundaryFailure() {
        AICoreService aiCoreService = mock(AICoreService.class);
        RagResponseGenerationSupport generationSupport = new RagResponseGenerationSupport(
            aiCoreService,
            mock(AIServiceConfig.class),
            promptTemplateResolver(),
            new PromptRenderer()
        );
        Intent inventory = information("Find current inventory", "dealer-vehicle");
        Intent policy = information("Find delivery policy", "document");
        OrchestrationResult denied = OrchestrationResult.builder()
            .type(OrchestrationResultType.ERROR)
            .success(false)
            .errorCode("ACCESS_DENIED")
            .message("Access denied.")
            .build();
        OrchestrationResult policyEvidence = OrchestrationResult.builder()
            .type(OrchestrationResultType.INFORMATION_PROVIDED)
            .success(true)
            .data(Map.of("documents", List.of(document(
                "policy-1", "document", "policy", "Delivery costs GBP 49."
            ))))
            .build();

        OrchestrationResult result = CompoundReadEvidenceSupport.synthesize(
            List.of(inventory, policy),
            List.of(denied, policyEvidence),
            List.of(),
            context("Find inventory and delivery policy", inventory, policy),
            generationSupport,
            true
        );

        assertThat(result).isNull();
        verify(aiCoreService, never()).generateTextResponse(anyString(), any());
    }

    @Test
    void reportsBlankFinalSynthesisAsFailureInsteadOfSuccessfulEmptyAnswer() {
        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateTextResponse(anyString(), eq(LlmPurpose.GENERATION)))
            .thenReturn(AIGenerationResponse.builder().content("   ").build());
        RagResponseGenerationSupport generationSupport = new RagResponseGenerationSupport(
            aiCoreService,
            mock(AIServiceConfig.class),
            promptTemplateResolver(),
            new PromptRenderer()
        );
        Intent inventory = information("Find current inventory", "dealer-vehicle");
        Intent policy = information("Find delivery policy", "document");
        OrchestrationResult inventoryEvidence = OrchestrationResult.builder()
            .type(OrchestrationResultType.INFORMATION_PROVIDED)
            .success(true)
            .data(Map.of("documents", List.of(document(
                "vehicle-1", "dealer-vehicle", "inventory", "Aster E1 is available."
            ))))
            .build();
        OrchestrationResult policyEvidence = OrchestrationResult.builder()
            .type(OrchestrationResultType.INFORMATION_PROVIDED)
            .success(true)
            .data(Map.of("documents", List.of(document(
                "policy-1", "document", "policy", "Delivery costs GBP 49."
            ))))
            .build();

        OrchestrationResult result = CompoundReadEvidenceSupport.synthesize(
            List.of(inventory, policy),
            List.of(inventoryEvidence, policyEvidence),
            List.of(),
            context("Find inventory and delivery policy", inventory, policy),
            generationSupport,
            true
        );

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ERROR);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("GENERATION_FAILED");
        assertThat(result.getData()).containsEntry("generationError", "Final synthesis returned no content.");
        @SuppressWarnings("unchecked")
        Map<String, Object> diagnostics = (Map<String, Object>) result.getData().get("compoundEvidence");
        assertThat(diagnostics)
            .containsEntry("finalSynthesisAttempted", true)
            .containsEntry("finalSynthesisPerformed", false);
    }

    private Intent information(String query, String vectorSpace) {
        return Intent.builder()
            .type(IntentType.INFORMATION)
            .intent(query)
            .optimizedQuery(query)
            .vectorSpace(vectorSpace)
            .requiresRetrieval(true)
            .requiresGeneration(true)
            .build();
    }

    private PipelineContext context(String query, Intent... intents) {
        OrchestrationContext orchestrationContext = OrchestrationContext.forUser("user-1");
        return PipelineContext.from(query, orchestrationContext)
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intents)).build())
            .build();
    }

    private IntentHandlingStep newStep(AIActionRegistry registry,
                                       RAGProvider ragProvider,
                                       AICoreService aiCoreService) {
        return newStep(registry, ragProvider, aiCoreService, null);
    }

    private IntentHandlingStep newStep(AIActionRegistry registry,
                                       RAGProvider ragProvider,
                                       AICoreService aiCoreService,
                                       AdvancedRAGProvider advancedRagProvider) {
        VectorSpaceRoutingProperties routingProperties = new VectorSpaceRoutingProperties();
        routingProperties.setClarificationThreshold(0.0d);
        return new IntentHandlingStep(
            registry,
            providerOf(ragProvider),
            aiCoreService,
            mock(AIServiceConfig.class),
            providerOf(advancedRagProvider),
            routingProperties,
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

    private RAGResponse response(RAGResponse.RAGDocument document) {
        return RAGResponse.builder().documents(List.of(document)).success(true).build();
    }

    private RAGResponse.RAGDocument document(String id, String vectorSpace, String source, String content) {
        return RAGResponse.RAGDocument.builder()
            .id(id)
            .type(vectorSpace)
            .source(source)
            .content(content)
            .score(0.9d)
            .metadata(Map.of("vectorSpace", vectorSpace, "sourceId", source))
            .build();
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
