package com.ai.fabric.runtime.search;

import com.ai.fabric.runtime.config.RuntimeDeploymentKnowledgeSourceConfigService;
import com.ai.infrastructure.core.AISearchService;
import com.ai.infrastructure.dto.RAGRequest;
import com.ai.infrastructure.rag.VectorDatabaseService;
import com.ai.infrastructure.rag.source.KnowledgeSourceAdapterType;
import com.ai.infrastructure.rag.source.ResolvedKnowledgeSource;
import com.ai.infrastructure.rag.source.SearchSource;
import com.ai.infrastructure.rag.source.SearchSourceRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class RuntimeDeploymentSearchSourceRegistry implements SearchSourceRegistry {

    public static final String CONTRACT_VERSION = "SEARCH_SOURCE_REGISTRY_V1";
    public static final String DIAGNOSTICS_CONTRACT_VERSION = "SEARCH_SOURCE_DIAGNOSTICS_V1";

    private static final List<String> SUPPORTED_ADAPTER_TYPES = List.of(
        KnowledgeSourceAdapterType.DEPLOYMENT_PRIVATE_VECTOR.wireValue(),
        KnowledgeSourceAdapterType.SHARED_INDEX.wireValue()
    );

    private final RuntimeDeploymentKnowledgeSourceConfigService knowledgeSourceConfigService;
    private final AISearchService searchService;
    private final VectorDatabaseService vectorDatabaseService;

    private volatile List<ResolvedKnowledgeSource> configuredSources = List.of();
    private final ConcurrentMap<String, SearchSourceHealthState> sourceHealth = new ConcurrentHashMap<>();
    private final AtomicLong recordedSearchExecutions = new AtomicLong();
    private final AtomicLong degradedSearchExecutions = new AtomicLong();
    private volatile Instant lastRecordedSearchAt;

    public RuntimeDeploymentSearchSourceRegistry(RuntimeDeploymentKnowledgeSourceConfigService knowledgeSourceConfigService,
                                                 AISearchService searchService,
                                                 VectorDatabaseService vectorDatabaseService) {
        this.knowledgeSourceConfigService = knowledgeSourceConfigService;
        this.searchService = searchService;
        this.vectorDatabaseService = vectorDatabaseService;
    }

    @PostConstruct
    void validateAndLoad() {
        List<ResolvedKnowledgeSource> sources = knowledgeSourceConfigService.currentSources();
        Map<String, Object> vectorDiagnostics = vectorDatabaseService.adminDiagnostics();
        boolean sharedStorageSupported = Boolean.TRUE.equals(vectorDiagnostics.get("sharedStorage"));
        for (ResolvedKnowledgeSource source : sources) {
            if (!SUPPORTED_ADAPTER_TYPES.contains(source.getAdapterType())) {
                throw new IllegalStateException(
                    "Unsupported deployment knowledge source adapter '" + source.getAdapterType()
                        + "' for source '" + source.getId() + "'. Supported adapters: " + SUPPORTED_ADAPTER_TYPES
                );
            }
            if (KnowledgeSourceAdapterType.SHARED_INDEX.wireValue().equals(source.getAdapterType())) {
                if (!StringUtils.hasText(source.getHandleRef())) {
                    throw new IllegalStateException(
                        "Shared-index knowledge source '" + source.getId() + "' requires handleRef."
                    );
                }
                if (!sharedStorageSupported) {
                    throw new IllegalStateException(
                        "Shared-index knowledge source '" + source.getId()
                            + "' requires a shared-storage-capable vector provider."
                    );
                }
            }
        }
        configuredSources = List.copyOf(sources);
        initializeHealthState(configuredSources);
    }

    @Override
    public String contractVersion() {
        return CONTRACT_VERSION;
    }

    @Override
    public List<String> supportedAdapterTypes() {
        return SUPPORTED_ADAPTER_TYPES;
    }

    @Override
    public List<SearchSource> resolveSearchSources(RAGRequest request) {
        List<SearchSource> resolved = new ArrayList<>();
        ResolvedKnowledgeSource configuredPrivateSource = configuredSources.stream()
            .filter(source -> KnowledgeSourceAdapterType.DEPLOYMENT_PRIVATE_VECTOR.wireValue().equals(source.getAdapterType()))
            .findFirst()
            .orElse(defaultPrivateSource(request));
        resolved.add(new DeploymentPrivateVectorSearchSource(configuredPrivateSource, searchService, vectorDatabaseService));
        configuredSources.stream()
            .filter(source -> KnowledgeSourceAdapterType.SHARED_INDEX.wireValue().equals(source.getAdapterType()))
            .map(source -> new SharedIndexSearchSource(source, searchService, vectorDatabaseService))
            .forEach(resolved::add);
        return List.copyOf(resolved);
    }

    @Override
    public void recordSearchExecution(List<Map<String, Object>> sourceDiagnostics, boolean degraded) {
        Instant recordedAt = Instant.now();
        lastRecordedSearchAt = recordedAt;
        recordedSearchExecutions.incrementAndGet();
        if (degraded) {
            degradedSearchExecutions.incrementAndGet();
        }
        if (sourceDiagnostics == null || sourceDiagnostics.isEmpty()) {
            return;
        }

        for (Map<String, Object> diagnostic : sourceDiagnostics) {
            if (diagnostic == null || diagnostic.isEmpty()) {
                continue;
            }
            String sourceId = textValue(diagnostic.get("sourceId"));
            if (!StringUtils.hasText(sourceId)) {
                continue;
            }
            SearchSourceHealthState state = sourceHealth.computeIfAbsent(
                sourceId,
                ignored -> SearchSourceHealthState.fromDiagnostic(diagnostic)
            );
            state.record(diagnostic, recordedAt);
        }
    }

    @Override
    public Map<String, Object> adminDiagnostics() {
        List<Map<String, Object>> sourceEntries = sourceHealth.values().stream()
            .sorted(Comparator.comparing(SearchSourceHealthState::sourceId))
            .map(SearchSourceHealthState::toDiagnostics)
            .toList();
        long degradedSources = sourceEntries.stream()
            .filter(entry -> "DEGRADED".equals(entry.get("healthStatus")))
            .count();
        long disabledSources = sourceEntries.stream()
            .filter(entry -> "DISABLED".equals(entry.get("healthStatus")))
            .count();

        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("contractVersion", DIAGNOSTICS_CONTRACT_VERSION);
        diagnostics.put("degradedSearchSupported", true);
        diagnostics.put("configuredSourcesCount", sourceEntries.size());
        diagnostics.put("recordedSearchExecutions", recordedSearchExecutions.get());
        diagnostics.put("degradedSearchExecutions", degradedSearchExecutions.get());
        diagnostics.put("degradedSourcesCount", degradedSources);
        diagnostics.put("disabledSourcesCount", disabledSources);
        diagnostics.put("lastRecordedSearchAt", lastRecordedSearchAt != null ? lastRecordedSearchAt.toString() : null);
        diagnostics.put("sources", sourceEntries);
        return Collections.unmodifiableMap(new LinkedHashMap<>(diagnostics));
    }

    public List<ResolvedKnowledgeSource> configuredSources() {
        return configuredSources;
    }

    private void initializeHealthState(List<ResolvedKnowledgeSource> sources) {
        sourceHealth.clear();
        sourceHealth.put("deployment-private-vector", SearchSourceHealthState.fromResolved(defaultPrivateSource(null), true));
        for (ResolvedKnowledgeSource source : sources) {
            if (source == null || !StringUtils.hasText(source.getId())) {
                continue;
            }
            sourceHealth.put(source.getId(), SearchSourceHealthState.fromResolved(source, false));
        }
    }

    private ResolvedKnowledgeSource defaultPrivateSource(RAGRequest request) {
        String entityType = request != null ? request.getEntityType() : null;
        return ResolvedKnowledgeSource.builder()
            .id("deployment-private-vector")
            .type("deployment-private-vector")
            .adapterType(KnowledgeSourceAdapterType.DEPLOYMENT_PRIVATE_VECTOR.wireValue())
            .attributionLabel("Deployment knowledge")
            .entityType(StringUtils.hasText(entityType) ? entityType : null)
            .filters(Map.of())
            .enabled(true)
            .build();
    }

    private String textValue(Object value) {
        if (value instanceof String text && StringUtils.hasText(text)) {
            return text.trim();
        }
        return null;
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static final class SearchSourceHealthState {
        private final String sourceId;
        private volatile String sourceType;
        private volatile String adapterType;
        private final boolean defaultSource;
        private volatile boolean enabled;
        private volatile String entityType;
        private volatile boolean handleRefConfigured;
        private volatile List<String> authModes;
        private volatile String lastStatus;
        private volatile String lastReason;
        private volatile String lastFailureMessage;
        private volatile Long lastProcessingTimeMs;
        private volatile Long lastResultsCount;
        private volatile Instant lastAttemptAt;
        private volatile Instant lastSuccessAt;
        private volatile Instant lastFailureAt;
        private volatile Instant lastSkippedAt;
        private long successCount;
        private long failureCount;
        private long skippedCount;

        private SearchSourceHealthState(String sourceId,
                                        String sourceType,
                                        String adapterType,
                                        boolean defaultSource,
                                        boolean enabled,
                                        String entityType,
                                        boolean handleRefConfigured,
                                        List<String> authModes) {
            this.sourceId = sourceId;
            this.sourceType = sourceType;
            this.adapterType = adapterType;
            this.defaultSource = defaultSource;
            this.enabled = enabled;
            this.entityType = entityType;
            this.handleRefConfigured = handleRefConfigured;
            this.authModes = authModes != null ? List.copyOf(authModes) : List.of();
        }

        private synchronized void record(Map<String, Object> diagnostic, Instant recordedAt) {
            this.lastAttemptAt = recordedAt;
            this.sourceType = coalesce(textValue(diagnostic.get("sourceType")), this.sourceType);
            this.adapterType = coalesce(textValue(diagnostic.get("adapterType")), this.adapterType);
            this.lastStatus = coalesce(textValue(diagnostic.get("status")), this.lastStatus);
            this.lastReason = coalesce(textValue(diagnostic.get("reason")), this.lastReason);
            this.lastFailureMessage = coalesce(textValue(diagnostic.get("errorMessage")), this.lastFailureMessage);
            Long processingTimeMs = longValue(diagnostic.get("processingTimeMs"));
            if (processingTimeMs != null) {
                this.lastProcessingTimeMs = processingTimeMs;
            }
            Long resultsCount = longValue(diagnostic.get("resultsCount"));
            if (resultsCount != null) {
                this.lastResultsCount = resultsCount;
            }
            if ("SUCCEEDED".equals(this.lastStatus)) {
                successCount++;
                lastSuccessAt = recordedAt;
            } else if ("FAILED".equals(this.lastStatus)) {
                failureCount++;
                lastFailureAt = recordedAt;
            } else if ("SKIPPED".equals(this.lastStatus)) {
                skippedCount++;
                lastSkippedAt = recordedAt;
            }
        }

        private Map<String, Object> toDiagnostics() {
            Map<String, Object> diagnostics = new LinkedHashMap<>();
            diagnostics.put("sourceId", sourceId);
            diagnostics.put("sourceType", sourceType);
            diagnostics.put("adapterType", adapterType);
            diagnostics.put("defaultSource", defaultSource);
            diagnostics.put("enabled", enabled);
            diagnostics.put("entityType", entityType);
            diagnostics.put("authModes", authModes);
            diagnostics.put("handleRefConfigured", handleRefConfigured);
            diagnostics.put("healthStatus", healthStatus());
            diagnostics.put("lastStatus", lastStatus);
            diagnostics.put("lastReason", lastReason);
            diagnostics.put("lastFailureMessage", lastFailureMessage);
            diagnostics.put("lastProcessingTimeMs", lastProcessingTimeMs);
            diagnostics.put("lastResultsCount", lastResultsCount);
            diagnostics.put("lastAttemptAt", lastAttemptAt != null ? lastAttemptAt.toString() : null);
            diagnostics.put("lastSuccessAt", lastSuccessAt != null ? lastSuccessAt.toString() : null);
            diagnostics.put("lastFailureAt", lastFailureAt != null ? lastFailureAt.toString() : null);
            diagnostics.put("lastSkippedAt", lastSkippedAt != null ? lastSkippedAt.toString() : null);
            diagnostics.put("successCount", successCount);
            diagnostics.put("failureCount", failureCount);
            diagnostics.put("skippedCount", skippedCount);
            return Collections.unmodifiableMap(new LinkedHashMap<>(diagnostics));
        }

        private String sourceId() {
            return sourceId;
        }

        private String healthStatus() {
            if (!enabled) {
                return "DISABLED";
            }
            if ("FAILED".equals(lastStatus)) {
                return "DEGRADED";
            }
            return "READY";
        }

        private static SearchSourceHealthState fromResolved(ResolvedKnowledgeSource source, boolean defaultSource) {
            return new SearchSourceHealthState(
                source.getId(),
                source.getType(),
                source.getAdapterType(),
                defaultSource,
                source.isEnabled(),
                source.getEntityType(),
                StringUtils.hasText(source.getHandleRef()),
                source.getAuthModes()
            );
        }

        private static SearchSourceHealthState fromDiagnostic(Map<String, Object> diagnostic) {
            String sourceId = textValue(diagnostic.get("sourceId"));
            return new SearchSourceHealthState(
                StringUtils.hasText(sourceId) ? sourceId : "unknown",
                textValue(diagnostic.get("sourceType")),
                textValue(diagnostic.get("adapterType")),
                false,
                true,
                null,
                false,
                List.of()
            );
        }

        private static String coalesce(String candidate, String currentValue) {
            return StringUtils.hasText(candidate) ? candidate : currentValue;
        }

        private static String textValue(Object value) {
            if (value instanceof String text && StringUtils.hasText(text)) {
                return text.trim();
            }
            return null;
        }

        private static Long longValue(Object value) {
            if (value instanceof Number number) {
                return number.longValue();
            }
            if (value instanceof String text && StringUtils.hasText(text)) {
                try {
                    return Long.parseLong(text.trim());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            return null;
        }
    }
}
