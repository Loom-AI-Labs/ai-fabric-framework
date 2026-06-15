package ai.fabric.datasync.normalize;

import ai.fabric.datasync.AIDataSyncProperties;
import ai.fabric.dto.AIEntityConfig;
import ai.fabric.dto.AIMetadataField;
import ai.fabric.dto.AISearchableField;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataSyncEntityNormalizerTest {

    @Test
    void normalize_shouldBuildContentFromSearchableFields_andIncludeMetadataFields() {
        AIDataSyncProperties props = new AIDataSyncProperties();
        props.setMaxContentChars(8_000);
        props.setMaxFieldValueChars(2_000);
        props.setMaxMetadataKeys(50);

        DataSyncEntityNormalizer normalizer = new DataSyncEntityNormalizer(props, null);

        AIEntityConfig config = AIEntityConfig.builder()
            .entityType("product")
            .indexable(true)
            .searchableFields(List.of(
                AISearchableField.builder().name("title").includeInRAG(true).enableSemanticSearch(true).weight(1.0).build(),
                AISearchableField.builder().name("description").includeInRAG(true).enableSemanticSearch(true).weight(1.0).build()
            ))
            .metadataFields(List.of(
                AIMetadataField.builder().name("price").type("number").includeInSearch(true).build()
            ))
            .build();

        Map<String, Object> entity = Map.of(
            "title", "Sony WH-1000XM5",
            "description", "Noise cancelling headphones",
            "price", 399
        );

        DataSyncEntityNormalizer.NormalizedEntity out = normalizer.normalize(
            config,
            "product",
            "p1",
            null,
            entity,
            Map.of("locale", "en_US")
        );

        assertThat(out.content()).contains("title: Sony WH-1000XM5");
        assertThat(out.content()).contains("description: Noise cancelling headphones");
        assertThat(out.metadata()).containsEntry("vectorSpace", "product");
        assertThat(out.metadata()).containsEntry("id", "p1");
        assertThat(out.metadata()).containsEntry("price", 399);
        assertThat(out.metadata()).containsEntry("locale", "en_US");
    }

    @Test
    void normalize_shouldFailWhenNormalizedContentExceedsMax() {
        AIDataSyncProperties props = new AIDataSyncProperties();
        props.setMaxContentChars(10);

        DataSyncEntityNormalizer normalizer = new DataSyncEntityNormalizer(props, null);

        AIEntityConfig config = AIEntityConfig.builder()
            .entityType("doc")
            .indexable(true)
            .searchableFields(List.of(AISearchableField.builder().name("content").build()))
            .build();

        assertThatThrownBy(() -> normalizer.normalize(
            config,
            "doc",
            "d1",
            "01234567890",
            null,
            null
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxContentChars");
    }
}

