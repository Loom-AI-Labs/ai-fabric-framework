package ai.fabric.execution.chain.manifest;

import ai.fabric.execution.chain.SpecialistChainDirective;
import ai.fabric.execution.chain.SpecialistChainLimits;
import ai.fabric.execution.chain.SpecialistChainManagerInput;
import ai.fabric.execution.config.AIExecutionProperties;
import ai.fabric.execution.specialist.RegisteredSpecialist;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.execution.specialist.manifest.JsonSchemaSpecialistInputAdapter;
import ai.fabric.execution.specialist.manifest.JsonSchemaSpecialistOutputAdapter;
import ai.fabric.execution.specialist.manifest.SpecialistConversationBinding;
import ai.fabric.execution.specialist.manifest.SpecialistInteractionCapability;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class DefaultSpecialistChainAuthoringCatalogProvider
    implements SpecialistChainAuthoringCatalogProvider {

    private final SpecialistRegistry specialistRegistry;
    private final AIExecutionProperties.SpecialistChains deployment;

    public DefaultSpecialistChainAuthoringCatalogProvider(
        SpecialistRegistry specialistRegistry,
        AIExecutionProperties.SpecialistChains deployment
    ) {
        this.specialistRegistry = Objects.requireNonNull(
            specialistRegistry,
            "specialistRegistry is required"
        );
        this.deployment = Objects.requireNonNull(
            deployment,
            "deployment is required"
        );
    }

    @Override
    public SpecialistChainAuthoringCatalog catalog() {
        SpecialistChainCompilationContextBounds bounds =
            new SpecialistChainCompilationContextBounds(deployment);
        List<SpecialistChainAuthoringCatalog.SpecialistOption> options =
            specialistRegistry.listRegistered().stream()
                .map(this::option)
                .sorted(Comparator.comparing(
                    SpecialistChainAuthoringCatalog.SpecialistOption::id
                ))
                .toList();
        return new SpecialistChainAuthoringCatalog(
            "ai.fabric/v1",
            deployment.isEnabled(),
            Set.of(SpecialistChainMappingSource.values()),
            Set.of(SpecialistChainResultProjectionType.values()),
            Set.of(SpecialistChainEvidencePolicy.values()),
            bounds.declarative(),
            new SpecialistChainLimits(
                deployment.getMaxDuration(),
                deployment.getMaxManagerDecisions(),
                deployment.getMaxWorkerInvocations(),
                deployment.getMaxParallelWorkers(),
                deployment.getMaxInvocationsPerTarget(),
                deployment.getMaxProjectedResultCharacters()
            ),
            options
        );
    }

    private SpecialistChainAuthoringCatalog.SpecialistOption option(
        RegisteredSpecialist registered
    ) {
        var definition = registered.definition();
        boolean schemaInput = definition.inputAdapter()
            instanceof JsonSchemaSpecialistInputAdapter;
        boolean schemaOutput = definition.outputAdapter()
            instanceof JsonSchemaSpecialistOutputAdapter;
        String inputSchema = schemaInput
            ? ((JsonSchemaSpecialistInputAdapter) definition.inputAdapter())
                .schemaDefinition().id().toString()
            : "";
        String outputSchema = schemaOutput
            ? ((JsonSchemaSpecialistOutputAdapter) definition.outputAdapter())
                .schemaDefinition().id().toString()
            : "";
        boolean nonInteractive = definition.inputAdapter()
                .interactionCapability()
                == SpecialistInteractionCapability.NON_INTERACTIVE
            && definition.inputAdapter().conversationBinding()
                == SpecialistConversationBinding.DISABLED
            && !definition.inputAdapter().recordValidatedTurns()
            && definition.inputAdapter().inputContinuation().isEmpty();
        boolean managerCandidate = definition.inputAdapter().inputType()
                == SpecialistChainManagerInput.class
            && definition.outputAdapter().outputType()
                == SpecialistChainDirective.class;
        return new SpecialistChainAuthoringCatalog.SpecialistOption(
            registered.id().toString(),
            registered.contentHash(),
            inputSchema,
            outputSchema,
            managerCandidate,
            !definition.executionProfile().writeEnabled(),
            nonInteractive,
            schemaInput && schemaOutput
        );
    }

    private record SpecialistChainCompilationContextBounds(
        AIExecutionProperties.SpecialistChains deployment
    ) {
        SpecialistChainDeclarativeBounds declarative() {
            return new SpecialistChainDeclarativeBounds(
                deployment.getMaxJsonPointerCharacters(),
                deployment.getMaxJsonPointerDepth(),
                SpecialistChainManagerInput.MAX_CONTEXT_VALUES,
                deployment.getMaxMappingsPerTarget(),
                deployment.getMaxMappingsPerChain(),
                deployment.getMaxCopiedNodeDepth(),
                deployment.getMaxCopiedNodeCount(),
                deployment.getMaxCopiedValueBytes(),
                deployment.getMaxMappedInputBytes(),
                deployment.getMaxMappingWorkUnits()
            );
        }
    }
}
