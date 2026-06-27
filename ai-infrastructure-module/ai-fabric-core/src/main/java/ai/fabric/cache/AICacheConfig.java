package ai.fabric.cache;

import ai.fabric.config.AIServiceConfig;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Set;

/**
 * AI Cache Configuration
 * 
 * This configuration sets up caching for AI services including
 * embeddings, search results, and other frequently accessed data.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Slf4j
@Configuration
@EnableCaching
@RequiredArgsConstructor
public class AICacheConfig {

    private static final Duration ACCESS_DECISION_TTL = Duration.ofSeconds(60);
    private static final Duration EMBEDDING_CACHE_TTL = Duration.ofHours(24);
    private static final Duration SEARCH_CACHE_TTL = Duration.ofMinutes(30);
    private static final Duration GENERATION_CACHE_TTL = Duration.ofHours(6);

    private final AIServiceConfig serviceConfig;
    
    /**
     * Configure Caffeine cache manager for AI services
     * 
     * @return configured cache manager
     */
    @Bean
    @ConditionalOnMissingBean(CacheManager.class)
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        
        AIServiceConfig.PerformanceConfig performance = serviceConfig.getPerformance();
        AIServiceConfig.CacheConfig cache = serviceConfig.getCache();

        int maxConcurrentRequests = performance != null && performance.getMaxConcurrentRequests() != null
            ? performance.getMaxConcurrentRequests()
            : 10;
        long configuredMaxSize = cache != null && cache.getMaxSize() != null
            ? cache.getMaxSize()
            : maxConcurrentRequests * 10L;
        Duration ttl = cache != null && cache.getDefaultTtl() != null
            ? cache.getDefaultTtl()
            : Duration.ofMinutes(60);
        
        long maxSize = Math.max(configuredMaxSize, 1);

        // Configure Caffeine cache
        Caffeine<Object, Object> caffeine = Caffeine.newBuilder()
            .maximumSize(maxSize)
            .expireAfterWrite(ttl)
            .recordStats()
            .removalListener((key, value, cause) -> 
                log.debug("Cache entry removed: {} - {}", key, cause));
        
        cacheManager.setCaffeine(caffeine);
        
        // Configure specific caches
        cacheManager.setCacheNames(Set.of(
            AICacheNames.EMBEDDINGS,
            AICacheNames.VECTOR_SEARCH,
            AICacheNames.TEXT_SEARCH,
            AICacheNames.AI_GENERATION,
            AICacheNames.AI_VALIDATION,
            AICacheNames.RETENTION_STATUS,
            AICacheNames.BEHAVIOR_RETENTION
        ));
        cacheManager.registerCustomCache(
            AICacheNames.EMBEDDINGS,
            buildNativeCache(maxSize, EMBEDDING_CACHE_TTL, "Embedding cache entry removed")
        );
        cacheManager.registerCustomCache(
            AICacheNames.VECTOR_SEARCH,
            buildNativeCache(maxSize, SEARCH_CACHE_TTL, "Vector search cache entry removed")
        );
        cacheManager.registerCustomCache(
            AICacheNames.TEXT_SEARCH,
            buildNativeCache(maxSize, SEARCH_CACHE_TTL, "Text search cache entry removed")
        );
        cacheManager.registerCustomCache(
            AICacheNames.AI_GENERATION,
            buildNativeCache(maxSize, GENERATION_CACHE_TTL, "Generation cache entry removed")
        );
        cacheManager.registerCustomCache(
            AICacheNames.ACCESS_DECISIONS,
            Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(ACCESS_DECISION_TTL)
                .recordStats()
                .removalListener((key, value, cause) ->
                    log.debug("Access decision cache entry removed: {} - {}", key, cause))
                .build()
        );
        
        log.info("AI Cache Manager configured with max size {} and TTL {}", configuredMaxSize, ttl);
        log.info("AI access decision cache configured with max size {} and TTL {}", configuredMaxSize, ACCESS_DECISION_TTL);
        
        return cacheManager;
    }

    private com.github.benmanes.caffeine.cache.Cache<Object, Object> buildNativeCache(long maxSize,
                                                                                      Duration ttl,
                                                                                      String removalMessage) {
        return Caffeine.newBuilder()
            .maximumSize(maxSize)
            .expireAfterWrite(ttl)
            .recordStats()
            .removalListener((key, value, cause) ->
                log.debug("{}: {} - {}", removalMessage, key, cause))
            .build();
    }
}
