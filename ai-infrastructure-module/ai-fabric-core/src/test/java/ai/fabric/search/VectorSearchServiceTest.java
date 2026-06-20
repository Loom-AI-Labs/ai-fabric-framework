package ai.fabric.search;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.rag.VectorDatabaseService;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class VectorSearchServiceTest {

    @Test
    void storeVectorEvictsCachedSearchResults() {
        VectorDatabaseService vectorDatabaseService = mock(VectorDatabaseService.class);
        ConcurrentMapCacheManager cacheManager = new ConcurrentMapCacheManager("vectorSearch");
        Cache cache = cacheManager.getCache("vectorSearch");
        cache.put("stale-query", "stale-result");
        VectorSearchService service = new VectorSearchService(new AIProviderConfig(), vectorDatabaseService, cacheManager);

        service.storeVector("product", "p-1", "Wireless headphones", List.of(0.1d, 0.2d), Map.of());

        assertThat(cache.get("stale-query")).isNull();
        verify(vectorDatabaseService).storeVector("product", "p-1", "Wireless headphones", List.of(0.1d, 0.2d), Map.of());
    }
}
