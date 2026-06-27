package com.ai.fabric.realapps.faq.service;

import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.dto.AIEntityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class SmartFaqAiEntityConfigTest {

    @Test
    void faqArticleConfigDefinesSearchableAndEmbeddableFields() {
        AIEntityConfigurationLoader loader = new AIEntityConfigurationLoader(new DefaultResourceLoader());

        loader.loadConfigurationFromFile("classpath:ai-entity-config.yml");

        AIEntityConfig config = loader.getEntityConfig("faq-article");
        assertThat(config).isNotNull();
        assertThat(config.getSearchableFields())
            .extracting("name")
            .containsExactly("title", "content", "category", "tags");
        assertThat(config.getEmbeddableFields())
            .extracting("name")
            .containsExactly("title", "content", "category", "tags");
    }
}
