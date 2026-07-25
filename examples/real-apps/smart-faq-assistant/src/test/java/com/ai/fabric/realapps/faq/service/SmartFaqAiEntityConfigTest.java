package com.ai.fabric.realapps.faq.service;

import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.indexing.descriptor.AIEntityDescriptorRegistry;
import ai.fabric.privacy.pii.PIIDetectionService;
import com.ai.fabric.realapps.faq.domain.FaqArticle;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SmartFaqAiEntityConfigTest {

    @Test
    void faqArticleAnnotationsCompileTheApprovedProjection() {
        AIEntityConfigurationLoader loader =
            new AIEntityConfigurationLoader(new MockEnvironment());
        loader.loadConfiguration();
        var piiProvider = new StaticListableBeanFactory()
            .getBeanProvider(PIIDetectionService.class);
        AIEntityDescriptorRegistry registry = new AIEntityDescriptorRegistry(
            loader,
            List.of(),
            List.of(),
            piiProvider,
            new ObjectMapper()
        );

        var descriptor = registry.resolve(FaqArticle.class);

        assertThat(descriptor.entityType()).isEqualTo("faq-article");
        assertThat(descriptor.indexingEnabled()).isTrue();
        assertThat(descriptor.searchableFields())
            .extracting("name")
            .containsExactly("title", "content", "category", "tags");
    }
}
