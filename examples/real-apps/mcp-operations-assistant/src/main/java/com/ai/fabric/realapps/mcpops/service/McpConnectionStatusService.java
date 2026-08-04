package com.ai.fabric.realapps.mcpops.service;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class McpConnectionStatusService {

    private final ObjectProvider<List<McpSyncClient>> clients;
    private final Environment environment;
    private final String apiKey;
    private final String executorMode;

    public McpConnectionStatusService(
        ObjectProvider<List<McpSyncClient>> clients,
        Environment environment,
        @Value("${app.mcp-operations.remote.api-key:}") String apiKey,
        @Value("${app.mcp-operations.executor:remote}") String executorMode
    ) {
        this.clients = clients;
        this.environment = environment;
        this.apiKey = apiKey;
        this.executorMode = executorMode;
    }

    public ConnectionStatus status() {
        if ("local-smoke".equals(executorMode)) {
            if (!environment.matchesProfiles("smoke")) {
                return new ConnectionStatus(
                    false,
                    false,
                    McpOperationsService.SERVER_REF,
                    "LOCAL_EXECUTOR_REJECTED",
                    Set.of(),
                    "The local MCP executor is restricted to the smoke profile."
                );
            }
            return new ConnectionStatus(
                true,
                true,
                McpOperationsService.SERVER_REF,
                "LOCAL_SMOKE_ONLY",
                McpOperationsService.REQUIRED_TOOLS,
                null
            );
        }
        boolean authConfigured = StringUtils.hasText(apiKey);
        if (!authConfigured) {
            return new ConnectionStatus(
                false,
                false,
                McpOperationsService.SERVER_REF,
                "STREAMABLE_HTTP",
                Set.of(),
                "MCP authentication is not configured."
            );
        }
        List<McpSyncClient> available = clients.getIfAvailable(List::of);
        for (McpSyncClient client : available) {
            try {
                if (!client.isInitialized()) {
                    client.initialize();
                }
                McpSchema.Implementation info = client.getServerInfo();
                if (info == null
                    || !McpOperationsService.SERVER_REF.equalsIgnoreCase(
                        info.name()
                    )) {
                    continue;
                }
                Set<String> tools = new LinkedHashSet<>();
                McpSchema.ListToolsResult result = client.listTools();
                if (result != null && result.tools() != null) {
                    result.tools().stream()
                        .filter(java.util.Objects::nonNull)
                        .map(McpSchema.Tool::name)
                        .filter(StringUtils::hasText)
                        .forEach(tools::add);
                }
                Set<String> safeTools = Set.copyOf(tools);
                boolean ready = safeTools.containsAll(
                    McpOperationsService.REQUIRED_TOOLS
                );
                return new ConnectionStatus(
                    ready,
                    true,
                    info.name(),
                    "STREAMABLE_HTTP",
                    safeTools,
                    ready ? null : "The MCP server is missing required tools."
                );
            } catch (RuntimeException ex) {
                return new ConnectionStatus(
                    false,
                    true,
                    McpOperationsService.SERVER_REF,
                    "STREAMABLE_HTTP",
                    Set.of(),
                    "The authenticated MCP server is unavailable."
                );
            }
        }
        return new ConnectionStatus(
            false,
            true,
            McpOperationsService.SERVER_REF,
            "STREAMABLE_HTTP",
            Set.of(),
            "No matching MCP server connection is available."
        );
    }

    public record ConnectionStatus(
        boolean ready,
        boolean authenticationConfigured,
        String serverRef,
        String transport,
        Set<String> tools,
        String failure
    ) {
    }
}
