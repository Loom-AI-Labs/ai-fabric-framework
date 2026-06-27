package ai.fabric.provider.springai;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.observation.AdvisorObservationContext;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.metadata.DefaultToolMetadata;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpringAiObservationDiagnosticsTest {

    @Test
    void recordsChatModelUsageWithoutPromptOrCompletionContent() {
        SpringAiObservationDiagnostics diagnostics = new SpringAiObservationDiagnostics();
        ObservationRegistry registry = registryWithHandler(diagnostics);
        ChatModelObservationContext context = ChatModelObservationContext.builder()
            .provider("openai")
            .prompt(new Prompt("secret prompt text"))
            .streaming(false)
            .build();

        Observation observation = Observation.start("spring.ai.chat.model", () -> context, registry);
        context.setResponse(new ChatResponse(
            List.of(new Generation(new AssistantMessage("secret completion text"))),
            ChatResponseMetadata.builder()
                .model("gpt-test")
                .usage(new DefaultUsage(5, 7, 12))
                .build()
        ));
        observation.stop();

        Map<String, Object> snapshot = diagnostics.snapshot();
        assertThat(snapshot).containsEntry("totalStarted", 1L);
        assertThat(snapshot).containsEntry("totalCompleted", 1L);
        assertThat(snapshot).containsEntry("totalErrors", 0L);
        Map<String, Object> dimension = onlyDimension(snapshot);
        assertThat(dimension).containsEntry("type", "chat_model");
        assertThat(dimension).containsEntry("provider", "openai");
        assertThat(dimension).containsEntry("promptTokens", 5L);
        assertThat(dimension).containsEntry("completionTokens", 7L);
        assertThat(dimension).containsEntry("totalTokens", 12L);
        assertThat(snapshot.toString())
            .doesNotContain("secret prompt text")
            .doesNotContain("secret completion text");
    }

    @Test
    void recordsErrorTypeWithoutErrorMessageOrPromptContent() {
        SpringAiObservationDiagnostics diagnostics = new SpringAiObservationDiagnostics();
        ObservationRegistry registry = registryWithHandler(diagnostics);
        ChatModelObservationContext context = ChatModelObservationContext.builder()
            .provider("anthropic")
            .prompt(new Prompt("secret support transcript"))
            .streaming(true)
            .build();

        Observation observation = Observation.start("spring.ai.chat.model", () -> context, registry);
        observation.error(new IllegalStateException("secret provider payload"));
        observation.stop();

        Map<String, Object> snapshot = diagnostics.snapshot();
        assertThat(snapshot).containsEntry("totalErrors", 1L);
        Map<String, Object> dimension = onlyDimension(snapshot);
        assertThat(dimension).containsEntry("lastErrorType", "IllegalStateException");
        assertThat(snapshot.toString())
            .doesNotContain("secret provider payload")
            .doesNotContain("secret support transcript");
    }

    @Test
    void recordsToolObservationWithoutArgumentsResultOrCallId() {
        SpringAiObservationDiagnostics diagnostics = new SpringAiObservationDiagnostics();
        ObservationRegistry registry = registryWithHandler(diagnostics);
        ToolCallingObservationContext context = ToolCallingObservationContext.builder()
            .toolDefinition(DefaultToolDefinition.builder()
                .name("lookup_order")
                .description("Lookup an order")
                .inputSchema("{\"type\":\"object\"}")
                .build())
            .toolMetadata(DefaultToolMetadata.builder().returnDirect(false).build())
            .toolType("function")
            .toolCallId("secret-tool-call-id")
            .toolCallArguments("{\"orderId\":\"secret-order-id\"}")
            .build();

        Observation observation = Observation.start("spring.ai.tool", () -> context, registry);
        context.setToolCallResult("{\"token\":\"secret-tool-result\"}");
        observation.stop();

        Map<String, Object> snapshot = diagnostics.snapshot();
        Map<String, Object> dimension = onlyDimension(snapshot);
        assertThat(dimension).containsEntry("type", "tool_calling");
        assertThat(dimension).containsEntry("component", "lookup_order");
        assertThat(snapshot.toString())
            .doesNotContain("secret-tool-call-id")
            .doesNotContain("secret-order-id")
            .doesNotContain("secret-tool-result");
    }

    @Test
    void recordsAdvisorNameWithoutChatRequestContent() {
        SpringAiObservationDiagnostics diagnostics = new SpringAiObservationDiagnostics();
        ObservationRegistry registry = registryWithHandler(diagnostics);
        AdvisorObservationContext context = AdvisorObservationContext.builder()
            .advisorName("tenant-redaction")
            .chatClientRequest(new ChatClientRequest(new Prompt("secret advisor prompt"), Map.of()))
            .order(10)
            .build();

        Observation.start("spring.ai.advisor", () -> context, registry).stop();

        Map<String, Object> dimension = onlyDimension(diagnostics.snapshot());
        assertThat(dimension).containsEntry("type", "advisor");
        assertThat(dimension).containsEntry("component", "tenant-redaction");
        assertThat(diagnostics.snapshot().toString()).doesNotContain("secret advisor prompt");
    }

    @Test
    void autoConfigurationRegistersObservationHandlerWithApplicationRegistry() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SpringAiProviderAutoConfiguration.class))
            .withBean(ObservationRegistry.class, ObservationRegistry::create)
            .run(context -> {
                SpringAiObservationDiagnostics diagnostics = context.getBean(SpringAiObservationDiagnostics.class);
                ObservationRegistry registry = context.getBean(ObservationRegistry.class);
                ChatModelObservationContext observationContext = ChatModelObservationContext.builder()
                    .provider("openai")
                    .prompt(new Prompt("secret prompt text"))
                    .streaming(false)
                    .build();

                Observation.start("spring.ai.chat.model", () -> observationContext, registry).stop();

                assertThat(diagnostics.hasObservations()).isTrue();
                assertThat(diagnostics.snapshot().toString()).doesNotContain("secret prompt text");
            });
    }

    private ObservationRegistry registryWithHandler(SpringAiObservationDiagnostics diagnostics) {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new SpringAiObservationHandler(diagnostics));
        return registry;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> onlyDimension(Map<String, Object> snapshot) {
        List<Map<String, Object>> dimensions = (List<Map<String, Object>>) snapshot.get("dimensions");
        assertThat(dimensions).hasSize(1);
        return dimensions.getFirst();
    }
}
