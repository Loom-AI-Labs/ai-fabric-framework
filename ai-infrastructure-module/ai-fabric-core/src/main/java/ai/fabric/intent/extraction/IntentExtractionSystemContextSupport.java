package ai.fabric.intent.extraction;

import org.springframework.util.StringUtils;

/**
 * Keeps trusted runtime extraction context at system-prompt priority.
 */
public final class IntentExtractionSystemContextSupport {

    private static final String HEADER =
        "TRUSTED RUNTIME INTENT-EXTRACTION CONTEXT";

    private IntentExtractionSystemContextSupport() {}

    public static String append(
        String baseSystemPrompt,
        IntentExtractionInput input
    ) {
        String context = input != null
            ? input.trustedSystemContext()
            : null;
        if (!StringUtils.hasText(context)) {
            return baseSystemPrompt;
        }
        String base = StringUtils.hasText(baseSystemPrompt)
            ? baseSystemPrompt.trim() + "\n\n"
            : "";
        return base + HEADER + "\n" + context.trim();
    }
}
