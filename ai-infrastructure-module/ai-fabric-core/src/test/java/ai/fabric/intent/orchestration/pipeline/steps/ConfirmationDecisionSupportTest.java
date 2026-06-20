package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.intent.orchestration.pipeline.steps.ConfirmationDecisionSupport.ConfirmationResolutionDecision;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfirmationDecisionSupportTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldTreatCurrentParamsAsSubsetOfPendingParams() {
        Map<String, Object> current = Map.of(
            "sku", " SKU-1 ",
            "quantity", 1,
            "items", List.of(Map.of("id", "A"))
        );
        Map<String, Object> pending = Map.of(
            "sku", "sku-1",
            "quantity", 1.0d,
            "items", List.of(Map.of("id", "B"), Map.of("id", "a")),
            "note", "extra pending value"
        );

        assertThat(ConfirmationDecisionSupport.actionParamsEquivalentOrSubset(current, pending)).isTrue();
        assertThat(ConfirmationDecisionSupport.actionParamsEquivalentOrSubset(Map.of(), pending)).isTrue();
    }

    @Test
    void shouldRejectDifferentOrUnknownCurrentParams() {
        assertThat(ConfirmationDecisionSupport.actionParamsEquivalentOrSubset(
            Map.of("sku", "SKU-2"),
            Map.of("sku", "SKU-1")
        )).isFalse();

        assertThat(ConfirmationDecisionSupport.actionParamsEquivalentOrSubset(
            Map.of("unknown", "value"),
            Map.of("sku", "SKU-1")
        )).isFalse();
    }

    @Test
    void shouldParsePositiveAndNegativeDecisionAliases() {
        assertThat(ConfirmationDecisionSupport.parseConfirmationDecision("{\"decision\":\"confirmed\"}", mapper))
            .isEqualTo(ConfirmationResolutionDecision.POSITIVE);
        assertThat(ConfirmationDecisionSupport.parseConfirmationDecision("{\"decision\":\"cancel\"}", mapper))
            .isEqualTo(ConfirmationResolutionDecision.NEGATIVE);
        assertThat(ConfirmationDecisionSupport.parseConfirmationDecision("{\"decision\":\"maybe\"}", mapper))
            .isEqualTo(ConfirmationResolutionDecision.UNKNOWN);
        assertThat(ConfirmationDecisionSupport.parseConfirmationDecision("not-json", mapper))
            .isEqualTo(ConfirmationResolutionDecision.UNKNOWN);
    }

    @Test
    void shouldParseWrappedConfirmationJson() {
        String wrapped = """
            Model rationale omitted.
            ```json
            {"decision":"approved","confidence":0.64}
            ```
            """;

        assertThat(ConfirmationDecisionSupport.parseConfirmationDecision(wrapped, mapper))
            .isEqualTo(ConfirmationResolutionDecision.POSITIVE);
        assertThat(ConfirmationDecisionSupport.parseConfirmationConfidence(wrapped, mapper))
            .isEqualTo(0.64d);
    }

    @Test
    void shouldClampParsedConfidence() {
        assertThat(ConfirmationDecisionSupport.parseConfirmationConfidence("{\"confidence\":0.72}", mapper))
            .isEqualTo(0.72d);
        assertThat(ConfirmationDecisionSupport.parseConfirmationConfidence("{\"confidence\":2.0}", mapper))
            .isEqualTo(1.0d);
        assertThat(ConfirmationDecisionSupport.parseConfirmationConfidence("{\"confidence\":-1.0}", mapper))
            .isEqualTo(0.0d);
        assertThat(ConfirmationDecisionSupport.parseConfirmationConfidence("not-json", mapper))
            .isEqualTo(0.0d);
    }
}
