package ai.fabric.prompt;

import java.util.Objects;

/**
 * A loaded prompt template.
 */
public record PromptTemplate(
    PromptTemplateKey key,
    String template
) {
    public PromptTemplate {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(template, "template must not be null");
    }
}

