package ai.fabric.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Provider-agnostic chat roles for multi-message LLM prompting.
 *
 * <p>Roles are intentionally aligned with common chat-completions APIs
 * (e.g., OpenAI/Anthropic/Gemini).</p>
 */
public enum AIChatRole {

    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant");

    private final String apiValue;

    AIChatRole(String apiValue) {
        this.apiValue = apiValue;
    }

    @JsonValue
    public String getApiValue() {
        return apiValue;
    }

    @JsonCreator
    public static AIChatRole fromValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase();
        for (AIChatRole role : values()) {
            if (role.apiValue.equals(normalized)) {
                return role;
            }
        }
        throw new IllegalArgumentException("Unsupported chat role: " + value);
    }
}

