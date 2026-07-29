package ai.fabric.execution.specialist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.capability.RequestedCapabilityProfile;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultSpecialistRegistryTest {

    private static final SpecialistId ID = SpecialistId.of("account-resolver", "1");

    @Test
    void registersValidatedDefinition() {
        SpecialistDefinition<String, String> definition = definition(
            "resolver",
            readProfile()
        );

        DefaultSpecialistRegistry registry = new DefaultSpecialistRegistry(
            List.of(definition),
            actionRegistry("inspect_account", ActionAccessMode.READ),
            Set.of("resolver")
        );

        assertThat(registry.require(ID)).isSameAs(definition);
        assertThat(registry.list()).containsExactly(definition);
    }

    @Test
    void normalizesModesIndependentlyOfTheDefaultJvmLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            SpecialistDefinition<String, String> definition = definition(
                "INSIGHT",
                readProfile()
            );

            DefaultSpecialistRegistry registry = new DefaultSpecialistRegistry(
                List.of(definition),
                actionRegistry("inspect_account", ActionAccessMode.READ),
                Set.of("insight")
            );

            assertThat(registry.require(ID)).isSameAs(definition);
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void rejectsDuplicateSpecialistIdentity() {
        SpecialistDefinition<String, String> definition = definition(
            "resolver",
            readProfile()
        );

        assertThatThrownBy(() -> new DefaultSpecialistRegistry(
            List.of(definition, definition),
            actionRegistry("inspect_account", ActionAccessMode.READ),
            Set.of("resolver")
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Duplicate specialist definition");
    }

    @Test
    void rejectsUnknownMode() {
        assertThatThrownBy(() -> new DefaultSpecialistRegistry(
            List.of(definition("missing-mode", readProfile())),
            actionRegistry("inspect_account", ActionAccessMode.READ),
            Set.of("resolver")
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("unknown Mode");
    }

    @Test
    void rejectsUnregisteredAction() {
        assertThatThrownBy(() -> new DefaultSpecialistRegistry(
            List.of(definition("resolver", readProfile())),
            actionRegistry("another_action", ActionAccessMode.READ),
            Set.of("resolver")
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("unregistered actions")
            .hasMessageContaining("inspect_account");
    }

    @Test
    void rejectsUnregisteredVectorSpaceWhenDeploymentInventoryIsKnown() {
        assertThatThrownBy(() -> new DefaultSpecialistRegistry(
            List.of(definition("resolver", readProfile())),
            actionRegistry("inspect_account", ActionAccessMode.READ),
            Set.of("resolver"),
            Set.of("another-space")
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("unregistered vector spaces")
            .hasMessageContaining("account-policy");
    }

    @Test
    void rejectsActionDeclaredWithTheWrongAccessMode() {
        assertThatThrownBy(() -> new DefaultSpecialistRegistry(
            List.of(definition("resolver", readProfile())),
            actionRegistry("inspect_account", ActionAccessMode.WRITE_ONLY),
            Set.of("resolver"),
            Set.of("account-policy")
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("non-READ action")
            .hasMessageContaining("inspect_account");
    }

    @Test
    void rejectsWriteCapabilityOnReadOnlyProfile() {
        RequestedCapabilityProfile requested = new RequestedCapabilityProfile(
            false,
            Set.of(),
            Set.of("request_refund"),
            Set.of(),
            Set.of("request_refund")
        );

        assertThatThrownBy(() -> new SpecialistExecutionProfile(
            "resolver",
            requested,
            ExecutionStrategy.SINGLE_PASS,
            SpecialistWritePolicy.DISABLED
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("writePolicy=DISABLED");
    }

    @Test
    void rejectsUnboundedRetrievalScope() {
        SpecialistExecutionProfile unbounded = new SpecialistExecutionProfile(
            "resolver",
            new RequestedCapabilityProfile(
                true,
                Set.of(),
                Set.of("inspect_account"),
                Set.of("inspect_account"),
                Set.of()
            ),
            ExecutionStrategy.SINGLE_PASS,
            SpecialistWritePolicy.DISABLED
        );

        assertThatThrownBy(() -> new DefaultSpecialistRegistry(
            List.of(definition("resolver", unbounded)),
            actionRegistry("inspect_account", ActionAccessMode.READ),
            Set.of("resolver")
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("without a bounded vector-space scope");
    }

    private SpecialistExecutionProfile readProfile() {
        return new SpecialistExecutionProfile(
            "resolver",
            new RequestedCapabilityProfile(
                true,
                Set.of("account-policy"),
                Set.of("inspect_account"),
                Set.of("inspect_account"),
                Set.of()
            ),
            ExecutionStrategy.SINGLE_PASS,
            SpecialistWritePolicy.DISABLED
        );
    }

    private SpecialistDefinition<String, String> definition(
        String mode,
        SpecialistExecutionProfile profile
    ) {
        return new SpecialistDefinition<>(
            new SpecialistIdentity(ID, "Account Resolver", "Resolves account blockers"),
            new SpecialistInstructions("Explain account blockers", null),
            new SpecialistExecutionProfile(
                mode,
                profile.requestedCapabilities(),
                profile.strategy(),
                profile.writePolicy()
            ),
            SpecialistLimits.defaults(),
            new SpecialistInputAdapter<>() {
                @Override
                public Class<String> inputType() {
                    return String.class;
                }

                @Override
                public void validate(String input) {
                    if (input.isBlank()) {
                        throw new IllegalArgumentException("input is required");
                    }
                }

                @Override
                public String renderModelInput(String input) {
                    return input;
                }

                @Override
                public OrchestrationContext orchestrationContext(String input) {
                    return OrchestrationContext.builder().build();
                }
            },
            new SpecialistOutputAdapter<>() {
                @Override
                public Class<String> outputType() {
                    return String.class;
                }

                @Override
                public String project(
                    ai.fabric.intent.orchestration.OrchestrationResult result,
                    List<ai.fabric.evidence.AIEvidenceReference> evidence
                ) {
                    return result.getMessage();
                }

                @Override
                public void validate(String output) {
                    if (output == null || output.isBlank()) {
                        throw new IllegalArgumentException("output is required");
                    }
                }
            }
        );
    }

    private AIActionRegistry actionRegistry(String action, ActionAccessMode accessMode) {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        when(registry.getAllMetadata()).thenReturn(List.of(
            AIActionMetaData.builder()
                .name(action)
                .accessMode(accessMode)
                .readActionResolutionEligible(accessMode == ActionAccessMode.READ)
                .build()
        ));
        return registry;
    }
}
