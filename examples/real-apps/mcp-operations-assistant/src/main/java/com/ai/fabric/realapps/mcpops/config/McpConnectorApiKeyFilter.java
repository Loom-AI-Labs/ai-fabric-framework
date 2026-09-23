package com.ai.fabric.realapps.mcpops.config;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class McpConnectorApiKeyFilter extends OncePerRequestFilter {

    public static final String PATH = "/internal/mcp-connector/";
    public static final String HEADER = "X-MCP-OPERATIONS-CONNECTOR-KEY";

    @Value("${app.mcp-operations.connector.api-key:}")
    private String expectedKey;

    @PostConstruct
    void validate() {
        if (!StringUtils.hasText(expectedKey)) {
            throw new IllegalStateException(
                "MCP_OPERATIONS_CONNECTOR_API_KEY is required"
            );
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PATH);
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String supplied = request.getHeader(HEADER);
        if (!matches(supplied, expectedKey)) {
            response.sendError(
                HttpServletResponse.SC_UNAUTHORIZED,
                "Connector authentication is required"
            );
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean matches(String supplied, String expected) {
        if (!StringUtils.hasText(supplied)
            || !StringUtils.hasText(expected)) {
            return false;
        }
        return MessageDigest.isEqual(
            supplied.getBytes(StandardCharsets.UTF_8),
            expected.getBytes(StandardCharsets.UTF_8)
        );
    }
}
