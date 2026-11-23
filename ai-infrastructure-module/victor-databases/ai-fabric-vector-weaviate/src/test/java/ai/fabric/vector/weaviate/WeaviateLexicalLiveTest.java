package ai.fabric.vector.weaviate;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.dto.AISearchRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in, read-only probe. The private configuration is never embedded in test reports. */
@EnabledIfEnvironmentVariable(named = "LOOMAI_LEXICAL_LIVE_CONFIG", matches = ".+")
class WeaviateLexicalLiveTest {
    @Test
    void existingApprovedCorpusSupportsScopedBm25WithoutReindexing() throws Exception {
        var mapper = new ObjectMapper();
        var input = mapper.readTree(Files.readString(Path.of(System.getenv("LOOMAI_LEXICAL_LIVE_CONFIG"))));
        var configuration = new AIProviderConfig();
        var provider = configuration.getWeaviate();
        provider.setEnabled(true);
        provider.setHost(input.path("host").asText());
        provider.setPort(input.path("port").asInt());
        provider.setScheme(input.path("scheme").asText());
        provider.setApiKey(input.path("apiKey").asText());
        provider.setClassPrefix(input.path("classPrefix").asText());
        provider.setNativeMultiTenancyEnabled(true);
        provider.setTenantName(input.path("tenantName").asText());
        var service = new WeaviateVectorDatabaseService(configuration);
        String entityType = input.path("entityType").asText();
        assertTrue(service.retrievalCapabilities(entityType).lexicalReady(), "Approved corpus text index must be ready");
        Map<String, Object> filters = mapper.convertValue(input.path("filters"), new TypeReference<>() { });
        String query = input.path("query").asText();
        assertFalse(query.isBlank(), "Probe must specify a literal verified in the frozen corpus");
        var response = service.keywordSearch(query, AISearchRequest.builder().entityType(entityType)
            .metadata(filters).limit(10).threshold(.99).build());
        assertFalse(response.getResults().isEmpty(), "Expected existing approved support guide must be found");
        for (var row : response.getResults()) {
            assertTrue(row.get("score") instanceof Number, "BM25 result must carry a numerical score");
            assertFalse(row.containsKey("similarity"), "BM25 is not vector confidence");
        }
        var deniedFilters = new LinkedHashMap<>(filters);
        deniedFilters.put("knowledgeSourceHandleRef", "01073-nonexistent-not-owned-source");
        var denied = service.keywordSearch(query, AISearchRequest.builder().entityType(entityType)
            .metadata(deniedFilters).limit(10).build());
        assertTrue(denied.getResults().isEmpty(), "Foreign source handle must not return any evidence");
    }
}
