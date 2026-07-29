package ai.fabric.execution.specialist.client;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.fabric.execution.specialist.manifest.SpecialistResourceMetadata;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaDefinition;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaDirection;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SpecialistSchemaBindingValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SpecialistSchemaBindingValidator validator =
        new SpecialistSchemaBindingValidator(objectMapper);

    @Test
    void acceptsStringPropertyConstrainedByPinnedManifestEnum() {
        SpecialistSchemaDefinition schema = schema("""
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["targetSpecialist"],
              "properties": {
                "targetSpecialist": {
                  "type": "string",
                  "enum": [
                    "account-resolver-read@1",
                    "billing-resolution-advisor@1"
                  ]
                }
              }
            }
            """);

        assertThatCode(() ->
            validator.validate(schema, StringRoute.class, "output")
        ).doesNotThrowAnyException();
    }

    @Test
    void stillRejectsJavaPropertyThatCannotRepresentStringValues() {
        SpecialistSchemaDefinition schema = schema("""
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["targetSpecialist"],
              "properties": {
                "targetSpecialist": {
                  "type": "string",
                  "enum": ["READ", "BILLING"]
                }
              }
            }
            """);

        assertThatThrownBy(() ->
            validator.validate(schema, NumericRoute.class, "output")
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("expects string");
    }

    private SpecialistSchemaDefinition schema(String json) {
        try {
            return new SpecialistSchemaDefinition(
                "ai.fabric/v1",
                "SpecialistSchema",
                new SpecialistResourceMetadata("delegation-route", "1"),
                new SpecialistSchemaSpec(
                    SpecialistSchemaDirection.OUTPUT,
                    "2020-12",
                    objectMapper.readTree(json)
                )
            );
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    record StringRoute(String targetSpecialist) {}

    record NumericRoute(Integer targetSpecialist) {}
}
