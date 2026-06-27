package ai.fabric.provider.springai;

import java.util.Locale;
import java.util.Optional;

enum SpringAiProviderFamily {
    OPENAI("openai"),
    AZURE("azure"),
    ANTHROPIC("anthropic"),
    GEMINI("gemini"),
    SPRING_AI_ONNX("spring-ai-onnx");

    private final String providerName;

    SpringAiProviderFamily(String providerName) {
        this.providerName = providerName;
    }

    String providerName() {
        return providerName;
    }

    static Optional<SpringAiProviderFamily> from(String providerName) {
        if (providerName == null || providerName.isBlank()) {
            return Optional.empty();
        }
        String normalized = providerName.trim().toLowerCase(Locale.ROOT);
        for (SpringAiProviderFamily family : values()) {
            if (family.providerName.equals(normalized)) {
                return Optional.of(family);
            }
        }
        return Optional.empty();
    }
}
