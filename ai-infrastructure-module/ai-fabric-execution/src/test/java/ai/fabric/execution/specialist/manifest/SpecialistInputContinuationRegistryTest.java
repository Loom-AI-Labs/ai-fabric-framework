package ai.fabric.execution.specialist.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.fabric.execution.input.SpecialistInputContinuation;
import ai.fabric.execution.input.SpecialistInputRequirement;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SpecialistInputContinuationRegistryTest {

    private static final SpecialistSchemaId RESPONSE_SCHEMA =
        SpecialistSchemaId.parse("billing-amount-response@1");

    @Test
    void registersAndRequiresAnExactVersionedContinuation() {
        SpecialistInputContinuation<JsonNode> continuation =
            continuation("billing-amount-input@1", Set.of(RESPONSE_SCHEMA));
        SpecialistInputContinuationRegistry registry =
            new SpecialistInputContinuationRegistry(List.of(continuation));

        assertThat(registry.require(continuation.id()))
            .isSameAs(continuation);
        assertThat(registry.list()).containsExactly(continuation);
    }

    @Test
    void rejectsDuplicateAndMalformedExtensionIds() {
        SpecialistInputContinuation<JsonNode> first =
            continuation("billing-amount-input@1", Set.of(RESPONSE_SCHEMA));
        SpecialistInputContinuation<JsonNode> duplicate =
            continuation("billing-amount-input@1", Set.of(RESPONSE_SCHEMA));

        assertThatThrownBy(() ->
            new SpecialistInputContinuationRegistry(List.of(first, duplicate))
        )
            .isInstanceOf(SpecialistManifestException.class)
            .satisfies(error -> assertThat(
                ((SpecialistManifestException) error).reason()
            ).isEqualTo("DUPLICATE_EXTENSION_ID"));
        assertThatThrownBy(() ->
            new SpecialistInputContinuationRegistry(List.of(
                continuation("BillingAmount", Set.of(RESPONSE_SCHEMA))
            ))
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingOrNullResponseSchemasAtStartup() {
        assertThatThrownBy(() ->
            new SpecialistInputContinuationRegistry(List.of(
                continuation("empty-schemas@1", Set.of())
            ))
        )
            .isInstanceOf(SpecialistManifestException.class)
            .satisfies(error -> assertThat(
                ((SpecialistManifestException) error).reason()
            ).isEqualTo("INPUT_CONTINUATION_SCHEMA_REQUIRED"));

        LinkedHashSet<SpecialistSchemaId> schemas = new LinkedHashSet<>();
        schemas.add(RESPONSE_SCHEMA);
        schemas.add(null);
        assertThatThrownBy(() ->
            new SpecialistInputContinuationRegistry(List.of(
                continuation("null-schema@1", schemas)
            ))
        )
            .isInstanceOf(SpecialistManifestException.class)
            .satisfies(error -> assertThat(
                ((SpecialistManifestException) error).reason()
            ).isEqualTo("INPUT_CONTINUATION_SCHEMA_INVALID"));
    }

    @Test
    void rejectsMissingInputTypeAtStartup() {
        SpecialistInputContinuation<JsonNode> continuation =
            new SpecialistInputContinuation<>() {
                @Override
                public String id() {
                    return "missing-type@1";
                }

                @Override
                public Class<JsonNode> inputType() {
                    return null;
                }

                @Override
                public Set<SpecialistSchemaId> responseSchemas() {
                    return Set.of(RESPONSE_SCHEMA);
                }

                @Override
                public Optional<SpecialistInputRequirement> requiredInput(
                    JsonNode input
                ) {
                    return Optional.empty();
                }

                @Override
                public JsonNode resume(
                    JsonNode originalInput,
                    SpecialistInputRequirement requirement,
                    JsonNode response
                ) {
                    return originalInput;
                }
            };

        assertThatThrownBy(() ->
            new SpecialistInputContinuationRegistry(List.of(continuation))
        )
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("inputType");
    }

    private SpecialistInputContinuation<JsonNode> continuation(
        String id,
        Set<SpecialistSchemaId> responseSchemas
    ) {
        return new SpecialistInputContinuation<>() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Class<JsonNode> inputType() {
                return JsonNode.class;
            }

            @Override
            public Set<SpecialistSchemaId> responseSchemas() {
                return responseSchemas;
            }

            @Override
            public Optional<SpecialistInputRequirement> requiredInput(
                JsonNode input
            ) {
                return Optional.empty();
            }

            @Override
            public JsonNode resume(
                JsonNode originalInput,
                SpecialistInputRequirement requirement,
                JsonNode response
            ) {
                return originalInput;
            }
        };
    }
}
