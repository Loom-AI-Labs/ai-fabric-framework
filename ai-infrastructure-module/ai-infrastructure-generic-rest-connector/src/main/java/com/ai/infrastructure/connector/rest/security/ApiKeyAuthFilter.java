package com.ai.infrastructure.connector.rest.security;

import com.ai.infrastructure.connector.rest.api.ActionResultDto;
import com.ai.infrastructure.connector.rest.config.RestConnectorAdminAuthProperties;
import com.ai.infrastructure.connector.rest.config.RestRoutingConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.util.PatternMatchUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String ERROR_UNAUTHORIZED = "UNAUTHORIZED";

    private final RestRoutingConfig config;
    private final ObjectMapper objectMapper;
    private final com.ai.infrastructure.connector.rest.config.CorsConfiguration.CorsProperties corsProperties;
    private final RestConnectorAdminAuthProperties adminAuthProperties;

    public ApiKeyAuthFilter(RestRoutingConfig config,
                            ObjectMapper objectMapper,
                            com.ai.infrastructure.connector.rest.config.CorsConfiguration.CorsProperties corsProperties,
                            RestConnectorAdminAuthProperties adminAuthProperties) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.corsProperties = corsProperties;
        this.adminAuthProperties = adminAuthProperties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (request == null) {
            return true;
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        if (!StringUtils.hasText(path)) {
            return true;
        }
        // Protect action execution, connector admin endpoints, and authz preflight.
        return !(path.startsWith("/actions/execute")
            || path.startsWith("/api/admin")
            || path.startsWith("/api/authz")
            );
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (isAdminRequest(request) && isAdminAuthConfigured()) {
            if (!matchesHeader(
                request,
                adminAuthProperties.getApiKeyHeader(),
                adminAuthProperties.getApiKey()
            )) {
                reject(request, response, "Unauthorized.");
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        RestRoutingConfig.Connector connector = config != null ? config.getConnector() : null;
        RestRoutingConfig.InboundAuth inbound = connector != null ? connector.getInboundAuth() : null;
        if (inbound == null || inbound.isAllowUnauthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        RestRoutingConfig.ApiKey apiKey = inbound.getApiKey();
        if (apiKey == null || !apiKey.isEnabled()) {
            reject(request, response, "Inbound auth is not configured.");
            return;
        }

        if (!StringUtils.hasText(apiKey.getHeader()) || !StringUtils.hasText(apiKey.getValue())) {
            reject(request, response, "Inbound auth is not configured.");
            return;
        }

        if (!matchesHeader(request, apiKey.getHeader(), apiKey.getValue())) {
            reject(request, response, "Unauthorized.");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isAdminRequest(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        String path = request.getRequestURI();
        return StringUtils.hasText(path) && path.startsWith("/api/admin");
    }

    private boolean isAdminAuthConfigured() {
        return adminAuthProperties != null
            && StringUtils.hasText(adminAuthProperties.getApiKeyHeader())
            && StringUtils.hasText(adminAuthProperties.getApiKey());
    }

    private boolean matchesHeader(HttpServletRequest request, String header, String expectedValue) {
        if (request == null || !StringUtils.hasText(header) || !StringUtils.hasText(expectedValue)) {
            return false;
        }
        String actual = request.getHeader(header.trim());
        return StringUtils.hasText(actual) && constantTimeEquals(expectedValue.trim(), actual.trim());
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (!StringUtils.hasText(expected) || !StringUtils.hasText(actual)) {
            return false;
        }
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = actual.getBytes(StandardCharsets.UTF_8);
        // Avoid early-return on length mismatch to reduce timing side-channels.
        int result = a.length ^ b.length;
        for (int i = 0; i < a.length; i++) {
            byte other = i < b.length ? b[i] : 0;
            result |= a[i] ^ other;
        }
        return result == 0;
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String message) throws IOException {
        if (request == null || response == null) {
            return;
        }
        maybeApplyCorsHeaders(request, response);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ActionResultDto body = ActionResultDto.failure(ERROR_UNAUTHORIZED, message);
        response.getWriter().write(objectMapper.writeValueAsString(body));
        response.getWriter().flush();
    }

    private void maybeApplyCorsHeaders(HttpServletRequest request, HttpServletResponse response) {
        if (request == null || response == null || corsProperties == null) {
            return;
        }
        String origin = request.getHeader("Origin");
        if (!StringUtils.hasText(origin)) {
            return;
        }

        if (!isOriginAllowed(origin.trim(), corsProperties.allowedOrigins(), corsProperties.allowedOriginPatterns())) {
            return;
        }

        response.setHeader("Access-Control-Allow-Origin", origin.trim());
        // Ensure caches vary by origin so different allowed origins don't leak.
        response.addHeader("Vary", "Origin");
        if (corsProperties.allowCredentials()) {
            response.setHeader("Access-Control-Allow-Credentials", "true");
        }
    }

    private boolean isOriginAllowed(String origin, List<String> allowedOrigins, List<String> allowedOriginPatterns) {
        if (!StringUtils.hasText(origin)) {
            return false;
        }
        List<String> origins = allowedOrigins != null ? allowedOrigins : List.of();
        for (String allowed : origins) {
            if (!StringUtils.hasText(allowed)) {
                continue;
            }
            String value = allowed.trim();
            if ("*".equals(value) || value.equalsIgnoreCase(origin)) {
                return true;
            }
        }
        List<String> patterns = allowedOriginPatterns != null ? allowedOriginPatterns : List.of();
        for (String pattern : patterns) {
            if (!StringUtils.hasText(pattern)) {
                continue;
            }
            String p = pattern.trim();
            if ("*".equals(p) || PatternMatchUtils.simpleMatch(p, origin)) {
                return true;
            }
        }
        return false;
    }
}
