package ai.fabric.config;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AIIndexingPropertiesTest {

    @Test
    void acceptsSafeDocumentDefaults() {
        AIIndexingProperties.DocumentProperties properties =
            new AIIndexingProperties.DocumentProperties();

        properties.validate();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getMaxMetadataEntriesPerChunk()).isGreaterThanOrEqualTo(10);
        assertThat(properties.getDefaultSplitter().getChunkSize()).isPositive();
    }

    @Test
    void rejectsUnsafeBounds() {
        AIIndexingProperties.DocumentProperties invalidMetadata =
            new AIIndexingProperties.DocumentProperties();
        invalidMetadata.setMaxMetadataEntriesPerChunk(9);

        assertThatThrownBy(invalidMetadata::validate)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be at least 10");

        AIIndexingProperties.DocumentProperties invalidTotal =
            new AIIndexingProperties.DocumentProperties();
        invalidTotal.setMaxTotalContentLength(100);
        invalidTotal.setMaxContentLengthPerChunk(101);
        assertThatThrownBy(invalidTotal::validate)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("greater than or equal");
    }

    @Test
    void normalizesConfiguredMetadataAllowlist() {
        AIIndexingProperties.DocumentProperties properties =
            new AIIndexingProperties.DocumentProperties();
        properties.getMetadata().setAllowedApplicationKeys(
            new LinkedHashSet<>(List.of("  department  ", "", "locale"))
        );

        properties.validate();

        assertThat(properties.getMetadata().getAllowedApplicationKeys())
            .containsExactly("department", "locale");
    }
}
