package com.ai.infrastructure.intent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RelationshipQueryHintPrefix {

    private static final Pattern HINT_PREFIX =
        Pattern.compile("^\\s*(relationship_query|relationship\\s+query|relationship-query)\\s*:\\s*(.*)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private RelationshipQueryHintPrefix() {
    }

    static String stripIfPresent(String text) {
        if (text == null) {
            return null;
        }

        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }

        Matcher matcher = HINT_PREFIX.matcher(trimmed);
        if (!matcher.matches()) {
            return trimmed;
        }

        return matcher.group(2) == null ? "" : matcher.group(2).trim();
    }
}

