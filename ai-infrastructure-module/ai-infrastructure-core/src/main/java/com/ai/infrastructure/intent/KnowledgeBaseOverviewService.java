package com.ai.infrastructure.intent;

import com.ai.infrastructure.config.AIEntityConfigurationLoader;
import com.ai.infrastructure.rag.VectorDatabaseService;
import lombok.extern.slf4j.Slf4j;
import com.ai.infrastructure.config.condition.VectorDbConfiguredCondition;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Provides lightweight statistics about the indexed knowledge base to enrich prompts.
 */
@Slf4j
@Service
@Conditional(VectorDbConfiguredCondition.class)
public class KnowledgeBaseOverviewService {

    private static final long FAST_OVERVIEW_CACHE_TTL_NANOS = Duration.ofSeconds(1).toNanos();
    private static final long EXPENSIVE_OVERVIEW_CACHE_TTL_NANOS = Duration.ofSeconds(15).toNanos();

    private final VectorDatabaseService vectorDatabaseService;
    private final AIEntityConfigurationLoader configurationLoader;
    private volatile CachedOverview cachedOverview;

    public KnowledgeBaseOverviewService(VectorDatabaseService vectorDatabaseService,
                                        AIEntityConfigurationLoader configurationLoader) {
        this.vectorDatabaseService = vectorDatabaseService;
        this.configurationLoader = configurationLoader;
    }

    public KnowledgeBaseOverview getOverview() {
        CachedOverview cached = cachedOverview;
        long now = System.nanoTime();
        if (cached != null && cached.expiresAtNanos() > now) {
            return cached.overview();
        }

        synchronized (this) {
            cached = cachedOverview;
            now = System.nanoTime();
            if (cached != null && cached.expiresAtNanos() > now) {
                return cached.overview();
            }

            KnowledgeBaseOverview overview = buildOverview();
            cachedOverview = new CachedOverview(overview, now + resolveCacheTtlNanos());
            return overview;
        }
    }

    private long resolveCacheTtlNanos() {
        try {
            return vectorDatabaseService.supportsEfficientEntityTypeCount()
                ? FAST_OVERVIEW_CACHE_TTL_NANOS
                : EXPENSIVE_OVERVIEW_CACHE_TTL_NANOS;
        } catch (Exception ex) {
            log.debug("Unable to determine vector overview cache TTL, using fast default", ex);
            return FAST_OVERVIEW_CACHE_TTL_NANOS;
        }
    }

    private KnowledgeBaseOverview buildOverview() {
        Map<String, Object> stats = safeStatistics();
        Map<String, Long> documentsByType = deriveDocumentsByType(stats);
        List<String> entityTypes = deriveEntityTypes(stats, documentsByType);

        ProviderCoverage coverage = deriveCoverageFromProvider(resolveCandidateEntityTypes(stats, entityTypes, documentsByType));
        if (!coverage.documentsByType().isEmpty()) {
            documentsByType = coverage.documentsByType();
        }
        if (!coverage.entityTypes().isEmpty()) {
            entityTypes = coverage.entityTypes();
        } else if (entityTypes.isEmpty() && !documentsByType.isEmpty()) {
            entityTypes = documentsByType.keySet().stream().toList();
        }

        long totalDocuments = deriveTotalDocuments(stats, documentsByType);
        LocalDateTime lastUpdated = deriveLastUpdated(stats);

        KnowledgeBaseOverview overview = KnowledgeBaseOverview.builder()
            .totalIndexedDocuments(totalDocuments)
            .documentsByType(documentsByType)
            .entityTypes(entityTypes)
            .lastIndexUpdateTime(lastUpdated)
            .rawStatistics(Collections.unmodifiableMap(new LinkedHashMap<>(stats)))
            .build();

        log.debug("Knowledge base overview computed: {}", overview);
        return overview;
    }

    private Map<String, Object> safeStatistics() {
        try {
            Map<String, Object> stats = vectorDatabaseService.getStatistics();
            return stats == null ? Collections.emptyMap() : stats;
        } catch (Exception ex) {
            log.warn("Unable to fetch vector database statistics, continuing with fallbacks", ex);
            return Collections.emptyMap();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> deriveDocumentsByType(Map<String, Object> stats) {
        Object entityTypeCounts = stats.get("entityTypeCounts");
        if (entityTypeCounts instanceof Map<?, ?> rawMap) {
            return rawMap.entrySet().stream()
                .filter(entry -> entry.getKey() != null)
                .collect(Collectors.toMap(
                    entry -> entry.getKey().toString(),
                    entry -> entry.getValue() instanceof Number number ? number.longValue() : 0L,
                    Long::sum,
                    LinkedHashMap::new
                ));
        }
        return Collections.emptyMap();
    }

    private List<String> deriveEntityTypes(Map<String, Object> stats, Map<String, Long> documentsByType) {
        if (documentsByType != null && !documentsByType.isEmpty()) {
            return documentsByType.keySet().stream().toList();
        }
        List<String> entityTypes = extractStringList(stats.get("entityTypes"));
        if (!entityTypes.isEmpty()) {
            return entityTypes;
        }
        return extractStringList(stats.get("knownEntityTypes"));
    }

    private List<String> resolveCandidateEntityTypes(Map<String, Object> stats,
                                                     List<String> entityTypes,
                                                     Map<String, Long> documentsByType) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        if (configurationLoader != null && configurationLoader.getSupportedEntityTypes() != null) {
            configurationLoader.getSupportedEntityTypes().stream()
                .filter(type -> type != null && !type.isBlank())
                .map(type -> type.trim().toLowerCase(Locale.ROOT))
                .forEach(ordered::add);
        }
        if (entityTypes != null) {
            entityTypes.stream()
                .filter(type -> type != null && !type.isBlank())
                .map(type -> type.trim().toLowerCase(Locale.ROOT))
                .forEach(ordered::add);
        }
        if (documentsByType != null) {
            documentsByType.keySet().stream()
                .filter(type -> type != null && !type.isBlank())
                .map(type -> type.trim().toLowerCase(Locale.ROOT))
                .forEach(ordered::add);
        }
        extractStringList(stats.get("entityTypes")).forEach(ordered::add);
        extractStringList(stats.get("knownEntityTypes")).forEach(ordered::add);
        return ordered.stream().toList();
    }

    private ProviderCoverage deriveCoverageFromProvider(List<String> candidateEntityTypes) {
        if (candidateEntityTypes == null || candidateEntityTypes.isEmpty()) {
            return ProviderCoverage.empty();
        }

        Map<String, Long> counts = new LinkedHashMap<>();
        LinkedHashSet<String> entityTypes = new LinkedHashSet<>();
        boolean useExactCounts = vectorDatabaseService.supportsEfficientEntityTypeCount();

        for (String entityType : candidateEntityTypes) {
            if (entityType == null || entityType.isBlank()) {
                continue;
            }

            String normalized = entityType.trim().toLowerCase(Locale.ROOT);
            try {
                if (useExactCounts || !vectorDatabaseService.supportsVectorScan()) {
                    long count = vectorDatabaseService.getVectorCountByEntityType(normalized);
                    if (count > 0) {
                        counts.put(normalized, count);
                        entityTypes.add(normalized);
                    }
                    continue;
                }

                com.ai.infrastructure.dto.VectorScanPage page = vectorDatabaseService.scan(
                    com.ai.infrastructure.dto.VectorScanRequest.builder()
                        .entityType(normalized)
                        .limit(1)
                        .includeContent(false)
                        .includeMetadata(false)
                        .build()
                );
                if (page != null && page.getVectors() != null && !page.getVectors().isEmpty()) {
                    entityTypes.add(normalized);
                }
            } catch (Exception ex) {
                log.debug("Failed to compute knowledge-base coverage for entity type {}", normalized, ex);
            }
        }

        if (!counts.isEmpty()) {
            return new ProviderCoverage(Collections.unmodifiableMap(counts), entityTypes.stream().toList());
        }
        return new ProviderCoverage(Collections.emptyMap(), entityTypes.stream().toList());
    }

    private long deriveTotalDocuments(Map<String, Object> stats, Map<String, Long> documentsByType) {
        List<String> keys = List.of("totalVectors", "totalIndexed", "totalIndexedDocuments");
        for (String key : keys) {
            Optional<Long> value = extractLong(stats.get(key));
            if (value.isPresent()) {
                return value.get();
            }
        }

        if (!documentsByType.isEmpty()) {
            return documentsByType.values().stream().mapToLong(Long::longValue).sum();
        }

        return 0L;
    }

    private LocalDateTime deriveLastUpdated(Map<String, Object> stats) {
        List<String> keys = List.of(
            "lastIndexUpdateTime",
            "lastUpdated",
            "updatedAt",
            "_indexedUpdatedAt"
        );
        for (String key : keys) {
            Optional<LocalDateTime> timestamp = extractDateTime(stats.get(key));
            if (timestamp.isPresent()) {
                return timestamp.get();
            }
        }
        return null;
    }

    private Optional<Long> extractLong(Object value) {
        if (value instanceof Number number) {
            return Optional.of(number.longValue());
        }
        if (value instanceof String string && !string.isBlank()) {
            try {
                return Optional.of(Long.parseLong(string.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return Optional.empty();
    }

    private Optional<LocalDateTime> extractDateTime(Object value) {
        if (value instanceof LocalDateTime dateTime) {
            return Optional.of(dateTime);
        }
        if (value instanceof Number number) {
            return Optional.of(LocalDateTime.ofEpochSecond(number.longValue() / 1000L, 0, ZoneOffset.UTC));
        }
        if (value instanceof String string && !string.isBlank()) {
            String trimmed = string.trim();
            try {
                return Optional.of(LocalDateTime.parse(trimmed));
            } catch (DateTimeParseException ignored) {
            }
            try {
                long millis = Long.parseLong(trimmed);
                return Optional.of(LocalDateTime.ofEpochSecond(millis / 1000L, 0, ZoneOffset.UTC));
            } catch (NumberFormatException ignored) {
            }
        }
        return Optional.empty();
    }

    private List<String> extractStringList(Object value) {
        if (!(value instanceof Collection<?> rawValues) || rawValues.isEmpty()) {
            return List.of();
        }
        return rawValues.stream()
            .filter(item -> item != null && !item.toString().isBlank())
            .map(item -> item.toString().trim().toLowerCase(Locale.ROOT))
            .distinct()
            .toList();
    }

    private record CachedOverview(KnowledgeBaseOverview overview, long expiresAtNanos) {
    }

    private record ProviderCoverage(Map<String, Long> documentsByType, List<String> entityTypes) {
        private static ProviderCoverage empty() {
            return new ProviderCoverage(Collections.emptyMap(), List.of());
        }
    }
}
