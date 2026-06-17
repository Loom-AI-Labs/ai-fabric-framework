package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.dto.Intent;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.information.ReadActionResolutionService;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InformationVectorSpaceRoutingSupportTest {

    @Test
    void shouldUseReadActionPreferredVectorSpacesWhenIntentDoesNotProvideAny() {
        Intent intent = Intent.builder().build();
        Map<String, Object> metadata = new LinkedHashMap<>();
        ReadActionResolutionService.ResolutionOutcome readActionResolution =
            ReadActionResolutionService.ResolutionOutcome.continueWithRag(
                null,
                List.of("policy"),
                List.of(),
                Map.of("attempted", true)
            );

        InformationVectorSpaceRoutingSupport.RoutedVectorSpaces routed =
            InformationVectorSpaceRoutingSupport.route(
                intent,
                metadata,
                null,
                false,
                true,
                true,
                readActionResolution,
                null,
                supportWithoutCatalog()
            );

        assertThat(routed.terminalResult()).isNull();
        assertThat(routed.vectorSpaces()).containsExactly("policy");
        assertThat(intent.getVectorSpace()).isEqualTo("policy");
        assertThat(metadata)
            .containsEntry("retrievalStrategy", "SINGLE_SPACE")
            .containsEntry("vectorSpacesSelectionSource", "READ_ACTION_PLANNER");
        assertThat(metadata.get("vectorSpacesSelected")).isEqualTo(List.of("policy"));
    }

    @Test
    void shouldReturnClarificationWhenVectorSpaceRequiredByAllowlistPolicy() {
        Intent intent = Intent.builder().build();
        Map<String, Object> metadata = new LinkedHashMap<>();
        OrchestrationPolicy.RagBudgets ragBudgets =
            new OrchestrationPolicy.RagBudgets(null, null, null, null, null, null, List.of("product", "policy"));

        InformationVectorSpaceRoutingSupport.RoutedVectorSpaces routed =
            InformationVectorSpaceRoutingSupport.route(
                intent,
                metadata,
                ragBudgets,
                true,
                true,
                true,
                ReadActionResolutionService.ResolutionOutcome.skipped("NOT_NEEDED"),
                null,
                supportWithoutCatalog()
            );

        OrchestrationResult result = routed.terminalResult();
        assertThat(result).isNotNull();
        assertThat(result.getType()).isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);
        assertThat(result.getMessage()).isEqualTo("Which knowledge base domain should I search?");

        Map<?, ?> data = (Map<?, ?>) result.getData();
        assertThat(data.get("reason")).isEqualTo("VECTOR_SPACE_REQUIRED_BY_POLICY");
        assertThat(data.get("allowedVectorSpaces")).isEqualTo(List.of("product", "policy"));
        assertThat(metadata).isEmpty();
    }

    @Test
    void shouldDenyRequestedVectorSpacesOutsideAllowlist() {
        Intent intent = Intent.builder()
            .vectorSpace("orders")
            .build();
        Map<String, Object> metadata = new LinkedHashMap<>();
        OrchestrationPolicy.RagBudgets ragBudgets =
            new OrchestrationPolicy.RagBudgets(null, null, null, null, null, null, List.of("product"));

        InformationVectorSpaceRoutingSupport.RoutedVectorSpaces routed =
            InformationVectorSpaceRoutingSupport.route(
                intent,
                metadata,
                ragBudgets,
                false,
                true,
                true,
                ReadActionResolutionService.ResolutionOutcome.skipped("NOT_NEEDED"),
                null,
                supportWithoutCatalog()
            );

        OrchestrationResult result = routed.terminalResult();
        assertThat(result).isNotNull();
        assertThat(result.getType()).isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);

        Map<?, ?> data = (Map<?, ?>) result.getData();
        assertThat(data.get("reason")).isEqualTo("VECTOR_SPACE_NOT_ALLOWED_BY_POLICY");
        assertThat(data.get("allowedVectorSpaces")).isEqualTo(List.of("product"));
        assertThat(data.get("requestedVectorSpaces")).isEqualTo(List.of("orders"));
        assertThat(data.get("deniedVectorSpaces")).isEqualTo(List.of("orders"));
    }

    @Test
    void shouldSuppressFanoutWhenPolicyDisablesFanout() {
        Intent intent = Intent.builder()
            .vectorSpace("product,policy")
            .build();
        Map<String, Object> metadata = new LinkedHashMap<>();

        InformationVectorSpaceRoutingSupport.RoutedVectorSpaces routed =
            InformationVectorSpaceRoutingSupport.route(
                intent,
                metadata,
                null,
                false,
                true,
                false,
                ReadActionResolutionService.ResolutionOutcome.skipped("NOT_NEEDED"),
                null,
                supportWithoutCatalog()
            );

        assertThat(routed.terminalResult()).isNull();
        assertThat(routed.vectorSpaces()).containsExactly("product");
        assertThat(intent.getVectorSpace()).isEqualTo("product");
        assertThat(metadata)
            .containsEntry("fanoutSuppressed", true)
            .containsEntry("fanoutSuppressedReason", "POLICY")
            .containsEntry("retrievalStrategy", "SINGLE_SPACE")
            .containsEntry("vectorSpacesSelectionSource", "LLM");
        assertThat(metadata.get("fanoutSuppressedRequestedSpaces")).isEqualTo(List.of("product", "policy"));
        assertThat(metadata.get("vectorSpacesSelected")).isEqualTo(List.of("product"));
    }

    private static VectorSpaceSelectionSupport supportWithoutCatalog() {
        return new VectorSpaceSelectionSupport(null, null, null);
    }
}
