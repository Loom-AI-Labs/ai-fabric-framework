package ai.fabric.intent.orchestration.information;

import ai.fabric.config.OrchestrationProperties;
import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.intent.IntentExtractionJsonSupport;
import ai.fabric.intent.action.AIActionHandler;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionPayload;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import ai.fabric.intent.orchestration.policy.OrchestrationProfile;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplate;
import ai.fabric.prompt.PromptTemplateKey;
import ai.fabric.prompt.PromptTemplateResolver;
import ai.fabric.prompt.ResolvedPromptTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReadActionResolutionServiceTest {

    @Test
    void shouldRespectPlannerSelectedReadActionWithoutApplicationOverride() {
        AICoreService aiCoreService = mock(AICoreService.class);
        AIActionRegistry actionRegistry = mock(AIActionRegistry.class);
        PromptTemplateResolver templateResolver = mock(PromptTemplateResolver.class);

        AIActionMetaData availability = AIActionMetaData.builder()
            .name("check_availability")
            .description("Check product stock by SKU.")
            .category("commerce")
            .accessMode(ActionAccessMode.READ)
            .groundingEligible(true)
            .readActionResolutionEligible(true)
            .requiredParameters(Set.of("sku"))
            .build();
        AIActionMetaData productDetails = AIActionMetaData.builder()
            .name("get_product_details")
            .description("Get full product details by SKU.")
            .category("commerce")
            .accessMode(ActionAccessMode.READ)
            .groundingEligible(true)
            .readActionResolutionEligible(true)
            .requiredParameters(Set.of("sku"))
            .build();

        AIActionHandler availabilityHandler = mock(AIActionHandler.class);
        AIActionHandler detailsHandler = mock(AIActionHandler.class);
        when(availabilityHandler.validateActionAllowed(any(ActionContext.class))).thenReturn(true);
        when(detailsHandler.validateActionAllowed(any(ActionContext.class))).thenReturn(true);
        when(detailsHandler.executeAction(eq(Map.of("sku", "SKU-AAA-100")), any(ActionContext.class)))
            .thenReturn(ActionResult.builder()
                .success(true)
                .message("Product details")
                .data(ActionPayload.object(Map.of(
                    "sku", "SKU-AAA-100",
                    "title", "Alpha Pack",
                    "available", true
                )))
                .build());
        when(detailsHandler.buildPostActionLlmFacts(any(ActionResult.class), any(ActionContext.class))).thenReturn(
            Optional.of(Map.of(
                "sku", "SKU-AAA-100",
                "title", "Alpha Pack",
                "available", true
            ))
        );

        when(actionRegistry.getAllMetadata()).thenReturn(List.of(availability, productDetails));
        when(actionRegistry.findHandler("check_availability")).thenReturn(Optional.of(availabilityHandler));
        when(actionRegistry.findHandler("get_product_details")).thenReturn(Optional.of(detailsHandler));
        when(actionRegistry.findMetadata("check_availability")).thenReturn(Optional.of(availability));
        when(actionRegistry.findMetadata("get_product_details")).thenReturn(Optional.of(productDetails));
        when(templateResolver.resolve("orchestration/read-action-resolution", "system"))
            .thenReturn(resolvedTemplate("system", ""));
        when(templateResolver.resolve("orchestration/read-action-resolution", "user"))
            .thenReturn(resolvedTemplate("user",
                "mode={{mode}}\nquery={{query}}\nintent={{intent_json}}\nactions={{eligible_actions_json}}\nprior={{prior_evidence_json}}\n"
                    + "max={{max_actions_per_iteration}}\ntotal={{max_total_actions}}\nrag={{rag_cooperation_mode}}\n"
                    + "iteration={{iteration}}\niterations={{max_iterations}}"));
        when(aiCoreService.generateContent(any(), eq(LlmPurpose.ORCHESTRATION))).thenReturn(
            AIGenerationResponse.builder()
                .content("""
                    {
                      "decision": "EXECUTE_READ_ACTIONS",
                      "actions": [
                        {"name": "get_product_details", "params": {"sku": "SKU-AAA-100"}, "priority": 1}
                      ],
                      "needsMoreSteps": false
                    }
                    """)
                .build()
        );

        ReadActionResolutionService service = new ReadActionResolutionService(
            aiCoreService,
            actionRegistry,
            new IntentExtractionJsonSupport(new ObjectMapper()),
            templateResolver,
            new PromptRenderer()
        );

        ReadActionResolutionService.ResolutionOutcome outcome = service.resolve(
            Intent.builder()
                .type(IntentType.INFORMATION)
                .intent("Is SKU-AAA-100 available right now?")
                .optimizedQuery("Check availability for SKU-AAA-100.")
                .build(),
            OrchestrationContext.forUser("user-1"),
            PipelineContext.from("Is SKU-AAA-100 available right now?", OrchestrationContext.forUser("user-1"))
                .toBuilder()
                .orchestrationPolicy(readActionPolicy("resolver_assistant", List.of("check_availability", "get_product_details"),
                    OrchestrationProperties.ReadActionResolutionPlanningMode.SINGLE_PASS,
                    OrchestrationProperties.ReadActionResolutionRagCooperationMode.RAG_IF_ACTIONS_INSUFFICIENT))
                .build()
        );

        assertThat(outcome.attempted()).isTrue();
        assertThat(outcome.useRag()).isFalse();
        assertThat(outcome.canAnswerFromActionEvidenceOnly()).isTrue();
        assertThat(outcome.executedActions()).hasSize(1);
        assertThat(outcome.executedActions().getFirst().actionName()).isEqualTo("get_product_details");
        assertThat(outcome.diagnostics()).containsEntry("executedActionsCount", 1);
        assertThat(outcome.diagnostics()).containsEntry("finalDecision", "EXECUTE_READ_ACTIONS");
        verify(detailsHandler).executeAction(eq(Map.of("sku", "SKU-AAA-100")), any(ActionContext.class));
        ArgumentCaptor<ActionContext> factsContext = ArgumentCaptor.forClass(ActionContext.class);
        verify(detailsHandler).buildPostActionLlmFacts(any(ActionResult.class), factsContext.capture());
        assertThat(factsContext.getValue().actionParams()).containsEntry("sku", "SKU-AAA-100");
        verify(availabilityHandler, never()).executeAction(any(), any(ActionContext.class));
    }

    @Test
    void shouldContinueWithRagWhenParallelModeHasUsableReadActionEvidence() {
        AICoreService aiCoreService = mock(AICoreService.class);
        AIActionRegistry actionRegistry = mock(AIActionRegistry.class);
        PromptTemplateResolver templateResolver = mock(
            PromptTemplateResolver.class
        );

        AIActionMetaData profile = AIActionMetaData.builder()
            .name("get_account_profile")
            .description("Read the current account profile.")
            .category("account")
            .accessMode(ActionAccessMode.READ)
            .groundingEligible(true)
            .readActionResolutionEligible(true)
            .build();
        AIActionHandler profileHandler = mock(AIActionHandler.class);
        when(profileHandler.validateActionAllowed(any(ActionContext.class)))
            .thenReturn(true);
        when(profileHandler.executeAction(eq(Map.of()), any(ActionContext.class)))
            .thenReturn(ActionResult.builder()
                .success(true)
                .message("Account profile loaded.")
                .data(ActionPayload.object(Map.of(
                    "subscriptionActive", true,
                    "billingAddressPresent", false
                )))
                .build());
        when(profileHandler.buildPostActionLlmFacts(
            any(ActionResult.class),
            any(ActionContext.class)
        )).thenReturn(Optional.of(Map.of(
            "subscriptionActive", true,
            "billingAddressPresent", false
        )));

        when(actionRegistry.getAllMetadata()).thenReturn(List.of(profile));
        when(actionRegistry.findHandler("get_account_profile"))
            .thenReturn(Optional.of(profileHandler));
        when(actionRegistry.findMetadata("get_account_profile"))
            .thenReturn(Optional.of(profile));
        when(templateResolver.resolve(
            "orchestration/read-action-resolution",
            "system"
        )).thenReturn(resolvedTemplate("system", ""));
        when(templateResolver.resolve(
            "orchestration/read-action-resolution",
            "user"
        )).thenReturn(resolvedTemplate(
            "user",
            "mode={{mode}}\nquery={{query}}\nintent={{intent_json}}\n"
                + "actions={{eligible_actions_json}}\n"
                + "prior={{prior_evidence_json}}\n"
                + "max={{max_actions_per_iteration}}\n"
                + "total={{max_total_actions}}\n"
                + "rag={{rag_cooperation_mode}}\n"
                + "iteration={{iteration}}\niterations={{max_iterations}}"
        ));
        when(aiCoreService.generateContent(
            any(),
            eq(LlmPurpose.ORCHESTRATION)
        )).thenReturn(AIGenerationResponse.builder()
            .content("""
                {
                  "decision": "EXECUTE_READ_ACTIONS",
                  "actions": [
                    {
                      "name": "get_account_profile",
                      "params": {},
                      "priority": 1
                    }
                  ],
                  "needsMoreSteps": false,
                  "suggestedVectorSpaces": ["account-resolution-policy"]
                }
                """)
            .build());

        ReadActionResolutionService service = new ReadActionResolutionService(
            aiCoreService,
            actionRegistry,
            new IntentExtractionJsonSupport(new ObjectMapper()),
            templateResolver,
            new PromptRenderer()
        );
        OrchestrationContext context = OrchestrationContext.builder()
            .userId("user-1")
            .specialistInstructions(
                "Objective: Assess current account readiness.\n"
                    + "Specialist constraints:\n"
                    + "Always read current profile facts before deciding."
            )
            .build();

        ReadActionResolutionService.ResolutionOutcome outcome = service.resolve(
            Intent.builder()
                .type(IntentType.INFORMATION)
                .intent("What prevents this account from placing an order?")
                .optimizedQuery("account ordering requirements")
                .build(),
            context,
            PipelineContext.from(
                "What prevents this account from placing an order?",
                context
            ).toBuilder()
                .orchestrationPolicy(readActionPolicy(
                    "resolver",
                    List.of("get_account_profile"),
                    OrchestrationProperties
                        .ReadActionResolutionPlanningMode
                        .SINGLE_PASS,
                    OrchestrationProperties
                        .ReadActionResolutionRagCooperationMode
                        .PARALLEL_ACTIONS_AND_RAG
                ))
                .build()
        );

        assertThat(outcome.attempted()).isTrue();
        assertThat(outcome.hasGroundingEvidence()).isTrue();
        assertThat(outcome.useRag()).isTrue();
        assertThat(outcome.canAnswerFromActionEvidenceOnly()).isFalse();
        assertThat(outcome.preferredVectorSpaces())
            .containsExactly("account-resolution-policy");
        assertThat(outcome.diagnostics())
            .containsEntry("ragCooperationMode", "PARALLEL_ACTIONS_AND_RAG")
            .containsEntry("useRag", true);
        ArgumentCaptor<AIGenerationRequest> plannerRequest =
            ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(aiCoreService).generateContent(
            plannerRequest.capture(),
            eq(LlmPurpose.ORCHESTRATION)
        );
        assertThat(plannerRequest.getValue().getSystemPrompt())
            .contains("APPLICATION-OWNED SPECIALIST CONTRACT")
            .contains("Objective: Assess current account readiness.")
            .contains("Always read current profile facts before deciding.");
    }

    @Test
    void shouldExecuteMultiplePlannerSelectedReadActionsWithinBudget() {
        AICoreService aiCoreService = mock(AICoreService.class);
        AIActionRegistry actionRegistry = mock(AIActionRegistry.class);
        PromptTemplateResolver templateResolver = mock(PromptTemplateResolver.class);

        AIActionMetaData searchProducts = AIActionMetaData.builder()
            .name("search_products")
            .description("Search live catalog products.")
            .category("catalog")
            .accessMode(ActionAccessMode.READ)
            .groundingEligible(true)
            .readActionResolutionEligible(true)
            .build();
        AIActionMetaData getPolicy = AIActionMetaData.builder()
            .name("get_policy")
            .description("Return a store policy.")
            .category("policy")
            .accessMode(ActionAccessMode.READ)
            .groundingEligible(true)
            .readActionResolutionEligible(true)
            .requiredParameters(Set.of("policyType"))
            .build();

        AIActionHandler searchHandler = mock(AIActionHandler.class);
        AIActionHandler policyHandler = mock(AIActionHandler.class);
        when(searchHandler.validateActionAllowed(any(ActionContext.class))).thenReturn(true);
        when(policyHandler.validateActionAllowed(any(ActionContext.class))).thenReturn(true);
        when(searchHandler.executeAction(eq(Map.of("query", "snowboard")), any(ActionContext.class))).thenReturn(
            ActionResult.builder()
                .success(true)
                .message("Products loaded.")
                .data(ActionPayload.object(Map.of("count", 3)))
                .build()
        );
        when(policyHandler.executeAction(eq(Map.of("policyType", "shipping")), any(ActionContext.class))).thenReturn(
            ActionResult.builder()
                .success(true)
                .message("Shipping policy loaded.")
                .data(ActionPayload.object(Map.of("shipsFrom", "warehouse")))
                .build()
        );
        when(searchHandler.buildPostActionLlmFacts(any(ActionResult.class), any(ActionContext.class)))
            .thenReturn(Optional.of(Map.of("productsFound", 3)));
        when(policyHandler.buildPostActionLlmFacts(any(ActionResult.class), any(ActionContext.class)))
            .thenReturn(Optional.of(Map.of("policyType", "shipping")));

        when(actionRegistry.getAllMetadata()).thenReturn(List.of(searchProducts, getPolicy));
        when(actionRegistry.findHandler("search_products")).thenReturn(Optional.of(searchHandler));
        when(actionRegistry.findHandler("get_policy")).thenReturn(Optional.of(policyHandler));
        when(actionRegistry.findMetadata("search_products")).thenReturn(Optional.of(searchProducts));
        when(actionRegistry.findMetadata("get_policy")).thenReturn(Optional.of(getPolicy));
        when(templateResolver.resolve("orchestration/read-action-resolution", "system"))
            .thenReturn(resolvedTemplate("system", ""));
        when(templateResolver.resolve("orchestration/read-action-resolution", "user"))
            .thenReturn(resolvedTemplate("user",
                "mode={{mode}}\nquery={{query}}\nintent={{intent_json}}\nactions={{eligible_actions_json}}\nprior={{prior_evidence_json}}\n"
                    + "max={{max_actions_per_iteration}}\ntotal={{max_total_actions}}\nrag={{rag_cooperation_mode}}\n"
                    + "iteration={{iteration}}\niterations={{max_iterations}}"));
        when(aiCoreService.generateContent(any(), eq(LlmPurpose.ORCHESTRATION))).thenReturn(
            AIGenerationResponse.builder()
                .content("""
                    {
                      "decision": "EXECUTE_READ_ACTIONS",
                      "actions": [
                        {"name": "search_products", "params": {"query": "snowboard"}, "priority": 1},
                        {"name": "get_policy", "params": {"policyType": "shipping"}, "priority": 2}
                      ],
                      "needsMoreSteps": false
                    }
                    """)
                .build()
        );

        ReadActionResolutionService service = new ReadActionResolutionService(
            aiCoreService,
            actionRegistry,
            new IntentExtractionJsonSupport(new ObjectMapper()),
            templateResolver,
            new PromptRenderer()
        );

        ReadActionResolutionService.ResolutionOutcome outcome = service.resolve(
            Intent.builder()
                .type(IntentType.INFORMATION)
                .intent("Compare snowboards and include shipping context.")
                .optimizedQuery("Compare snowboards and shipping policy")
                .build(),
            OrchestrationContext.forUser("user-1"),
            PipelineContext.from("Compare snowboards and include shipping context.", OrchestrationContext.forUser("user-1"))
                .toBuilder()
                .orchestrationPolicy(readActionPolicy("thinker", List.of("search_products", "get_policy"),
                    OrchestrationProperties.ReadActionResolutionPlanningMode.SINGLE_PASS,
                    OrchestrationProperties.ReadActionResolutionRagCooperationMode.NONE))
                .build()
        );

        assertThat(outcome.attempted()).isTrue();
        assertThat(outcome.executedActions()).hasSize(2);
        assertThat(outcome.executedActions().stream().map(ReadActionResolutionService.ExecutedReadAction::actionName).toList())
            .containsExactly("search_products", "get_policy");
        assertThat(outcome.diagnostics()).containsEntry("executedActionsCount", 2);
        verify(searchHandler).executeAction(eq(Map.of("query", "snowboard")), any(ActionContext.class));
        verify(policyHandler).executeAction(eq(Map.of("policyType", "shipping")), any(ActionContext.class));
    }

    @Test
    void shouldKeepOversizedActionEvidenceAsValidBoundedJson() throws Exception {
        AICoreService aiCoreService = mock(AICoreService.class);
        AIActionRegistry actionRegistry = mock(AIActionRegistry.class);
        PromptTemplateResolver templateResolver = mock(PromptTemplateResolver.class);

        AIActionMetaData searchRecords = AIActionMetaData.builder()
            .name("search_records")
            .description("Search live records.")
            .category("records")
            .accessMode(ActionAccessMode.READ)
            .groundingEligible(true)
            .readActionResolutionEligible(true)
            .build();
        AIActionHandler searchHandler = mock(AIActionHandler.class);
        when(searchHandler.validateActionAllowed(any(ActionContext.class))).thenReturn(true);
        when(searchHandler.executeAction(eq(Map.of("query", "candidates")), any(ActionContext.class))).thenReturn(
            ActionResult.builder()
                .success(true)
                .message("Records loaded.")
                .data(ActionPayload.object(Map.of("count", 12)))
                .build()
        );
        when(searchHandler.buildPostActionLlmFacts(any(ActionResult.class), any(ActionContext.class)))
            .thenReturn(Optional.of(Map.of(
                "count", 12,
                "items", List.of(
                    recordFact("Alpha Candidate", "8.5", true, 50),
                    recordFact("Beta Candidate", "11.5", true, 0),
                    recordFact("Gamma Candidate", "12.5", false, 0),
                    recordFact("Delta Candidate", "9.5", true, 50),
                    recordFact("Epsilon Candidate", "6.5", true, 8),
                    recordFact("Zeta Candidate", "7.5", true, 15)
                )
            )));

        when(actionRegistry.getAllMetadata()).thenReturn(List.of(searchRecords));
        when(actionRegistry.findHandler("search_records")).thenReturn(Optional.of(searchHandler));
        when(actionRegistry.findMetadata("search_records")).thenReturn(Optional.of(searchRecords));
        when(templateResolver.resolve("orchestration/read-action-resolution", "system"))
            .thenReturn(resolvedTemplate("system", ""));
        when(templateResolver.resolve("orchestration/read-action-resolution", "user"))
            .thenReturn(resolvedTemplate("user", "query={{query}}\nactions={{eligible_actions_json}}"));
        when(aiCoreService.generateContent(any(), eq(LlmPurpose.ORCHESTRATION))).thenReturn(
            AIGenerationResponse.builder()
                .content("""
                    {
                      "decision": "EXECUTE_READ_ACTIONS",
                      "actions": [
                        {"name": "search_records", "params": {"query": "candidates"}, "priority": 1}
                      ],
                      "needsMoreSteps": false
                    }
                    """)
                .build()
        );

        ReadActionResolutionService service = new ReadActionResolutionService(
            aiCoreService,
            actionRegistry,
            new IntentExtractionJsonSupport(new ObjectMapper()),
            templateResolver,
            new PromptRenderer()
        );

        ReadActionResolutionService.ResolutionOutcome outcome = service.resolve(
            Intent.builder()
                .type(IntentType.INFORMATION)
                .intent("Which candidates are under 10?")
                .optimizedQuery("candidates")
                .build(),
            OrchestrationContext.forUser("user-1"),
            PipelineContext.from("Which candidates are under 10?", OrchestrationContext.forUser("user-1"))
                .toBuilder()
                .orchestrationPolicy(readActionPolicy("thinker", List.of("search_records"),
                    OrchestrationProperties.ReadActionResolutionPlanningMode.SINGLE_PASS,
                    OrchestrationProperties.ReadActionResolutionRagCooperationMode.NONE))
                .build()
        );

        String evidence = outcome.executedActions().getFirst().evidenceSummary();
        assertThat(evidence).doesNotEndWith("...");
        new ObjectMapper().readTree(evidence);
        assertThat(evidence).contains("Alpha Candidate");
    }

    @Test
    void shouldPreserveConstraintMatchesWhenActionEvidenceIsOversized() throws Exception {
        AICoreService aiCoreService = mock(AICoreService.class);
        AIActionRegistry actionRegistry = mock(AIActionRegistry.class);
        PromptTemplateResolver templateResolver = mock(PromptTemplateResolver.class);

        AIActionMetaData relationshipQuery = AIActionMetaData.builder()
            .name("relationship_query")
            .description("Run a live relationship query.")
            .category("records")
            .accessMode(ActionAccessMode.READ)
            .groundingEligible(true)
            .readActionResolutionEligible(true)
            .build();
        AIActionHandler handler = mock(AIActionHandler.class);
        when(handler.validateActionAllowed(any(ActionContext.class))).thenReturn(true);
        when(handler.executeAction(eq(Map.of("query", "Find ready candidates with score < 10")), any(ActionContext.class))).thenReturn(
            ActionResult.builder()
                .success(true)
                .message("Action executed.")
                .data(ActionPayload.object(Map.of("count", 10)))
                .build()
        );

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("action", "relationship_query");
        facts.put("category", "records");
        facts.put("success", true);
        facts.put("message", "Action executed.");
        facts.put("query", "Find ready candidates with score < 10");
        facts.put("totalResults", 10);
        facts.put("returnedResults", 10);
        facts.put("recordsConstraintMatches", List.of(
            recordFact("Epsilon Candidate", "6.5", true, 100),
            recordFact("Zeta Candidate", "7.5", true, 15),
            recordFact("Alpha Candidate", "8.5", true, 50)
        ));
        facts.put("recordsConstraintMatchCount", 3);
        facts.put("recordsMatchedScoreSummary", Map.of(
            "lowestScoreName", "Epsilon Candidate",
            "lowestScore", 6.5,
            "highestScoreName", "Alpha Candidate",
            "highestScore", 8.5
        ));
        facts.put("recordsScoreSummary", Map.of(
            "lowestScoreName", "Epsilon Candidate",
            "lowestScore", 6.5,
            "highestScoreName", "Beta Candidate",
            "highestScore", 11.5
        ));
        facts.put("records", List.of(
            recordFact("Epsilon Candidate", "6.5", true, 100),
            recordFact("Zeta Candidate", "7.5", true, 15),
            recordFact("Alpha Candidate", "8.5", true, 50),
            recordFact("Beta Candidate", "11.5", true, 0),
            recordFact("Gamma Candidate", "12.5", false, 0),
            recordFact("Delta Candidate", "9.5", true, 50)
        ));
        facts.put("recordsCount", 6);
        when(handler.buildPostActionLlmFacts(any(ActionResult.class), any(ActionContext.class))).thenReturn(Optional.of(facts));

        when(actionRegistry.getAllMetadata()).thenReturn(List.of(relationshipQuery));
        when(actionRegistry.findHandler("relationship_query")).thenReturn(Optional.of(handler));
        when(actionRegistry.findMetadata("relationship_query")).thenReturn(Optional.of(relationshipQuery));
        when(templateResolver.resolve("orchestration/read-action-resolution", "system"))
            .thenReturn(resolvedTemplate("system", ""));
        when(templateResolver.resolve("orchestration/read-action-resolution", "user"))
            .thenReturn(resolvedTemplate("user", "query={{query}}\nactions={{eligible_actions_json}}"));
        when(aiCoreService.generateContent(any(), eq(LlmPurpose.ORCHESTRATION))).thenReturn(
            AIGenerationResponse.builder()
                .content("""
                    {
                      "decision": "EXECUTE_READ_ACTIONS",
                      "actions": [
                        {"name": "relationship_query", "params": {"query": "Find ready candidates with score < 10"}, "priority": 1}
                      ],
                      "needsMoreSteps": false
                    }
                    """)
                .build()
        );

        ReadActionResolutionService service = new ReadActionResolutionService(
            aiCoreService,
            actionRegistry,
            new IntentExtractionJsonSupport(new ObjectMapper()),
            templateResolver,
            new PromptRenderer()
        );

        ReadActionResolutionService.ResolutionOutcome outcome = service.resolve(
            Intent.builder()
                .type(IntentType.INFORMATION)
                .intent("Find ready candidates under 10.")
                .optimizedQuery("Find ready candidates under 10.")
                .build(),
            OrchestrationContext.forUser("user-1"),
            PipelineContext.from("Find ready candidates under 10.", OrchestrationContext.forUser("user-1"))
                .toBuilder()
                .orchestrationPolicy(readActionPolicy("thinker", List.of("relationship_query"),
                    OrchestrationProperties.ReadActionResolutionPlanningMode.SINGLE_PASS,
                    OrchestrationProperties.ReadActionResolutionRagCooperationMode.NONE))
                .build()
        );

        String evidence = outcome.executedActions().getFirst().evidenceSummary();
        new ObjectMapper().readTree(evidence);
        assertThat(evidence)
            .contains("recordsConstraintMatches")
            .contains("Epsilon Candidate")
            .contains("Zeta Candidate")
            .contains("Alpha Candidate")
            .contains("8.5")
            .doesNotContain("\"truncated\":true");
    }

    @Test
    void shouldExecuteOnlyPlannerEligibleReadActionsFromAllowlist() {
        AICoreService aiCoreService = mock(AICoreService.class);
        AIActionRegistry actionRegistry = mock(AIActionRegistry.class);
        PromptTemplateResolver templateResolver = mock(PromptTemplateResolver.class);

        AIActionMetaData eligibleRead = AIActionMetaData.builder()
            .name("get_policy")
            .description("Return the store refund policy.")
            .category("policy")
            .accessMode(ActionAccessMode.READ)
            .groundingEligible(true)
            .readActionResolutionEligible(true)
            .requiredParameters(Set.of("policyType"))
            .build();
        AIActionMetaData ineligibleWrite = AIActionMetaData.builder()
            .name("delete_order")
            .description("Delete an order.")
            .category("orders")
            .accessMode(ActionAccessMode.WRITE_ONLY)
            .groundingEligible(false)
            .readActionResolutionEligible(false)
            .requiredParameters(Set.of("orderId"))
            .build();

        AIActionHandler readHandler = mock(AIActionHandler.class);
        AIActionHandler writeHandler = mock(AIActionHandler.class);
        when(readHandler.validateActionAllowed(any(ActionContext.class))).thenReturn(true);
        when(readHandler.executeAction(eq(Map.of("policyType", "refund")), any(ActionContext.class))).thenReturn(
            ActionResult.builder()
                .success(true)
                .message("Refund policy loaded.")
                .data(ActionPayload.object(Map.of("windowDays", 30)))
                .build()
        );
        when(readHandler.buildPostActionLlmFacts(any(ActionResult.class), any(ActionContext.class))).thenReturn(
            Optional.of(Map.of("policyType", "refund", "windowDays", 30))
        );

        when(actionRegistry.getAllMetadata()).thenReturn(List.of(eligibleRead, ineligibleWrite));
        when(actionRegistry.findHandler("get_policy")).thenReturn(Optional.of(readHandler));
        when(actionRegistry.findHandler("delete_order")).thenReturn(Optional.of(writeHandler));
        when(actionRegistry.findMetadata("get_policy")).thenReturn(Optional.of(eligibleRead));
        when(templateResolver.resolve("orchestration/read-action-resolution", "system"))
            .thenReturn(resolvedTemplate("system", ""));
        when(templateResolver.resolve("orchestration/read-action-resolution", "user"))
            .thenReturn(resolvedTemplate("user",
                "mode={{mode}}\nquery={{query}}\nintent={{intent_json}}\nactions={{eligible_actions_json}}\nprior={{prior_evidence_json}}\n"
                    + "max={{max_actions_per_iteration}}\ntotal={{max_total_actions}}\nrag={{rag_cooperation_mode}}\n"
                    + "iteration={{iteration}}\niterations={{max_iterations}}"));
        when(aiCoreService.generateContent(any(), eq(LlmPurpose.ORCHESTRATION))).thenReturn(
            AIGenerationResponse.builder()
                .content("""
                    {
                      "decision": "EXECUTE_READ_ACTIONS",
                      "actions": [
                        {"name": "delete_order", "params": {"orderId": "ord-1"}, "priority": 1},
                        {"name": "get_policy", "params": {"policyType": "refund"}, "priority": 2}
                      ],
                      "needsMoreSteps": false
                    }
                    """)
                .build()
        );

        ReadActionResolutionService service = new ReadActionResolutionService(
            aiCoreService,
            actionRegistry,
            new IntentExtractionJsonSupport(new ObjectMapper()),
            templateResolver,
            new PromptRenderer()
        );

        ReadActionResolutionService.ResolutionOutcome outcome = service.resolve(
            Intent.builder()
                .type(IntentType.INFORMATION)
                .intent("What is the refund policy?")
                .optimizedQuery("refund policy")
                .build(),
            OrchestrationContext.forUser("user-1"),
            PipelineContext.from("What is the refund policy?", OrchestrationContext.forUser("user-1"))
                .toBuilder()
                .orchestrationPolicy(readActionPolicy("resolver_assistant", List.of("get_policy"),
                    OrchestrationProperties.ReadActionResolutionPlanningMode.SINGLE_PASS,
                    OrchestrationProperties.ReadActionResolutionRagCooperationMode.NONE))
                .build()
        );

        assertThat(outcome.attempted()).isTrue();
        assertThat(outcome.useRag()).isFalse();
        assertThat(outcome.canAnswerFromActionEvidenceOnly()).isTrue();
        assertThat(outcome.executedActions()).hasSize(1);
        assertThat(outcome.executedActions().getFirst().actionName()).isEqualTo("get_policy");
        assertThat(outcome.diagnostics()).containsEntry("executedActionsCount", 1);
        verify(readHandler).executeAction(eq(Map.of("policyType", "refund")), any(ActionContext.class));
        verify(writeHandler, never()).executeAction(any(), any(ActionContext.class));
    }

    @Test
    void shouldRefusePlannerProposalWhenRequiredParamsAreMissingAndFallBackToRag() {
        AICoreService aiCoreService = mock(AICoreService.class);
        AIActionRegistry actionRegistry = mock(AIActionRegistry.class);
        PromptTemplateResolver templateResolver = mock(PromptTemplateResolver.class);

        AIActionMetaData availability = AIActionMetaData.builder()
            .name("check_availability")
            .description("Check product stock.")
            .category("catalog")
            .accessMode(ActionAccessMode.READ)
            .groundingEligible(true)
            .readActionResolutionEligible(true)
            .requiredParameters(Set.of("sku"))
            .build();
        AIActionHandler availabilityHandler = mock(AIActionHandler.class);

        when(actionRegistry.getAllMetadata()).thenReturn(List.of(availability));
        when(actionRegistry.findHandler("check_availability")).thenReturn(Optional.of(availabilityHandler));
        when(actionRegistry.findMetadata("check_availability")).thenReturn(Optional.of(availability));
        when(templateResolver.resolve("orchestration/read-action-resolution", "system"))
            .thenReturn(resolvedTemplate("system", ""));
        when(templateResolver.resolve("orchestration/read-action-resolution", "user"))
            .thenReturn(resolvedTemplate("user",
                "mode={{mode}}\nquery={{query}}\nintent={{intent_json}}\nactions={{eligible_actions_json}}\nprior={{prior_evidence_json}}\n"
                    + "max={{max_actions_per_iteration}}\ntotal={{max_total_actions}}\nrag={{rag_cooperation_mode}}\n"
                    + "iteration={{iteration}}\niterations={{max_iterations}}"));
        when(aiCoreService.generateContent(any(), eq(LlmPurpose.ORCHESTRATION))).thenReturn(
            AIGenerationResponse.builder()
                .content("""
                    {
                      "decision": "EXECUTE_READ_ACTIONS",
                      "actions": [
                        {"name": "check_availability", "params": {}, "priority": 1}
                      ],
                      "needsMoreSteps": false
                    }
                    """)
                .build()
        );

        ReadActionResolutionService service = new ReadActionResolutionService(
            aiCoreService,
            actionRegistry,
            new IntentExtractionJsonSupport(new ObjectMapper()),
            templateResolver,
            new PromptRenderer()
        );

        ReadActionResolutionService.ResolutionOutcome outcome = service.resolve(
            Intent.builder()
                .type(IntentType.INFORMATION)
                .intent("Is SKU-0001 available?")
                .optimizedQuery("SKU-0001 availability")
                .build(),
            OrchestrationContext.forUser("user-1"),
            PipelineContext.from("Is SKU-0001 available?", OrchestrationContext.forUser("user-1"))
                .toBuilder()
                .orchestrationPolicy(readActionPolicy("resolver_assistant", List.of("check_availability"),
                    OrchestrationProperties.ReadActionResolutionPlanningMode.SINGLE_PASS,
                    OrchestrationProperties.ReadActionResolutionRagCooperationMode.RAG_IF_ACTIONS_INSUFFICIENT))
                .build()
        );

        assertThat(outcome.attempted()).isTrue();
        assertThat(outcome.executedActions()).isEmpty();
        assertThat(outcome.useRag()).isTrue();
        verify(availabilityHandler, never()).executeAction(any(), any(ActionContext.class));
    }

    @Test
    void shouldPlanFromEffectiveUserQueryInsteadOfLossyOptimizedQuery() {
        AICoreService aiCoreService = mock(AICoreService.class);
        AIActionRegistry actionRegistry = mock(AIActionRegistry.class);
        PromptTemplateResolver templateResolver = mock(PromptTemplateResolver.class);

        AIActionMetaData getPolicy = AIActionMetaData.builder()
            .name("get_policy")
            .description("Return a store policy.")
            .category("policy")
            .accessMode(ActionAccessMode.READ)
            .groundingEligible(true)
            .readActionResolutionEligible(true)
            .requiredParameters(Set.of("policyType"))
            .build();
        AIActionHandler policyHandler = mock(AIActionHandler.class);

        when(actionRegistry.getAllMetadata()).thenReturn(List.of(getPolicy));
        when(actionRegistry.findHandler("get_policy")).thenReturn(Optional.of(policyHandler));
        when(actionRegistry.findMetadata("get_policy")).thenReturn(Optional.of(getPolicy));
        when(templateResolver.resolve("orchestration/read-action-resolution", "system"))
            .thenReturn(resolvedTemplate("system", ""));
        when(templateResolver.resolve("orchestration/read-action-resolution", "user"))
            .thenReturn(resolvedTemplate("user", "query={{query}}\nintent={{intent_json}}"));
        when(aiCoreService.generateContent(any(), eq(LlmPurpose.ORCHESTRATION))).thenReturn(
            AIGenerationResponse.builder()
                .content("""
                    {
                      "decision": "USE_RAG_ONLY",
                      "actions": [],
                      "needsMoreSteps": false
                    }
                    """)
                .build()
        );

        ReadActionResolutionService service = new ReadActionResolutionService(
            aiCoreService,
            actionRegistry,
            new IntentExtractionJsonSupport(new ObjectMapper()),
            templateResolver,
            new PromptRenderer()
        );

        service.resolve(
            Intent.builder()
                .type(IntentType.INFORMATION)
                .intent("Is Liquid available and what is the return policy?")
                .optimizedQuery("Product availability for productHandle='the-collection-snowboard-liquid'")
                .build(),
            OrchestrationContext.forUser("user-1"),
            PipelineContext.from(
                    "Is The Collection Snowboard: Liquid available, and what is the return policy?",
                    OrchestrationContext.forUser("user-1")
                )
                .toBuilder()
                .orchestrationPolicy(readActionPolicy("thinker", List.of("get_policy"),
                    OrchestrationProperties.ReadActionResolutionPlanningMode.SINGLE_PASS,
                    OrchestrationProperties.ReadActionResolutionRagCooperationMode.RAG_IF_ACTIONS_INSUFFICIENT))
                .build()
        );

        ArgumentCaptor<AIGenerationRequest> promptCaptor = ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(aiCoreService).generateContent(promptCaptor.capture(), eq(LlmPurpose.ORCHESTRATION));
        assertThat(promptCaptor.getValue().getPrompt())
            .contains("query=Is The Collection Snowboard: Liquid available, and what is the return policy?")
            .doesNotContain("query=Product availability for productHandle='the-collection-snowboard-liquid'");
    }

    @Test
    void shouldTreatSuccessfulEmptyReadActionResultAsInsufficientEvidenceAndUseRag() {
        AICoreService aiCoreService = mock(AICoreService.class);
        AIActionRegistry actionRegistry = mock(AIActionRegistry.class);
        PromptTemplateResolver templateResolver = mock(PromptTemplateResolver.class);

        AIActionMetaData getPolicy = AIActionMetaData.builder()
            .name("get_policy")
            .description("Return matching policy documents.")
            .category("policy")
            .accessMode(ActionAccessMode.READ)
            .groundingEligible(true)
            .readActionResolutionEligible(true)
            .requiredParameters(Set.of("query"))
            .build();
        AIActionHandler policyHandler = mock(AIActionHandler.class);
        when(policyHandler.validateActionAllowed(any(ActionContext.class))).thenReturn(true);
        when(policyHandler.executeAction(eq(Map.of("query", "return policy")), any(ActionContext.class)))
            .thenReturn(ActionResult.builder()
                .success(true)
                .message("Action executed.")
                .data(ActionPayload.object(Map.of("count", 0)))
                .build());
        when(policyHandler.buildPostActionLlmFacts(any(ActionResult.class), any(ActionContext.class)))
            .thenReturn(Optional.of(Map.of(
                "action", "get_policy",
                "category", "commerce-companion",
                "success", true,
                "message", "Action executed.",
                "query", "return policy",
                "count", 0
            )));

        when(actionRegistry.getAllMetadata()).thenReturn(List.of(getPolicy));
        when(actionRegistry.findHandler("get_policy")).thenReturn(Optional.of(policyHandler));
        when(actionRegistry.findMetadata("get_policy")).thenReturn(Optional.of(getPolicy));
        when(templateResolver.resolve("orchestration/read-action-resolution", "system"))
            .thenReturn(resolvedTemplate("system", ""));
        when(templateResolver.resolve("orchestration/read-action-resolution", "user"))
            .thenReturn(resolvedTemplate("user",
                "mode={{mode}}\nquery={{query}}\nintent={{intent_json}}\nactions={{eligible_actions_json}}\nprior={{prior_evidence_json}}\n"
                    + "max={{max_actions_per_iteration}}\ntotal={{max_total_actions}}\nrag={{rag_cooperation_mode}}\n"
                    + "iteration={{iteration}}\niterations={{max_iterations}}"));
        when(aiCoreService.generateContent(any(), eq(LlmPurpose.ORCHESTRATION))).thenReturn(
            AIGenerationResponse.builder()
                .content("""
                    {
                      "decision": "EXECUTE_READ_ACTIONS",
                      "actions": [
                        {"name": "get_policy", "params": {"query": "return policy"}, "priority": 1}
                      ],
                      "needsMoreSteps": true,
                      "suggestedVectorSpaces": ["policy"]
                    }
                    """)
                .build(),
            AIGenerationResponse.builder()
                .content("""
                    {
                      "decision": "ANSWER_FROM_CONTEXT",
                      "actions": [],
                      "needsMoreSteps": false,
                      "missingEvidenceReason": "Policy action returned no matching documents.",
                      "suggestedVectorSpaces": ["policy"]
                    }
                    """)
                .build()
        );

        ReadActionResolutionService service = new ReadActionResolutionService(
            aiCoreService,
            actionRegistry,
            new IntentExtractionJsonSupport(new ObjectMapper()),
            templateResolver,
            new PromptRenderer()
        );

        ReadActionResolutionService.ResolutionOutcome outcome = service.resolve(
            Intent.builder()
                .type(IntentType.INFORMATION)
                .intent("What return policy applies?")
                .optimizedQuery("return policy")
                .build(),
            OrchestrationContext.forUser("user-1"),
            PipelineContext.from("What return policy applies?", OrchestrationContext.forUser("user-1"))
                .toBuilder()
                .orchestrationPolicy(readActionPolicy("thinker", List.of("get_policy"),
                    OrchestrationProperties.ReadActionResolutionPlanningMode.ITERATIVE,
                    OrchestrationProperties.ReadActionResolutionRagCooperationMode.RAG_IF_ACTIONS_INSUFFICIENT))
                .build()
        );

        assertThat(outcome.attempted()).isTrue();
        assertThat(outcome.executedActions()).hasSize(1);
        assertThat(outcome.executedActions().getFirst().groundingUsable()).isFalse();
        assertThat(outcome.hasGroundingEvidence()).isFalse();
        assertThat(outcome.canAnswerFromActionEvidenceOnly()).isFalse();
        assertThat(outcome.useRag()).isTrue();
        assertThat(outcome.diagnostics()).containsEntry("groundingUsableActionCount", 0L);
        assertThat(outcome.diagnostics()).containsEntry("insufficientActionEvidenceCount", 1L);
    }

    private ResolvedPromptTemplate resolvedTemplate(String name, String body) {
        return new ResolvedPromptTemplate(
            new PromptTemplate(new PromptTemplateKey("orchestration/read-action-resolution", "v1", name), body),
            List.of("v1")
        );
    }

    private Map<String, Object> recordFact(String name, String scoreValue, boolean ready, int rank) {
        return Map.of(
            "name", name,
            "kind", "candidate",
            "scoreValue", scoreValue,
            "ready", ready,
            "rank", rank,
            "evidencePresent", false
        );
    }

    private OrchestrationPolicy readActionPolicy(String mode,
                                                 List<String> allowedActions,
                                                 OrchestrationProperties.ReadActionResolutionPlanningMode planningMode,
                                                 OrchestrationProperties.ReadActionResolutionRagCooperationMode ragMode) {
        return new OrchestrationPolicy(
            OrchestrationProfile.PRODUCTION_CHAT,
            mode,
            null,
            OrchestrationProperties.InformationMode.DETERMINISTIC_RAG_GENERATE,
            OrchestrationPolicy.OrchestrationCapabilities.defaults(),
            new OrchestrationPolicy.ReadActionResolutionPolicy(
                true,
                planningMode,
                allowedActions,
                true,
                2,
                2,
                3,
                1,
                4_000,
                1_200,
                ragMode,
                true
            ),
            OrchestrationPolicy.RagBudgets.defaults()
        );
    }
}
