package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.OrchestrationProperties;
import ai.fabric.config.VectorSpaceRoutingProperties;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.KnowledgeBaseOverview;
import ai.fabric.intent.KnowledgeBaseOverviewService;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationContextMetadataKeys;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import ai.fabric.intent.orchestration.policy.OrchestrationProfile;
import ai.fabric.intent.orchestration.policy.SemanticRecoveryPolicy;
import ai.fabric.intent.orchestration.targets.ResolvedTarget;
import ai.fabric.intent.orchestration.targets.ResolvedTargetSource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VectorSpaceResolutionStepTest {

    @Test
    void preservesRegisteredModelDecision() {
        VectorSpaceResolutionStep step = step(overview("policies", "faq"));
        Intent intent = informationIntent("policies");

        PipelineContext updated = step.process(context(intent, null, OrchestrationContext.forUser("user")));

        assertThat(updated.isShouldTerminate()).isFalse();
        assertThat(intent.getVectorSpace()).isEqualTo("policies");
        assertThat(String.valueOf(updated.getMetadata().get("vectorSpaceRouting")))
            .contains("LLM_VALIDATED");
    }

    @Test
    void strictPolicyRejectsInvalidModelDecisionWithoutGuessingReplacement() {
        VectorSpaceResolutionStep step = step(overview("policies", "faq"));
        Intent intent = informationIntent("commerce");

        PipelineContext updated = step.process(context(intent, null, OrchestrationContext.forUser("user")));

        assertThat(updated.isShouldTerminate()).isTrue();
        assertThat(updated.getEarlyTerminationResult().getType()).isEqualTo(OrchestrationResultType.ERROR);
        assertThat(updated.getEarlyTerminationResult().getErrorCode()).isEqualTo("INVALID_VECTOR_SPACE_DECISION");
        assertThat(intent.getVectorSpace()).isEqualTo("commerce");
        assertThat(String.valueOf(updated.getEarlyTerminationResult().getMetadata()))
            .contains("LLM_REPAIR_THEN_FAIL")
            .contains("REJECTED_NOT_REGISTERED");
    }

    @Test
    void explicitInvalidDecisionPolicyClarifiesWithoutSelectingAllowlistEntry() {
        VectorSpaceResolutionStep step = step(overview("policy", "product"));
        Intent intent = informationIntent("policies");
        SemanticRecoveryPolicy recovery = new SemanticRecoveryPolicy(
            null,
            null,
            null,
            OrchestrationProperties.InvalidVectorSpaceRecovery.LLM_REPAIR_THEN_CLARIFY,
            null,
            null,
            null
        );
        OrchestrationPolicy policy = policy(
            new OrchestrationPolicy.RagBudgets(true, 2, null, null, null, null, List.of("policy")),
            recovery
        );

        PipelineContext updated = step.process(context(intent, policy, OrchestrationContext.forUser("user")));

        assertThat(updated.isShouldTerminate()).isTrue();
        assertThat(updated.getEarlyTerminationResult().getType())
            .isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);
        assertThat(updated.getEarlyTerminationResult().getData())
            .containsEntry("candidateVectorSpaces", List.of("policy"));
        assertThat(intent.getVectorSpace()).isEqualTo("policies");
    }

    @Test
    void trustedServerHintMayProvideAnExactAllowedScope() {
        VectorSpaceResolutionStep step = step(overview("primary-docs", "reference-docs"));
        Intent intent = informationIntent("wrong-space");
        OrchestrationContext request = OrchestrationContext.builder()
            .userId("user")
            .metadata(Map.of(
                OrchestrationContextMetadataKeys.RAG_PREFERRED_VECTOR_SPACES,
                List.of("primary-docs")
            ))
            .build();

        PipelineContext updated = step.process(context(intent, null, request));

        assertThat(updated.isShouldTerminate()).isFalse();
        assertThat(intent.getVectorSpace()).isEqualTo("primary-docs");
        assertThat(String.valueOf(updated.getMetadata().get("vectorSpaceRouting")))
            .contains("TRUSTED_SERVER_HINT");
    }

    @Test
    void trustedServerHintCannotExceedTheModeAllowlist() {
        VectorSpaceResolutionStep step = step(overview("dealer-vehicle", "document"));
        Intent intent = informationIntent(null);
        OrchestrationPolicy policy = policy(
            new OrchestrationPolicy.RagBudgets(true, 2, null, null, null, null, List.of("document")),
            SemanticRecoveryPolicy.strictDefaults()
        );
        OrchestrationContext request = OrchestrationContext.builder()
            .userId("user")
            .metadata(Map.of(
                OrchestrationContextMetadataKeys.RAG_PREFERRED_VECTOR_SPACES,
                List.of("dealer-vehicle")
            ))
            .build();

        PipelineContext updated = step.process(context(intent, policy, request));

        assertThat(updated.isShouldTerminate()).isTrue();
        assertThat(updated.getEarlyTerminationResult().getType()).isEqualTo(OrchestrationResultType.ERROR);
        assertThat(updated.getEarlyTerminationResult().getErrorCode())
            .isEqualTo("INVALID_TRUSTED_VECTOR_SPACE_HINT");
        assertThat(intent.getVectorSpace()).isNull();
    }

    @Test
    void modelSelectedFanoutAboveTheConfiguredBoundRequiresClarification() {
        VectorSpaceResolutionStep step = step(overview("dealer-vehicle", "document", "policy"));
        Intent intent = informationIntent("dealer-vehicle,document,policy");
        OrchestrationPolicy policy = policy(
            new OrchestrationPolicy.RagBudgets(
                true,
                2,
                null,
                null,
                null,
                null,
                List.of("dealer-vehicle", "document", "policy")
            ),
            SemanticRecoveryPolicy.strictDefaults()
        );

        PipelineContext updated = step.process(context(intent, policy, OrchestrationContext.forUser("user")));

        assertThat(updated.isShouldTerminate()).isTrue();
        assertThat(updated.getEarlyTerminationResult().getType())
            .isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);
        assertThat(updated.getEarlyTerminationResult().getData())
            .containsEntry("reason", "VECTOR_SPACE_FANOUT_LIMIT_EXCEEDED")
            .containsEntry("maxSpaces", 2);
        assertThat(intent.getVectorSpace()).isEqualTo("dealer-vehicle,document,policy");
    }

    @Test
    void rawPublicRequestContextCannotDriveRetrievalScope() {
        VectorSpaceResolutionStep step = step(overview("dealer-vehicle", "document"));
        Intent intent = informationIntent("dealer-vehicle");
        OrchestrationContext request = OrchestrationContext.builder()
            .userId("user")
            .metadata(Map.of("requestContext", Map.of(
                "preferredVectorSpaces", List.of("document"),
                "vectorSpace", "document"
            )))
            .build();

        PipelineContext updated = step.process(context(intent, null, request));

        assertThat(updated.isShouldTerminate()).isFalse();
        assertThat(intent.getVectorSpace()).isEqualTo("dealer-vehicle");
    }

    @Test
    void strictPolicyClarifiesWhenModelOmitsRequiredScope() {
        VectorSpaceResolutionStep step = step(overview("dealer-vehicle", "document"));
        Intent intent = informationIntent(null);

        PipelineContext updated = step.process(context(intent, null, OrchestrationContext.forUser("user")));

        assertThat(updated.isShouldTerminate()).isTrue();
        assertThat(updated.getEarlyTerminationResult().getType())
            .isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);
        assertThat(updated.getEarlyTerminationResult().getData())
            .containsEntry("candidateVectorSpaces", List.of("dealer-vehicle", "document"));
        assertThat(intent.getVectorSpace()).isNull();
    }

    @Test
    void boundedFanOutRunsOnlyWhenNamedRecoveryPolicyAllowsIt() {
        KnowledgeBaseOverviewService overview = overview("product", "review", "policy");
        when(overview.getOverview()).thenReturn(KnowledgeBaseOverview.builder()
            .entityTypes(List.of("product", "review", "policy"))
            .documentsByType(Map.of("review", 50L, "product", 100L, "policy", 10L))
            .build());
        VectorSpaceResolutionStep step = step(overview);
        Intent intent = informationIntent(null);
        SemanticRecoveryPolicy recovery = new SemanticRecoveryPolicy(
            null,
            null,
            OrchestrationProperties.MissingVectorSpaceRecovery.BOUNDED_ALLOWED_FAN_OUT,
            null,
            null,
            null,
            null
        );
        OrchestrationPolicy policy = policy(
            new OrchestrationPolicy.RagBudgets(
                true,
                2,
                null,
                null,
                null,
                null,
                List.of("product", "review", "policy")
            ),
            recovery
        );

        PipelineContext updated = step.process(context(intent, policy, OrchestrationContext.forUser("user")));

        assertThat(updated.isShouldTerminate()).isFalse();
        assertThat(intent.getVectorSpace()).isEqualTo("product,review");
        assertThat(String.valueOf(updated.getMetadata().get("vectorSpaceRouting")))
            .contains("EXPLICIT_BOUNDED_FAN_OUT")
            .contains("BOUNDED_ALLOWED_FAN_OUT");
    }

    @Test
    void namedFanOutRecoveryRequiresAnExplicitPositiveBudget() {
        VectorSpaceResolutionStep step = step(overview("product", "policy"));
        Intent intent = informationIntent(null);
        SemanticRecoveryPolicy recovery = new SemanticRecoveryPolicy(
            null,
            null,
            OrchestrationProperties.MissingVectorSpaceRecovery.BOUNDED_ALLOWED_FAN_OUT,
            null,
            null,
            null,
            null
        );
        OrchestrationPolicy policy = policy(
            new OrchestrationPolicy.RagBudgets(
                true, null, null, null, null, null, List.of("product", "policy")
            ),
            recovery
        );

        PipelineContext updated = step.process(context(intent, policy, OrchestrationContext.forUser("user")));

        assertThat(updated.isShouldTerminate()).isTrue();
        assertThat(updated.getEarlyTerminationResult().getType())
            .isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);
        assertThat(intent.getVectorSpace()).isNull();
        assertThat(String.valueOf(updated.getEarlyTerminationResult().getMetadata()))
            .contains("EXPLICIT_FAN_OUT_BUDGET_REQUIRED");
    }

    @Test
    void trustedTargetScopeIsUsedOnlyAfterModelRequestsTargetResolution() {
        VectorSpaceResolutionStep step = step(overview("dealer-vehicle", "document"));
        Intent intent = informationIntent(null);
        intent.setRequiresTargetResolution(true);
        OrchestrationPolicy policy = policy(
            new OrchestrationPolicy.RagBudgets(true, 2, null, null, null, null,
                List.of("dealer-vehicle", "document")),
            SemanticRecoveryPolicy.strictDefaults()
        );
        PipelineContext input = context(intent, policy, OrchestrationContext.forUser("user"))
            .toBuilder()
            .resolvedTargets(List.of(
                target("one", "dealer-vehicle"),
                target("two", "dealer-vehicle")
            ))
            .build();

        PipelineContext updated = step.process(input);

        assertThat(updated.isShouldTerminate()).isFalse();
        assertThat(intent.getVectorSpace()).isEqualTo("dealer-vehicle");
        assertThat(String.valueOf(updated.getMetadata().get("vectorSpaceRouting")))
            .contains("TARGET_CONTEXT");
    }

    @Test
    void disagreeingTrustedTargetsDoNotCreateASemanticSelection() {
        VectorSpaceResolutionStep step = step(overview("dealer-vehicle", "document"));
        Intent intent = informationIntent(null);
        intent.setRequiresTargetResolution(true);
        OrchestrationPolicy policy = policy(
            new OrchestrationPolicy.RagBudgets(true, 2, null, null, null, null,
                List.of("dealer-vehicle", "document")),
            SemanticRecoveryPolicy.strictDefaults()
        );
        PipelineContext input = context(intent, policy, OrchestrationContext.forUser("user"))
            .toBuilder()
            .resolvedTargets(List.of(
                target("one", "dealer-vehicle"),
                target("two", "document")
            ))
            .build();

        PipelineContext updated = step.process(input);

        assertThat(updated.isShouldTerminate()).isTrue();
        assertThat(updated.getEarlyTerminationResult().getType())
            .isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);
        assertThat(intent.getVectorSpace()).isNull();
    }

    @Test
    void skipsScopeResolutionWhenRetrievalIsNotRequested() {
        VectorSpaceResolutionStep step = step(null);
        Intent intent = informationIntent(null);
        intent.setRequiresRetrieval(false);

        PipelineContext updated = step.process(context(intent, null, OrchestrationContext.forUser("user")));

        assertThat(updated.isShouldTerminate()).isFalse();
        assertThat(intent.getVectorSpace()).isNull();
    }

    private Intent informationIntent(String vectorSpace) {
        return Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("answer_request")
            .userGoal("Answer the request")
            .obligationId("obligation-1")
            .requiresRetrieval(true)
            .requiresGeneration(true)
            .requiresTargetResolution(false)
            .needsAdvancedRAG(false)
            .vectorSpace(vectorSpace)
            .build();
    }

    private PipelineContext context(
        Intent intent,
        OrchestrationPolicy policy,
        OrchestrationContext orchestrationContext
    ) {
        return PipelineContext.from("q", orchestrationContext)
            .toBuilder()
            .orchestrationPolicy(policy)
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();
    }

    private OrchestrationPolicy policy(
        OrchestrationPolicy.RagBudgets ragBudgets,
        SemanticRecoveryPolicy semanticRecoveryPolicy
    ) {
        return new OrchestrationPolicy(
            OrchestrationProfile.PRODUCTION_CHAT,
            "executor",
            "search",
            OrchestrationProperties.InformationMode.LLM_DRIVEN,
            OrchestrationPolicy.OrchestrationCapabilities.defaults(),
            null,
            ragBudgets,
            null,
            semanticRecoveryPolicy
        );
    }

    private ResolvedTarget target(String id, String vectorSpace) {
        return ResolvedTarget.builder()
            .id(id)
            .vectorSpace(vectorSpace)
            .source(ResolvedTargetSource.ACTION_RESULT_ITEMS)
            .build();
    }

    private KnowledgeBaseOverviewService overview(String... spaces) {
        KnowledgeBaseOverviewService service = mock(KnowledgeBaseOverviewService.class);
        when(service.getOverview()).thenReturn(KnowledgeBaseOverview.builder()
            .entityTypes(List.of(spaces))
            .build());
        return service;
    }

    private VectorSpaceResolutionStep step(KnowledgeBaseOverviewService overviewService) {
        return new VectorSpaceResolutionStep(
            new VectorSpaceRoutingProperties(),
            providerOf(overviewService)
        );
    }

    private <T> ObjectProvider<T> providerOf(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
