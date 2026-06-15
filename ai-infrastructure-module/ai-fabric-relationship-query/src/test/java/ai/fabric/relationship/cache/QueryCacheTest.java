package ai.fabric.relationship.cache;

import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.relationship.config.RelationshipQueryProperties;
import ai.fabric.relationship.dto.JpqlQuery;
import ai.fabric.relationship.dto.RelationshipQueryPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class QueryCacheTest {

    private QueryCache cache;
    private RelationshipQueryProperties properties;

    @BeforeEach
    void setUp() {
        properties = new RelationshipQueryProperties();
        properties.setEnableQueryCaching(true);
        RelationshipQueryProperties.CacheProperties cacheProperties = properties.getCache();
        cacheProperties.setEnabled(true);
        cacheProperties.setCacheResults(true);
        cacheProperties.getPlan().setTtlSeconds(1);
        cacheProperties.getEmbedding().setTtlSeconds(1);
        cacheProperties.getResult().setTtlSeconds(1);
        cacheProperties.getPlan().setMaxEntries(2);
        cache = new QueryCache(properties);
    }

    @Test
    void shouldCacheAndRetrievePlan() {
        RelationshipQueryPlan plan = RelationshipQueryPlan.builder()
            .originalQuery("find documents")
            .build();
        String key = QueryCache.hash("find documents");

        cache.putPlan(key, plan);

        Optional<RelationshipQueryPlan> cached = cache.getPlan(key);
        assertThat(cached).isPresent();
        assertThat(cached.get().getOriginalQuery()).isEqualTo("find documents");
        assertThat(cache.getPlanStats().hits()).isEqualTo(1);
    }

    @Test
    void shouldExpirePlanAfterTtl() throws Exception {
        RelationshipQueryPlan plan = RelationshipQueryPlan.builder()
            .originalQuery("expiring")
            .build();
        String key = QueryCache.hash("expiring");

        cache.putPlan(key, plan);
        Thread.sleep(1100);

        Optional<RelationshipQueryPlan> cached = cache.getPlan(key);
        assertThat(cached).isEmpty();
        assertThat(cache.getPlanStats().misses()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void shouldEvictOldEntriesWhenMaxExceeded() {
        RelationshipQueryPlan plan1 = RelationshipQueryPlan.builder().originalQuery("q1").build();
        RelationshipQueryPlan plan2 = RelationshipQueryPlan.builder().originalQuery("q2").build();
        RelationshipQueryPlan plan3 = RelationshipQueryPlan.builder().originalQuery("q3").build();

        cache.putPlan(QueryCache.hash("q1"), plan1);
        cache.putPlan(QueryCache.hash("q2"), plan2);
        cache.putPlan(QueryCache.hash("q3"), plan3);

        assertThat(cache.getPlanStats().size()).isEqualTo(2);
    }

    @Test
    void shouldCacheEmbeddingsAndResultsIndependently() {
        AIEmbeddingResponse embedding = AIEmbeddingResponse.builder()
            .dimensions(10)
            .model("test-model")
            .embedding(List.of(0.1d, 0.2d))
            .build();
        String embeddingKey = QueryCache.hash("semantic query");

        cache.putEmbedding(embeddingKey, embedding);
        assertThat(cache.getEmbedding(embeddingKey)).contains(embedding);

        JpqlQuery jpqlQuery = JpqlQuery.builder()
            .jpql("SELECT d FROM Document d")
            .parameters(java.util.Map.of("status", "ACTIVE"))
            .build();
        String resultKey = QueryCache.hash(jpqlQuery);
        cache.putQueryResult(resultKey, List.of("1", "2"));

        assertThat(cache.getQueryResult(resultKey)).contains(List.of("1", "2"));
    }
}
