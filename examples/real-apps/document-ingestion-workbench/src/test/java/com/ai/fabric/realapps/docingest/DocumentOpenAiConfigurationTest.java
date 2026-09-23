package com.ai.fabric.realapps.docingest;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.embedding.EmbeddingProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.profiles.active=openai",
        "spring.datasource.url=jdbc:h2:mem:document_workbench_openai_config;DB_CLOSE_DELAY=-1",
        "OPENAI_ENABLED=true",
        "OPENAI_API_KEY=test-openai-key",
        "OPENAI_BASE_URL=https://api.openai.example/v1",
        "OPENAI_EMBEDDING_MODEL=text-embedding-3-small",
        "OPENAI_EMBEDDING_DIMENSIONS=512",
        "OPENAI_TIMEOUT=45",
        "AI_VECTOR_DB_LUCENE_INDEX_PATH=./target/document-openai-config-lucene",
        "ai.indexing.sync-retry-worker.enabled=false",
        "ai.indexing.async-worker.enabled=false",
        "ai.indexing.batch-worker.enabled=false",
        "ai.indexing.cleanup.enabled=false"
    }
)
class DocumentOpenAiConfigurationTest {

    @Autowired
    private AIProviderConfig providerConfig;

    @Autowired
    private List<EmbeddingProvider> embeddingProviders;

    @Test
    void mapsDeploymentEnvironmentIntoOpenAiEmbeddingConfiguration() {
        assertThat(providerConfig.getEmbeddingProvider()).isEqualTo("openai");
        assertThat(providerConfig.getOpenai().isEnabled()).isTrue();
        assertThat(providerConfig.getOpenai().getApiKey()).isEqualTo("test-openai-key");
        assertThat(providerConfig.getOpenai().getBaseUrl()).isEqualTo("https://api.openai.example/v1");
        assertThat(providerConfig.getOpenai().getEmbeddingModel()).isEqualTo("text-embedding-3-small");
        assertThat(providerConfig.getOpenai().getEmbeddingDimensions()).isEqualTo(512);
        assertThat(providerConfig.getOpenai().getTimeout()).isEqualTo(45);
        assertThat(embeddingProviders)
            .extracting(EmbeddingProvider::getProviderName)
            .contains("openai");
    }
}
