package com.ai.infrastructure.config;

import com.ai.infrastructure.dto.AIEntityConfig;
import com.ai.infrastructure.dto.AIMetadataField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AIEntityConfigurationLoader metadata merge")
class AIEntityConfigurationLoaderMetadataMergeTest {

    @Test
    @DisplayName("Should merge additional metadata fields without overwriting configured types")
    void shouldMergeMetadataFields() {
        AIEntityConfigurationLoader loader = new AIEntityConfigurationLoader(new DefaultResourceLoader());
        loader.loadConfigurationFromFile("ai-entity-config.yml");

        AIEntityConfig config = loader.getEntityConfig("product");
        assertThat(config).isNotNull();
        assertThat(config.getMetadataFields()).isNull();

        loader.mergeMetadataFields("product", List.of(
            AIMetadataField.builder().name("imageUrl").type("string").includeInSearch(true).build(),
            AIMetadataField.builder().name("stock").type("number").includeInSearch(true).build()
        ));

        config = loader.getEntityConfig("product");
        assertThat(config.getMetadataFields()).extracting(AIMetadataField::getName)
            .containsExactly("imageUrl", "stock");
        assertThat(config.getMetadataFields()).extracting(AIMetadataField::getType)
            .containsExactly("string", "number");

        // Existing entries win (YAML override behavior): don't overwrite type
        config.setMetadataFields(List.of(
            AIMetadataField.builder().name("stock").type("string").includeInSearch(true).build()
        ));

        loader.mergeMetadataFields("product", List.of(
            AIMetadataField.builder().name("stock").type("number").includeInSearch(true).build(),
            AIMetadataField.builder().name("category").type("string").includeInSearch(true).build()
        ));

        config = loader.getEntityConfig("product");
        assertThat(config.getMetadataFields()).extracting(AIMetadataField::getName)
            .containsExactly("stock", "category");
        assertThat(config.getMetadataFields()).extracting(AIMetadataField::getType)
            .containsExactly("string", "string");
    }
}

