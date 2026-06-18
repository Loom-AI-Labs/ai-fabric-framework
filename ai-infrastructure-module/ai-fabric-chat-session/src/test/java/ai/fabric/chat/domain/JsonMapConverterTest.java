package ai.fabric.chat.domain;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonMapConverterTest {

    private final JsonMapConverter converter = new JsonMapConverter();

    @Test
    void shouldRoundTripMetadataMapAsImmutableEntityAttribute() {
        String json = converter.convertToDatabaseColumn(Map.of(
            "action", "get_order",
            "count", 2,
            "nested", Map.of("id", "order-1")
        ));

        Map<String, Object> metadata = converter.convertToEntityAttribute(json);

        assertThat(metadata)
            .containsEntry("action", "get_order")
            .containsEntry("count", 2);
        assertThat(metadata.get("nested")).isInstanceOf(Map.class);
        assertThatThrownBy(() -> metadata.put("new", "value"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldTreatNullAndBlankValuesAsEmptyMetadata() {
        assertThat(converter.convertToDatabaseColumn(Map.of())).isNull();
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isEmpty();
        assertThat(converter.convertToEntityAttribute("  ")).isEmpty();
    }

    @Test
    void shouldFailFastForMalformedJson() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("{not-json"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unable to deserialize metadata map");
    }
}
