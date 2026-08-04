package com.ai.fabric.realapps.livesync.service;

import ai.fabric.embedding.EmbeddingProvider;
import ai.fabric.provider.AIProvider;
import ai.fabric.service.VectorManagementService;
import com.ai.fabric.examples.smoke.health.DemoHealthContributor;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class LiveSyncHealthContributor implements DemoHealthContributor {

    private final Environment environment;
    private final List<AIProvider> generationProviders;
    private final List<EmbeddingProvider> embeddingProviders;
    private final DataSource dataSource;
    private final VectorManagementService vectors;

    public LiveSyncHealthContributor(
        Environment environment,
        List<AIProvider> generationProviders,
        List<EmbeddingProvider> embeddingProviders,
        DataSource dataSource,
        VectorManagementService vectors
    ) {
        this.environment = environment;
        this.generationProviders = generationProviders == null
            ? List.of()
            : List.copyOf(generationProviders);
        this.embeddingProviders = embeddingProviders == null
            ? List.of()
            : List.copyOf(embeddingProviders);
        this.dataSource = dataSource;
        this.vectors = vectors;
    }

    @Override
    public Map<String, Object> details() {
        String generationName = environment.getProperty(
            "ai.providers.llm-provider",
            "unknown"
        );
        String embeddingName = environment.getProperty(
            "ai.providers.embedding-provider",
            "unknown"
        );
        boolean generationReady = available(
            generationProviders,
            generationName
        );
        boolean embeddingReady = embeddingProviders.stream().anyMatch(
            provider -> sameProvider(provider.getProviderName(), embeddingName)
                && safelyAvailable(provider)
        );
        boolean domainReady = domainReady();
        boolean vectorReady = vectorReady();
        boolean liveProviderRequired = environment.getProperty(
            "app.demo.require-real-ai",
            Boolean.class,
            false
        );

        Map<String, Object> out = new LinkedHashMap<>();
        out.put(
            "status",
            domainReady
                && vectorReady
                && embeddingReady
                && (!liveProviderRequired || generationReady)
                ? "UP"
                : "DOWN"
        );
        out.put("provider", Map.of(
            "generation", generationName,
            "embeddings", embeddingName,
            "generationReady", generationReady,
            "embeddingReady", embeddingReady,
            "liveProviderRequired", liveProviderRequired
        ));
        out.put("storage", Map.of(
            "domain", domainReady ? "UP" : "DOWN",
            "vector", vectorReady ? "UP" : "DOWN",
            "indexing", domainReady ? "UP" : "DOWN"
        ));
        out.put("indexingLifecycle", Map.of(
            "statusContract", "IndexingWorkStatus",
            "durableReceipts", true,
            "sourceVersionMetadata", "version"
        ));
        return Map.copyOf(out);
    }

    private boolean available(
        List<AIProvider> providers,
        String requested
    ) {
        return providers.stream().anyMatch(provider ->
            sameProvider(provider.getProviderName(), requested)
                && safelyAvailable(provider)
        );
    }

    private boolean safelyAvailable(AIProvider provider) {
        try {
            return provider.isAvailable() && provider.getStatus().isHealthy();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean safelyAvailable(EmbeddingProvider provider) {
        try {
            return provider.isAvailable();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean sameProvider(String actual, String requested) {
        return actual != null
            && requested != null
            && actual.trim().toLowerCase(Locale.ROOT).equals(
                requested.trim().toLowerCase(Locale.ROOT)
            );
    }

    private boolean domainReady() {
        try (var connection = dataSource.getConnection()) {
            return connection.isValid(1);
        } catch (SQLException exception) {
            return false;
        }
    }

    private boolean vectorReady() {
        try {
            vectors.getVectorsByEntityType(EntityKind.PRODUCT.entityType());
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
