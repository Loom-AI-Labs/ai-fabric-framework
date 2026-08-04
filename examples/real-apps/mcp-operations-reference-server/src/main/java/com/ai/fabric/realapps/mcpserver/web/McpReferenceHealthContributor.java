package com.ai.fabric.realapps.mcpserver.web;

import com.ai.fabric.examples.smoke.health.DemoHealthContributor;
import com.ai.fabric.realapps.mcpserver.repository.SandboxServiceStateRepository;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class McpReferenceHealthContributor implements DemoHealthContributor {

    private static final List<String> TOOLS = List.of(
        "get_sandbox_service_status",
        "list_recent_sandbox_incidents",
        "restart_sandbox_service"
    );

    private final SandboxServiceStateRepository repository;

    public McpReferenceHealthContributor(
        SandboxServiceStateRepository repository
    ) {
        this.repository = repository;
    }

    @Override
    public Map<String, Object> details() {
        boolean storageReady;
        try {
            repository.count();
            storageReady = true;
        } catch (RuntimeException exception) {
            storageReady = false;
        }
        return Map.of(
            "status", storageReady ? "UP" : "DOWN",
            "mcp", Map.of(
                "serverRef", "ai-fabric-operations-reference",
                "protocol", "STREAMABLE_HTTP",
                "authenticated", true,
                "tools", TOOLS
            ),
            "storage", Map.of(
                "sandboxState", storageReady ? "UP" : "DOWN"
            )
        );
    }
}
