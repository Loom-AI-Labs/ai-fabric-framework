package ai.fabric.provider.springai;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

public class SpringAiChatClientFactory {

    private final ObservationRegistry observationRegistry;
    private final List<ChatClientBuilderCustomizer> builderCustomizers;

    public SpringAiChatClientFactory(ObservationRegistry observationRegistry,
                                     List<ChatClientBuilderCustomizer> builderCustomizers) {
        this.observationRegistry = observationRegistry != null ? observationRegistry : ObservationRegistry.NOOP;
        this.builderCustomizers = builderCustomizers != null ? List.copyOf(builderCustomizers) : List.of();
    }

    public static SpringAiChatClientFactory noOp() {
        return new SpringAiChatClientFactory(ObservationRegistry.NOOP, List.of());
    }

    public ChatClient create(ChatModel chatModel) {
        if (chatModel == null) {
            throw new IllegalArgumentException("ChatModel cannot be null");
        }
        ChatClient.Builder builder = ChatClient.builder(chatModel, observationRegistry, null, null);
        builderCustomizers.forEach(customizer -> customizer.customize(builder));
        return builder.build();
    }
}
