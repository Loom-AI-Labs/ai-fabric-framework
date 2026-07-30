package ai.fabric.execution.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.execution.gateway.AIExecutionGateway;
import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.specialist.ExecutionStrategy;
import ai.fabric.execution.specialist.RegisteredSpecialist;
import ai.fabric.execution.specialist.SpecialistDefinition;
import ai.fabric.execution.specialist.SpecialistDefinitionSource;
import ai.fabric.execution.specialist.SpecialistDelegationPolicy;
import ai.fabric.execution.specialist.SpecialistExecutionProfile;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.SpecialistIdentity;
import ai.fabric.execution.specialist.SpecialistInputAdapter;
import ai.fabric.execution.specialist.SpecialistInstructions;
import ai.fabric.execution.specialist.SpecialistLimits;
import ai.fabric.execution.specialist.SpecialistOutputAdapter;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.execution.specialist.SpecialistWritePolicy;
import ai.fabric.execution.specialist.client.DefaultSpecialistClientFactory;
import ai.fabric.execution.specialist.client.SpecialistClientFactory;
import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import ai.fabric.execution.specialist.manifest.SpecialistConversationBinding;
import ai.fabric.execution.specialist.manifest.SpecialistInteractionCapability;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.capability.RequestedCapabilityProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultConversationManagerRegistryTest {

    private static final SpecialistId MANAGER =
        SpecialistId.of("account-manager", "1");
    private static final SpecialistId WORKER =
        SpecialistId.of("account-read", "1");

    @Test
    void validatesAndFingerprintsAClosedTypedManager() {
        SpecialistRegistry specialists = registry(
            manager(false, SpecialistDelegationPolicy.oneLevel(Set.of(WORKER))),
            worker(false, SpecialistInteractionCapability.NON_INTERACTIVE)
        );
        ConversationManagerDefinition<String> definition =
            definition("Inspect the current account.");

        RegisteredConversationManager first = registry(
            List.of(definition),
            specialists
        ).require(definition.id());
        RegisteredConversationManager second = registry(
            List.of(definition),
            specialists
        ).require(definition.id());
        RegisteredConversationManager changed = registry(
            List.of(definition("Inspect current account billing state.")),
            specialists
        ).require(definition.id());

        assertThat(first.contentHash())
            .hasSize(64)
            .isEqualTo(second.contentHash())
            .isNotEqualTo(changed.contentHash());
        assertThat(first.definition().targets())
            .extracting(target -> target.specialistId().toString())
            .containsExactly(WORKER.toString());
    }

    @Test
    void rejectsManagerThatRecordsItsInternalDirective() {
        SpecialistRegistry specialists = registry(
            manager(true, SpecialistDelegationPolicy.oneLevel(Set.of(WORKER))),
            worker(false, SpecialistInteractionCapability.NON_INTERACTIVE)
        );

        assertThatThrownBy(() -> registry(
            List.of(definition("Inspect the current account.")),
            specialists
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(
                "manager specialist must defer conversation recording"
            );
    }

    @Test
    void rejectsUnknownManagersWorkersAndDuplicateManagerIds() {
        SpecialistRegistry noSpecialists = registry();

        assertThatThrownBy(() -> registry(
            List.of(definition("Inspect the current account.")),
            noSpecialists
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("manager references unknown specialist");

        SpecialistRegistry managerOnly = registry(
            manager(false, SpecialistDelegationPolicy.oneLevel(Set.of(WORKER)))
        );
        assertThatThrownBy(() -> registry(
            List.of(definition("Inspect the current account.")),
            managerOnly
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("target references unknown specialist");

        SpecialistRegistry specialists = registry(
            manager(false, SpecialistDelegationPolicy.oneLevel(Set.of(WORKER))),
            worker(false, SpecialistInteractionCapability.NON_INTERACTIVE)
        );
        assertThatThrownBy(() -> registry(
            List.of(
                definition("Inspect the current account."),
                definition("Inspect the current account.")
            ),
            specialists
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("duplicates an existing manager ID");
    }

    @Test
    void rejectsIneligibleOrCapabilityBearingManagers() {
        SpecialistRegistry nonDialogue = registry(
            manager(
                false,
                SpecialistDelegationPolicy.oneLevel(Set.of(WORKER)),
                SpecialistInteractionCapability.NON_INTERACTIVE,
                false
            ),
            worker(false, SpecialistInteractionCapability.NON_INTERACTIVE)
        );
        assertThatThrownBy(() -> registry(
            List.of(definition("Inspect the current account.")),
            nonDialogue
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("manager specialist must be DIALOGUE_CAPABLE");

        SpecialistRegistry capabilityBearing = registry(
            manager(
                false,
                SpecialistDelegationPolicy.oneLevel(Set.of(WORKER)),
                SpecialistInteractionCapability.DIALOGUE_CAPABLE,
                true
            ),
            worker(false, SpecialistInteractionCapability.NON_INTERACTIVE)
        );
        assertThatThrownBy(() -> registry(
            List.of(definition("Inspect the current account.")),
            capabilityBearing
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(
                "manager specialist cannot declare retrieval or actions"
            );
    }

    @Test
    void rejectsTargetsOutsideTheClosedDelegationSet() {
        SpecialistRegistry specialists = registry(
            manager(false, SpecialistDelegationPolicy.disabled()),
            worker(false, SpecialistInteractionCapability.NON_INTERACTIVE)
        );

        assertThatThrownBy(() -> registry(
            List.of(definition("Inspect the current account.")),
            specialists
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("absent from the manager delegation allowlist");
    }

    @Test
    void rejectsWriteCapableOrInteractiveTargets() {
        SpecialistRegistry writeSpecialists = registry(
            manager(false, SpecialistDelegationPolicy.oneLevel(Set.of(WORKER))),
            worker(true, SpecialistInteractionCapability.NON_INTERACTIVE)
        );
        SpecialistRegistry dialogueSpecialists = registry(
            manager(false, SpecialistDelegationPolicy.oneLevel(Set.of(WORKER))),
            worker(false, SpecialistInteractionCapability.DIALOGUE_CAPABLE)
        );
        SpecialistRegistry recordingSpecialists = registry(
            manager(false, SpecialistDelegationPolicy.oneLevel(Set.of(WORKER))),
            worker(
                false,
                SpecialistInteractionCapability.NON_INTERACTIVE,
                true
            )
        );

        assertThatThrownBy(() -> registry(
            List.of(definition("Inspect the current account.")),
            writeSpecialists
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("manager targets must be read-only");

        assertThatThrownBy(() -> registry(
            List.of(definition("Inspect the current account.")),
            dialogueSpecialists
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("manager targets must be non-interactive");

        assertThatThrownBy(() -> registry(
            List.of(definition("Inspect the current account.")),
            recordingSpecialists
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(
                "manager targets must be conversation-isolated"
            );
    }

    @Test
    void rejectsTypedBoundaryAndDeploymentLimitMismatches() {
        SpecialistRegistry specialists = registry(
            manager(false, SpecialistDelegationPolicy.oneLevel(Set.of(WORKER))),
            worker(false, SpecialistInteractionCapability.NON_INTERACTIVE)
        );
        ConversationManagerDefinition<String> wrongRequestType =
            new ConversationManagerDefinition<>(
                ConversationManagerId.of("wrong-request", "1"),
                MANAGER,
                String.class,
                inputAdapter(),
                List.of(new ConversationManagerTarget<>(
                    WORKER,
                    "Inspect the current account.",
                    wrongRequestMapper(),
                    projector()
                )),
                Duration.ofSeconds(20)
            );
        ConversationManagerDefinition<String> tooSlow =
            new ConversationManagerDefinition<>(
                ConversationManagerId.of("too-slow", "1"),
                MANAGER,
                String.class,
                inputAdapter(),
                List.of(target("Inspect the current account.")),
                Duration.ofMinutes(2)
            );

        assertThatThrownBy(() -> registry(
            List.of(wrongRequestType),
            specialists
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("target input mapper request");

        assertThatThrownBy(() -> registry(
            List.of(tooSlow),
            specialists
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maximumDuration exceeds");
    }

    private DefaultConversationManagerRegistry registry(
        List<ConversationManagerDefinition<?>> definitions,
        SpecialistRegistry specialists
    ) {
        return new DefaultConversationManagerRegistry(
            definitions,
            specialists,
            clientFactory(specialists),
            new CanonicalJsonSupport(new ObjectMapper()),
            Duration.ofMinutes(1)
        );
    }

    private ConversationManagerDefinition<String> definition(
        String description
    ) {
        return new ConversationManagerDefinition<>(
            ConversationManagerId.of("account-conversation", "1"),
            MANAGER,
            String.class,
            inputAdapter(),
            List.of(target(description)),
            Duration.ofSeconds(20)
        );
    }

    private ConversationManagerTarget<String, String, Integer> target(
        String description
    ) {
        return new ConversationManagerTarget<>(
            WORKER,
            description,
            mapper(String.class),
            projector()
        );
    }

    private ConversationManagerInputAdapter<String> inputAdapter() {
        return new ConversationManagerInputAdapter<>() {
            @Override
            public ConversationManagerComponentId id() {
                return ConversationManagerComponentId.of(
                    "account-manager-input",
                    "1"
                );
            }

            @Override
            public Class<String> inputType() {
                return String.class;
            }

            @Override
            public String currentUserMessage(String input) {
                return input;
            }

            @Override
            public List<ConversationManagerContextValue> applicationContext(
                String input
            ) {
                return List.of(new ConversationManagerContextValue(
                    "account",
                    "current"
                ));
            }
        };
    }

    private <P> ConversationManagerTargetInputMapper<P, String> mapper(
        Class<P> requestType
    ) {
        return new ConversationManagerTargetInputMapper<>() {
            @Override
            public ConversationManagerComponentId id() {
                return ConversationManagerComponentId.of(
                    "account-read-input",
                    "1"
                );
            }

            @Override
            public Class<P> managerRequestType() {
                return requestType;
            }

            @Override
            public Class<String> targetInputType() {
                return String.class;
            }

            @Override
            public String map(P request) {
                return request.toString();
            }
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ConversationManagerTargetInputMapper<String, String>
        wrongRequestMapper() {
        return new ConversationManagerTargetInputMapper<>() {
            @Override
            public ConversationManagerComponentId id() {
                return ConversationManagerComponentId.of(
                    "wrong-request-input",
                    "1"
                );
            }

            @Override
            public Class<String> managerRequestType() {
                return (Class) Integer.class;
            }

            @Override
            public Class<String> targetInputType() {
                return String.class;
            }

            @Override
            public String map(String request) {
                return request;
            }
        };
    }

    private ConversationManagerTargetResultProjector<String, Integer>
        projector() {
        return new ConversationManagerTargetResultProjector<>() {
            @Override
            public ConversationManagerComponentId id() {
                return ConversationManagerComponentId.of(
                    "account-read-result",
                    "1"
                );
            }

            @Override
            public Class<String> managerRequestType() {
                return String.class;
            }

            @Override
            public Class<Integer> targetOutputType() {
                return Integer.class;
            }

            @Override
            public String project(
                String request,
                AIExecutionResult<Integer> targetExecution
            ) {
                return request + ": " + targetExecution.output();
            }
        };
    }

    private SpecialistDefinition<
        ConversationManagerInput,
        ConversationManagerDirective
    > manager(
        boolean recordTurns,
        SpecialistDelegationPolicy delegationPolicy
    ) {
        return manager(
            recordTurns,
            delegationPolicy,
            SpecialistInteractionCapability.DIALOGUE_CAPABLE,
            false
        );
    }

    private SpecialistDefinition<
        ConversationManagerInput,
        ConversationManagerDirective
    > manager(
        boolean recordTurns,
        SpecialistDelegationPolicy delegationPolicy,
        SpecialistInteractionCapability interactionCapability,
        boolean requestedCapabilities
    ) {
        return new SpecialistDefinition<>(
            new SpecialistIdentity(
                MANAGER,
                "Account manager",
                "Selects one approved read-only specialist."
            ),
            new SpecialistInstructions("Select one directive.", null),
            profile(requestedCapabilities),
            SpecialistLimits.defaults(),
            delegationPolicy,
            input(
                ConversationManagerInput.class,
                SpecialistConversationBinding.REQUIRED,
                recordTurns,
                interactionCapability
            ),
            output(ConversationManagerDirective.class)
        );
    }

    private SpecialistDefinition<String, Integer> worker(
        boolean writeEnabled,
        SpecialistInteractionCapability interactionCapability
    ) {
        return worker(writeEnabled, interactionCapability, false);
    }

    private SpecialistDefinition<String, Integer> worker(
        boolean writeEnabled,
        SpecialistInteractionCapability interactionCapability,
        boolean recordTurns
    ) {
        return new SpecialistDefinition<>(
            new SpecialistIdentity(
                WORKER,
                "Account reader",
                "Reads the current account."
            ),
            new SpecialistInstructions("Read account state.", null),
            profile(writeEnabled),
            SpecialistLimits.defaults(),
            input(
                String.class,
                SpecialistConversationBinding.DISABLED,
                recordTurns,
                interactionCapability
            ),
            output(Integer.class)
        );
    }

    private SpecialistExecutionProfile profile(boolean writeEnabled) {
        RequestedCapabilityProfile capabilities =
            new RequestedCapabilityProfile(
                false,
                Set.of(),
                writeEnabled ? Set.of("write") : Set.of(),
                Set.of(),
                writeEnabled ? Set.of("write") : Set.of()
            );
        return new SpecialistExecutionProfile(
            "test",
            capabilities,
            ExecutionStrategy.SINGLE_PASS,
            writeEnabled
                ? SpecialistWritePolicy.CONFIRMATION_RECEIPT_REQUIRED
                : SpecialistWritePolicy.DISABLED
        );
    }

    private <I> SpecialistInputAdapter<I> input(
        Class<I> type,
        SpecialistConversationBinding binding,
        boolean recordTurns,
        SpecialistInteractionCapability interactionCapability
    ) {
        return new SpecialistInputAdapter<>() {
            @Override
            public Class<I> inputType() {
                return type;
            }

            @Override
            public void validate(I input) {}

            @Override
            public String renderModelInput(I input) {
                return input.toString();
            }

            @Override
            public String conversationInput(I input) {
                return input.toString();
            }

            @Override
            public OrchestrationContext orchestrationContext(I input) {
                return OrchestrationContext.builder().build();
            }

            @Override
            public SpecialistConversationBinding conversationBinding() {
                return binding;
            }

            @Override
            public boolean recordValidatedTurns() {
                return recordTurns;
            }

            @Override
            public SpecialistInteractionCapability interactionCapability() {
                return interactionCapability;
            }
        };
    }

    private <O> SpecialistOutputAdapter<O> output(Class<O> type) {
        return new SpecialistOutputAdapter<>() {
            @Override
            public Class<O> outputType() {
                return type;
            }

            @Override
            public O project(
                OrchestrationResult result,
                List<AIEvidenceReference> evidence
            ) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void validate(O output) {}
        };
    }

    private SpecialistRegistry registry(
        SpecialistDefinition<?, ?>... definitions
    ) {
        Map<SpecialistId, RegisteredSpecialist> values =
            new LinkedHashMap<>();
        for (SpecialistDefinition<?, ?> definition : definitions) {
            values.put(
                definition.id(),
                new RegisteredSpecialist(
                    definition,
                    SpecialistDefinitionSource.JAVA,
                    CanonicalJsonSupport.sha256(definition.id().toString()),
                    "test:" + definition.id(),
                    Map.of()
                )
            );
        }
        return new SpecialistRegistry() {
            @Override
            public Optional<SpecialistDefinition<?, ?>> find(SpecialistId id) {
                return Optional.ofNullable(values.get(id))
                    .map(RegisteredSpecialist::definition);
            }

            @Override
            public List<SpecialistDefinition<?, ?>> list() {
                return values.values().stream()
                    .map(RegisteredSpecialist::definition)
                    .toList();
            }

            @Override
            public Optional<RegisteredSpecialist> findRegistered(
                SpecialistId id
            ) {
                return Optional.ofNullable(values.get(id));
            }
        };
    }

    private SpecialistClientFactory clientFactory(
        SpecialistRegistry specialists
    ) {
        return new DefaultSpecialistClientFactory(
            specialists,
            mock(AIExecutionGateway.class),
            new ObjectMapper()
        );
    }
}
