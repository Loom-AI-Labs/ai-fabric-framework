package com.ai.infrastructure.intent.action.connector.registry.security;

import com.ai.infrastructure.intent.action.connector.registry.AIActionDbRegistryProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "ai.actions.db", name = "enabled", havingValue = "true")
public class ConnectorActionRegistryApiKeyFilter extends OncePerRequestFilter {

    private static final String PATH_PREFIX = "/api/ai/actions/registry";

    private final AIActionDbRegistryProperties properties;

    public ConnectorActionRegistryApiKeyFilter(AIActionDbRegistryProperties properties) {
        this.properties = properties;

        AIActionDbRegistryProperties.ApiKeyProperties apiKey = properties != null ? properties.getApiKey() : null;
        if (apiKey != null && apiKey.isEnabled() && !StringUtils.hasText(apiKey.getValue())) {
            throw new IllegalStateException("ai.actions.db.apiKey.value is required when ai.actions.db.enabled=true and ai.actions.db.apiKey.enabled=true.");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (request == null) {
            return true;
        }
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        String path = normalizePath(contextPath, uri);
        return path == null || !path.startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        AIActionDbRegistryProperties.ApiKeyProperties apiKey = properties != null ? properties.getApiKey() : null;
        if (apiKey == null || !apiKey.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        String headerName = StringUtils.hasText(apiKey.getHeader()) ? apiKey.getHeader().trim() : "X-AIFABRIC-API-KEY";
        String provided = request.getHeader(headerName);
        if (!StringUtils.hasText(provided) || !constantTimeEquals(apiKey.getValue(), provided.trim())) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"errorCode\":\"UNAUTHORIZED\",\"message\":\"Unauthorized\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String normalizePath(String contextPath, String uri) {
        if (!StringUtils.hasText(uri)) {
            return null;
        }
        String out = uri.trim();
        if (StringUtils.hasText(contextPath)) {
            String prefix = contextPath.trim();
            if (!prefix.isEmpty() && out.startsWith(prefix)) {
                out = out.substring(prefix.length());
            }
        }
        return out;
    }

    private boolean constantTimeEquals(String expected, String provided) {
        if (expected == null || provided == null) {
            return false;
        }
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = provided.getBytes(StandardCharsets.UTF_8);
        // Avoid early-return on length mismatch to reduce timing side-channels.
        int result = a.length ^ b.length;
        for (int i = 0; i < a.length; i++) {
            byte other = i < b.length ? b[i] : 0;
            result |= a[i] ^ other;
        }
        return result == 0;
    }
}
