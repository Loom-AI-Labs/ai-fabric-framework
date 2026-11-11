package ai.fabric.dto;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IntentTest {

    @Test
    void setActionParamsPreservesExplicitNullButDropsNullKeys() {
        Intent intent = new Intent();

        Map<String, Object> params = new HashMap<>();
        params.put("ok", 123);
        params.put("nullValue", null);
        params.put(null, "nullKey");

        intent.setActionParams(params);

        assertThat(intent.getActionParams())
            .containsEntry("ok", 123)
            .containsEntry("nullValue", null)
            .doesNotContainKey("absent")
            .doesNotContainKey(null);
        intent.normalize();
        assertThat(intent.getActionParams()).containsEntry("nullValue", null).doesNotContainKey("absent");
    }

    @Test
    void jsonAndBuilderNormalizationKeepNullDistinctFromAbsent() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var intent = json.readValue("{\"actionParams\":{\"clear\":null,\"items\":[null,2]}}", Intent.class);
        intent.normalize();
        var restored = json.readValue(json.writeValueAsString(intent), Intent.class);
        assertThat(restored.getActionParams()).containsEntry("clear", null).doesNotContainKey("absent");
        var built = Intent.builder().actionParams(java.util.Collections.singletonMap("clear", null)).build();
        built.normalize();
        assertThat(built.getActionParams()).containsEntry("clear", null).doesNotContainKey("absent");
    }
}

