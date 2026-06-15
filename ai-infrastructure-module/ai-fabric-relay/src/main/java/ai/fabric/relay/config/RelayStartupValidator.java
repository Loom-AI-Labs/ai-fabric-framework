package ai.fabric.relay.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.Locale;

@Slf4j
@Component
public class RelayStartupValidator {

    public RelayStartupValidator(RelayProperties properties) {
        validate(properties);
    }

    private void validate(RelayProperties properties) {
        if (properties == null) {
            throw new IllegalStateException("RelayProperties is required.");
        }

        validateAuth(properties);
        validateRouting(properties);
        validateStore(properties);
    }

    private void validateAuth(RelayProperties properties) {
        RelayProperties.Auth auth = properties.getAuth();
        if (auth == null) {
            throw new IllegalStateException("relay.auth is required.");
        }

        RelayProperties.ApiKey apiKey = auth.getApiKey();
        RelayProperties.Hmac hmac = auth.getHmac();

        boolean apiKeyEnabled = apiKey != null && apiKey.isEnabled();
        boolean hmacEnabled = hmac != null && hmac.isEnabled();

        if (!auth.isAllowUnauthenticated() && !apiKeyEnabled && !hmacEnabled) {
            throw new IllegalStateException("Relay inbound auth is not configured. Enable relay.auth.apiKey.enabled and/or relay.auth.hmac.enabled (or explicitly set relay.auth.allowUnauthenticated=true).");
        }

        if (apiKeyEnabled && !StringUtils.hasText(apiKey.getValue())) {
            throw new IllegalStateException("relay.auth.apiKey.enabled=true but relay.auth.apiKey.value is missing.");
        }

        if (hmacEnabled && !StringUtils.hasText(hmac.getSecret())) {
            throw new IllegalStateException("relay.auth.hmac.enabled=true but relay.auth.hmac.secret is missing.");
        }
    }

    private void validateRouting(RelayProperties properties) {
        RelayProperties.Routing routing = properties.getRouting();
        if (routing == null) {
            throw new IllegalStateException("relay.routing is required.");
        }

        if (routing.getMode() == RelayProperties.Routing.Mode.DISPATCHER) {
            RelayProperties.Dispatcher dispatcher = routing.getDispatcher();
            if (dispatcher == null || !StringUtils.hasText(dispatcher.getUrl())) {
                throw new IllegalStateException("relay.routing.mode=DISPATCHER requires relay.routing.dispatcher.url.");
            }
            validateUrl(dispatcher.getUrl().trim(), "relay.routing.dispatcher.url");
        } else {
            if (routing.getActions() == null || routing.getActions().isEmpty()) {
                log.warn("Relay is running in mapping mode but relay.routing.actions is empty. /actions/execute will return ACTION_NOT_SUPPORTED.");
            } else {
                routing.getActions().forEach((actionId, route) -> {
                    if (route == null || !StringUtils.hasText(route.getUrl())) {
                        return;
                    }
                    validateUrl(route.getUrl().trim(), "relay.routing.actions." + actionId + ".url");
                });
            }
        }

        RelayProperties.Retrieval retrieval = routing.getRetrieval();
        if (retrieval != null && StringUtils.hasText(retrieval.getUrl())) {
            validateUrl(retrieval.getUrl().trim(), "relay.routing.retrieval.url");
        }
    }

    private void validateStore(RelayProperties properties) {
        RelayProperties.Store store = properties.getStore();
        if (store == null) {
            return;
        }

        if (!StringUtils.hasText(store.getKeyPrefix())) {
            throw new IllegalStateException("relay.store.keyPrefix must not be blank.");
        }

        if (store.getBackend() != RelayProperties.Store.Backend.REDIS) {
            return;
        }

        RelayProperties.Redis redis = store.getRedis();
        if (redis == null) {
            throw new IllegalStateException("relay.store.backend=REDIS requires relay.store.redis configuration.");
        }

        if (StringUtils.hasText(redis.getUri())) {
            String uri = redis.getUri().trim();
            try {
                URI parsed = URI.create(uri);
                String scheme = parsed.getScheme();
                if (!StringUtils.hasText(scheme)) {
                    throw new IllegalArgumentException("missing scheme");
                }
                String normalized = scheme.trim().toLowerCase(Locale.ROOT);
                if (!"redis".equals(normalized) && !"rediss".equals(normalized)) {
                    throw new IllegalArgumentException("unsupported scheme '" + scheme + "'");
                }
            } catch (Exception ex) {
                throw new IllegalStateException("relay.store.redis.uri must be a valid redis/rediss URI (got '" + uri + "'): " + ex.getMessage(), ex);
            }
            return;
        }

        if (!StringUtils.hasText(redis.getHost())) {
            throw new IllegalStateException("relay.store.backend=REDIS requires relay.store.redis.uri or relay.store.redis.host.");
        }
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
