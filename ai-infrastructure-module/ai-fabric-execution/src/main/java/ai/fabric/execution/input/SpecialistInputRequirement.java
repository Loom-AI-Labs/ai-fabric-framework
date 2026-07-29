package ai.fabric.execution.input;

import ai.fabric.execution.specialist.manifest.SpecialistSchemaId;
import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Application-owned declaration of one factual input required by a specialist.
 */
public record SpecialistInputRequirement(
    String purposeCode,
    String safeQuestion,
    SpecialistSchemaId responseSchemaId,
    Duration ttl,
    int maxAttempts
) {
    private static final Pattern PURPOSE = Pattern.compile(
        "[A-Z][A-Z0-9_]{0,63}"
    );
    private static final int MAX_QUESTION_CHARACTERS = 500;
    private static final int MAX_DECLARED_ATTEMPTS = 10;

    public SpecialistInputRequirement {
        purposeCode = requireText(purposeCode, "purposeCode");
        if (!PURPOSE.matcher(purposeCode).matches()) {
            throw new IllegalArgumentException(
                "purposeCode must use uppercase letters, digits, and underscores"
            );
        }
        safeQuestion = requireText(safeQuestion, "safeQuestion");
        if (safeQuestion.length() > MAX_QUESTION_CHARACTERS) {
            throw new IllegalArgumentException(
                "safeQuestion must not exceed "
                    + MAX_QUESTION_CHARACTERS + " characters"
            );
        }
        Objects.requireNonNull(
            responseSchemaId,
            "responseSchemaId is required"
        );
        if (ttl != null && (ttl.isZero() || ttl.isNegative())) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        if (maxAttempts < 1 || maxAttempts > MAX_DECLARED_ATTEMPTS) {
            throw new IllegalArgumentException(
                "maxAttempts must be between 1 and "
                    + MAX_DECLARED_ATTEMPTS
            );
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(
            value,
            field + " is required"
        ).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
