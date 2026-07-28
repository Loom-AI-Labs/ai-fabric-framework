package ai.fabric.execution.action;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class MicrometerActionProposalMetricsTest {

    @Test
    void recordsOnlyBoundedReceiptEventTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerActionProposalMetrics metrics =
            new MicrometerActionProposalMetrics(registry);

        metrics.record(
            "decision_replayed",
            "update_address",
            ActionProposalReceiptStatus.SUCCEEDED
        );

        assertThat(registry.get("ai.fabric.execution.action.receipts")
            .tags(
                "event", "decision_replayed",
                "action", "update_address",
                "status", "SUCCEEDED"
            )
            .counter()
            .count())
            .isEqualTo(1.0);
        assertThat(registry.getMeters()).singleElement().satisfies(meter ->
            assertThat(meter.getId().getTags())
                .extracting(tag -> tag.getKey())
                .containsExactlyInAnyOrder("event", "action", "status")
        );
    }
}
