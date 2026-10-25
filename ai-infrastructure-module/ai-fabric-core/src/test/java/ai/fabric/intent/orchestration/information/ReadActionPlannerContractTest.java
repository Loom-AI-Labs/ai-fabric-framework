package ai.fabric.intent.orchestration.information;

import ai.fabric.config.AIEntityConfigurationLoader;
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
import ai.fabric.intent.action.ActionGroundingSufficiency;
import ai.fabric.intent.action.ActionPayload;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilityProfile;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplate;
import ai.fabric.prompt.PromptTemplateKey;
import ai.fabric.prompt.PromptTemplateResolver;
import ai.fabric.prompt.ResolvedPromptTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReadActionPlannerContractTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AICoreService core = mock(AICoreService.class);
    private final AIActionRegistry registry = mock(AIActionRegistry.class);
    private final AIActionHandler handler = mock(AIActionHandler.class);
    private final AIEntityConfigurationLoader entities = mock(AIEntityConfigurationLoader.class);
    private final PromptTemplateResolver templates = mock(PromptTemplateResolver.class);
    private final ReadActionResolutionService service = new ReadActionResolutionService(
        core, registry, new IntentExtractionJsonSupport(json), templates, new PromptRenderer(), entities
    );

    @BeforeEach
    void configurePlanner() {
        AIActionMetaData action = AIActionMetaData.builder()
            .name("list_records")
            .description("Read current authorized records")
            .accessMode(ActionAccessMode.READ)
            .groundingEligible(true)
            .readActionResolutionEligible(true)
            .build();
        when(registry.getAllMetadata()).thenReturn(List.of(action));
        when(registry.findHandler("list_records")).thenReturn(Optional.of(handler));
        when(registry.findMetadata("list_records")).thenReturn(Optional.of(action));
        when(templates.resolve("orchestration/read-action-resolution", "system"))
            .thenReturn(template("system", "Plan only authorized reads."));
        when(templates.resolve("orchestration/read-action-resolution", "user"))
            .thenReturn(template("user", "{{knowledge_sources_json}}"));
        when(core.generateContent(any(), eq(LlmPurpose.ORCHESTRATION))).thenReturn(
            AIGenerationResponse.builder().content("""
                {"decision":"USE_RAG_ONLY","actions":[],"needsMoreSteps":false,"suggestedVectorSpaces":["policy"]}
                """).build()
        );
    }

    @Test
    void suppliesAuthorizedChoicesWhenExtractionDidNotRequestRetrieval() throws Exception {
        when(entities.getSupportedEntityTypes()).thenReturn(Set.of("foreign-records", "private-payroll"));
        var outcome = resolve(policy(true, true, List.of("records", "policy"), 5, 8_000), null);

        JsonNode catalog = sentCatalog();
        assertThat(json.convertValue(catalog.path("vectorSpaces"), List.class)).containsExactly("policy", "records");
        assertThat(catalog.path("selectionRequired").asBoolean()).isTrue();
        assertThat(catalog.path("missingVectorSpaceRecovery").asText()).isEqualTo("LLM_REPAIR_THEN_CLARIFY");
        assertThat(catalog.has("totalIndexedDocuments")).isFalse();
        assertThat(catalog.has("documentsByType")).isFalse();
        assertThat(outcome.preferredVectorSpaces()).containsExactly("policy");
        assertThat(outcome.useRag()).isTrue();
        verify(entities, never()).getSupportedEntityTypes();
    }

    @Test
    void intersectsCatalogueWithEffectiveExecutionProfile() throws Exception {
        OrchestrationPolicy policy = policy(true, true, List.of("records", "policy"), 5, 8_000);
        var effective = new EffectiveCapabilityProfile(
            "restricted", "resolver", true, Set.of("policy"), Set.of("list_records"),
            Set.of("list_records"), Set.of(), policy.ragBudgets(), policy.readActionResolutionPolicy(), "exact-profile"
        );
        resolve(policy, effective);

        assertThat(json.convertValue(sentCatalog().path("vectorSpaces"), List.class)).containsExactly("policy");
    }

    @Test
    void exposesNoSourcesWhenRetrievalIsDisabled() throws Exception {
        resolve(policy(false, true, List.of("policy"), 5, 8_000), null);

        assertThat(sentCatalog().path("retrievalEnabled").asBoolean()).isFalse();
        assertThat(sentCatalog().path("vectorSpaces").isEmpty()).isTrue();
        verify(entities, never()).getSupportedEntityTypes();
    }

    @Test
    void missingRequiredAllowlistDoesNotBecomeRegisteredSourceFanout() throws Exception {
        when(entities.getSupportedEntityTypes()).thenReturn(Set.of("private-payroll", "records"));
        resolve(policy(true, true, List.of(), 5, 8_000), null);

        assertThat(sentCatalog().path("vectorSpaces").isEmpty()).isTrue();
        verify(entities, never()).getSupportedEntityTypes();
    }

    @Test
    void retrievalDisabledByEffectiveProfileExposesNoSources() throws Exception {
        OrchestrationPolicy policy = policy(true, true, List.of("records", "policy"), 5, 8_000);
        var effective = new EffectiveCapabilityProfile(
            "restricted", "resolver", false, Set.of(), Set.of("list_records"),
            Set.of("list_records"), Set.of(), policy.ragBudgets(), policy.readActionResolutionPolicy(), "no-retrieval"
        );
        resolve(policy, effective);

        JsonNode catalog = sentCatalog();
        assertThat(catalog.path("retrievalEnabled").asBoolean()).isFalse();
        assertThat(catalog.path("vectorSpaces").isEmpty()).isTrue();
    }

    @Test
    void unrestrictedModeUsesRegisteredNamesWithoutProviderStatistics() throws Exception {
        when(entities.getSupportedEntityTypes()).thenReturn(Set.of(" RECORDS ", "policy"));
        resolve(policy(true, false, List.of(), 5, 8_000), null);

        assertThat(json.convertValue(sentCatalog().path("vectorSpaces"), List.class)).containsExactly("policy", "records");
    }

    @Test
    void catalogueDoesNotInventAMissingModelSourceSelection() {
        when(core.generateContent(any(), eq(LlmPurpose.ORCHESTRATION))).thenReturn(
            AIGenerationResponse.builder()
                .content("{\"decision\":\"USE_RAG_ONLY\",\"actions\":[],\"needsMoreSteps\":false}").build()
        );
        var outcome = resolve(policy(true, true, List.of("records", "policy"), 5, 8_000), null);

        assertThat(outcome.preferredVectorSpaces()).isEmpty();
        assertThat(outcome.useRag()).isTrue();
        verify(handler, never()).executeAction(any(), any());
    }

    @Test
    void configuredItemLimitRetainsSixStructuredEvidenceRecords() throws Exception {
        prepareReadEvidence();
        var outcome = resolve(policy(true, true, List.of("records"), 6, 8_000), null);
        JsonNode evidence = json.readTree(outcome.executedActions().getFirst().evidenceSummary());

        assertThat(evidence.path("items").size()).isEqualTo(6);
        assertThat(evidence.path("items").get(5).path("id").asText()).isEqualTo("record-6");
        assertThat(evidence.path("items").get(5).has("_truncatedItems")).isFalse();
        assertThat(outcome.diagnostics()).containsEntry("maxActionEvidenceItems", 6);
    }

    @Test
    void defaultFiveItemBudgetKeepsExplicitTruncation() throws Exception {
        prepareReadEvidence();
        var outcome = resolve(policy(true, true, List.of("records"), 5, 8_000), null);
        JsonNode evidence = json.readTree(outcome.executedActions().getFirst().evidenceSummary());

        assertThat(evidence.path("items").size()).isEqualTo(6);
        assertThat(evidence.path("items").get(4).path("id").asText()).isEqualTo("record-5");
        assertThat(evidence.path("items").get(5).path("_truncatedItems").asInt()).isEqualTo(1);
    }

    @Test
    void itemBudgetAlsoAppliesWithoutCustomFactsProjection() throws Exception {
        prepareReadEvidence();
        when(handler.buildPostActionLlmFacts(any(), any())).thenReturn(Optional.empty());
        var outcome = resolve(policy(true, true, List.of("records"), 6, 8_000), null);
        JsonNode evidence = json.readTree(outcome.executedActions().getFirst().evidenceSummary());

        assertThat(evidence.path("data").path("_items").size()).isEqualTo(6);
        assertThat(evidence.path("data").path("_items").get(5).has("_truncatedItems")).isFalse();
    }

    @Test
    void fallbackProjectionKeepsOriginalOmittedCountAndNumericTypes() throws Exception {
        prepareReadEvidence();
        var records = IntStream.rangeClosed(1, 20)
            .mapToObj(index -> Map.of("id", "record-" + index, "amount", index)).toList();
        when(handler.executeAction(eq(Map.of()), any(ActionContext.class)))
            .thenReturn(ActionResult.builder().success(true).data(ActionPayload.list(records)).build());
        when(handler.buildPostActionLlmFacts(any(), any())).thenReturn(Optional.empty());
        var outcome = resolve(policy(true, true, List.of("records"), 6, 8_000), null);
        JsonNode evidence = json.readTree(outcome.executedActions().getFirst().evidenceSummary());

        JsonNode items = evidence.path("data").path("_items");
        assertThat(items.size()).isEqualTo(7);
        assertThat(items.get(6).path("_truncatedItems").asInt()).isEqualTo(14);
        assertThat(items.get(5).path("amount").isIntegralNumber()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 10, 18, 40})
    void characterCompactionCannotClaimGroundingWithoutRetainedFacts(int charLimit) throws Exception {
        prepareReadEvidence();
        var result = ActionResult.builder().success(true)
            .groundingSufficiency(ActionGroundingSufficiency.SUFFICIENT)
            .data(ActionPayload.list(List.of(Map.of("fact", "Approved " + "x".repeat(200))))).build();
        when(handler.executeAction(eq(Map.of()), any(ActionContext.class))).thenReturn(result);
        when(handler.buildPostActionLlmFacts(any(), any()))
            .thenReturn(Optional.of(Map.of("items", List.of(Map.of("fact", "Approved " + "x".repeat(200))))));
        var outcome = resolve(policy(true, true, List.of("records"), 6, charLimit), null);
        var action = outcome.executedActions().getFirst();

        if (action.evidenceSummary() != null) {
            assertThat(action.evidenceSummary()).hasSizeLessThanOrEqualTo(charLimit);
            assertThat(json.readTree(action.evidenceSummary())).isNotNull();
        }
        assertThat(action.groundingUsable()).isFalse();
        assertThat(outcome.hasGroundingEvidence()).isFalse();
        assertThat(outcome.useRag()).isTrue();
    }

    @Test
    void permittedNonGroundingReadNeedsNoVectorSpaceOrRetrieval() {
        prepareReadEvidence();
        var action = AIActionMetaData.builder().name("list_records").accessMode(ActionAccessMode.READ)
            .groundingEligible(false).readActionResolutionEligible(true).build();
        when(registry.getAllMetadata()).thenReturn(List.of(action));
        when(registry.findMetadata("list_records")).thenReturn(Optional.of(action));
        OrchestrationPolicy policy = policy(false, false, List.of(), 6, 8_000);
        var read = policy.readActionResolutionPolicy();
        policy = new OrchestrationPolicy(policy.profile(), policy.mode(), policy.position(), policy.informationMode(),
            policy.capabilities(), new OrchestrationPolicy.ReadActionResolutionPolicy(true, read.planningMode(),
                read.allowedReadActions(), read.requireAllowlist(), read.maxIterations(), read.maxActionsPerIteration(),
                read.maxTotalActions(), read.maxParallelActions(), read.maxPlannerContextChars(),
                read.maxActionEvidenceCharsPerAction(), read.maxActionEvidenceItems(), read.ragCooperationMode(), false),
            policy.ragBudgets());

        var outcome = resolve(policy, null);

        assertThat(outcome.executedActions()).hasSize(1);
        assertThat(outcome.executedActions().getFirst().actionResult().isSuccess()).isTrue();
        assertThat(outcome.preferredVectorSpaces()).isEmpty();
        assertThat(outcome.useRag()).isFalse();
    }

    @Test
    void largerItemBudgetCannotOverrideCharacterBudget() throws Exception {
        prepareReadEvidence();
        var outcome = resolve(policy(true, true, List.of("records"), 100, 180), null);
        String summary = outcome.executedActions().getFirst().evidenceSummary();

        assertThat(summary).hasSizeLessThanOrEqualTo(180);
        assertThat(json.readTree(summary)).isNotNull();
        assertThat(summary).containsAnyOf("_omittedItems", "truncated");
    }

    @Test
    void aggregateContextBudgetCannotLoseFactsAndStillClaimAnActionOnlyAnswer() {
        prepareReadEvidence();
        OrchestrationPolicy policy = policy(true, true, List.of("records"), 6, 8_000);
        var read = policy.readActionResolutionPolicy();
        policy = new OrchestrationPolicy(policy.profile(), policy.mode(), policy.position(), policy.informationMode(),
            policy.capabilities(), new OrchestrationPolicy.ReadActionResolutionPolicy(true, read.planningMode(),
                read.allowedReadActions(), read.requireAllowlist(), read.maxIterations(), read.maxActionsPerIteration(),
                read.maxTotalActions(), read.maxParallelActions(), 40,
                read.maxActionEvidenceCharsPerAction(), read.maxActionEvidenceItems(), read.ragCooperationMode(), true),
            policy.ragBudgets());

        var outcome = resolve(policy, null);

        assertThat(outcome.executedActions().getFirst().groundingUsable()).isTrue();
        assertThat(outcome.evidenceContext()).hasSizeLessThanOrEqualTo(40);
        assertThat(outcome.hasGroundingEvidence()).isFalse();
        assertThat(outcome.canAnswerFromActionEvidenceOnly()).isFalse();
        assertThat(outcome.useRag()).isTrue();
        assertThat(outcome.diagnostics()).containsEntry("contextOmittedActionsCount", 1)
            .containsEntry("contextGroundingUsable", false);
    }

    private void prepareReadEvidence() {
        var records = IntStream.rangeClosed(1, 6)
            .mapToObj(index -> Map.of("id", "record-" + index, "label", "Approved record " + index)).toList();
        ActionResult result = ActionResult.builder().success(true).data(ActionPayload.list(records)).build();
        when(handler.validateActionAllowed(any(ActionContext.class))).thenReturn(true);
        when(handler.executeAction(eq(Map.of()), any(ActionContext.class))).thenReturn(result);
        when(handler.buildPostActionLlmFacts(eq(result), any(ActionContext.class)))
            .thenReturn(Optional.of(Map.of("items", records, "itemsCount", 6)));
        when(core.generateContent(any(), eq(LlmPurpose.ORCHESTRATION))).thenReturn(
            AIGenerationResponse.builder().content("""
                {"decision":"EXECUTE_READ_ACTIONS","actions":[{"name":"list_records","params":{}}],"needsMoreSteps":false}
                """).build()
        );
    }

    private ReadActionResolutionService.ResolutionOutcome resolve(OrchestrationPolicy policy,
                                                                 EffectiveCapabilityProfile effective) {
        OrchestrationContext context = OrchestrationContext.forUser("user-1");
        context.setMetadata(Map.of("preferredVectorSpaces", List.of("private-payroll")));
        return service.resolve(
            Intent.builder().type(IntentType.INFORMATION).intent("Read approved records and their policy")
                .requiresRetrieval(false).requiresGeneration(true).build(),
            context,
            PipelineContext.from("Read approved records and their policy", context).toBuilder()
                .orchestrationPolicy(policy).effectiveCapabilityProfile(effective).build()
        );
    }

    private JsonNode sentCatalog() throws Exception {
        ArgumentCaptor<AIGenerationRequest> request = ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(core).generateContent(request.capture(), eq(LlmPurpose.ORCHESTRATION));
        return json.readTree(request.getValue().getPrompt());
    }

    private OrchestrationPolicy policy(boolean retrievalEnabled, boolean requireAllowlist,
                                        List<String> spaces, int itemLimit, int charLimit) {
        return new OrchestrationPolicy(null, "resolver", null, null,
            new OrchestrationPolicy.OrchestrationCapabilities(true, retrievalEnabled, false, false, false,
                false, true, requireAllowlist, true, true, false, false, false),
            new OrchestrationPolicy.ReadActionResolutionPolicy(true,
                OrchestrationProperties.ReadActionResolutionPlanningMode.SINGLE_PASS, List.of("list_records"),
                true, 1, 1, 1, 1, 12_000, charLimit, itemLimit,
                OrchestrationProperties.ReadActionResolutionRagCooperationMode.RAG_IF_ACTIONS_INSUFFICIENT, true),
            new OrchestrationPolicy.RagBudgets(false, 2, 3, 8, 8, 8_000, spaces));
    }

    private ResolvedPromptTemplate template(String name, String body) {
        return new ResolvedPromptTemplate(
            new PromptTemplate(new PromptTemplateKey("orchestration/read-action-resolution", "v1", name), body),
            List.of("v1"));
    }
}
