package ai.fabric.execution.specialist.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import java.util.function.Function;

public interface SpecialistOutputNormalizer {

    String id();

    JsonNode normalize(SpecialistOutputNormalizationContext context);

    static SpecialistOutputNormalizer named(
        String id,
        Function<SpecialistOutputNormalizationContext, JsonNode> normalizer
    ) {
        String normalized = SpecialistExtensionRegistrySupport.requireId(id);
        Function<SpecialistOutputNormalizationContext, JsonNode> required =
            Objects.requireNonNull(normalizer, "normalizer is required");
        return new SpecialistOutputNormalizer() {
            @Override
            public String id() {
                return normalized;
            }

            @Override
            public JsonNode normalize(
                SpecialistOutputNormalizationContext context
            ) {
                return required.apply(context);
            }
        };
    }
}
