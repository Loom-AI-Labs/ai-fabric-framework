package ai.fabric.execution.specialist.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.fabric.execution.gateway.ExecutionCapabilityInventory;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.ActionAccessMode;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultSpecialistAuthoringCatalogProviderTest {

    @Test
    void exposesOnlyDeploymentApprovedCapabilitiesWithoutGrantingAuthority() {
        AIActionRegistry actionRegistry = mock(AIActionRegistry.class);
        when(actionRegistry.getAllMetadata()).thenReturn(List.of(
            action(
                "get_account_profile",
                ActionAccessMode.READ,
                false,
                Set.of()
            ),
            action(
                "update_address",
                ActionAccessMode.READ_WRITE,
                true,
                Set.of("city", "country")
            ),
            action(
                "internal_admin",
                ActionAccessMode.WRITE_ONLY,
                true,
                Set.of()
            )
        ));
        ExecutionCapabilityInventory inventory =
            new ExecutionCapabilityInventory() {
                @Override
                public Set<String> registeredVectorSpaces() {
                    return Set.of("Account-Policy");
                }

                @Override
                public Set<String> deploymentAllowedActions() {
                    return Set.of(
                        "get_account_profile",
                        "update_address"
                    );
                }
            };
        var schemaValidator = new SpecialistJsonSchemaValidator();

        SpecialistAuthoringCatalog catalog =
            new DefaultSpecialistAuthoringCatalogProvider(
                Set.of("Resolver"),
                inventory,
                actionRegistry,
                new SpecialistJsonSchemaRegistry(
                    List.of(
                        ManifestTestFixtures.inputSchema(),
                        ManifestTestFixtures.outputSchema()
                    ),
                    schemaValidator
                ),
                new SpecialistPromptProfileRegistry(
                    List.of(ManifestTestFixtures.promptProfile())
                ),
                new SpecialistGroundingValidatorRegistry(List.of(
                    SpecialistGroundingValidator.named(
                        "grounding-check@1",
                        context -> {}
                    )
                )),
                new SpecialistFinalOutputValidatorRegistry(List.of()),
                new SpecialistDirectOutputProjectorRegistry(List.of()),
                new SpecialistOutputNormalizerRegistry(List.of())
            ).catalog();

        assertThat(catalog.modes()).containsExactly("resolver");
        assertThat(catalog.vectorSpaces()).containsExactly("account-policy");
        assertThat(catalog.actions())
            .extracting(SpecialistAuthoringCatalog.ActionOption::name)
            .containsExactly("get_account_profile", "update_address");
        assertThat(catalog.actions().getFirst().requestableRead()).isTrue();
        assertThat(catalog.actions().get(1).proposableWrite()).isTrue();
        assertThat(catalog.actions().get(1).requiredParameters())
            .containsExactlyInAnyOrder("city", "country");
        assertThat(catalog.schemas()).hasSize(2);
        assertThat(catalog.promptProfiles())
            .containsExactly("grounded-support@1");
        assertThat(catalog.extensions().groundingValidators())
            .containsExactly("grounding-check@1");
        assertThat(catalog.limits()).isEqualTo(
            SpecialistFrameworkLimits.DEFAULT
        );
    }

    private AIActionMetaData action(
        String name,
        ActionAccessMode accessMode,
        boolean confirmationRequired,
        Set<String> requiredParameters
    ) {
        return AIActionMetaData.builder()
            .name(name)
            .displayName(name)
            .description("Test action")
            .accessMode(accessMode)
            .confirmationRequired(confirmationRequired)
            .groundingEligible(accessMode == ActionAccessMode.READ)
            .requiredParameters(requiredParameters)
            .build();
    }
}
