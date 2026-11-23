package ai.fabric.execution.specialist.manifest;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SpecialistJsonSchemaValidatorTest {

    private final ObjectMapper mapper = ManifestTestFixtures.objectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum"})
    void nullableNumberBoundsCompileAndStillRejectOutOfRangeValues(String keyword) throws Exception {
        var schema = mapper.createObjectNode();
        schema.putArray("type").add("number").add("null");
        schema.put(keyword, 10);
        var definition = new SpecialistSchemaDefinition(
            "ai.fabric/v1", "SpecialistSchema", new SpecialistResourceMetadata("bounded-number", "1"),
            new SpecialistSchemaSpec(SpecialistSchemaDirection.INPUT, "2020-12", schema)
        );
        var validator = new SpecialistJsonSchemaValidator();

        assertThatCode(() -> validator.validateDefinition(definition, "test"))
            .doesNotThrowAnyException();
        validator.validate(definition, mapper.nullNode());
        boolean minimum = keyword.equals("minimum") || keyword.equals("exclusiveMinimum");
        validator.validate(definition, mapper.readTree(minimum ? "11" : "9"));
        assertThatThrownBy(() -> validator.validate(definition, mapper.readTree(minimum ? "9" : "11")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not satisfy schema");
        if (keyword.startsWith("exclusive")) {
            assertThatThrownBy(() -> validator.validate(definition, mapper.readTree("10")))
                .isInstanceOf(IllegalArgumentException.class);
        } else {
            validator.validate(definition, mapper.readTree("10"));
        }
        assertThatThrownBy(() -> validator.validate(definition, mapper.readTree("\"11\"")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
