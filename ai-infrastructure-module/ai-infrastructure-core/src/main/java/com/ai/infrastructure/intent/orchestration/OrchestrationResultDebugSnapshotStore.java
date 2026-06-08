package com.ai.infrastructure.intent.orchestration;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Stores a minimal snapshot of the most recently produced orchestration result.
 *
 * <p>This exists to help test harnesses print canonical error codes in CI logs without
 * depending on LLM message wording or wrapper shapes. The snapshot intentionally excludes
 * message and data to reduce risk of leaking sensitive content.</p>
 *
 * <p>The snapshot MAY include safe, provider-agnostic diagnostics such as the progressive intent
 * extraction path and validator issue codes. It must never include raw user queries, retrieved
 * content, or document IDs.</p>
 */
public final class OrchestrationResultDebugSnapshotStore {

    private static final int MAX_RECENT = 25;

    private static final String METADATA_KEY_EXTRACTION_DIAGNOSTICS = "extractionDiagnostics";
    private static final String METADATA_KEY_INTENT_METADATA = "intentMetadata";

    private static final String EXTRACTION_KEY_PATH = "extractionPath";
    private static final String EXTRACTION_KEY_LLM_CALLS = "llmCalls";
    private static final String EXTRACTION_KEY_ATTEMPTS = "attempts";
    private static final String EXTRACTION_ATTEMPT_ISSUE_CODES = "issueCodes";

    private static final String INTENT_METADATA_KEY_NORMALIZATION = "normalization";
    private static final String NORMALIZATION_KEY_RULES = "appliedRules";

    private static final Object LOCK = new Object();
    private static final Deque<Snapshot> RECENT = new ArrayDeque<>(MAX_RECENT);
    private static volatile Snapshot last;

    private OrchestrationResultDebugSnapshotStore() {}

    public static void record(String requestId, OrchestrationResult result) {
        if (result == null) {
            return;
        }

        ExtractionDiagnostics diagnostics = extractDiagnostics(result);

        Snapshot snapshot = new Snapshot(
            requestId,
            result.getType() != null ? result.getType().name() : null,
            result.isSuccess(),
            result.getErrorCode(),
            Instant.now().toString(),
            diagnostics.extractionPath(),
            diagnostics.llmCalls(),
            diagnostics.issueCodes(),
            diagnostics.normalizationRules()
        );
        synchronized (LOCK) {
            last = snapshot;
            RECENT.addLast(snapshot);
            while (RECENT.size() > MAX_RECENT) {
                RECENT.removeFirst();
            }
        }
    }

    public static Snapshot getLast() {
        return last;
    }

    /**
     * Returns a defensive copy of the most recent snapshots (oldest -> newest).
     */
    public static List<Snapshot> getRecent() {
        synchronized (LOCK) {
            return new ArrayList<>(RECENT);
        }
    }

    /**
     * Clears stored snapshots (useful for isolating test output).
     */
    public static void clear() {
        synchronized (LOCK) {
            last = null;
            RECENT.clear();
        }
    }

    public record Snapshot(
        String requestId,
        String type,
        boolean success,
        String errorCode,
        String recordedAt,
        String extractionPath,
        Integer llmCalls,
        List<String> issueCodes,
        List<String> normalizationRules
    ) {
        @Override
        public String toString() {
            return "Snapshot{" +
                "requestId=" + Objects.toString(requestId, "null") +
                ", type=" + Objects.toString(type, "null") +
                ", success=" + success +
                ", errorCode=" + Objects.toString(errorCode, "null") +
                ", recordedAt=" + Objects.toString(recordedAt, "null") +
                ", extractionPath=" + Objects.toString(extractionPath, "null") +
                ", llmCalls=" + Objects.toString(llmCalls, "null") +
                ", issueCodes=" + Objects.toString(issueCodes, "null") +
                ", normalizationRules=" + Objects.toString(normalizationRules, "null") +
                '}';
        }
    }

    private record ExtractionDiagnostics(
        String extractionPath,
        Integer llmCalls,
        List<String> issueCodes,
        List<String> normalizationRules
    ) {}

    private static ExtractionDiagnostics extractDiagnostics(OrchestrationResult result) {
        if (result == null || result.getMetadata() == null || result.getMetadata().isEmpty()) {
            return new ExtractionDiagnostics(null, null, List.of(), List.of());
        }

        Map<String, Object> metadata = result.getMetadata();
        String extractionPath = null;
        Integer llmCalls = null;
        Set<String> issueCodes = new LinkedHashSet<>();
        Set<String> normalizationRules = new LinkedHashSet<>();

        Object extraction = metadata.get(METADATA_KEY_EXTRACTION_DIAGNOSTICS);
        if (extraction instanceof Map<?, ?> extractionMap) {
            Object pathValue = extractionMap.get(EXTRACTION_KEY_PATH);
            if (pathValue instanceof String text && !text.isBlank()) {
                extractionPath = text.trim();
            }
            Object llmValue = extractionMap.get(EXTRACTION_KEY_LLM_CALLS);
            if (llmValue instanceof Number number) {
                llmCalls = number.intValue();
            } else if (llmValue instanceof String text && !text.isBlank()) {
                try {
                    llmCalls = Integer.parseInt(text.trim());
                } catch (NumberFormatException ignored) {
                    // ignore
                }
            }

            Object attempts = extractionMap.get(EXTRACTION_KEY_ATTEMPTS);
            if (attempts instanceof List<?> attemptList) {
                for (Object attempt : attemptList) {
                    if (!(attempt instanceof Map<?, ?> attemptMap)) {
                        continue;
                    }
                    Object codes = attemptMap.get(EXTRACTION_ATTEMPT_ISSUE_CODES);
                    if (codes instanceof List<?> codeList) {
                        for (Object code : codeList) {
                            if (code != null) {
                                String value = code.toString().trim();
                                if (!value.isEmpty()) {
                                    issueCodes.add(value);
                                }
                            }
                        }
                    }
                }
            }
        }

        Object intentMetadata = metadata.get(METADATA_KEY_INTENT_METADATA);
        if (intentMetadata instanceof Map<?, ?> intentMap) {
            Object normalization = intentMap.get(INTENT_METADATA_KEY_NORMALIZATION);
            if (normalization instanceof Map<?, ?> normalizationMap) {
                Object rules = normalizationMap.get(NORMALIZATION_KEY_RULES);
                if (rules instanceof List<?> list) {
                    for (Object rule : list) {
                        if (rule != null) {
                            String value = rule.toString().trim();
                            if (!value.isEmpty()) {
                                normalizationRules.add(value);
                            }
                        }
                    }
                }
            }
        }

        return new ExtractionDiagnostics(
            extractionPath,
            llmCalls,
            List.copyOf(issueCodes),
            List.copyOf(normalizationRules)
        );
    }
}
