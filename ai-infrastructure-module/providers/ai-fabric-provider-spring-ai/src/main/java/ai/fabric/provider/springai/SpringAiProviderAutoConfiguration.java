package ai.fabric.provider.springai;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.embedding.EmbeddingProvider;
import ai.fabric.intent.action.tool.AIActionToolCallbackFactory;
import ai.fabric.provider.AIProvider;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
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
    public SpringAiObservationDiagnostics springAiObservationDiagnostics() {
        return new SpringAiObservationDiagnostics();
    }

    @Bean
    @ConditionalOnMissingBean
    public SpringAiObservationHandler springAiObservationHandler(SpringAiObservationDiagnostics diagnostics) {
        return new SpringAiObservationHandler(diagnostics);
    }

    @Bean
    public SpringAiObservationRegistration springAiObservationRegistration(
        ObjectProvider<ObservationRegistry> observationRegistry,
        SpringAiObservationHandler observationHandler
    ) {
        return new SpringAiObservationRegistration(
            observationRegistry.getIfAvailable(),
            observationHandler
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public SpringAiModelResolver springAiModelResolver(AIProviderConfig providerConfig,
                                                       ObjectProvider<ObservationRegistry> observationRegistry) {
        return new SpringAiModelResolver(
            providerConfig,
            observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP)
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public SpringAiChatClientFactory springAiChatClientFactory(
        ObjectProvider<ObservationRegistry> observationRegistry,
        ObjectProvider<ChatClientBuilderCustomizer> builderCustomizers
    ) {
        return new SpringAiChatClientFactory(
            observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP),
            builderCustomizers.orderedStream().toList()
        );
    }

    @Bean
    @ConditionalOnProperty(prefix = "ai.providers.openai", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AIProvider openAiSpringAiProvider(SpringAiModelResolver resolver,
                                             SpringAiChatClientFactory chatClientFactory,
                                             ObjectProvider<AIActionToolCallbackFactory> actionToolCallbackFactory) {
        return new SpringAiChatProvider("openai", resolver, chatClientFactory, actionToolCallbackFactory::getIfAvailable);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ai.providers.openai", name = "enabled", havingValue = "true", matchIfMissing = true)
    public EmbeddingProvider openAiSpringAiEmbeddingProvider(SpringAiModelResolver resolver) {
        return new SpringAiEmbeddingProvider("openai", resolver);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ai.providers.azure", name = "enabled", havingValue = "true")
    public AIProvider azureSpringAiProvider(SpringAiModelResolver resolver,
                                            SpringAiChatClientFactory chatClientFactory,
                                            ObjectProvider<AIActionToolCallbackFactory> actionToolCallbackFactory) {
        return new SpringAiChatProvider("azure", resolver, chatClientFactory, actionToolCallbackFactory::getIfAvailable);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ai.providers.azure", name = "enabled", havingValue = "true")
    public EmbeddingProvider azureSpringAiEmbeddingProvider(SpringAiModelResolver resolver) {
        return new SpringAiEmbeddingProvider("azure", resolver);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ai.providers.anthropic", name = "enabled", havingValue = "true")
    public AIProvider anthropicSpringAiProvider(SpringAiModelResolver resolver,
                                                SpringAiChatClientFactory chatClientFactory,
                                                ObjectProvider<AIActionToolCallbackFactory> actionToolCallbackFactory) {
        return new SpringAiChatProvider("anthropic", resolver, chatClientFactory, actionToolCallbackFactory::getIfAvailable);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ai.providers.gemini", name = "enabled", havingValue = "true")
    public AIProvider geminiSpringAiProvider(SpringAiModelResolver resolver,
                                             SpringAiChatClientFactory chatClientFactory,
                                             ObjectProvider<AIActionToolCallbackFactory> actionToolCallbackFactory) {
        return new SpringAiChatProvider("gemini", resolver, chatClientFactory, actionToolCallbackFactory::getIfAvailable);
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
