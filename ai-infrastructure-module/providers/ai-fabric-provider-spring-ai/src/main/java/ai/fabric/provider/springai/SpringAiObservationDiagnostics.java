package ai.fabric.provider.springai;

import io.micrometer.observation.Observation;
import org.springframework.ai.chat.client.observation.ChatClientObservationContext;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.embedding.observation.EmbeddingModelObservationContext;
import org.springframework.ai.observation.AiOperationMetadata;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.ai.chat.client.advisor.observation.AdvisorObservationContext;

import java.util.Collections;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class SpringAiObservationDiagnostics {

    static final Object START_NANOS_KEY = SpringAiObservationDiagnostics.class.getName() + ".startNanos";

    private final AtomicLong totalStarted = new AtomicLong();
    private final AtomicLong totalCompleted = new AtomicLong();
    private final AtomicLong totalErrors = new AtomicLong();
    private final Map<ObservationDimension, ObservationBucket> buckets = new ConcurrentHashMap<>();

    void recordStart(Observation.Context context) {
        ObservationDimension dimension = dimensionFor(context);
        if (dimension == null) {
            return;
        }
        context.put(START_NANOS_KEY, System.nanoTime());
        totalStarted.incrementAndGet();
        buckets.computeIfAbsent(dimension, ignored -> new ObservationBucket()).recordStarted();
    }

    void recordStop(Observation.Context context) {
        ObservationDimension dimension = dimensionFor(context);
        if (dimension == null) {
            return;
        }
        Long startNanos = context.get(START_NANOS_KEY);
        long durationMs = startNanos != null ? Math.max((System.nanoTime() - startNanos) / 1_000_000L, 0L) : 0L;
        Throwable error = context.getError();
        UsageSnapshot usage = usageFor(context);
        totalCompleted.incrementAndGet();
        if (error != null) {
            totalErrors.incrementAndGet();
        }
        buckets.computeIfAbsent(dimension, ignored -> new ObservationBucket())
            .recordCompleted(durationMs, error, usage);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("totalStarted", totalStarted.get());
        value.put("totalCompleted", totalCompleted.get());
        value.put("totalErrors", totalErrors.get());
        List<Map<String, Object>> dimensions = buckets.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getValue().snapshot(entry.getKey()))
            .toList();
        value.put("dimensions", dimensions);
        return Collections.unmodifiableMap(value);
    }

    public boolean hasObservations() {
        return totalStarted.get() > 0 || totalCompleted.get() > 0;
    }

    private ObservationDimension dimensionFor(Observation.Context context) {
        if (context instanceof ChatClientObservationContext chatClient) {
            AiOperationMetadata metadata = chatClient.getOperationMetadata();
            return new ObservationDimension(
                "chat_client",
                provider(metadata),
                operation(metadata, "chat_client"),
                "none",
                Boolean.toString(chatClient.isStream())
            );
        }
        if (context instanceof ChatModelObservationContext chatModel) {
            AiOperationMetadata metadata = chatModel.getOperationMetadata();
            return new ObservationDimension(
                "chat_model",
                provider(metadata),
                operation(metadata, "chat_model"),
                "none",
                Boolean.toString(chatModel.isStreaming())
            );
        }
        if (context instanceof EmbeddingModelObservationContext embeddingModel) {
            AiOperationMetadata metadata = embeddingModel.getOperationMetadata();
            return new ObservationDimension(
                "embedding_model",
                provider(metadata),
                operation(metadata, "embedding_model"),
                "none",
                "unknown"
            );
        }
        if (context instanceof AdvisorObservationContext advisor) {
            return new ObservationDimension(
                "advisor",
                "spring-ai",
                "chat_client_advisor",
                safeValue(advisor.getAdvisorName(), "unknown"),
                "unknown"
            );
        }
        if (context instanceof ToolCallingObservationContext toolCalling) {
            AiOperationMetadata metadata = toolCalling.getOperationMetadata();
            String toolName = toolCalling.getToolDefinition() != null
                ? toolCalling.getToolDefinition().name()
                : toolCalling.getToolType();
            return new ObservationDimension(
                "tool_calling",
                provider(metadata),
                operation(metadata, "tool_calling"),
                safeValue(toolName, "unknown"),
                "unknown"
            );
        }
        return null;
    }

    private UsageSnapshot usageFor(Observation.Context context) {
        Usage usage = null;
        if (context instanceof ChatModelObservationContext chatModel
            && chatModel.getResponse() != null
            && chatModel.getResponse().getMetadata() != null) {
            usage = chatModel.getResponse().getMetadata().getUsage();
        } else if (context instanceof ChatClientObservationContext chatClient
            && chatClient.getResponse() != null
            && chatClient.getResponse().chatResponse() != null
            && chatClient.getResponse().chatResponse().getMetadata() != null) {
            usage = chatClient.getResponse().chatResponse().getMetadata().getUsage();
        }
        if (usage == null) {
            return UsageSnapshot.empty();
        }
        return new UsageSnapshot(
            valueOrZero(usage.getPromptTokens()),
            valueOrZero(usage.getCompletionTokens()),
            valueOrZero(usage.getTotalTokens())
        );
    }

    private String provider(AiOperationMetadata metadata) {
        return metadata != null ? safeValue(metadata.provider(), "unknown") : "unknown";
    }

    private String operation(AiOperationMetadata metadata, String fallback) {
        return metadata != null ? safeValue(metadata.operationType(), fallback) : fallback;
    }

    private static long valueOrZero(Number value) {
        return value != null ? Math.max(value.longValue(), 0L) : 0L;
    }

    private static String safeValue(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String sanitized = value.trim()
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace('\t', ' ');
        if (sanitized.length() > 80) {
            return sanitized.substring(0, 80);
        }
        return sanitized;
    }

    private record ObservationDimension(String type,
                                        String provider,
                                        String operation,
                                        String component,
                                        String streaming) implements Comparable<ObservationDimension> {

        @Override
        public int compareTo(ObservationDimension other) {
            return Comparator.comparing(ObservationDimension::type)
                .thenComparing(ObservationDimension::provider)
                .thenComparing(ObservationDimension::operation)
                .thenComparing(ObservationDimension::component)
                .thenComparing(ObservationDimension::streaming)
                .compare(this, other);
        }

        Map<String, Object> asMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("type", type);
            value.put("provider", provider);
            value.put("operation", operation);
            value.put("component", component);
            value.put("streaming", streaming);
            return value;
        }
    }

    private static final class ObservationBucket {
        private final AtomicLong started = new AtomicLong();
        private final AtomicLong completed = new AtomicLong();
        private final AtomicLong errors = new AtomicLong();
        private final AtomicLong totalDurationMs = new AtomicLong();
        private final AtomicLong maxDurationMs = new AtomicLong();
        private final AtomicLong promptTokens = new AtomicLong();
        private final AtomicLong completionTokens = new AtomicLong();
        private final AtomicLong totalTokens = new AtomicLong();
        private volatile Instant lastObservedAt;
        private volatile Instant lastErrorAt;
        private volatile String lastErrorType;

        void recordStarted() {
            started.incrementAndGet();
            lastObservedAt = Instant.now();
        }

        void recordCompleted(long durationMs, Throwable error, UsageSnapshot usage) {
            completed.incrementAndGet();
            totalDurationMs.addAndGet(durationMs);
            maxDurationMs.accumulateAndGet(durationMs, Math::max);
            promptTokens.addAndGet(usage.promptTokens());
            completionTokens.addAndGet(usage.completionTokens());
            totalTokens.addAndGet(usage.totalTokens());
            lastObservedAt = Instant.now();
            if (error != null) {
                errors.incrementAndGet();
                lastErrorAt = Instant.now();
                lastErrorType = error.getClass().getSimpleName();
            }
        }

        Map<String, Object> snapshot(ObservationDimension dimension) {
            long completedCount = completed.get();
            Map<String, Object> value = new LinkedHashMap<>(dimension.asMap());
            value.put("started", started.get());
            value.put("completed", completedCount);
            value.put("errors", errors.get());
            value.put("totalDurationMs", totalDurationMs.get());
            value.put("averageDurationMs", completedCount == 0 ? 0.0d : (double) totalDurationMs.get() / completedCount);
            value.put("maxDurationMs", maxDurationMs.get());
            value.put("promptTokens", promptTokens.get());
            value.put("completionTokens", completionTokens.get());
            value.put("totalTokens", totalTokens.get());
            value.put("lastObservedAt", Objects.toString(lastObservedAt, null));
            value.put("lastErrorAt", Objects.toString(lastErrorAt, null));
            value.put("lastErrorType", lastErrorType);
            return Collections.unmodifiableMap(value);
        }
    }

    private record UsageSnapshot(long promptTokens, long completionTokens, long totalTokens) {
        static UsageSnapshot empty() {
            return new UsageSnapshot(0L, 0L, 0L);
        }
    }
}
