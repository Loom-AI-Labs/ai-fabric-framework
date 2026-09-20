package ai.fabric.indexing.document;

import ai.fabric.indexing.document.model.DocumentIngestionException;
import ai.fabric.indexing.document.model.DocumentIngestionFailureCode;
import ai.fabric.indexing.document.model.DocumentIngestionWarningCode;
import ai.fabric.indexing.document.model.DocumentMetadataKeys;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentMetadataNormalizerTest {

    private final DocumentMetadataNormalizer normalizer =
        new DocumentMetadataNormalizer();

    @Test
    void keepsOnlyAllowlistedScalarMetadataAndNeverLeaksDroppedValues() {
        var result = normalizer.normalize(
            Map.of(
                "documentTitle", "  Refunds  ",
                "rank", 3,
                "secret", "never-leak-this",
                "nested", Map.of("value", "never-leak-this-either")
            ),
            Map.of("locale", "en", "enabled", true),
            Set.of("rank", "enabled"),
            8,
            32,
            true
        );

        assertThat(result.metadata())
            .containsEntry("documentTitle", "Refunds")
            .containsEntry("rank", 3)
            .containsEntry("locale", "en")
            .containsEntry("enabled", true)
            .doesNotContainKeys("secret", "nested");
        assertThat(result.warnings())
            .allSatisfy(warning -> assertThat(warning.toString())
                .doesNotContain("never-leak-this"));
    }

    @Test
    void truncatesAllowedDisplayValuesWithWarningEvidence() {
        var result = normalizer.normalize(
            Map.of("documentTitle", "123456"),
            Map.of(),
            Set.of(),
            2,
            4,
            true
        );

        assertThat(result.metadata()).containsEntry("documentTitle", "1234");
        assertThat(result.warnings())
            .extracting(warning -> warning.code())
            .containsExactly(DocumentIngestionWarningCode.METADATA_VALUE_TRUNCATED);
    }

    @Test
    void rejectsProtectedAndNormalizationDuplicateKeys() {
        assertRejected(Map.of(DocumentMetadataKeys.SOURCE_ID, "spoofed"), Map.of());
        assertRejected(
            Map.of("document-title", "one"),
            Map.of("document_title", "two")
        );
    }

    @Test
    void enforcesMetadataEntryLimitDeterministically() {
        var result = normalizer.normalize(
            Map.of("documentTitle", "Title", "locale", "en"),
            Map.of(),
            Set.of(),
            1,
            32,
            true
        );

        assertThat(result.metadata()).hasSize(1);
        assertThat(result.droppedCount()).isEqualTo(1);
        assertThat(result.warnings())
            .extracting(warning -> warning.code())
            .contains(DocumentIngestionWarningCode.METADATA_LIMIT_REACHED);
    }

    private void assertRejected(
        Map<String, Object> parser,
        Map<String, Object> application
    ) {
        assertThatThrownBy(() -> normalizer.normalize(
            parser,
            application,
            Set.of("document-title", "document_title"),
            8,
            32,
            true
        ))
            .isInstanceOfSatisfying(
                DocumentIngestionException.class,
                exception -> assertThat(exception.getCode())
                    .isEqualTo(DocumentIngestionFailureCode.DOCUMENT_METADATA_REJECTED)
            );
    }
}
