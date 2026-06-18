package ai.fabric.provider.springai;

import ai.fabric.dto.AIChatMessage;
import ai.fabric.dto.AIGenerationInputPart;
import ai.fabric.dto.AIGenerationInputType;
import ai.fabric.dto.AIGenerationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpringAiPromptMapperTest {

    @Test
    void mapsSystemContextHistoryPromptAndTextPartsAsNativeMessages() {
        AIGenerationRequest request = AIGenerationRequest.builder()
            .systemPrompt("You are concise.")
            .context("Account tier: gold")
            .messages(List.of(
                AIChatMessage.user("What is my status?"),
                AIChatMessage.assistant("You are active.")))
            .prompt("Summarize the account.")
            .inputParts(List.of(AIGenerationInputPart.builder()
                .type(AIGenerationInputType.TEXT)
                .text("Latest invoice is paid.")
                .build()))
            .build();

        Prompt prompt = SpringAiPromptMapper.toPrompt(
            SpringAiProviderFamily.OPENAI,
            request,
            ChatOptions.builder().model("test-model").build());

        assertThat(prompt.getInstructions()).hasSize(5);
        assertThat(prompt.getInstructions().get(0)).isInstanceOf(SystemMessage.class);
        assertThat(prompt.getInstructions().get(1)).isInstanceOf(SystemMessage.class);
        assertThat(prompt.getInstructions().get(2)).isInstanceOf(UserMessage.class);
        assertThat(prompt.getInstructions().get(3)).isInstanceOf(AssistantMessage.class);
        assertThat(prompt.getInstructions().get(4)).isInstanceOf(UserMessage.class);
        assertThat(prompt.getInstructions().get(4).getText())
            .contains("Summarize the account.")
            .contains("Latest invoice is paid.");
    }

    @Test
    void rejectsTransientMediaThatSpringAiAdapterCannotRepresentSafely() {
        AIGenerationRequest request = AIGenerationRequest.builder()
            .prompt("Analyze this.")
            .inputParts(List.of(AIGenerationInputPart.builder()
                .type(AIGenerationInputType.FILE_URL)
                .url("https://files.example.com/temporary/report.pdf")
                .contentType("application/pdf")
                .documentId("doc-1")
                .build()))
            .build();

        assertThat(SpringAiPromptMapper.unsupportedTransientInputReason(SpringAiProviderFamily.OPENAI, request))
            .hasValueSatisfying(reason -> assertThat(reason).contains("application/pdf"));
    }

    @Test
    void acceptsVisionImageFileUrlsForOpenAiCompatibleProviders() {
        AIGenerationRequest request = AIGenerationRequest.builder()
            .prompt("Analyze this.")
            .inputParts(List.of(AIGenerationInputPart.builder()
                .type(AIGenerationInputType.FILE_URL)
                .url("https://files.example.com/temporary/image.png")
                .contentType("image/png")
                .documentId("doc-2")
                .build()))
            .build();

        assertThat(SpringAiPromptMapper.unsupportedTransientInputReason(SpringAiProviderFamily.OPENAI, request))
            .isEmpty();
    }
}
