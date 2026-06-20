package ai.fabric.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VectorRecordLifecycleMetadataTest {

    @Test
    void enrichForStoreAddsCreatedAndUpdatedTimestampsWithoutMutatingInput() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("tenant", "retail");

        Map<String, Object> enriched = VectorRecordLifecycleMetadata.enrichForStore(input);

        assertThat(input).doesNotContainKeys(
            VectorRecordLifecycleMetadata.INDEXED_CREATED_AT_KEY,
            VectorRecordLifecycleMetadata.INDEXED_UPDATED_AT_KEY
        );
        assertThat(enriched)
            .containsEntry("tenant", "retail")
            .containsKeys(
                VectorRecordLifecycleMetadata.INDEXED_CREATED_AT_KEY,
                VectorRecordLifecycleMetadata.INDEXED_UPDATED_AT_KEY
            );
        assertThat(VectorRecordLifecycleMetadata.readCreatedAt(enriched)).isPresent();
        assertThat(VectorRecordLifecycleMetadata.readUpdatedAt(enriched)).isPresent();
    }

    @Test
    void enrichForUpdatePreservesExistingCreatedAtAndRefreshesUpdatedAt() {
        LocalDateTime createdAt = LocalDateTime.parse("2026-06-19T10:00:00");
        Map<String, Object> input = Map.of(
            VectorRecordLifecycleMetadata.INDEXED_CREATED_AT_KEY, createdAt.toString(),
            VectorRecordLifecycleMetadata.INDEXED_UPDATED_AT_KEY, "2026-06-19T10:01:00"
        );

        Map<String, Object> enriched = VectorRecordLifecycleMetadata.enrichForUpdate(input, LocalDateTime.parse("2026-06-19T09:00:00"));

        assertThat(enriched.get(VectorRecordLifecycleMetadata.INDEXED_CREATED_AT_KEY)).isEqualTo(createdAt.toString());
        assertThat(VectorRecordLifecycleMetadata.readUpdatedAt(enriched).orElseThrow())
            .isAfter(LocalDateTime.parse("2026-06-19T10:01:00"));
    }

    @Test
    void enrichForUpdateUsesCreatedAtHintWhenMetadataDoesNotContainOne() {
        LocalDateTime createdAt = LocalDateTime.parse("2026-06-19T10:00:00");

        Map<String, Object> enriched = VectorRecordLifecycleMetadata.enrichForUpdate(Map.of("tenant", "retail"), createdAt);

        assertThat(enriched.get(VectorRecordLifecycleMetadata.INDEXED_CREATED_AT_KEY)).isEqualTo(createdAt.toString());
        assertThat(VectorRecordLifecycleMetadata.readUpdatedAt(enriched)).isPresent();
    }
}
