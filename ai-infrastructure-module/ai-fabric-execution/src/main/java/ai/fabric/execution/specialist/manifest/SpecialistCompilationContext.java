package ai.fabric.execution.specialist.manifest;

import ai.fabric.execution.specialist.SpecialistDefinitionValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import java.util.Set;

public record SpecialistCompilationContext(
    SpecialistJsonSchemaRegistry schemaRegistry,
    SpecialistPromptProfileRegistry promptProfileRegistry,
    SpecialistGroundingValidatorRegistry groundingValidatorRegistry,
    SpecialistFinalOutputValidatorRegistry finalOutputValidatorRegistry,
    SpecialistDirectOutputProjectorRegistry directOutputProjectorRegistry,
    SpecialistOutputNormalizerRegistry outputNormalizerRegistry,
    SpecialistJsonSchemaValidator schemaValidator,
    SpecialistDefinitionValidator definitionValidator,
    CanonicalJsonSupport canonicalJson,
    ObjectMapper objectMapper,
    Set<String> iterativeModes,
    String source,
    String contentHash
) {
    public SpecialistCompilationContext {
        Objects.requireNonNull(schemaRegistry, "schemaRegistry is required");
        Objects.requireNonNull(
            promptProfileRegistry,
            "promptProfileRegistry is required"
        );
        Objects.requireNonNull(
            groundingValidatorRegistry,
            "groundingValidatorRegistry is required"
        );
        Objects.requireNonNull(
            finalOutputValidatorRegistry,
            "finalOutputValidatorRegistry is required"
        );
        Objects.requireNonNull(
            directOutputProjectorRegistry,
            "directOutputProjectorRegistry is required"
        );
        Objects.requireNonNull(
            outputNormalizerRegistry,
            "outputNormalizerRegistry is required"
        );
        Objects.requireNonNull(
            schemaValidator,
            "schemaValidator is required"
        );
        Objects.requireNonNull(
            definitionValidator,
            "definitionValidator is required"
        );
        Objects.requireNonNull(canonicalJson, "canonicalJson is required");
        Objects.requireNonNull(objectMapper, "objectMapper is required");
        iterativeModes = iterativeModes == null
            ? Set.of()
            : Set.copyOf(iterativeModes);
        source = requireText(source, "source");
        contentHash = requireText(contentHash, "contentHash");
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
}
