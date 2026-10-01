package ai.fabric.intent.action;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ActionGroundingSupportTest {

    @Test
    void shouldRequireAdditionalGroundingForCanonicalEmptyList() {
        ActionResult result = ActionResult.builder()
            .success(true)
            .data(ActionListPayload.of(List.of()))
            .build();

        assertThat(ActionGroundingSupport.requiresAdditionalGrounding(result)).isTrue();
    }

    @Test
    void shouldHonorExplicitInsufficientDecisionForNonEmptyPayload() {
        ActionResult result = ActionResult.builder()
            .success(true)
            .groundingSufficiency(ActionGroundingSufficiency.INSUFFICIENT)
            .data(ActionObjectPayload.of(Map.of("status", "partial")))
            .build();

        assertThat(ActionGroundingSupport.requiresAdditionalGrounding(result)).isTrue();
    }

    @Test
    void shouldHonorExplicitSufficientDecisionForEmptyPayload() {
        ActionResult result = ActionResult.builder()
            .success(true)
            .groundingSufficiency(ActionGroundingSufficiency.SUFFICIENT)
            .data(ActionListPayload.of(List.of()))
            .build();

        assertThat(ActionGroundingSupport.requiresAdditionalGrounding(result)).isFalse();
    }

    @Test
    void shouldNotRequireAdditionalGroundingForCanonicalNonEmptyList() {
        ActionResult result = ActionResult.builder()
            .success(true)
            .data(ActionListPayload.of(List.of(Map.of("id", "item-1"))))
            .build();

        assertThat(ActionGroundingSupport.requiresAdditionalGrounding(result)).isFalse();
    }
}
