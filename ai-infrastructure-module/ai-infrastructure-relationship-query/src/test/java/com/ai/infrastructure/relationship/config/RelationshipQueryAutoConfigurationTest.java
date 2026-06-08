package com.ai.infrastructure.relationship.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class RelationshipQueryAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RelationshipQueryAutoConfiguration.class));

    @Test
    void shouldCreateMetadataBeanWhenModuleEnabled() {
        contextRunner
            .withPropertyValues(
                "ai.infrastructure.relationship.enabled=true",
                "ai.infrastructure.relationship.max-traversal-depth=4",
                "ai.infrastructure.relationship.default-return-mode=full"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(RelationshipModuleMetadata.class);
                RelationshipModuleMetadata metadata = context.getBean(RelationshipModuleMetadata.class);
                assertThat(metadata.maxTraversalDepth()).isEqualTo(4);
                assertThat(metadata.defaultReturnMode()).isNotNull();
            });
    }

    @Test
    void shouldBackOffWhenModuleDisabled() {
        contextRunner
            .withPropertyValues("ai.infrastructure.relationship.enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(RelationshipModuleMetadata.class));
    }
}
