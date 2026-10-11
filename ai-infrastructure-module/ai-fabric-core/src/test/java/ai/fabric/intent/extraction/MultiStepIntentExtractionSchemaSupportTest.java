package ai.fabric.intent.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MultiStepIntentExtractionSchemaSupportTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void classificationRequiresANonNullObligationIdAndEverySemanticDecision() throws Exception {
        JsonNode schema = objectMapper.readTree(
            MultiStepIntentExtractionSchemaSupport.classificationSchema(objectMapper)
        );
        JsonNode intent = schema.at("/properties/intents/items");

        assertThat(intent.at("/properties/obligationId/type").asText()).isEqualTo("string");
        assertThat(intent.get("required")).extracting(JsonNode::asText).contains(
            "obligationId",
            "requiresRetrieval",
            "requiresGeneration",
            "requiresTargetResolution",
            "needsAdvancedRAG"
        );
        assertThat(intent.at("/additionalProperties").asBoolean()).isFalse();
    }

    @Test
    void actionSelectionRequiresOneBoundedMappingPerActionIntent() throws Exception {
        JsonNode schema = objectMapper.readTree(
            MultiStepIntentExtractionSchemaSupport.actionSelectionSchema(
                objectMapper,
                List.of(1, 4),
                List.of("search_inventory", "compare_inventory")
            )
        );
        JsonNode mappings = schema.at("/properties/mappings");

        assertThat(mappings.get("minItems").asInt()).isEqualTo(2);
        assertThat(mappings.get("maxItems").asInt()).isEqualTo(2);
        assertThat(mappings.at("/items/properties/intentIndex/enum"))
            .extracting(JsonNode::asInt)
            .containsExactly(1, 4);
        assertThat(mappings.at("/items/properties/selectedAction/enum"))
            .extracting(JsonNode::isNull, JsonNode::asText)
            .hasSize(3);
    }
}
