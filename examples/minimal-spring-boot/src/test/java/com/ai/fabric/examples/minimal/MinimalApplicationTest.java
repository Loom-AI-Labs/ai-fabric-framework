package com.ai.fabric.examples.minimal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.fabric.annotation.EnableAIInfrastructure;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class MinimalApplicationTest {

    @Test
    void applicationEnablesSpringBootAndAIFabric() {
        assertThat(MinimalApplication.class.isAnnotationPresent(SpringBootApplication.class)).isTrue();
        assertThat(MinimalApplication.class.isAnnotationPresent(EnableAIInfrastructure.class)).isTrue();
    }

    @Test
    void applicationConfigurationKeepsProviderInputsExternalized() throws IOException {
        PropertySource<?> properties = new YamlPropertySourceLoader()
            .load("minimal-application", new ClassPathResource("application.yml"))
            .get(0);

        assertThat(properties.getProperty("spring.application.name")).isEqualTo("ai-fabric-minimal-example");
        assertThat(properties.getProperty("ai.providers.llm-provider")).isEqualTo("openai");
        assertThat(properties.getProperty("ai.providers.embedding-provider")).isEqualTo("onnx");
        assertThat(properties.getProperty("ai.providers.openai.api-key")).isEqualTo("${OPENAI_API_KEY:}");
        assertThat(properties.getProperty("ai.providers.onnx.model-path"))
            .isEqualTo("${AI_FABRIC_ONNX_MODEL_PATH:./models/embeddings/all-MiniLM-L6-v2.onnx}");
        assertThat(properties.getProperty("ai.providers.onnx.tokenizer-path"))
            .isEqualTo("${AI_FABRIC_ONNX_TOKENIZER_PATH:./models/embeddings/tokenizer.json}");
        assertThat(properties.getProperty("ai.vector-db.type")).isEqualTo("lucene");
    }
}
