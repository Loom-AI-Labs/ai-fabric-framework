package com.ai.infrastructure.connector.rest.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class RestRoutingConfig {

    @Valid
    private Connector connector = new Connector();

    @Valid
    private Authz authz = new Authz();

    @Valid
    private Map<String, ActionRoute> actions = new LinkedHashMap<>();

    @Data
    public static class Connector {
        @Valid
        private InboundAuth inboundAuth = new InboundAuth();

        @Valid
        private Upstream upstream = new Upstream();

        @Valid
        private Http http = new Http();

        @Valid
        private Idempotency idempotency = new Idempotency();
    }

    @Data
    public static class Authz {
        /**
         * When true, {@code POST /api/authz/check} is enabled and forwards to the configured upstream.
         *
         * <p>This endpoint is protected by the same inbound auth filter as {@code /actions/execute}
         * when {@code connector.inbound-auth.allow-unauthenticated=false}.</p>
         */
        private boolean enabled = false;

        /**
         * Upstream path for the authz check endpoint (relative to {@code authz.upstream.base-url}).
         */
        private String path = "/api/authz/check";

        @Valid
        private Upstream upstream = new Upstream();

        @Valid
        private AuthzHttp http = new AuthzHttp();
    }

    @Data
    public static class AuthzHttp {
        @Min(100)
        @Max(120_000)
        private int connectTimeoutMs = 500;

        @Min(100)
        @Max(120_000)
        private int timeoutMs = 1500;
    }

    @Data
    public static class InboundAuth {
        /**
         * When false (default), inbound API key auth must be enabled.
         * Set to true only when protected by network controls and you explicitly accept the risk.
         */
        private boolean allowUnauthenticated = false;

        @Valid
        private ApiKey apiKey = new ApiKey();
    }

    @Data
    public static class ApiKey {
        private boolean enabled = false;
        private String header = "X-AIFABRIC-API-KEY";
        private String value;
    }

    @Data
    public static class Upstream {
        private String baseUrl;

        @Valid
        private UpstreamAuth auth = new UpstreamAuth();
    }

    @Data
    public static class UpstreamAuth {
        private AuthType type = AuthType.NONE;
        private String header = "Authorization";
        private String value;

        public enum AuthType {
            NONE,
            API_KEY
        }
    }

    @Data
    public static class Http {
        @Min(100)
        @Max(120_000)
        private int connectTimeoutMs = 2000;

        @Min(100)
        @Max(120_000)
        private int timeoutMs = 8000;

        @Valid
        private Retry retry = new Retry();
    }

    @Data
    public static class Retry {
        private boolean enabled = false;

        @Min(1)
        @Max(10)
        private int maxAttempts = 1;

        @Min(0)
        @Max(30_000)
        private int backoffMs = 200;

        private List<Integer> retryOn = List.of(429, 502, 503, 504);
    }

    @Data
    public static class Idempotency {
        private boolean enabled = true;

        @Min(60)
        @Max(7 * 24 * 3600)
        private int ttlSeconds = 300;

        @Min(0)
        @Max(120_000)
        private int inProgressMaxWaitMs = 2000;
    }

    @Data
    public static class ActionRoute {
        /**
         * Absolute upstream URL (overrides {@link #path} and {@code connector.upstream.baseUrl}).
         */
        private String url;

        /**
         * Upstream path (relative to {@code connector.upstream.baseUrl}).
         */
        private String path;

        private String method = "POST";

        private Integer timeoutMs;

        private Map<String, String> headers = new LinkedHashMap<>();

        @Valid
        private Request request = new Request();

        @Valid
        private Response response = new Response();

        @Valid
        private ActionAuthz authz = new ActionAuthz();
    }

    @Data
    public static class ActionAuthz {
        /**
         * When true, execute a remote authz preflight before the upstream action call.
         */
        private boolean enabled = false;

        /**
         * Resource identifier presented to the authz service. Supports templating from {@code params}
         * and {@code trace} context.
         */
        private String resourceId;

        /**
         * Operation type presented to the authz service. Defaults to EXECUTE_ACTION.
         */
        private String operationType = "EXECUTE_ACTION";

        /**
         * Optional requested scopes presented to the authz service.
         */
        private List<String> requestedScopes = new ArrayList<>();

        /**
         * Optional request context payload presented to the authz service.
         */
        private Object requestContext;
    }

    @Data
    public static class Request {
        private Map<String, Object> query = new LinkedHashMap<>();

        /**
         * Arbitrary JSON template (object/array/scalar) built from {@code params} and {@code trace}.
         */
        private Object body;
    }

    @Data
    public static class Response {
        /**
         * If empty, any 2xx status is considered success.
         */
        private List<Integer> successHttpStatus = new ArrayList<>();

        /**
         * Template for the connector {@code data} payload. If absent, the upstream JSON body is normalized and returned.
         */
        private Object result;

        /**
         * Optional message template. If absent, a stable default is returned.
         */
        private String message;

        /**
         * Optional pinned targets template.
         */
        private Object pinnedTargets;
    }
}
