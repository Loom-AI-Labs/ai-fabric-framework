package ai.fabric.cache;

import ai.fabric.config.AIServiceConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import static org.assertj.core.api.Assertions.assertThat;

class AICacheConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(AICacheConfig.class)
        .withBean(AIServiceConfig.class, AIServiceConfig::new);

    @Test
    void createsOnlyNamespacedFrameworkCacheRegions() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(CacheManager.class);
            assertThat(context).doesNotHaveBean("embeddingCache");
            assertThat(context).doesNotHaveBean("searchCache");
            assertThat(context).doesNotHaveBean("generationCache");

            CacheManager cacheManager = context.getBean(CacheManager.class);
            assertThat(cacheManager).isInstanceOf(CaffeineCacheManager.class);
            assertThat(cacheManager.getCacheNames()).contains(
                AICacheNames.EMBEDDINGS,
                AICacheNames.VECTOR_SEARCH,
                AICacheNames.TEXT_SEARCH,
                AICacheNames.AI_GENERATION,
                AICacheNames.AI_VALIDATION,
                AICacheNames.RETENTION_STATUS,
                AICacheNames.BEHAVIOR_RETENTION,
                AICacheNames.ACCESS_DECISIONS
            );

            assertThat(cacheManager.getCache("embeddings")).isNull();
            assertThat(cacheManager.getCache("vectorSearch")).isNull();
            assertThat(cacheManager.getCache("textSearch")).isNull();
            assertThat(cacheManager.getCache("aiGeneration")).isNull();
            assertThat(cacheManager.getCache("aiValidation")).isNull();
            assertThat(cacheManager.getCache("retentionStatus")).isNull();
            assertThat(cacheManager.getCache("behaviorRetention")).isNull();
            assertThat(cacheManager.getCache("accessDecisions")).isNull();
        });
    }

    @Test
    void backsOffWhenApplicationProvidesCacheManager() {
        ConcurrentMapCacheManager applicationCacheManager = new ConcurrentMapCacheManager("application-cache");

        contextRunner
            .withBean(CacheManager.class, () -> applicationCacheManager)
            .run(context -> {
                assertThat(context).hasSingleBean(CacheManager.class);
                assertThat(context.getBean(CacheManager.class)).isSameAs(applicationCacheManager);
                assertThat(context).doesNotHaveBean("embeddingCache");
                assertThat(context).doesNotHaveBean("searchCache");
                assertThat(context).doesNotHaveBean("generationCache");

                assertThat(applicationCacheManager.getCacheNames()).containsExactly("application-cache");
                assertThat(applicationCacheManager.getCache(AICacheNames.EMBEDDINGS)).isNull();
                assertThat(applicationCacheManager.getCache(AICacheNames.VECTOR_SEARCH)).isNull();
            });
    }

}
