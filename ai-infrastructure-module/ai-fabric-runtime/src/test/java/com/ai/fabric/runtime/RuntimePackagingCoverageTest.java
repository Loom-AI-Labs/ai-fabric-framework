package com.ai.fabric.runtime;

import com.ai.infrastructure.provider.azure.AzureOpenAIAutoConfiguration;
import com.ai.infrastructure.provider.cohere.CohereAutoConfiguration;
import com.ai.infrastructure.provider.gemini.GeminiAutoConfiguration;
import com.ai.infrastructure.vector.memory.MemoryVectorAutoConfiguration;
import com.ai.infrastructure.vector.milvus.MilvusVectorAutoConfiguration;
import com.ai.infrastructure.vector.pinecone.PineconeVectorAutoConfiguration;
import com.ai.infrastructure.vector.weaviate.WeaviateVectorAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimePackagingCoverageTest {

    @Test
    void runtimeJarIncludesExpandedProviderAndVectorModules() {
        assertThat(AzureOpenAIAutoConfiguration.class).isNotNull();
        assertThat(CohereAutoConfiguration.class).isNotNull();
        assertThat(GeminiAutoConfiguration.class).isNotNull();
        assertThat(MemoryVectorAutoConfiguration.class).isNotNull();
        assertThat(MilvusVectorAutoConfiguration.class).isNotNull();
        assertThat(PineconeVectorAutoConfiguration.class).isNotNull();
        assertThat(WeaviateVectorAutoConfiguration.class).isNotNull();
    }

    @Test
    void runtimeJarAutoConfigurationImportsIncludeExpandedVectorModules() {
        var candidates = ImportCandidates.load(
            AutoConfiguration.class,
            RuntimePackagingCoverageTest.class.getClassLoader()
        );

        assertThat(candidates)
            .contains(MemoryVectorAutoConfiguration.class.getName())
            .contains(MilvusVectorAutoConfiguration.class.getName())
            .contains(PineconeVectorAutoConfiguration.class.getName())
            .contains(WeaviateVectorAutoConfiguration.class.getName());
    }

    @Test
    void runtimeClasspathIncludesSupportCuratedPack() {
        assertThat(RuntimePackagingCoverageTest.class.getClassLoader().getResource("ai-curated/packs/default.yml"))
            .isNotNull();
        assertThat(RuntimePackagingCoverageTest.class.getClassLoader().getResource("ai-curated/packs/support.yml"))
            .isNotNull();
    }
}
