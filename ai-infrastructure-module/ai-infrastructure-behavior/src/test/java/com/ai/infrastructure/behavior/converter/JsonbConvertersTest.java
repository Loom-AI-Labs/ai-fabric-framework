package com.ai.infrastructure.behavior.converter;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonbConvertersTest {

    private final JsonbListConverter listConverter = new JsonbListConverter();
    private final JsonbMapConverter mapConverter = new JsonbMapConverter();

    @Test
    void listConverter_roundTrips() {
        List<String> original = List.of("a", "b");
        String db = listConverter.convertToDatabaseColumn(original);
        List<String> back = listConverter.convertToEntityAttribute(db);

        assertThat(back).containsExactlyElementsOf(original);
    }

    @Test
    void mapConverter_roundTrips() {
        Map<String, Object> original = Map.of("k1", 1, "k2", "v");
        String db = mapConverter.convertToDatabaseColumn(original);
        Map<String, Object> back = mapConverter.convertToEntityAttribute(db);

        assertThat(back).containsEntry("k1", 1).containsEntry("k2", "v");
    }

    @Test
    void convertersHandleNullOrEmpty() {
        assertThat(listConverter.convertToDatabaseColumn(null)).isNull();
        assertThat(listConverter.convertToEntityAttribute("")).isEmpty();

        assertThat(mapConverter.convertToDatabaseColumn(Map.of())).isNull();
        assertThat(mapConverter.convertToEntityAttribute("")).isEmpty();
    }
}
