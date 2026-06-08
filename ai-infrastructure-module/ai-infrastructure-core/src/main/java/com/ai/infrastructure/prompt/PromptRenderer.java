package com.ai.infrastructure.prompt;

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders templates with {@code {{placeholders}}} using strict validation.
 *
 * <p>Fail-closed: missing placeholder values throw an exception.</p>
 */
@Component
public class PromptRenderer {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_\\-]+)\\s*}}");

    public String render(PromptTemplate template, Map<String, String> values) {
        Objects.requireNonNull(template, "template must not be null");
        Objects.requireNonNull(values, "values must not be null");

        Set<String> required = requiredPlaceholders(template.template());
        Set<String> missing = new LinkedHashSet<>();
        for (String key : required) {
            if (!values.containsKey(key) || values.get(key) == null) {
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing prompt placeholders for " + template.key().id() + ": " + missing);
        }

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template.template());
        StringBuilder out = new StringBuilder(template.template().length() + 128);
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement = values.get(key);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    public Set<String> requiredPlaceholders(String template) {
        Objects.requireNonNull(template, "template must not be null");
        Set<String> placeholders = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        while (matcher.find()) {
            placeholders.add(matcher.group(1));
        }
        return placeholders;
    }
}

