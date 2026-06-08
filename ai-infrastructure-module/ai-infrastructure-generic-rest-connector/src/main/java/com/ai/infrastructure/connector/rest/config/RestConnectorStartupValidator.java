package com.ai.infrastructure.connector.rest.config;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Component
public class RestConnectorStartupValidator {

    public RestConnectorStartupValidator(RestRoutingConfig config, Validator validator) {
        validate(config, validator);
    }

    private void validate(RestRoutingConfig config, Validator validator) {
        if (config == null) {
            throw new IllegalStateException("RestRoutingConfig is required.");
        }

        if (validator != null) {
            Set<ConstraintViolation<RestRoutingConfig>> violations = validator.validate(config);
            if (violations != null && !violations.isEmpty()) {
                String first = violations.iterator().next().getPropertyPath() + " " + violations.iterator().next().getMessage();
                throw new IllegalStateException("Invalid routing config: " + first);
            }
        }

        validateInboundAuth(config);
        validateUpstream(config);
        validateAuthz(config);
        validateRoutes(config);
    }

    private void validateInboundAuth(RestRoutingConfig config) {
        RestRoutingConfig.Connector connector = config.getConnector();
        RestRoutingConfig.InboundAuth auth = connector != null ? connector.getInboundAuth() : null;
        if (auth == null) {
            throw new IllegalStateException("connector.inbound-auth is required.");
        }

        RestRoutingConfig.ApiKey apiKey = auth.getApiKey();
        boolean apiKeyEnabled = apiKey != null && apiKey.isEnabled();

        if (!auth.isAllowUnauthenticated() && !apiKeyEnabled) {
            throw new IllegalStateException("Inbound auth is not configured. Enable connector.inbound-auth.api-key.enabled (or explicitly set connector.inbound-auth.allow-unauthenticated=true).");
        }

        if (apiKeyEnabled && (!StringUtils.hasText(apiKey.getHeader()) || !StringUtils.hasText(apiKey.getValue()))) {
            throw new IllegalStateException("connector.inbound-auth.api-key.enabled=true but header/value is missing.");
        }
    }

    private void validateUpstream(RestRoutingConfig config) {
        RestRoutingConfig.Connector connector = config.getConnector();
        RestRoutingConfig.Upstream upstream = connector != null ? connector.getUpstream() : null;
        if (upstream == null) {
            return;
        }

        if (StringUtils.hasText(upstream.getBaseUrl())) {
            validateUrl(upstream.getBaseUrl().trim(), "connector.upstream.base-url");
        }
    }

    private void validateAuthz(RestRoutingConfig config) {
        RestRoutingConfig.Authz authz = config != null ? config.getAuthz() : null;
        if (authz == null || !authz.isEnabled()) {
            return;
        }

        RestRoutingConfig.Upstream upstream = authz.getUpstream();
        String baseUrl = upstream != null ? upstream.getBaseUrl() : null;
        if (!StringUtils.hasText(baseUrl)) {
            // Convenience: allow reusing connector upstream baseUrl when authz is served by the same upstream.
            RestRoutingConfig.Connector connector = config.getConnector();
            RestRoutingConfig.Upstream connectorUpstream = connector != null ? connector.getUpstream() : null;
            baseUrl = connectorUpstream != null ? connectorUpstream.getBaseUrl() : null;
        }
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalStateException("authz.enabled=true but authz.upstream.base-url is missing.");
        }
        validateUrl(baseUrl.trim(), "authz.upstream.base-url");

        String path = authz.getPath();
        if (!StringUtils.hasText(path)) {
            throw new IllegalStateException("authz.enabled=true but authz.path is missing.");
        }
        String p = path.trim();
        if (p.contains("://")) {
            throw new IllegalStateException("authz.path must be a relative path (no scheme).");
        }
        if (!p.startsWith("/")) {
            throw new IllegalStateException("authz.path must start with '/'.");
        }
    }

    private void validateRoutes(RestRoutingConfig config) {
        if (config.getActions() == null || config.getActions().isEmpty()) {
            log.warn("No actions configured under 'actions'. /actions/execute will return ACTION_NOT_SUPPORTED.");
            return;
        }

        RestRoutingConfig.Connector connector = config.getConnector();
        String baseUrl = connector != null && connector.getUpstream() != null ? connector.getUpstream().getBaseUrl() : null;
        boolean baseUrlPresent = StringUtils.hasText(baseUrl);

        config.getActions().forEach((actionId, route) -> {
            if (!StringUtils.hasText(actionId)) {
                throw new IllegalStateException("Action route key must not be blank.");
            }
            if (route == null) {
                throw new IllegalStateException("Action route '" + actionId.trim() + "' is null.");
            }

            String url = route.getUrl();
            String path = route.getPath();
            if (!StringUtils.hasText(url) && !StringUtils.hasText(path)) {
                throw new IllegalStateException("Action route '" + actionId.trim() + "' must set either url or path.");
            }

            if (StringUtils.hasText(url)) {
                validateUrl(url.trim(), "actions." + actionId.trim() + ".url");
            } else {
                if (!baseUrlPresent) {
                    throw new IllegalStateException("Action route '" + actionId.trim() + "' uses path but connector.upstream.base-url is missing.");
                }
                String p = path.trim();
                if (p.contains("://")) {
                    throw new IllegalStateException("Action route '" + actionId.trim() + "': path must be a relative path (no scheme).");
                }
                if (!p.startsWith("/")) {
                    throw new IllegalStateException("Action route '" + actionId.trim() + "': path must start with '/'.");
                }
            }

            String method = route.getMethod();
            if (!StringUtils.hasText(method)) {
                throw new IllegalStateException("Action route '" + actionId.trim() + "': method is required.");
            }

            Integer timeoutMs = route.getTimeoutMs();
            if (timeoutMs != null && (timeoutMs < 100 || timeoutMs > 120_000)) {
                throw new IllegalStateException("Action route '" + actionId.trim() + "': timeout-ms must be between 100 and 120000.");
            }

            RestRoutingConfig.Response response = route.getResponse();
            if (response != null && response.getSuccessHttpStatus() != null) {
                for (Integer status : response.getSuccessHttpStatus()) {
                    if (status == null) {
                        continue;
                    }
                    if (status < 100 || status > 599) {
                        throw new IllegalStateException("Action route '" + actionId.trim() + "': response.success-http-status must be valid HTTP codes.");
                    }
                }
            }
        });
    }

    private void validateUrl(String url, String key) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (!StringUtils.hasText(scheme)) {
                throw new IllegalArgumentException("missing scheme");
            }
            String normalized = scheme.trim().toLowerCase(Locale.ROOT);
            if (!"http".equals(normalized) && !"https".equals(normalized)) {
                throw new IllegalArgumentException("unsupported scheme '" + scheme + "'");
            }
            if (!StringUtils.hasText(uri.getHost())) {
                throw new IllegalArgumentException("missing host");
            }
        } catch (Exception ex) {
            throw new IllegalStateException(key + " must be a valid absolute http(s) URL (got '" + url + "'): " + ex.getMessage(), ex);
        }
    }
}
