package ai.fabric.intent.orchestration.pipeline.steps;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ActionValueSupportTest {

    @Test
    void shouldDetectMeaningfulNestedJavaValues() {
        assertThat(ActionValueSupport.hasMeaningfulJavaValue(null)).isFalse();
        assertThat(ActionValueSupport.hasMeaningfulJavaValue("   ")).isFalse();
        assertThat(ActionValueSupport.hasMeaningfulJavaValue(List.of("", "value"))).isTrue();
        assertThat(ActionValueSupport.hasMeaningfulJavaValue(Map.of("empty", List.of("   ")))).isFalse();
        assertThat(ActionValueSupport.hasMeaningfulJavaValue(Map.of("nested", Map.of("count", 0)))).isTrue();
    }

    @Test
    void shouldParseNumericValues() {
        assertThat(ActionValueSupport.numericValue(3)).isEqualTo(3.0d);
        assertThat(ActionValueSupport.numericValue(" 4.5 ")).isEqualTo(4.5d);
        assertThat(ActionValueSupport.numericValue("abc")).isNull();
        assertThat(ActionValueSupport.numericValue(null)).isNull();
    }
}
