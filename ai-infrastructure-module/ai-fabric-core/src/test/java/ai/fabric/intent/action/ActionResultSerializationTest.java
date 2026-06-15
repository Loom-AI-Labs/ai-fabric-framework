package ai.fabric.intent.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ActionResultSerializationTest {

    @Test
    void shouldRoundTripPinnedTargets() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        ActionResult input = ActionResult.builder()
            .success(true)
            .message("ok")
            .pinnedTargets(List.of(
                new ActionTargetRef("85", "product", "snippet", Map.of("sku", "SKU-85"))
            ))
            .build();

        String json = mapper.writeValueAsString(input);
        assertThat(json).contains("pinnedTargets");

        ActionResult output = mapper.readValue(json, ActionResult.class);
        assertThat(output.isSuccess()).isTrue();
        assertThat(output.getPinnedTargets()).hasSize(1);
        assertThat(output.getPinnedTargets().getFirst().id()).isEqualTo("85");
        assertThat(output.getPinnedTargets().getFirst().vectorSpace()).isEqualTo("product");
    }
}
