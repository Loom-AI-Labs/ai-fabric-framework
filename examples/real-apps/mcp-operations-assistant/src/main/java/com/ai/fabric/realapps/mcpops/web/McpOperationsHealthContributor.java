package com.ai.fabric.realapps.mcpops.web;

import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.provider.AIProvider;
import com.ai.fabric.examples.smoke.health.DemoHealthContributor;
import com.ai.fabric.realapps.mcpops.service.McpConnectionStatusService;
import com.ai.fabric.realapps.mcpops.specialist.McpOperationsSpecialists;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class McpOperationsHealthContributor implements DemoHealthContributor {

    private final McpConnectionStatusService connections;
    private final SpecialistRegistry specialists;
    private final List<AIProvider> providers;
    private final DataSource dataSource;
    private final Environment environment;

    public McpOperationsHealthContributor(
        McpConnectionStatusService connections,
        SpecialistRegistry specialists,
        List<AIProvider> providers,
        DataSource dataSource,
        Environment environment
    ) {
        this.connections = connections;
        this.specialists = specialists;
        this.providers = providers != null ? List.copyOf(providers) : List.of();
        this.dataSource = dataSource;
        this.environment = environment;
    }

    @Override
    public Map<String, Object> details() {
        McpConnectionStatusService.ConnectionStatus connection =
            connections.status();
        boolean specialistReady = specialists.findRegistered(
            McpOperationsSpecialists.OPERATIONS
        ).isPresent();
        String providerName = environment.getProperty(
            "ai.providers.llm-provider",
            "unknown"
        );
        boolean providerReady = providers.stream().anyMatch(provider ->
            provider.getProviderName() != null
                && provider.getProviderName().trim().toLowerCase(Locale.ROOT)
                    .equals(providerName.trim().toLowerCase(Locale.ROOT))
                && safelyAvailable(provider)
        );
        boolean storageReady = storageReady();
        boolean smoke = environment.matchesProfiles("smoke");
        boolean ready = connection.ready()
            && specialistReady
            && providerReady
            && storageReady;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", ready ? "UP" : "DOWN");
        out.put("mcp", connection);
        out.put("specialist", Map.of(
            "id", McpOperationsSpecialists.OPERATIONS.toString(),
            "ready", specialistReady
        ));
        out.put("provider", Map.of(
            "name", providerName,
            "ready", providerReady,
            "realProviderRequired", !smoke
        ));
        out.put("storage", Map.of(
            "jdbc", storageReady,
            "durableReceipts", true,
            "backendConversationMemory", true
        ));
        out.put("localDeterministicExecutor", smoke);
        out.put("liveFallbackEnabled", false);
        return Map.copyOf(out);
    }

    private boolean safelyAvailable(AIProvider provider) {
        try {
            return provider.isAvailable() && provider.getStatus().isHealthy();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean storageReady() {
        try (var connection = dataSource.getConnection()) {
            return connection.isValid(1);
        } catch (SQLException exception) {
            return false;
        }
    }
}
