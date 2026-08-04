package com.ai.fabric.realapps.mcpserver.config;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class McpApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-MCP-API-KEY";

    @Value("${app.mcp.api-key:}")
    private String expectedKey;

    @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}")
    private String endpoint;

    @PostConstruct
    void validate() {
        if (!StringUtils.hasText(expectedKey)) {
            throw new IllegalStateException(
                "MCP_SERVER_API_KEY is required"
            );
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(endpoint);
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
                "MCP authentication is required"
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
