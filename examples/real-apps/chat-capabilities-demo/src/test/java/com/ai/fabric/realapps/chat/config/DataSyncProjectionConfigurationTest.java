package com.ai.fabric.realapps.chat.config;

import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.dto.AIEntityConfig;
import ai.fabric.indexing.api.AIContextDestination;
import ai.fabric.indexing.api.AISearchDestination;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class DataSyncProjectionConfigurationTest {

    @Test
    void declaresReceiverOwnedProjectionForEveryConnectorVectorSpace()
        throws IOException {
        AIEntityConfigurationLoader loader = loadEntityConfiguration();

        assertProjection(
            loader.getEntityConfig("product"),
            List.of(
                "sku",
                "name",
                "description",
                "category",
                "tags",
                "price",
                "currency",
                "inStockQty"
            ),
            List.of("sku", "category", "tags", "imageUrl")
        );
        assertProjection(
            loader.getEntityConfig("policy"),
            List.of("title", "text", "classification"),
            List.of("classification")
        );
        assertProjection(
            loader.getEntityConfig("review"),
            List.of("text"),
            List.of("userId", "productId", "sku")
        );
    }

    private AIEntityConfigurationLoader loadEntityConfiguration()
        throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
            "ai-entity-config",
            new ClassPathResource("ai-entity-config.yml")
        );
        for (int index = sources.size() - 1; index >= 0; index--) {
            environment.getPropertySources().addFirst(sources.get(index));
        }

        AIEntityConfigurationLoader loader =
            new AIEntityConfigurationLoader(environment);
        loader.loadConfiguration();
        return loader;
    }

    private void assertProjection(
        AIEntityConfig config,
        List<String> searchableFields,
        List<String> metadataFields
    ) {
        assertThat(config).isNotNull();
        assertThat(config.getIndexing().getEnabled()).isTrue();
        assertThat(config.getSearchableFields())
            .extracting(field -> field.getName())
            .containsExactlyElementsOf(searchableFields);
        assertThat(config.getSearchableFields()).allSatisfy(field ->
            assertThat(field.getDestinations())
                .contains(
                    AISearchDestination.SEMANTIC_SEARCH,
                    AISearchDestination.RAG_CONTEXT
                )
        );
        assertThat(config.getMetadataFields())
            .extracting(field -> field.getName())
            .containsExactlyElementsOf(metadataFields);
        assertThat(config.getMetadataFields()).allSatisfy(field ->
            assertThat(field.getDestinations())
                .contains(
                    AIContextDestination.VECTOR_METADATA,
                    AIContextDestination.LLM_CONTEXT,
                    AIContextDestination.API_RESPONSE
                )
        );
    }
}
