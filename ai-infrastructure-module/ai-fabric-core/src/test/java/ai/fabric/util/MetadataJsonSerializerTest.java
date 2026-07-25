package ai.fabric.util;

import ai.fabric.dto.AIEntityConfig;
import ai.fabric.dto.AIMetadataField;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataJsonSerializerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    @Test
    void serializesMetadataAsValidJsonStrings() throws Exception {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("quote", "He said \"ready\"");
        metadata.put("path", "C:\\ai\\fabric");
        metadata.put("line", "first\nsecond");
        metadata.put("tab", "one\ttwo");
        metadata.put("control", "low" + (char) 0x01 + "char");

        String json = MetadataJsonSerializer.serialize(metadata);

        Map<String, String> parsed = parse(json);
        assertThat(parsed)
            .containsEntry("quote", "He said \"ready\"")
            .containsEntry("path", "C:\\ai\\fabric")
            .containsEntry("line", "first\nsecond")
            .containsEntry("tab", "one\ttwo")
            .containsEntry("control", "low" + (char) 0x01 + "char");
    }

    @Test
    void escapesMetadataKeysAndPreservesNullValueContract() throws Exception {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("line\nkey", "value");
        metadata.put("path\\key", null);

        Map<String, String> parsed = parse(MetadataJsonSerializer.serialize(metadata));

        assertThat(parsed)
            .containsEntry("line\nkey", "value")
            .containsEntry("path\\key", "");
    }

    @Test
    void configMetadataFieldsAreSerializedFirstInConfiguredOrder() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("remaining", "after");
        metadata.put("tenant", "retail");
        metadata.put("rank", 7);

        AIEntityConfig config = AIEntityConfig.builder()
            .metadataFields(List.of(
                AIMetadataField.builder().name("rank").build(),
                AIMetadataField.builder().name("tenant").build()
            ))
            .build();

        String json = MetadataJsonSerializer.serialize(metadata, config);

        assertThat(json).isEqualTo("{\"rank\":\"7\",\"tenant\":\"retail\",\"remaining\":\"after\"}");
    }

    @Test
    void nonLinkedMetadataIsSerializedInStableKeyOrder() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("zeta", "last");
        metadata.put("alpha", "first");
        metadata.put("middle", null);

        String json = MetadataJsonSerializer.serialize(metadata);

        assertThat(json).isEqualTo("{\"alpha\":\"first\",\"middle\":\"\",\"zeta\":\"last\"}");
    }

    @Test
    void serializesEmptyMetadataAsEmptyJsonObject() throws JsonProcessingException {
        assertThat(OBJECT_MAPPER.readTree(MetadataJsonSerializer.serialize(null)).isObject()).isTrue();
        assertThat(OBJECT_MAPPER.readTree(MetadataJsonSerializer.serialize(Map.of())).isObject()).isTrue();
        assertThat(MetadataJsonSerializer.serialize(null)).isEqualTo("{}");
        assertThat(MetadataJsonSerializer.serialize(Map.of())).isEqualTo("{}");
    }

    private static Map<String, String> parse(String json) throws JsonProcessingException {
        return OBJECT_MAPPER.readValue(json, STRING_MAP);
    }
}
