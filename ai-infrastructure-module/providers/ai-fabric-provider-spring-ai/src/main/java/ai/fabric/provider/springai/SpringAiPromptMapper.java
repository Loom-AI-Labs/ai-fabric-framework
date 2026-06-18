package ai.fabric.provider.springai;

import ai.fabric.dto.AIChatMessage;
import ai.fabric.dto.AIChatRole;
import ai.fabric.dto.AIGenerationInputPart;
import ai.fabric.dto.AIGenerationInputType;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.provider.TransientInputSupport;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeType;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class SpringAiPromptMapper {

    private static final String DOCUMENT_USAGE_INSTRUCTION = """
        The request includes transient file inputs. Do not expose or repeat the file URLs. \
        Return documentUsage evidence in the response so the caller can verify which transient documents were used.
        """;

    private SpringAiPromptMapper() {
    }

    static Prompt toPrompt(SpringAiProviderFamily family, AIGenerationRequest request, ChatOptions options) {
        List<Message> messages = new ArrayList<>();
        if (hasText(request.getSystemPrompt())) {
            messages.add(new SystemMessage(request.getSystemPrompt().trim()));
        }
        if (hasText(request.getContext())) {
            messages.add(new SystemMessage("Context:\n" + request.getContext().trim()));
        }

        if (request.getMessages() != null && !request.getMessages().isEmpty()) {
            for (AIChatMessage message : request.getMessages()) {
                if (message == null || !hasText(message.getContent())) {
                    continue;
                }
                messages.add(toSpringMessage(message));
            }
            appendSupplementalUserMessage(family, request, messages);
        } else {
            messages.add(toUserMessage(family, request));
        }

        return new Prompt(messages, options);
    }

    static Optional<String> unsupportedTransientInputReason(SpringAiProviderFamily family,
                                                           AIGenerationRequest request) {
        for (AIGenerationInputPart part : TransientInputSupport.fileUrlInputParts(request)) {
            try {
                TransientInputSupport.validateFileUrlInput(part);
            } catch (IllegalArgumentException ex) {
                return Optional.of(ex.getMessage());
            }
            String contentType = TransientInputSupport.normalizeContentType(part.getContentType());
            if (!isSupportedTransientMedia(family, contentType)) {
                return Optional.of("Spring AI provider " + family.providerName()
                    + " does not support transient FILE_URL media type " + contentType + " through this adapter.");
            }
        }
        return Optional.empty();
    }

    private static Message toSpringMessage(AIChatMessage message) {
        AIChatRole role = message.getRole();
        String content = message.getContent().trim();
        if (AIChatRole.SYSTEM.equals(role)) {
            return new SystemMessage(content);
        }
        if (AIChatRole.ASSISTANT.equals(role)) {
            return new AssistantMessage(content);
        }
        return new UserMessage(content);
    }

    private static void appendSupplementalUserMessage(SpringAiProviderFamily family,
                                                      AIGenerationRequest request,
                                                      List<Message> messages) {
        String supplementalText = supplementalUserText(request, true);
        List<Media> media = mediaParts(family, request);
        if (hasText(supplementalText) || !media.isEmpty()) {
            messages.add(userMessage(supplementalText, media));
        }
    }

    private static UserMessage toUserMessage(SpringAiProviderFamily family, AIGenerationRequest request) {
        return userMessage(supplementalUserText(request, true), mediaParts(family, request));
    }

    private static UserMessage userMessage(String text, List<Media> media) {
        String effectiveText = hasText(text) ? text.trim() : "Analyze the supplied input.";
        if (media.isEmpty()) {
            return new UserMessage(effectiveText);
        }
        return UserMessage.builder()
            .text(effectiveText)
            .media(media)
            .build();
    }

    private static String supplementalUserText(AIGenerationRequest request, boolean includePrompt) {
        StringBuilder text = new StringBuilder();
        if (includePrompt && hasText(request.getPrompt())) {
            text.append(request.getPrompt().trim());
        }
        if (request.getInputParts() != null) {
            for (AIGenerationInputPart part : request.getInputParts()) {
                if (part == null || !AIGenerationInputType.TEXT.equals(part.getType()) || !hasText(part.getText())) {
                    continue;
                }
                appendSection(text, "Input", part.getText().trim());
            }
        }
        if (TransientInputSupport.hasFileUrlInputs(request)) {
            appendSection(text, "Transient input policy", DOCUMENT_USAGE_INSTRUCTION.trim());
        }
        return text.toString();
    }

    private static List<Media> mediaParts(SpringAiProviderFamily family, AIGenerationRequest request) {
        List<Media> media = new ArrayList<>();
        for (AIGenerationInputPart part : TransientInputSupport.fileUrlInputParts(request)) {
            String contentType = TransientInputSupport.normalizeContentType(part.getContentType());
            if (!isSupportedTransientMedia(family, contentType)) {
                continue;
            }
            Media item = Media.builder()
                .mimeType(MimeType.valueOf(contentType))
                .data(URI.create(part.getUrl()))
                .name(mediaName(part))
                .build();
            media.add(item);
        }
        return List.copyOf(media);
    }

    private static boolean isSupportedTransientMedia(SpringAiProviderFamily family, String contentType) {
        return switch (family) {
            case OPENAI, AZURE, ANTHROPIC -> TransientInputSupport.isProviderVisionImageContentType(contentType);
            case GEMINI -> TransientInputSupport.isGeminiInlineContentType(contentType);
            case SPRING_AI_ONNX -> false;
        };
    }

    private static String mediaName(AIGenerationInputPart part) {
        if (hasText(part.getFileName())) {
            return part.getFileName().trim();
        }
        if (hasText(part.getDocumentId())) {
            return part.getDocumentId().trim();
        }
        return null;
    }

    private static void appendSection(StringBuilder target, String title, String value) {
        if (target.length() > 0) {
            target.append("\n\n");
        }
        target.append(title).append(":\n").append(value);
    }

    private static boolean hasText(String value) {
        return StringUtils.hasText(value);
    }
}
