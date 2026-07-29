package ai.fabric.execution.specialist.manifest;

import ai.fabric.intent.action.ActionAccessMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Safe deployment catalogue for trusted specialist-authoring applications.
 *
 * <p>This is descriptive inventory only. It grants no runtime authority.</p>
 */
public record SpecialistAuthoringCatalog(
    Set<String> modes,
    Set<String> vectorSpaces,
    List<ActionOption> actions,
    List<SchemaOption> schemas,
    Set<String> promptProfiles,
    ExtensionOptions extensions,
    SpecialistFrameworkLimits limits
) {
    public SpecialistAuthoringCatalog {
        modes = immutable(modes);
        vectorSpaces = immutable(vectorSpaces);
        actions = actions == null ? List.of() : List.copyOf(actions);
        schemas = schemas == null ? List.of() : List.copyOf(schemas);
        promptProfiles = immutable(promptProfiles);
        extensions = Objects.requireNonNull(
            extensions,
            "extensions are required"
        );
        limits = Objects.requireNonNull(limits, "limits are required");
    }

    public record ActionOption(
        String name,
        String displayName,
        String description,
        ActionAccessMode accessMode,
        boolean confirmationRequired,
        boolean groundingEligible,
        Set<String> requiredParameters
    ) {
        public ActionOption {
            name = requireText(name, "name");
            displayName = optionalText(displayName);
            description = optionalText(description);
            Objects.requireNonNull(accessMode, "accessMode is required");
            requiredParameters = immutable(requiredParameters);
        }

        public boolean requestableRead() {
            return accessMode == ActionAccessMode.READ;
        }

        public boolean proposableWrite() {
            return accessMode != ActionAccessMode.READ
                && confirmationRequired;
        }
    }

    public record SchemaOption(
        String id,
        SpecialistSchemaDirection direction
    ) {
        public SchemaOption {
            id = requireText(id, "id");
            Objects.requireNonNull(direction, "direction is required");
        }
    }

    public record ExtensionOptions(
        Set<String> groundingValidators,
        Set<String> finalOutputValidators,
        Set<String> directOutputProjectors,
        Set<String> outputNormalizers
    ) {
        public ExtensionOptions {
            groundingValidators = immutable(groundingValidators);
            finalOutputValidators = immutable(finalOutputValidators);
            directOutputProjectors = immutable(directOutputProjectors);
            outputNormalizers = immutable(outputNormalizers);
        }
    }

    private static Set<String> immutable(Set<String> values) {
        return values == null || values.isEmpty()
            ? Set.of()
            : Set.copyOf(new LinkedHashSet<>(values));
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(
            value,
            field + " is required"
        ).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
