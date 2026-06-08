package com.ai.infrastructure.prompt;

import com.ai.infrastructure.intent.orchestration.OrchestrationContextMetadataKeys;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Utilities for request-scoped prompt preview overlays.
 *
 * <p>These overlays are intended for short-lived operator testing and should not be treated as
 * deployment-wide prompt configuration.</p>
 */
public final class PromptPreviewOverlaySupport {

    public static final List<String> SUPPORTED_KEYS = List.of(
        "systemPrompt",
        "intentExtractionPrompt",
        "actionSelectionPrompt",
        "clarificationPrompt",
        "answerGenerationPrompt",
        "retrievalPrompt",
        "assistantUiPrompt"
    );

    private PromptPreviewOverlaySupport() {
    }

    public static Map<String, String> extract(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Object raw = metadata.get(OrchestrationContextMetadataKeys.PROMPT_PREVIEW);
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }

        Map<String, String> sanitized = new LinkedHashMap<>();
        for (String key : SUPPORTED_KEYS) {
            Object candidate = map.get(key);
            if (!(candidate instanceof String text) || !StringUtils.hasText(text)) {
                continue;
            }
            sanitized.put(key, text.trim());
        }
        return sanitized.isEmpty() ? Map.of() : Map.copyOf(sanitized);
    }

    public static String appendOverlaySection(String base,
                                              Map<String, String> overlay,
                                              String key,
                                              String heading) {
        if (!StringUtils.hasText(base) || overlay == null || overlay.isEmpty() || !StringUtils.hasText(key)) {
            return base;
        }
        String addition = overlay.get(key);
        if (!StringUtils.hasText(addition)) {
            return base;
        }
        return base.trim() + "\n\n## " + heading.trim() + "\n" + addition.trim() + "\n";
    }

    public static boolean hasAny(Map<String, String> overlay, String... keys) {
        if (overlay == null || overlay.isEmpty() || keys == null || keys.length == 0) {
            return false;
        }
        for (String key : keys) {
            if (StringUtils.hasText(overlay.get(key))) {
                return true;
            }
        }
        return false;
    }

    public static String resolveOverlayValue(Map<String, String> overlay, String key, String defaultValue) {
        if (StringUtils.hasText(key) && overlay != null) {
            String candidate = overlay.get(key);
            if (StringUtils.hasText(candidate)) {
                return candidate.trim();
            }
        }
        return StringUtils.hasText(defaultValue) ? defaultValue.trim() : "";
    }
}
