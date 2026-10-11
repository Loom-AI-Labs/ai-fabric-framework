package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.dto.Intent;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.information.ReadActionResolutionService;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
                false,
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
    void shouldPreserveActionContractRoutingSource() {
        Intent intent = Intent.builder().build();
        Map<String, Object> metadata = new LinkedHashMap<>();
        ReadActionResolutionService.ResolutionOutcome readActionResolution =
            ReadActionResolutionService.ResolutionOutcome.continueWithRagAfterInsufficientActions(
                "Insufficient read evidence",
                List.of("dealer-vehicle"),
                List.of(),
                Map.of(
                    "attempted", true,
                    "preferredVectorSpacesSource", "ACTION_CONTRACT"
                )
            );

        InformationVectorSpaceRoutingSupport.RoutedVectorSpaces routed =
            InformationVectorSpaceRoutingSupport.route(
                intent,
                metadata,
                new OrchestrationPolicy.RagBudgets(
                    true, 2, null, null, null, null, List.of("dealer-vehicle", "document")
                ),
                true,
                true,
                false,
                readActionResolution,
                null,
                supportWithoutCatalog()
            );

        assertThat(routed.terminalResult()).isNull();
        assertThat(routed.vectorSpaces()).containsExactly("dealer-vehicle");
        assertThat(metadata).containsEntry("vectorSpacesSelectionSource", "ACTION_CONTRACT");
    }

    @Test
    void shouldRejectReadActionPreferredSpaceMissingFromEntityConfiguration() {
        Intent intent = Intent.builder().build();
        Map<String, Object> metadata = new LinkedHashMap<>();
        AIEntityConfigurationLoader entityConfigurationLoader = mock(AIEntityConfigurationLoader.class);
        when(entityConfigurationLoader.getSupportedEntityTypes()).thenReturn(Set.of("document", "policy"));
        ReadActionResolutionService.ResolutionOutcome readActionResolution =
            ReadActionResolutionService.ResolutionOutcome.continueWithRagAfterInsufficientActions(
                "Insufficient read evidence",
                List.of("dealer-vehicle"),
                List.of(),
                Map.of(
                    "attempted", true,
                    "preferredVectorSpacesSource", "ACTION_CONTRACT"
                )
            );

        InformationVectorSpaceRoutingSupport.RoutedVectorSpaces routed =
            InformationVectorSpaceRoutingSupport.route(
                intent,
                metadata,
                new OrchestrationPolicy.RagBudgets(
                    true, 2, null, null, null, null, List.of("document", "policy")
                ),
                true,
                true,
                false,
                readActionResolution,
                entityConfigurationLoader,
                supportWithoutCatalog()
            );

        assertThat(routed.terminalResult()).isNotNull();
        assertThat(routed.terminalResult().getType())
            .isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);
        assertThat(metadata)
            .containsEntry("readActionVectorSpacesInvalidRequested", List.of("dealer-vehicle"));
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
                false,
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
                false,
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
    void shouldRejectFanoutWhenPolicyDisablesFanoutWithoutDroppingModelSelectedDomains() {
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
                false,
                false,
                ReadActionResolutionService.ResolutionOutcome.skipped("NOT_NEEDED"),
                null,
                supportWithoutCatalog()
            );

        assertThat(routed.vectorSpaces()).isEmpty();
        assertThat(routed.terminalResult()).isNotNull();
        assertThat(routed.terminalResult().getType())
            .isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);
        assertThat(intent.getVectorSpace()).isEqualTo("product,policy");
        assertThat(metadata).isEmpty();
        Map<?, ?> data = (Map<?, ?>) routed.terminalResult().getData();
        assertThat(data.get("reason")).isEqualTo("VECTOR_SPACE_FANOUT_DISABLED");
        assertThat(data.get("requestedVectorSpaces")).isEqualTo(List.of("product", "policy"));
    }

    @Test
    void shouldUseAllowlistsOnlyAsCeilingsWithoutExplicitMissingSpaceRecovery() {
        Intent intent = Intent.builder().build();
        Map<String, Object> metadata = new LinkedHashMap<>();
        OrchestrationPolicy.RagBudgets ragBudgets =
            new OrchestrationPolicy.RagBudgets(true, 2, null, null, null, null, List.of("product"));

        InformationVectorSpaceRoutingSupport.RoutedVectorSpaces routed =
            InformationVectorSpaceRoutingSupport.route(
                intent,
                metadata,
                ragBudgets,
                false,
                false,
                false,
                ReadActionResolutionService.ResolutionOutcome.skipped("NOT_NEEDED"),
                null,
                supportWithoutCatalog()
            );

        assertThat(routed.terminalResult()).isNotNull();
        assertThat(routed.terminalResult().getType())
            .isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);
        assertThat(intent.getVectorSpace()).isNull();
        Map<?, ?> data = (Map<?, ?>) routed.terminalResult().getData();
        assertThat(data.get("reason")).isEqualTo("VECTOR_SPACE_SELECTION_REQUIRED");
        assertThat(data.get("allowedVectorSpaces")).isEqualTo(List.of("product"));
    }

    @Test
    void shouldUseBoundedAllowlistsOnlyWhenMissingSpaceRecoveryIsExplicitlyEnabled() {
        Intent intent = Intent.builder().build();
        Map<String, Object> metadata = new LinkedHashMap<>();
        OrchestrationPolicy.RagBudgets ragBudgets =
            new OrchestrationPolicy.RagBudgets(true, 2, null, null, null, null, List.of("product", "policy"));

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

        assertThat(routed.terminalResult()).isNull();
        assertThat(routed.vectorSpaces()).containsExactly("product", "policy");
        assertThat(intent.getVectorSpace()).isEqualTo("product,policy");
        assertThat(metadata)
            .containsEntry("retrievalStrategy", "FAN_OUT")
            .containsEntry("vectorSpacesSelectionSource", "EXPLICIT_BOUNDED_FAN_OUT_RECOVERY");
    }

    @Test
    void shouldRejectNamedRecoveryWithoutAnExplicitPositiveFanOutBudget() {
        Intent intent = Intent.builder().build();
        Map<String, Object> metadata = new LinkedHashMap<>();
        OrchestrationPolicy.RagBudgets ragBudgets = new OrchestrationPolicy.RagBudgets(
            true, null, null, null, null, null, List.of("product", "policy")
        );

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

        assertThat(routed.terminalResult()).isNotNull();
        assertThat(routed.terminalResult().getType())
            .isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);
        assertThat(intent.getVectorSpace()).isNull();
    }

    @Test
    void shouldNotInferAConfiguredSpaceWithoutNamedRecovery() {
        Intent intent = Intent.builder().build();
        Map<String, Object> metadata = new LinkedHashMap<>();
        AIEntityConfigurationLoader loader = mock(AIEntityConfigurationLoader.class);
        when(loader.getSupportedEntityTypes()).thenReturn(Set.of("product", "policy"));

        InformationVectorSpaceRoutingSupport.RoutedVectorSpaces routed =
            InformationVectorSpaceRoutingSupport.route(
                intent,
                metadata,
                new OrchestrationPolicy.RagBudgets(
                    true, 2, null, null, null, null, List.of("product", "policy")
                ),
                false,
                true,
                false,
                ReadActionResolutionService.ResolutionOutcome.skipped("NOT_NEEDED"),
                loader,
                new VectorSpaceSelectionSupport(null, loader)
            );

        assertThat(routed.terminalResult()).isNotNull();
        assertThat(routed.terminalResult().getType())
            .isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);
        assertThat(intent.getVectorSpace()).isNull();
        assertThat(metadata).isEmpty();
    }

    private static VectorSpaceSelectionSupport supportWithoutCatalog() {
        return new VectorSpaceSelectionSupport(null, null);
    }
}
