package ai.fabric.intent.actiondraft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ActionDraftSubmissionTest {

    @Test
    void keepsExplicitNullsAndArrayPositionsWithoutInventingAbsentParameters() {
        var params = new java.util.LinkedHashMap<String, Object>();
        params.put("value", null);
        params.put("items", java.util.Arrays.asList(null, 2));
        var submission = new ActionDraftSubmission("update_value", params);
        assertThat(submission.parameters()).containsEntry("value", null).doesNotContainKey("absent");
        assertThat((java.util.List<?>) submission.parameters().get("items")).hasSize(2).first().isNull();
    }

    @Test
    void shouldCopyInputAndKeepValuesOutOfToString() {
        ActionDraftSubmission submission = new ActionDraftSubmission(
            " request_callback ",
            Map.of("name", "Private Customer Name")
        );

        assertThat(submission.action()).isEqualTo("request_callback");
        assertThat(submission.parameters())
            .containsEntry("name", "Private Customer Name")
            .isUnmodifiable();
        assertThat(submission.toString())
            .contains("request_callback", "name")
            .doesNotContain("Private Customer Name");
    }

    @Test
    void shouldRejectUnsupportedAndDeeplyNestedValues() {
        assertThatThrownBy(() -> new ActionDraftSubmission(
            "request_callback",
            Map.of("value", new Object())
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ActionDraftSubmission(
            "request_callback",
            Map.of("value", Map.of(
                "a", Map.of(
                    "b", Map.of(
                        "c", Map.of(
                            "d", Map.of("e", "too deep")
                        )
                    )
                )
            ))
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
