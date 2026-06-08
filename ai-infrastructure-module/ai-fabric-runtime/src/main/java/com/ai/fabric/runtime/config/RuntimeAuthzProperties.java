package com.ai.fabric.runtime.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Runtime product authorization configuration.
 *
 * <p>Goal: product deployments should be able to wire authorization via configuration and a remote API,
 * without requiring customers to ship custom Java code.</p>
 */
@Data
@Validated
@ConfigurationProperties(prefix = "ai.fabric.runtime.authz")
public class RuntimeAuthzProperties {

    public enum Mode {
        /**
         * Use a remote HTTP policy (typically routed via the REST connector).
         */
        REMOTE_HTTP,

        /**
         * Allow requests that arrived through the verified runtime auth contract with a non-anonymous subject.
         *
         * <p>This is intended for platform-managed deployments that do not have a separate customer authz service,
         * but still require verified private/public runtime identity before serving chat traffic.</p>
         */
        ALLOW_VERIFIED,

        /**
         * Deny all access (fail-closed). Useful during rollout or when explicitly disabling runtime access.
         */
        DENY_ALL
    }

    /**
     * Authorization mode. When a customer provides an {@code EntityAccessPolicy} bean directly, it always wins.
     */
    private Mode mode = Mode.REMOTE_HTTP;

    private Remote remote = new Remote();

    @Data
    public static class Remote {
        /**
         * Base URL for the remote authorization endpoint.
         *
         * <p>When blank, runtime will fall back to {@code ai.actions.connector.base-url} if available.</p>
         */
        private String baseUrl;

        /**
         * Authorization check endpoint path (relative to {@link #baseUrl}).
         */
        private String path = "/api/authz/check";

        /**
         * Connect timeout for remote authz calls.
         */
        private int connectTimeoutMs = 500;

        /**
         * Request timeout for remote authz calls (entire request).
         */
        private int timeoutMs = 1500;

        private OutboundAuth outboundAuth = new OutboundAuth();
    }

    public enum OutboundAuthType {
        NONE,
        API_KEY,
        /**
         * Compatibility mode that reuses the same header/value pair configured for connector calls.
         *
         * <p>In runtime packaging this is resolved through runtime configuration defaults rather than a hard bean dependency
         * on the actions connector properties type.</p>
         */
        INHERIT_ACTIONS_API_KEY
    }

    @Data
    public static class OutboundAuth {
        /**
         * How runtime authenticates to the authz proxy/service.
         */
        private OutboundAuthType type = OutboundAuthType.INHERIT_ACTIONS_API_KEY;

        /**
         * Header name used when {@link #type} is {@link OutboundAuthType#API_KEY}.
         */
        private String apiKeyHeader = "X-AIFABRIC-API-KEY";

        /**
         * Value used when {@link #type} is {@link OutboundAuthType#API_KEY}.
         */
        private String apiKeyValue;
    }
}
