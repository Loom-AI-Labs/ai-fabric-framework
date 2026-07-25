package ai.fabric.indexing.model;

import ai.fabric.indexing.api.AIContextDataType;
import ai.fabric.indexing.api.AIIndexWorkType;
import ai.fabric.indexing.api.AIProcessOperation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AIIndexDocumentTest {

    private static final String HASH = "a".repeat(64);

    @Test
    void acceptsClassFreeApprovedPayload() {
        AIIndexDocument document = document(
            AIIndexWorkType.UPSERT,
            AIProcessOperation.UPDATE,
            "approved text",
            Map.of("tenantId", "tenant-1"),
            Map.of(
                "status",
                new AIContextValue("ACTIVE", AIContextDataType.STRING, "Status")
            ),
            7L
        );

        assertThat(document.vectorMetadata()).containsEntry("tenantId", "tenant-1");
        assertThat(document.sourceVersion()).isEqualTo(7L);
    }

    @Test
    void rejectsDeletePayloadThatLeaksProjectedData() {
        assertThatThrownBy(() -> document(
            AIIndexWorkType.DELETE,
            AIProcessOperation.DELETE,
            null,
            Map.of("secret", "must-not-survive"),
            Map.of(),
            null
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not contain projected entity data");
    }

    @Test
    void rejectsOperationAndWorkTypeMismatch() {
        assertThatThrownBy(() -> document(
            AIIndexWorkType.UPSERT,
            AIProcessOperation.DELETE,
            "content",
            Map.of(),
            Map.of(),
            null
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("CREATE or UPDATE");
    }

    @Test
    void rejectsApplicationClassesInsideDurablePayload() {
        assertThatThrownBy(() -> document(
            AIIndexWorkType.UPSERT,
            AIProcessOperation.CREATE,
            "content",
            Map.of("unsafe", new UnsafeValue("raw")),
            Map.of(),
            null
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unsupported payload type");
    }

    @Test
    void rejectsInvalidEnvelopeBoundsAndHashes() {
        assertThatThrownBy(() -> new AIIndexDocument(
            AIIndexDocument.CURRENT_SCHEMA_VERSION,
            "not-a-hash",
            "product",
            "1",
            AIIndexWorkType.UPSERT,
            AIProcessOperation.CREATE,
            "content",
            "",
            Map.of(),
            Map.of(),
            Map.of(),
            null,
            "",
            Instant.EPOCH
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("lowercase SHA-256");
    }

    @Test
    void rejectsNegativeSourceVersion() {
        assertThatThrownBy(() -> document(
            AIIndexWorkType.UPSERT,
            AIProcessOperation.UPDATE,
            "content",
            Map.of(),
            Map.of(),
            -1L
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not be negative");
    }

    private AIIndexDocument document(
        AIIndexWorkType workType,
        AIProcessOperation operation,
        String semanticText,
        Map<String, Object> metadata,
        Map<String, AIContextValue> llmContext,
        Long sourceVersion
    ) {
        return new AIIndexDocument(
            AIIndexDocument.CURRENT_SCHEMA_VERSION,
            HASH,
            "product",
            "product-1",
            workType,
            operation,
            semanticText,
            semanticText,
            metadata,
            llmContext,
            Map.of(),
            sourceVersion,
            "trace-1",
            Instant.EPOCH
        );
    }

    private record UnsafeValue(String secret) {
    }
}
