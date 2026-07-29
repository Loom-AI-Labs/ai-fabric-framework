package ai.fabric.execution.specialist.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.execution.specialist.SpecialistDefinition;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonSchemaSpecialistAdapterTest {

    @SuppressWarnings("unchecked")
    private final SpecialistDefinition<JsonNode, JsonNode> definition =
        (SpecialistDefinition<JsonNode, JsonNode>)
            new DefaultSpecialistManifestCompiler()
                .compile(
                    ManifestTestFixtures.manifest(),
                    ManifestTestFixtures.compilationContext()
                )
                .specialist()
                .definition();

    @Test
    void validatesAndCanonicallyRendersSchemaBoundInput() throws Exception {
        JsonNode input = ManifestTestFixtures.objectMapper().readTree(
            "{\"question\":\"How do I reset MFA?\"}"
        );

        definition.inputAdapter().validate(input);

        assertThat(definition.inputAdapter().renderModelInput(input))
            .isEqualTo("Application question:\nHow do I reset MFA?");
        assertThat(definition.inputAdapter().conversationInput(input))
            .isEqualTo("How do I reset MFA?");
    }

    @Test
    void rejectsAdditionalInputAndOutputFields() throws Exception {
        JsonNode invalidInput = ManifestTestFixtures.objectMapper().readTree(
            "{\"question\":\"Help\",\"tenantId\":\"attacker\"}"
        );
        JsonNode invalidOutput = ManifestTestFixtures.objectMapper().readTree(
            "{\"answer\":\"Use approved recovery.\",\"secret\":\"raw\"}"
        );

        assertThatThrownBy(() ->
            definition.inputAdapter().validate(invalidInput)
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("support-question@1");
        assertThatThrownBy(() ->
            definition.outputAdapter().validate(invalidOutput)
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("support-answer@1");
    }

    @Test
    void enforcesGroundingFromAnAllowedVectorSpace() {
        OrchestrationResult result = OrchestrationResult.builder()
            .type(OrchestrationResultType.INFORMATION_PROVIDED)
            .success(true)
            .message("Grounded answer")
            .build();

        assertThatThrownBy(() ->
            definition.outputAdapter().validateGrounding(result, List.of())
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cited evidence");

        definition.outputAdapter().validateGrounding(
            result,
            List.of(new AIEvidenceReference(
                "mfa-1",
                "Approved MFA recovery policy.",
                0.9,
                "policy",
                null,
                "support-policy",
                Map.of()
            ))
        );
    }

    @Test
    void rejectsExternalSchemaReferencesBeforeRuntimeValidation() {
        JsonNode externalSchema = ManifestTestFixtures.objectMapper()
            .createObjectNode()
            .put("$ref", "https://example.test/schema.json");
        SpecialistSchemaDefinition definition =
            new SpecialistSchemaDefinition(
                "ai.fabric/v1",
                "SpecialistSchema",
                new SpecialistResourceMetadata("external-schema", "1"),
                new SpecialistSchemaSpec(
                    SpecialistSchemaDirection.INPUT,
                    "2020-12",
                    externalSchema
                )
            );

        assertThatThrownBy(() ->
            new SpecialistJsonSchemaValidator().validateDefinition(
                definition,
                "external.yml#1"
            )
        )
            .isInstanceOfSatisfying(
                SpecialistManifestException.class,
                failure -> assertThat(failure.reason())
                    .isEqualTo("SCHEMA_EXTERNAL_REFERENCE_FORBIDDEN")
            );
    }
}
