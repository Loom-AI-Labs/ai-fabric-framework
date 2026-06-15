package ai.fabric.dto;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IntentTest {

    @Test
    void setActionParamsDropsNullKeysAndValues() {
        Intent intent = new Intent();

        Map<String, Object> params = new HashMap<>();
        params.put("ok", 123);
        params.put("nullValue", null);
        params.put(null, "nullKey");

        intent.setActionParams(params);

        assertThat(intent.getActionParams())
            .containsEntry("ok", 123)
            .doesNotContainKey("nullValue")
            .doesNotContainKey(null);
    }
}

