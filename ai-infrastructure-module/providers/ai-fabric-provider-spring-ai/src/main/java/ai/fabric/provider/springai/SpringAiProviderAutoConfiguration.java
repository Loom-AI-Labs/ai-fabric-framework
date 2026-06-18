package ai.fabric.provider.springai;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.embedding.EmbeddingProvider;
import ai.fabric.provider.AIProvider;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass({ChatModel.class, EmbeddingModel.class})
@EnableConfigurationProperties(AIProviderConfig.class)
public class SpringAiProviderAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SpringAiModelResolver springAiModelResolver(AIProviderConfig providerConfig) {
        return new SpringAiModelResolver(providerConfig);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ai.providers.openai", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AIProvider openAiSpringAiProvider(SpringAiModelResolver resolver) {
        return new SpringAiChatProvider("openai", resolver);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ai.providers.openai", name = "enabled", havingValue = "true", matchIfMissing = true)
    public EmbeddingProvider openAiSpringAiEmbeddingProvider(SpringAiModelResolver resolver) {
        return new SpringAiEmbeddingProvider("openai", resolver);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ai.providers.azure", name = "enabled", havingValue = "true")
    public AIProvider azureSpringAiProvider(SpringAiModelResolver resolver) {
        return new SpringAiChatProvider("azure", resolver);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ai.providers.azure", name = "enabled", havingValue = "true")
    public EmbeddingProvider azureSpringAiEmbeddingProvider(SpringAiModelResolver resolver) {
        return new SpringAiEmbeddingProvider("azure", resolver);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ai.providers.anthropic", name = "enabled", havingValue = "true")
    public AIProvider anthropicSpringAiProvider(SpringAiModelResolver resolver) {
        return new SpringAiChatProvider("anthropic", resolver);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ai.providers.gemini", name = "enabled", havingValue = "true")
    public AIProvider geminiSpringAiProvider(SpringAiModelResolver resolver) {
        return new SpringAiChatProvider("gemini", resolver);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ai.providers.gemini", name = "enabled", havingValue = "true")
    public EmbeddingProvider geminiSpringAiEmbeddingProvider(SpringAiModelResolver resolver) {
        return new SpringAiEmbeddingProvider("gemini", resolver);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ai.providers", name = "embedding-provider", havingValue = "spring-ai-onnx")
    public EmbeddingProvider springAiOnnxEmbeddingProvider(SpringAiModelResolver resolver) {
        return new SpringAiEmbeddingProvider("spring-ai-onnx", resolver);
    }
}
