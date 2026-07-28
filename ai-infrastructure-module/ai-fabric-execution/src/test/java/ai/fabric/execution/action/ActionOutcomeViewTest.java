package ai.fabric.execution.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ActionOutcomeViewTest {

    @Test
    void deeplyCopiesAndFreezesJsonSafeProjection() {
        List<Object> nested = new ArrayList<>(List.of("BILLING"));
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("updated", true);
        source.put("types", nested);

        ActionOutcomeView view = new ActionOutcomeView(
            "update_address",
            "Address updated.",
            source
        );
        nested.add("SHIPPING");
        source.put("internalId", "secret");

        assertThat(view.data())
            .containsEntry("updated", true)
            .doesNotContainKey("internalId");
        assertThat(view.data().get("types"))
            .isEqualTo(List.of("BILLING"));
        assertThatThrownBy(() -> view.data().put("other", true))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsArbitraryDomainObjectsAndOversizedMessages() {
        assertThatThrownBy(() -> new ActionOutcomeView(
                "update_address",
                "Address updated.",
                Map.of("entity", new Object())
            ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("JSON-safe");
        assertThatThrownBy(() -> new ActionOutcomeView(
                "update_address",
                "x".repeat(1001),
                Map.of()
            ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("1000");
    }
}
