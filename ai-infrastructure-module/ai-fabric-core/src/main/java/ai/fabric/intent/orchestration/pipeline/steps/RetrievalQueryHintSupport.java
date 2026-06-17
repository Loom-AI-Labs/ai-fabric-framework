package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.dto.Intent;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * Strict validation and application for LLM-produced retrieval query hints.
 */
final class RetrievalQueryHintSupport {

    static final String METADATA_KEY_RETRIEVAL_QUERY_HINT_APPLIED = "retrievalQueryHintApplied";
    static final String INTENT_METADATA_KEY_RETRIEVAL_QUERY_HINT = "retrievalQueryHint";
    private static final int MAX_RETRIEVAL_QUERY_HINT_LENGTH = 200;

    private RetrievalQueryHintSupport() {
    }

    static String applyRetrievalQueryHint(String baseQuery,
                                          PipelineContext pipelineContext,
                                          Intent intent,
                                          Map<String, Object> metadata) {
        boolean applied = false;
        String result = baseQuery;

        String hint = resolveValidRetrievalQueryHint(pipelineContext, intent);
        if (StringUtils.hasText(hint)) {
            result = baseQuery + " " + hint;
            applied = true;
        }

        if (metadata != null) {
            metadata.put(METADATA_KEY_RETRIEVAL_QUERY_HINT_APPLIED, applied);
        }
        return result;
    }

    static String resolveValidRetrievalQueryHint(PipelineContext pipelineContext, Intent currentIntent) {
        if (pipelineContext == null || currentIntent == null) {
            return null;
        }

        MultiIntentResponse response = pipelineContext.getIntentResponse();
        if (response == null) {
            return null;
        }

        if (!hasExactlyOneRetrievalIntent(response)) {
            return null;
        }

        Map<String, Object> metadata = response.getMetadata();
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }

        Object raw = metadata.get(INTENT_METADATA_KEY_RETRIEVAL_QUERY_HINT);
        if (!(raw instanceof String value)) {
            return null;
        }

        String hint = value.trim();
        if (!StringUtils.hasText(hint)) {
            return null;
        }

        if (!isSafeRetrievalQueryHint(hint)) {
            return null;
        }

        return hint;
    }

    static boolean hasExactlyOneRetrievalIntent(MultiIntentResponse response) {
        if (response == null || response.getIntents() == null || response.getIntents().isEmpty()) {
            return false;
        }

        long count = response.getIntents().stream()
            .filter(java.util.Objects::nonNull)
            .filter(intent -> Boolean.TRUE.equals(intent.getRequiresRetrieval()))
            .count();

        return count == 1;
    }

    static boolean isSafeRetrievalQueryHint(String hint) {
        if (!StringUtils.hasText(hint)) {
            return false;
        }
        if (hint.length() > MAX_RETRIEVAL_QUERY_HINT_LENGTH) {
            return false;
        }
        if (hint.indexOf('@') >= 0) {
            return false;
        }
        if (hint.indexOf('\n') >= 0 || hint.indexOf('\r') >= 0) {
            return false;
        }
        return !hasConsecutiveWhitespace(hint);
    }

    private static boolean hasConsecutiveWhitespace(String value) {
        boolean lastWasWhitespace = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            boolean whitespace = Character.isWhitespace(ch);
            if (whitespace && lastWasWhitespace) {
                return true;
            }
            lastWasWhitespace = whitespace;
        }
        return false;
    }
}
