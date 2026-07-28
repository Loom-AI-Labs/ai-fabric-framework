package ai.fabric.intent.extraction;

import ai.fabric.exception.AIServiceException;
import org.springframework.util.StringUtils;

final class IntentExtractionFailureSanitizer {

    static final String PROVIDER_FAILURE_MESSAGE = "AI provider request failed";
    private static final int MAX_DIAGNOSTIC_LENGTH = 240;

    private IntentExtractionFailureSanitizer() {
    }

    static String diagnosticMessage(Throwable error) {
        if (error == null) {
            return "Unknown extraction failure";
        }
        if (hasCause(error, AIServiceException.class)) {
            return PROVIDER_FAILURE_MESSAGE;
        }

        String message = error.getMessage();
        if (!StringUtils.hasText(message)) {
            return error.getClass().getSimpleName();
        }
        String normalized = message.replaceAll("\\s+", " ").trim();
        return normalized.length() <= MAX_DIAGNOSTIC_LENGTH
            ? normalized
            : normalized.substring(0, MAX_DIAGNOSTIC_LENGTH);
    }

    static boolean isProviderFailure(Throwable error) {
        return hasCause(error, AIServiceException.class);
    }

    private static boolean hasCause(
        Throwable error,
        Class<? extends Throwable> expectedType
    ) {
        Throwable current = error;
        while (current != null) {
            if (expectedType.isInstance(current)) {
                return true;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return false;
    }
}
