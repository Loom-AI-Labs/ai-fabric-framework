package com.ai.fabric.realapps.mcpops.config;

import ai.fabric.intent.action.connector.McpActionExecutor;
import ai.fabric.intent.action.connector.springai.SpringAiMcpActionExecutor;
import com.ai.fabric.realapps.mcpops.service.AuditedMcpActionExecutor;
import com.ai.fabric.realapps.mcpops.service.LocalOperationsMcpActionExecutor;
import com.ai.fabric.realapps.mcpops.service.McpInvocationAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import java.time.Clock;
import java.util.List;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
public class McpClientConfiguration {

    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnProperty(
        name = "app.mcp-operations.executor",
        havingValue = "remote",
        matchIfMissing = true
    )
    McpActionExecutor remoteMcpActionExecutor(
        ObjectProvider<List<McpSyncClient>> clients,
        ObjectMapper objectMapper,
        McpInvocationAuditService audit,
        Clock clock
    ) {
        SpringAiMcpActionExecutor remote = new SpringAiMcpActionExecutor(
            () -> clients.getIfAvailable(List::of),
            objectMapper
        );
        return new AuditedMcpActionExecutor(remote, audit, clock);
    }

    @Bean
    @Profile("smoke")
    @ConditionalOnProperty(
        name = "app.mcp-operations.executor",
        havingValue = "local-smoke"
    )
    McpActionExecutor localMcpActionExecutor(
        McpInvocationAuditService audit,
        Clock clock
    ) {
        return new AuditedMcpActionExecutor(
            new LocalOperationsMcpActionExecutor(),
            audit,
            clock
        );
    }

    @Bean
    @ConditionalOnProperty(
        name = "app.mcp-operations.executor",
        havingValue = "remote",
        matchIfMissing = true
    )
    McpClientCustomizer<HttpClientStreamableHttpTransport.Builder>
    operationsMcpAuthentication(
        @Value("${app.mcp-operations.remote.api-key:}") String apiKey
    ) {
        return (connectionName, builder) -> {
            if (!"operations".equals(connectionName)
                || !StringUtils.hasText(apiKey)) {
                return;
            }
            builder.httpRequestCustomizer((request, method, uri, body, context) ->
                request.header("X-MCP-API-KEY", apiKey.trim())
            );
        };
    }
}
