package ai.fabric.intent.orchestration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestrationResultDebugSnapshotStoreTest {

    @AfterEach
    void clearSnapshots() {
        OrchestrationResultDebugSnapshotStore.clear();
    }

    @Test
    void recordCapturesOnlySafeProviderAgnosticDiagnostics() {
        OrchestrationResult result = OrchestrationResult.builder()
            .type(OrchestrationResultType.ERROR)
            .success(false)
            .errorCode("ACTION_NOT_FOUND")
            .message("User asked about SSN 123-45-6789")
            .data(Map.of("rawProviderPayload", "customer secret payload"))
            .metadata(Map.of(
                "extractionDiagnostics", Map.of(
                    "extractionPath", "repair",
                    "llmCalls", "2",
                    "attempts", List.of(Map.of("issueCodes", List.of("STRUCTURAL_JSON_ERROR")))
                ),
                "intentMetadata", Map.of(
                    "normalization", Map.of("appliedRules", List.of("child_error_bubbled"))
                )
            ))
            .build();

        OrchestrationResultDebugSnapshotStore.record("request-1", result);

        OrchestrationResultDebugSnapshotStore.Snapshot snapshot =
            OrchestrationResultDebugSnapshotStore.getLast();

        assertThat(snapshot.type()).isEqualTo("ERROR");
        assertThat(snapshot.success()).isFalse();
        assertThat(snapshot.errorCode()).isEqualTo("ACTION_NOT_FOUND");
        assertThat(snapshot.extractionPath()).isEqualTo("repair");
        assertThat(snapshot.llmCalls()).isEqualTo(2);
        assertThat(snapshot.issueCodes()).containsExactly("STRUCTURAL_JSON_ERROR");
        assertThat(snapshot.normalizationRules()).containsExactly("child_error_bubbled");
        assertThat(snapshot.toString())
            .doesNotContain("SSN")
            .doesNotContain("123-45-6789")
            .doesNotContain("customer secret payload");
    }
}
