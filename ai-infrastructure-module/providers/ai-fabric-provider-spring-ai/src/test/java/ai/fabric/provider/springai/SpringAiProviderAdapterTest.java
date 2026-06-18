package ai.fabric.provider.springai;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.dto.AIGenerationInputPart;
import ai.fabric.dto.AIGenerationInputType;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIEmbeddingRequest;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringAiProviderAdapterTest {

    @Test
    void chatProviderMapsSpringAiResponseToAiFabricResponse() {
        RecordingChatModel chatModel = new RecordingChatModel();
        StubResolver resolver = new StubResolver(chatModel, null);
        SpringAiChatProvider provider = new SpringAiChatProvider("openai", resolver);

        var response = provider.generateContent(AIGenerationRequest.builder()
            .entityId("entity-1")
            .entityType("review")
            .generationType("summary")
            .prompt("Summarize this.")
            .build());

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getContent()).isEqualTo("Generated answer");
        assertThat(response.getModel()).isEqualTo("spring-model");
        assertThat(response.getMetadata()).containsEntry("executionLayer", "spring-ai");
        assertThat(chatModel.prompt()).isNotNull();
        assertThat(chatModel.prompt().getInstructions()).hasSize(1);
    }

    @Test
    void chatProviderFailsClosedBeforeCallingModelForUnsupportedTransientMedia() {
        RecordingChatModel chatModel = new RecordingChatModel();
        StubResolver resolver = new StubResolver(chatModel, null);
        SpringAiChatProvider provider = new SpringAiChatProvider("openai", resolver);

        var response = provider.generateContent(AIGenerationRequest.builder()
            .prompt("Analyze this.")
            .inputParts(List.of(AIGenerationInputPart.builder()
                .type(AIGenerationInputType.FILE_URL)
                .url("https://files.example.com/temporary/report.pdf")
                .contentType("application/pdf")
                .documentId("doc-1")
                .build()))
            .build());

        assertThat(response.getStatus()).isEqualTo("PROVIDER_FILE_URL_INPUT_UNSUPPORTED");
        assertThat(chatModel.calls()).isZero();
        assertThat(response.getMetadata()).containsKey("documentUsage");
    }

    @Test
    void embeddingProviderMapsSpringAiFloatVectorsToAiFabricDoubles() {
        StubResolver resolver = new StubResolver(null, new FixedEmbeddingModel());
        SpringAiEmbeddingProvider provider = new SpringAiEmbeddingProvider("openai", resolver);

        var response = provider.generateEmbedding(AIEmbeddingRequest.builder()
            .text("embed me")
            .model("text-embedding-test")
            .build());

        assertThat(response.getEmbedding()).containsExactly(0.25d, 0.5d, 0.75d);
        assertThat(response.getDimensions()).isEqualTo(3);
        assertThat(response.getModel()).isEqualTo("embedding-model");
    }

    @Test
    void springAiOnnxEmbeddingProviderIsExplicitLocalProvider() {
        StubResolver resolver = new StubResolver(null, new FixedEmbeddingModel());
        SpringAiEmbeddingProvider provider = new SpringAiEmbeddingProvider("spring-ai-onnx", resolver);

        var response = provider.generateEmbedding(AIEmbeddingRequest.builder()
            .text("embed me")
            .build());

        assertThat(provider.getProviderName()).isEqualTo("spring-ai-onnx");
        assertThat(response.getEmbedding()).containsExactly(0.25d, 0.5d, 0.75d);
    }

    @Test
    void springAiOnnxResolverIsAvailableWithoutApiKeyAndEmbeddingOnly() {
        AIProviderConfig config = new AIProviderConfig();
        config.setEmbeddingProvider("spring-ai-onnx");
        SpringAiModelResolver resolver = new SpringAiModelResolver(config);

        assertThat(resolver.supportsEmbedding("spring-ai-onnx")).isTrue();
        assertThat(resolver.isEmbeddingAvailable("spring-ai-onnx")).isTrue();
        assertThat(resolver.isChatAvailable("spring-ai-onnx")).isFalse();
        assertThat(resolver.embeddingDimension("spring-ai-onnx")).isEqualTo(384);
        assertThat(resolver.resolveEmbeddingOptions("spring-ai-onnx",
            AIEmbeddingRequest.builder().text("embed me").build())).isNull();
        assertThat(resolver.providerConfig("spring-ai-onnx").getDefaultEmbeddingModel())
            .isEqualTo("all-MiniLM-L6-v2");
        assertThatThrownBy(() -> resolver.resolveChatOptions("spring-ai-onnx",
            AIGenerationRequest.builder().prompt("hello").build()))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("embedding-only");
    }

    @Test
    void springAiOnnxResolverHonorsEnabledFlag() {
        AIProviderConfig config = new AIProviderConfig();
        config.setEmbeddingProvider("spring-ai-onnx");
        config.getSpringAiOnnx().setEnabled(false);
        SpringAiModelResolver resolver = new SpringAiModelResolver(config);

        assertThat(resolver.isEmbeddingAvailable("spring-ai-onnx")).isFalse();
    }

    private static final class StubResolver extends SpringAiModelResolver {
        private final ChatModel chatModel;
        private final EmbeddingModel embeddingModel;

        private StubResolver(ChatModel chatModel, EmbeddingModel embeddingModel) {
            super(new AIProviderConfig());
            this.chatModel = chatModel;
            this.embeddingModel = embeddingModel;
        }

        @Override
        public ChatModel resolveChatModel(String providerName, AIGenerationRequest request) {
            return chatModel;
        }

        @Override
        public ChatOptions resolveChatOptions(String providerName, AIGenerationRequest request) {
            return ChatOptions.builder().model("spring-model").build();
        }

        @Override
        public EmbeddingModel resolveEmbeddingModel(String providerName, AIEmbeddingRequest request) {
            return embeddingModel;
        }

        @Override
        public EmbeddingOptions resolveEmbeddingOptions(String providerName, AIEmbeddingRequest request) {
            return null;
        }

        @Override
        public boolean isChatAvailable(String providerName) {
            return chatModel != null;
        }

        @Override
        public boolean isEmbeddingAvailable(String providerName) {
            return embeddingModel != null;
        }

        @Override
        public boolean supportsEmbedding(String providerName) {
            return embeddingModel != null;
        }
    }

    private static final class RecordingChatModel implements ChatModel {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<Prompt> prompt = new AtomicReference<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            this.prompt.set(prompt);
            this.calls.incrementAndGet();
            return new ChatResponse(
                List.of(new Generation(new AssistantMessage("Generated answer"))),
                ChatResponseMetadata.builder().model("spring-model").build());
        }

        int calls() {
            return calls.get();
        }

        Prompt prompt() {
            return prompt.get();
        }
    }

    private static final class FixedEmbeddingModel implements EmbeddingModel {
        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            return new EmbeddingResponse(
                List.of(new Embedding(new float[] {0.25f, 0.5f, 0.75f}, 0)),
                new org.springframework.ai.embedding.EmbeddingResponseMetadata("embedding-model", null));
        }

        @Override
        public float[] embed(Document document) {
            return new float[] {0.25f, 0.5f, 0.75f};
        }
    }
}
