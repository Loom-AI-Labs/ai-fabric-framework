package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.OrchestrationProperties;
import ai.fabric.dto.Intent;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.information.ReadActionResolutionService;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import ai.fabric.intent.orchestration.policy.OrchestrationProfile;
import ai.fabric.intent.orchestration.targets.ResolvedTarget;
import ai.fabric.intent.orchestration.targets.ResolvedTargetSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReadActionResolutionSupportTest {

    @Test
    void shouldEnforceReadActionResolutionPolicy() {
        AIActionMetaData eligible = readMeta(true, true);
        OrchestrationPolicy policy = policy(
            new OrchestrationPolicy.ReadActionResolutionPolicy(
                true,
                OrchestrationProperties.ReadActionResolutionPlanningMode.SINGLE_PASS,
                List.of("check_status"),
                true,
                1,
                2,
                2,
                1,
                4_000,
                2_400,
                OrchestrationProperties.ReadActionResolutionRagCooperationMode.RAG_IF_ACTIONS_INSUFFICIENT,
                true
            )
        );

        assertThat(ReadActionResolutionSupport.isActionExecutionAllowedByPolicy("CHECK_STATUS", eligible, policy)).isTrue();
        assertThat(ReadActionResolutionSupport.isActionExecutionAllowedByPolicy("other_action", eligible, policy)).isFalse();
        assertThat(ReadActionResolutionSupport.isActionExecutionAllowedByPolicy("check_status", readMeta(false, true), policy)).isFalse();
        assertThat(ReadActionResolutionSupport.isActionExecutionAllowedByPolicy("check_status", readMeta(true, false), policy)).isFalse();
        assertThat(ReadActionResolutionSupport.isActionExecutionAllowedByPolicy(
            "check_status",
            AIActionMetaData.builder()
                .accessMode(ActionAccessMode.WRITE_ONLY)
                .readActionResolutionEligible(true)
                .groundingEligible(true)
                .build(),
            policy
        )).isFalse();
    }

    @Test
    void shouldResolveThroughServiceAndCopyDiagnosticsIntoMetadata() {
        ReadActionResolutionService service = mock(ReadActionResolutionService.class);
        ReadActionResolutionService.ResolutionOutcome outcome =
            ReadActionResolutionService.ResolutionOutcome.continueWithRag(
                "READ ACTION EVIDENCE\n- live fact",
                List.of("policy"),
                List.of(),
                Map.of("attempted", true, "executedActionsCount", 1)
            );
        when(service.resolve(any(), any(), any())).thenReturn(outcome);

        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        ReadActionResolutionService.ResolutionOutcome resolved = ReadActionResolutionSupport.resolve(
            providerOf(service),
            Intent.builder().build(),
            OrchestrationContext.forUser("user-1"),
            PipelineContext.from("query", OrchestrationContext.forUser("user-1")),
            metadata
        );

        assertThat(resolved).isSameAs(outcome);
        assertThat(metadata).containsKey(ReadActionResolutionSupport.METADATA_KEY);
        Map<?, ?> diagnostics = (Map<?, ?>) metadata.get(ReadActionResolutionSupport.METADATA_KEY);
        assertThat(diagnostics.get("executedActionsCount")).isEqualTo(1);
    }

    @Test
    void shouldFailClosedWhenResolutionServiceThrows() {
        ReadActionResolutionService service = mock(ReadActionResolutionService.class);
        when(service.resolve(any(), any(), any())).thenThrow(new IllegalStateException("planner unavailable"));

        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        ReadActionResolutionService.ResolutionOutcome resolved = ReadActionResolutionSupport.resolve(
            providerOf(service),
            Intent.builder().build(),
            OrchestrationContext.forUser("user-1"),
            PipelineContext.from("query", OrchestrationContext.forUser("user-1")),
            metadata
        );

        assertThat(resolved.attempted()).isFalse();
        assertThat(resolved.skipReason()).isEqualTo("ERROR");
        Map<?, ?> diagnostics = (Map<?, ?>) metadata.get(ReadActionResolutionSupport.METADATA_KEY);
        assertThat(diagnostics.get("skipReason")).isEqualTo("ERROR");
        assertThat(diagnostics.get("message")).isEqualTo("planner unavailable");
    }

    @Test
    void shouldAttachDiagnosticsToResultMetadataAndData() {
        ReadActionResolutionService.ResolutionOutcome outcome =
            ReadActionResolutionService.ResolutionOutcome.continueWithRag(
                "READ ACTION EVIDENCE\n- live fact",
                List.of("records"),
                List.of(),
                Map.of("attempted", true, "useRag", true)
            );
        OrchestrationResult result = OrchestrationResult.builder()
            .success(true)
            .message("ok")
            .metadata(Map.of("existing", true))
            .data(Map.of("answer", "ok"))
            .build();

        OrchestrationResult attached = ReadActionResolutionSupport.attachDiagnostics(result, outcome);

        assertThat(attached).isSameAs(result);
        assertThat(attached.getMetadata()).containsEntry("existing", true);
        Map<?, ?> metadataDiagnostics = (Map<?, ?>) attached.getMetadata().get(ReadActionResolutionSupport.METADATA_KEY);
        assertThat(metadataDiagnostics.get("useRag")).isEqualTo(true);
        assertThat(attached.getData()).containsEntry("answer", "ok");
        Map<?, ?> dataDiagnostics = (Map<?, ?>) attached.getData().get(ReadActionResolutionSupport.METADATA_KEY);
        assertThat(dataDiagnostics.get("attempted")).isEqualTo(true);
    }

    @Test
    void shouldMergeReadActionEvidenceAheadOfRetrievedContextAndPinnedTargets() {
        ReadActionResolutionService.ResolutionOutcome outcome =
            ReadActionResolutionService.ResolutionOutcome.answerFromActionsOnly(
                "READ ACTION EVIDENCE\n- status: ready",
                List.of(),
                List.of(),
                Map.of("attempted", true)
            );
        PipelineContext context = PipelineContext.from("query", OrchestrationContext.forUser("user-1"))
            .toBuilder()
            .resolvedTargets(List.of(ResolvedTarget.builder()
                .id("target-1")
                .vectorSpace("records")
                .contentText("active target")
                .source(ResolvedTargetSource.WORKING_SET)
                .build()))
            .pinnedTargetsContext("PINNED TARGETS:\n- active target")
            .build();

        String merged = ReadActionResolutionSupport.mergeEvidenceIntoGenerationContext(
            "retrieved context",
            context,
            outcome,
            RagContextSupport.NO_CONTEXT_MESSAGE
        );

        assertThat(merged)
            .startsWith("PINNED TARGETS")
            .contains("READ ACTION EVIDENCE POLICY")
            .contains("- status: ready")
            .contains("retrieved context");
    }

    private AIActionMetaData readMeta(boolean resolutionEligible, boolean groundingEligible) {
        return AIActionMetaData.builder()
            .accessMode(ActionAccessMode.READ)
            .readActionResolutionEligible(resolutionEligible)
            .groundingEligible(groundingEligible)
            .build();
    }

    private OrchestrationPolicy policy(OrchestrationPolicy.ReadActionResolutionPolicy readPolicy) {
        return new OrchestrationPolicy(
            OrchestrationProfile.DEFAULT,
            "navigator",
            null,
            null,
            OrchestrationPolicy.OrchestrationCapabilities.defaults(),
            readPolicy,
            OrchestrationPolicy.RagBudgets.defaults()
        );
    }

    private <T> ObjectProvider<T> providerOf(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
