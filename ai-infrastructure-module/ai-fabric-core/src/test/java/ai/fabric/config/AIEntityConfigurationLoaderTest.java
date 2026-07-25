package ai.fabric.config;

import ai.fabric.dto.AIEntityConfig;
import ai.fabric.indexing.api.AIContextDestination;
import ai.fabric.indexing.api.AISearchDestination;
import ai.fabric.indexing.api.AISearchPreprocessing;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AIEntityConfigurationLoaderTest {

    @Test
    void bindsTypedEntityPolicyFromSpringEnvironment() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("ai-entities.product.indexing.enabled", "false")
            .withProperty("ai-entities.product.indexing.max-characters", "2048")
            .withProperty("ai-entities.product.searchable-fields[0].name", "title")
            .withProperty(
                "ai-entities.product.searchable-fields[0].destinations[0]",
                "SEMANTIC_SEARCH"
            )
            .withProperty(
                "ai-entities.product.searchable-fields[0].preprocessing",
                "SANITIZE"
            )
            .withProperty("ai-entities.product.metadata-fields[0].name", "tenantId")
            .withProperty(
                "ai-entities.product.metadata-fields[0].destinations[0]",
                "VECTOR_METADATA"
            );

        AIEntityConfigurationLoader loader = new AIEntityConfigurationLoader(environment);
        loader.loadConfiguration();

        AIEntityConfig config = loader.getEntityConfig("product");
        assertThat(config).isNotNull();
        assertThat(config.getEntityType()).isEqualTo("product");
        assertThat(config.getIndexing().getEnabled()).isFalse();
        assertThat(config.getIndexing().getMaxCharacters()).isEqualTo(2048);
        assertThat(config.getSearchableFields()).singleElement().satisfies(field -> {
            assertThat(field.getName()).isEqualTo("title");
            assertThat(field.getDestinations()).containsExactly(AISearchDestination.SEMANTIC_SEARCH);
            assertThat(field.getPreprocessing()).isEqualTo(AISearchPreprocessing.SANITIZE);
            assertThat(field.getMaxLength()).isNull();
        });
        assertThat(config.getMetadataFields()).singleElement().satisfies(field -> {
            assertThat(field.getName()).isEqualTo("tenantId");
            assertThat(field.getDestinations()).containsExactly(AIContextDestination.VECTOR_METADATA);
            assertThat(field.getDataType()).isNull();
        });
    }

    @Test
    void rejectsNestedEntityTypeEvenWhenItMatchesTheMapKey() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("ai-entities.product.entity-type", "product");
        AIEntityConfigurationLoader loader = new AIEntityConfigurationLoader(environment);

        assertThatThrownBy(loader::loadConfiguration)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Nested")
            .hasMessageContaining("map key");
    }
}
