package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.OrchestrationResultNormalizationProperties;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultDebugSnapshotStore;
import ai.fabric.intent.orchestration.OrchestrationResultNormalizer;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestrationResultNormalizationStepTest {

    @AfterEach
    void clearSnapshots() {
        OrchestrationResultDebugSnapshotStore.clear();
    }

    @Test
    void processRecordsNormalizedSnapshotWhenSnapshotsAreEnabled() {
        OrchestrationResult childError = OrchestrationResult.builder()
            .type(OrchestrationResultType.ERROR)
            .success(false)
            .message("No action handler registered for action 'unknown_action'")
            .build();

        OrchestrationResult raw = OrchestrationResult.builder()
            .type(OrchestrationResultType.COMPOUND_HANDLED)
            .success(true)
            .message("Some intents failed. See results for details.")
            .children(List.of(childError))
            .build();

        OrchestrationResultNormalizationProperties properties = new OrchestrationResultNormalizationProperties();
        properties.setDebugSnapshotEnabled(true);

        OrchestrationResultNormalizationStep step = new OrchestrationResultNormalizationStep(
            new OrchestrationResultNormalizer(),
            properties
        );

        PipelineContext context = PipelineContext.from(
                "show my order",
                OrchestrationContext.forUser("user-1").toBuilder().requestId("request-1").build()
            )
            .toBuilder()
            .intentResult(raw)
            .build();

        PipelineContext updated = step.process(context);

        assertThat(updated.getIntentResult().getType()).isEqualTo(OrchestrationResultType.ERROR);
        assertThat(updated.getIntentResult().getErrorCode())
            .isEqualTo(OrchestrationResultNormalizer.ERROR_CODE_ACTION_NOT_FOUND);

        OrchestrationResultDebugSnapshotStore.Snapshot snapshot =
            OrchestrationResultDebugSnapshotStore.getLast();
        assertThat(snapshot.requestId()).isEqualTo("request-1");
        assertThat(snapshot.type()).isEqualTo("ERROR");
        assertThat(snapshot.success()).isFalse();
        assertThat(snapshot.errorCode())
            .isEqualTo(OrchestrationResultNormalizer.ERROR_CODE_ACTION_NOT_FOUND);
    }

    @Test
    void processDoesNotRecordSnapshotWhenSnapshotsAreDisabled() {
        OrchestrationResult raw = OrchestrationResult.builder()
            .type(OrchestrationResultType.INFORMATION_PROVIDED)
            .success(true)
            .message("ok")
            .build();

        OrchestrationResultNormalizationStep step = new OrchestrationResultNormalizationStep(
            new OrchestrationResultNormalizer(),
            new OrchestrationResultNormalizationProperties()
        );

        PipelineContext context = PipelineContext.from("q", OrchestrationContext.forUser("user-1"))
            .toBuilder()
            .intentResult(raw)
            .build();

        step.process(context);

        assertThat(OrchestrationResultDebugSnapshotStore.getLast()).isNull();
    }
}
