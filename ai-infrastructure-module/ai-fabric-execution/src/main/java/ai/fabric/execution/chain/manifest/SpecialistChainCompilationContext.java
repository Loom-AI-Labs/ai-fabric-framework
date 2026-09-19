package ai.fabric.execution.chain.manifest;

import ai.fabric.execution.config.AIExecutionProperties;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.execution.specialist.client.SpecialistClientFactory;
import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import ai.fabric.execution.specialist.manifest.SpecialistJsonSchemaRegistry;
import ai.fabric.execution.specialist.manifest.SpecialistJsonSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;

/** Reviewed startup dependencies used to compile declarative chains. */
public record SpecialistChainCompilationContext(
    SpecialistRegistry specialistRegistry,
    SpecialistClientFactory clientFactory,
    SpecialistJsonSchemaRegistry schemaRegistry,
    SpecialistJsonSchemaValidator schemaValidator,
    CanonicalJsonSupport canonicalJson,
    ObjectMapper objectMapper,
    AIExecutionProperties.SpecialistChains deployment
) {
    public SpecialistChainCompilationContext {
        Objects.requireNonNull(
            specialistRegistry,
            "specialistRegistry is required"
        );
        Objects.requireNonNull(clientFactory, "clientFactory is required");
        Objects.requireNonNull(schemaRegistry, "schemaRegistry is required");
        Objects.requireNonNull(schemaValidator, "schemaValidator is required");
        Objects.requireNonNull(canonicalJson, "canonicalJson is required");
        Objects.requireNonNull(objectMapper, "objectMapper is required");
        Objects.requireNonNull(deployment, "deployment is required");
    }

    public SpecialistChainDeclarativeBounds bounds() {
        return new SpecialistChainDeclarativeBounds(
            deployment.getMaxJsonPointerCharacters(),
            deployment.getMaxJsonPointerDepth(),
            ai.fabric.execution.chain.SpecialistChainManagerInput
                .MAX_CONTEXT_VALUES,
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
