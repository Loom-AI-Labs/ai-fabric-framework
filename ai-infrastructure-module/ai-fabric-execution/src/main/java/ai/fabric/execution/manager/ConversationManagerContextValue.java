package ai.fabric.execution.manager;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One application-approved scalar exposed to a conversation manager.
 */
public record ConversationManagerContextValue(String name, String value) {

    public static final int MAX_NAME_CHARACTERS = 100;
    public static final int MAX_VALUE_CHARACTERS = 1000;
    private static final Pattern NAME_PATTERN = Pattern.compile(
        "[A-Za-z][A-Za-z0-9_.-]{0,99}"
    );

    public ConversationManagerContextValue {
        name = Objects.requireNonNull(name, "name is required").trim();
        value = Objects.requireNonNull(value, "value is required").trim();
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                "name must be a safe context identifier"
            );
        }
        if (value.isEmpty()) {
            throw new IllegalArgumentException("value is required");
        }
        if (value.length() > MAX_VALUE_CHARACTERS) {
            throw new IllegalArgumentException(
                "value must not exceed " + MAX_VALUE_CHARACTERS + " characters"
            );
        }
    }
}
