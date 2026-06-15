package ai.fabric.relationship.cache;

import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.relationship.config.RelationshipQueryProperties;
import ai.fabric.relationship.dto.JpqlQuery;
import ai.fabric.relationship.dto.RelationshipQueryPlan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Centralized cache manager for relationship query artifacts (plans, embeddings, and results).
 */
public class QueryCache {

    private final boolean enabled;
    private final boolean resultEnabled;
    private final CacheRegion<String, RelationshipQueryPlan> planCache;
    private final CacheRegion<String, AIEmbeddingResponse> embeddingCache;
    private final CacheRegion<String, List<String>> resultCache;

    public QueryCache(RelationshipQueryProperties properties) {
        RelationshipQueryProperties.CacheProperties cacheProperties = properties.getCache();
        this.enabled = properties.isEnableQueryCaching() && cacheProperties.isEnabled();
        this.resultEnabled = cacheProperties.isCacheResults();
        planCache = new CacheRegion<>(
            cacheProperties.getPlan().ttlMillis(),
            cacheProperties.getPlan().getMaxEntries()
        );
        embeddingCache = new CacheRegion<>(
            cacheProperties.getEmbedding().ttlMillis(),
            cacheProperties.getEmbedding().getMaxEntries()
        );
        resultCache = new CacheRegion<>(
            cacheProperties.getResult().ttlMillis(),
            cacheProperties.getResult().getMaxEntries()
        );
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Optional<RelationshipQueryPlan> getPlan(String key) {
        return enabled ? planCache.get(key) : Optional.empty();
    }

    public void putPlan(String key, RelationshipQueryPlan plan) {
        if (enabled && plan != null) {
            planCache.put(key, plan);
        }
    }

    public Optional<AIEmbeddingResponse> getEmbedding(String key) {
        return enabled ? embeddingCache.get(key) : Optional.empty();
    }

    public void putEmbedding(String key, AIEmbeddingResponse embedding) {
        if (enabled && embedding != null) {
            embeddingCache.put(key, embedding);
        }
    }

    public Optional<List<String>> getQueryResult(String key) {
        return enabled && resultEnabled ? resultCache.get(key) : Optional.empty();
    }

    public void putQueryResult(String key, List<String> entityIds) {
        if (enabled && resultEnabled && entityIds != null) {
            resultCache.put(key, List.copyOf(entityIds));
        }
    }

    public CacheStats getPlanStats() {
        return planCache.stats();
    }

    public CacheStats getEmbeddingStats() {
        return embeddingCache.stats();
    }

    public CacheStats getResultStats() {
        return resultCache.stats();
    }

    public void invalidatePlan(String key) {
        planCache.invalidate(key);
    }

    public void invalidateResult(String key) {
        resultCache.invalidate(key);
    }

    public void clearAll() {
        planCache.clear();
        embeddingCache.clear();
        resultCache.clear();
    }

    public static String hash(String value) {
        if (value == null || value.isBlank()) {
            return "empty";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashed) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    public static String hash(JpqlQuery jpqlQuery) {
        if (jpqlQuery == null) {
            return "empty";
        }
        String payload = jpqlQuery.getJpql() + "::" + normalizeParameters(jpqlQuery.getParameters())
            + "::" + jpqlQuery.getLimit();
        return hash(payload);
    }

    private static String normalizeParameters(Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return "{}";
        }
        StringBuilder builder = new StringBuilder("{");
        parameters.keySet().stream().sorted().forEach(key -> {
            Object value = parameters.get(key);
            builder.append(key).append('=').append(value).append(';');
        });
        builder.append('}');
        return builder.toString();
    }

    public record CacheStats(long hits, long misses, long evictions, long size) { }

    private static final class CacheRegion<K, V> {
        private final long ttlMillis;
        private final int maxEntries;
        private final ConcurrentHashMap<K, CacheEntry<V>> entries = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<K> evictionQueue = new ConcurrentLinkedQueue<>();
        private final AtomicLong hits = new AtomicLong();
        private final AtomicLong misses = new AtomicLong();
        private final AtomicLong evictions = new AtomicLong();

        private CacheRegion(long ttlMillis, int maxEntries) {
            this.ttlMillis = ttlMillis;
            this.maxEntries = Math.max(1, maxEntries);
        }

        Optional<V> get(K key) {
            if (key == null) {
                misses.incrementAndGet();
                return Optional.empty();
            }
            CacheEntry<V> entry = entries.get(key);
            if (entry == null) {
                misses.incrementAndGet();
                return Optional.empty();
            }
            if (entry.isExpired(ttlMillis)) {
                entries.remove(key, entry);
                misses.incrementAndGet();
                return Optional.empty();
            }
            hits.incrementAndGet();
            return Optional.of(entry.value());
        }

        void put(K key, V value) {
            if (key == null || value == null) {
                return;
            }
            entries.put(key, new CacheEntry<>(value, System.currentTimeMillis()));
            evictionQueue.add(key);
            evictIfNecessary();
        }

        void invalidate(K key) {
            if (key != null) {
                entries.remove(key);
            }
        }

        void clear() {
            entries.clear();
            evictionQueue.clear();
        }

        CacheStats stats() {
            return new CacheStats(hits.get(), misses.get(), evictions.get(), entries.size());
        }

        private void evictIfNecessary() {
            evictExpiredEntries();
            while (entries.size() > maxEntries) {
                K oldest = evictionQueue.poll();
                if (oldest == null) {
                    break;
                }
                if (entries.remove(oldest) != null) {
                    evictions.incrementAndGet();
                }
            }
        }

        private void evictExpiredEntries() {
            if (ttlMillis <= 0) {
                return;
            }
            for (int i = 0; i < 32; i++) {
                K key = evictionQueue.peek();
                if (key == null) {
                    return;
                }
                CacheEntry<V> entry = entries.get(key);
                if (entry == null) {
                    evictionQueue.poll();
                    continue;
                }
                if (entry.isExpired(ttlMillis)) {
                    evictionQueue.poll();
                    if (entries.remove(key, entry)) {
                        evictions.incrementAndGet();
                    }
                } else {
                    return;
                }
            }
        }
    }

    private record CacheEntry<V>(V value, long timestamp) {
        boolean isExpired(long ttlMillis) {
            return ttlMillis > 0 && (System.currentTimeMillis() - timestamp) > ttlMillis;
        }
    }
}
