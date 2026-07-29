package ai.fabric.execution.specialist.manifest;

import ai.fabric.execution.gateway.ExecutionCapabilityInventory;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionNames;
import ai.fabric.intent.action.AIActionRegistry;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class DefaultSpecialistAuthoringCatalogProvider
    implements SpecialistAuthoringCatalogProvider {

    private final Set<String> modes;
    private final ExecutionCapabilityInventory capabilityInventory;
    private final AIActionRegistry actionRegistry;
    private final SpecialistJsonSchemaRegistry schemaRegistry;
    private final SpecialistPromptProfileRegistry promptProfileRegistry;
    private final SpecialistGroundingValidatorRegistry groundingValidators;
    private final SpecialistFinalOutputValidatorRegistry finalValidators;
    private final SpecialistDirectOutputProjectorRegistry directProjectors;
    private final SpecialistOutputNormalizerRegistry outputNormalizers;

    public DefaultSpecialistAuthoringCatalogProvider(
        Set<String> modes,
        ExecutionCapabilityInventory capabilityInventory,
        AIActionRegistry actionRegistry,
        SpecialistJsonSchemaRegistry schemaRegistry,
        SpecialistPromptProfileRegistry promptProfileRegistry,
        SpecialistGroundingValidatorRegistry groundingValidators,
        SpecialistFinalOutputValidatorRegistry finalValidators,
        SpecialistDirectOutputProjectorRegistry directProjectors,
        SpecialistOutputNormalizerRegistry outputNormalizers
    ) {
        this.modes = normalize(modes);
        this.capabilityInventory = Objects.requireNonNull(
            capabilityInventory,
            "capabilityInventory is required"
        );
        this.actionRegistry = Objects.requireNonNull(
            actionRegistry,
            "actionRegistry is required"
        );
        this.schemaRegistry = Objects.requireNonNull(
            schemaRegistry,
            "schemaRegistry is required"
        );
        this.promptProfileRegistry = Objects.requireNonNull(
            promptProfileRegistry,
            "promptProfileRegistry is required"
        );
        this.groundingValidators = Objects.requireNonNull(
            groundingValidators,
            "groundingValidators are required"
        );
        this.finalValidators = Objects.requireNonNull(
            finalValidators,
            "finalValidators are required"
        );
        this.directProjectors = Objects.requireNonNull(
            directProjectors,
            "directProjectors are required"
        );
        this.outputNormalizers = Objects.requireNonNull(
            outputNormalizers,
            "outputNormalizers are required"
        );
    }

    @Override
    public SpecialistAuthoringCatalog catalog() {
        Set<String> deploymentAllowed = normalize(
            capabilityInventory.deploymentAllowedActions()
        );
        List<SpecialistAuthoringCatalog.ActionOption> actions =
            actionRegistry.getAllMetadata().stream()
                .filter(Objects::nonNull)
                .filter(value -> value.getName() != null)
                .filter(value ->
                    deploymentAllowed.isEmpty()
                        || deploymentAllowed.contains(
                            AIActionNames.normalize(value.getName())
                        )
                )
                .filter(value -> value.getAccessMode() != null)
                .map(this::action)
                .sorted(Comparator.comparing(
                    SpecialistAuthoringCatalog.ActionOption::name
                ))
                .toList();
        List<SpecialistAuthoringCatalog.SchemaOption> schemas =
            schemaRegistry.list().stream()
                .map(schema -> new SpecialistAuthoringCatalog.SchemaOption(
                    schema.id().toString(),
                    schema.spec().direction()
                ))
                .sorted(Comparator.comparing(
                    SpecialistAuthoringCatalog.SchemaOption::id
                ))
                .toList();
        return new SpecialistAuthoringCatalog(
            modes,
            normalize(capabilityInventory.registeredVectorSpaces()),
            actions,
            schemas,
            ids(promptProfileRegistry.list().stream()
                .map(profile -> profile.id().toString())
                .toList()),
            new SpecialistAuthoringCatalog.ExtensionOptions(
                ids(groundingValidators.list().stream()
                    .map(SpecialistGroundingValidator::id)
                    .toList()),
                ids(finalValidators.list().stream()
                    .map(SpecialistFinalOutputValidator::id)
                    .toList()),
                ids(directProjectors.list().stream()
                    .map(SpecialistDirectOutputProjector::id)
                    .toList()),
                ids(outputNormalizers.list().stream()
                    .map(SpecialistOutputNormalizer::id)
                    .toList())
            ),
            SpecialistFrameworkLimits.DEFAULT
        );
    }

    private SpecialistAuthoringCatalog.ActionOption action(
        AIActionMetaData metadata
    ) {
        return new SpecialistAuthoringCatalog.ActionOption(
            AIActionNames.normalize(metadata.getName()),
            metadata.getDisplayName(),
            metadata.getDescription(),
            metadata.getAccessMode(),
            metadata.isConfirmationRequired(),
            metadata.isGroundingEligible(),
            metadata.getRequiredParameters()
        );
    }

    private Set<String> ids(List<String> values) {
        return values.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .sorted()
            .collect(java.util.stream.Collectors.toCollection(
                LinkedHashSet::new
            ));
    }

    private Set<String> normalize(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        return values.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .map(value -> value.toLowerCase(Locale.ROOT))
            .sorted()
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
